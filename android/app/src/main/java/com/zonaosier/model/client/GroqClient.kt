/**
 * ZONA-OSIER — GroqClient.
 * Klien LLM untuk Groq API (inference cepat, 300-800 tok/s).
 *
 * Groq adalah provider utama untuk Voice Assistant (foreground, real-time).
 * Mendukung function calling (tool calls) dan streaming.
 */
package com.zonaosier.model.client

import com.zonaosier.agent.*
import com.zonaosier.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Konfigurasi Groq client.
 */
data class GroqConfig(
    val baseUrl: String = "https://api.groq.com/openai/v1",
    val defaultModel: String = "llama-3.3-70b-versatile",
    val timeoutMs: Long = 30_000,
    val streamTimeoutMs: Long = 60_000
)

class GroqClient(
    private val modelId: String? = null,
    private val fallbackModelId: String? = null,
    private val temperature: Float = 0.7f,
    private val config: GroqConfig = GroqConfig(),
    private val apiKey: String? = BuildConfig.GROQ_API_KEY.ifBlank { null }
) : ModelClient {

    override val providerName: String = "Groq"
    override val modelId: String = modelId ?: config.defaultModel
    override val isAvailable: Boolean = !apiKey.isNullOrBlank()

    override suspend fun chat(messages: List<Message>): ChatResponse {
        val key = apiKey ?: return ChatResponse(
            text = "API key Groq belum dikonfigurasi. Tambahkan GROQ_API_KEY di local.properties."
        )

        val startTime = System.currentTimeMillis()

        val requestBody = buildRequestBody(messages, streaming = false)
        val responseJson = doPostRequest(requestBody)

        val latency = System.currentTimeMillis() - startTime
        return parseResponse(responseJson, latency)
    }

    override fun chatStream(messages: List<Message>): Flow<String> = flow {
        val key = apiKey ?: {
            emit("API key Groq belum dikonfigurasi.")
            return@flow
        }()

        val requestBody = buildRequestBody(messages, streaming = true)
        val url = URL("${config.baseUrl}/chat/completions")

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            connectTimeout = config.timeoutMs.toInt()
            readTimeout = config.streamTimeoutMs.toInt()
            doOutput = true
        }

        connection.outputStream.use { os ->
            OutputStreamWriter(os).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }
        }

        if (connection.responseCode != 200) {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            emit("Groq error: $error")
            return@flow
        }

        // Parse SSE stream
        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val json = JSONObject(data)
                        val delta = json.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("delta")
                        val content = delta.optString("content", "")
                        if (content.isNotBlank()) {
                            emit(content)
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private fun buildRequestBody(messages: List<Message>, streaming: Boolean): String {
        val json = JSONObject()
        json.put("model", modelId)
        json.put("temperature", temperature)
        json.put("stream", streaming)
        json.put("max_tokens", 4096)

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)

            // Tool calls dari assistant
            if (msg.toolCalls != null) {
                val toolCallsArray = JSONArray()
                for (tc in msg.toolCalls) {
                    val tcObj = JSONObject()
                    tcObj.put("id", tc.id)
                    tcObj.put("type", "function")
                    val funcObj = JSONObject()
                    funcObj.put("name", tc.name)
                    funcObj.put("arguments", JSONObject(tc.arguments).toString())
                    tcObj.put("function", funcObj)
                    toolCallsArray.put(tcObj)
                }
                msgObj.put("tool_calls", toolCallsArray)
            }

            // Tool call ID (untuk role "tool")
            if (msg.toolCallId != null) {
                msgObj.put("tool_call_id", msg.toolCallId)
            }

            messagesArray.put(msgObj)
        }
        json.put("messages", messagesArray)

        // TODO: Tambahkan tools definition dari registry
        // json.put("tools", toolDefinitions)

        return json.toString()
    }

    private fun doPostRequest(body: String): JSONObject {
        val url = URL("${config.baseUrl}/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = config.timeoutMs.toInt()
            readTimeout = config.timeoutMs.toInt()
            doOutput = true
        }

        connection.outputStream.use { os ->
            OutputStreamWriter(os).use { writer ->
                writer.write(body)
                writer.flush()
            }
        }

        val responseCode = connection.responseCode
        val responseBody = if (responseCode == 200) {
            connection.inputStream.bufferedReader().readText()
        } else {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            return JSONObject().apply {
                put("error", JSONObject().apply { put("message", error) })
            }
        }

        return try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            JSONObject().apply {
                put("error", JSONObject().apply { put("message", "Parse error: ${e.message}") })
            }
        }
    }

    private fun parseResponse(json: JSONObject, latencyMs: Long): ChatResponse {
        // Cek error
        if (json.has("error")) {
            val errorMsg = json.getJSONObject("error").optString("message", "Unknown error")
            return ChatResponse(text = "Groq error: $errorMsg", latencyMs = latencyMs)
        }

        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return ChatResponse(text = "(respons kosong dari Groq)", latencyMs = latencyMs)
        }

        val message = choices.getJSONObject(0).getJSONObject("message")
        val text = message.optString("content", null)
        val toolCalls = parseToolCalls(message.optJSONArray("tool_calls"))

        // Parse usage
        val usage = json.optJSONObject("usage")?.let { u ->
            TokenUsage(
                promptTokens = u.optInt("prompt_tokens", 0),
                completionTokens = u.optInt("completion_tokens", 0),
                totalTokens = u.optInt("total_tokens", 0)
            )
        }

        return ChatResponse(
            text = text?.ifBlank { null },
            toolCalls = toolCalls,
            usage = usage,
            latencyMs = latencyMs
        )
    }

    private fun parseToolCalls(toolCallsArray: JSONArray?): List<ToolCall>? {
        if (toolCallsArray == null) return null
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until toolCallsArray.length()) {
            val tc = toolCallsArray.getJSONObject(i)
            val func = tc.getJSONObject("function")
            val argsStr = func.optString("arguments", "{}")
            val argsMap = mutableMapOf<String, Any>()
            try {
                val argsJson = JSONObject(argsStr)
                argsJson.keys().forEach { key ->
                    argsMap[key] = argsJson.get(key)
                }
            } catch (_: Exception) { }

            calls.add(
                ToolCall(
                    id = tc.getString("id"),
                    name = func.getString("name"),
                    arguments = argsMap
                )
            )
        }
        return if (calls.isEmpty()) null else calls
    }
}