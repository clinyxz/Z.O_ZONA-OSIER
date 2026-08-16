/**
 * ZONA-OSIER — AnthropicClient.
 * Klien LLM untuk Anthropic Claude API.
 *
 * Anthropic menggunakan format MESSAGES API (bukan OpenAI-compatible):
 * POST /v1/messages
 * Headers: x-api-key, anthropic-version, anthropic-dangerous-direct-browser-access
 *
 * Tool calling format berbeda (tool_use content block, bukan function).
 * Max tokens WAJIB diisi (Anthropic tidak punya default).
 *
 * Model: claude-sonnet-4-20250514, claude-haiku-4-20250414
 */
package com.zonaosier.model.client.cloud

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

class AnthropicClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.ANTHROPIC_API_KEY.ifBlank { null }
) : ModelClient {

    override val providerName: String = "Anthropic"
    override val modelId: String = modelId ?: "claude-sonnet-4-20250514"
    override val isAvailable: Boolean = !apiKey.isNullOrBlank()

    private val baseUrl = "https://api.anthropic.com/v1"
    private val anthropicVersion = "2023-06-01"
    private val timeoutMs = 45_000L
    private val streamTimeoutMs = 120_000L
    private val maxTokens = 4096

    // ==================== Chat ====================

    override suspend fun chat(messages: List<Message>): ChatResponse {
        val key = apiKey ?: return ChatResponse(text = "API key Anthropic belum dikonfigurasi.")
        val startTime = System.currentTimeMillis()

        val requestBody = buildAnthropicRequest(messages, streaming = false)
        val url = URL("$baseUrl/messages")

        val connection = createAnthropicConnection(url, key)

        connection.outputStream.use { os ->
            OutputStreamWriter(os).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }
        }

        val responseCode = connection.responseCode
        val responseBody = if (responseCode == 200) {
            connection.inputStream.bufferedReader().readText()
        } else {
            connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
        }
        connection.disconnect()

        val latency = System.currentTimeMillis() - startTime

        if (responseCode != 200) {
            return ChatResponse(text = "Anthropic error (HTTP $responseCode): $responseBody", latencyMs = latency)
        }

        return parseAnthropicResponse(responseBody, latency)
    }

    // ==================== Streaming ====================

    override fun chatStream(messages: List<Message>): Flow<String> = flow {
        val key = apiKey ?: {
            emit("API key Anthropic belum dikonfigurasi.")
            return@flow
        }()

        val requestBody = buildAnthropicRequest(messages, streaming = true)
        val url = URL("$baseUrl/messages")

        val connection = createAnthropicConnection(url, key)

        connection.outputStream.use { os ->
            OutputStreamWriter(os).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }
        }

        if (connection.responseCode != 200) {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            emit("Anthropic streaming error (HTTP ${connection.responseCode}): $error")
            connection.disconnect()
            return@flow
        }

        // Anthropic SSE format: event: content_block_delta, data: {delta: {type: "text_delta", text: "..."}}
        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            var currentEvent = ""
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                when {
                    currentLine.startsWith("event: ") -> {
                        currentEvent = currentLine.removePrefix("event: ").trim()
                    }
                    currentLine.startsWith("data: ") -> {
                        val data = currentLine.removePrefix("data: ").trim()
                        if (data == "[DONE]") break

                        if (currentEvent == "content_block_delta") {
                            try {
                                val json = JSONObject(data)
                                val delta = json.optJSONObject("delta")
                                if (delta?.optString("type") == "text_delta") {
                                    val text = delta.optString("text", "")
                                    if (text.isNotBlank()) emit(text)
                                }
                            } catch (_: Exception) { }
                        }
                        currentEvent = ""
                    }
                }
            }
        }
        connection.disconnect()
    }

    // ==================== Request Building ====================

    /**
     * Konversi Message[] Z.O → Anthropic format.
     * Anthropic memisahkan system prompt dari messages array.
     */
    private fun buildAnthropicRequest(messages: List<Message>, streaming: Boolean): String {
        val json = JSONObject()
        json.put("model", modelId)
        json.put("max_tokens", maxTokens)
        json.put("temperature", temperature)
        json.put("stream", streaming)

        // Pisahkan system prompt
        val systemMessages = mutableListOf<String>()
        val userMessages = JSONArray()

        for (msg in messages) {
            when (msg.role) {
                "system" -> systemMessages.add(msg.content)
                "tool" -> {
                    // Anthropic tool_result: {role: "user", content: [{type: "tool_result", tool_use_id: ..., content: ...}]}
                    val contentArr = JSONArray()
                    contentArr.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", msg.toolCallId ?: "")
                        put("content", msg.content)
                    })
                    userMessages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", contentArr)
                    })
                }
                "assistant" -> {
                    val assistantObj = JSONObject().apply {
                        put("role", "assistant")
                        if (msg.toolCalls != null) {
                            val contentArr = JSONArray()
                            if (msg.content.isNotBlank()) {
                                contentArr.put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", msg.content)
                                })
                            }
                            for (tc in msg.toolCalls) {
                                contentArr.put(JSONObject().apply {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("input", JSONObject(tc.arguments))
                                })
                            }
                            put("content", contentArr)
                        } else {
                            put("content", msg.content)
                        }
                    }
                    userMessages.put(assistantObj)
                }
                else -> {
                    userMessages.put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            }
        }

        if (systemMessages.isNotEmpty()) {
            json.put("system", systemMessages.joinToString("\n\n"))
        }
        json.put("messages", userMessages)

        return json.toString()
    }

    // ==================== HTTP ====================

    private fun createAnthropicConnection(url: URL, key: String): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", anthropicVersion)
            setRequestProperty("anthropic-dangerous-direct-browser-access", "true")
            connectTimeout = timeoutMs.toInt()
            readTimeout = streamTimeoutMs.toInt()
            doOutput = true
        }
    }

    // ==================== Response Parsing ====================

    private fun parseAnthropicResponse(responseBody: String, latencyMs: Long): ChatResponse {
        return try {
            val json = JSONObject(responseBody)

            if (json.has("error")) {
                val errorMsg = json.getJSONObject("error").optString("message", "Unknown")
                return ChatResponse(text = "Anthropic error: $errorMsg", latencyMs = latencyMs)
            }

            val contentBlocks = json.optJSONArray("content")
            val textBuilder = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()

            if (contentBlocks != null) {
                for (i in 0 until contentBlocks.length()) {
                    val block = contentBlocks.getJSONObject(i)
                    when (block.optString("type")) {
                        "text" -> textBuilder.append(block.optString("text", ""))
                        "tool_use" -> {
                            toolCalls.add(ToolCall(
                                id = block.getString("id"),
                                name = block.getString("name"),
                                arguments = try {
                                    val inputObj = block.getJSONObject("input")
                                    val map = mutableMapOf<String, Any>()
                                    inputObj.keys().forEach { k -> map[k] = inputObj.get(k) }
                                    map
                                } catch (_: Exception) { emptyMap() }
                            ))
                        }
                    }
                }
            }

            val usage = json.optJSONObject("usage")?.let { u ->
                TokenUsage(
                    promptTokens = u.optInt("input_tokens", 0),
                    completionTokens = u.optInt("output_tokens", 0),
                    totalTokens = u.optInt("input_tokens", 0) + u.optInt("output_tokens", 0)
                )
            }

            ChatResponse(
                text = textBuilder.toString().ifBlank { null },
                toolCalls = if (toolCalls.isEmpty()) null else toolCalls,
                usage = usage,
                latencyMs = latencyMs
            )
        } catch (e: Exception) {
            ChatResponse(text = "Parse error: ${e.message}", latencyMs = latencyMs)
        }
    }
}
