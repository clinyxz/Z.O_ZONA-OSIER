/**
 * ZONA-OSIER — HuggingFaceClient.
 * Klien LLM untuk HuggingFace Inference API.
 *
 * Endpoint: /models/{model_id}/v1/chat/completions
 * Model ID bisa berupa model hosted di HF (meta-llama/Llama-3.3-70B-Instruct, dll).
 *
 * Free tier: rate limit ketat (~1-5 RPM untuk model populer).
 * Pro tier: lebih longgar.
 *
 * ⚠️ Passive health-check wajib untuk free tier.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.agent.*
import com.zonaosier.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class HuggingFaceClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.HUGGINGFACE_API_KEY.ifBlank { null }
) : ModelClient {

    override val providerName: String = "HuggingFace"
    override val modelId: String = modelId ?: "meta-llama/Llama-3.3-70B-Instruct"
    override val isAvailable: Boolean = !apiKey.isNullOrBlank()

    private val baseUrl = "https://api-inference.huggingface.co"
    private val timeoutMs = 45_000L
    private val streamTimeoutMs = 90_000L

    /**
     * HF Inference API menggunakan URL format:
     * /models/{model_id}/v1/chat/completions
     */
    private fun buildUrl(): String = "$baseUrl/models/$modelId/v1/chat/completions"

    override suspend fun chat(messages: List<Message>): ChatResponse {
        val key = apiKey ?: return ChatResponse(text = "API key HuggingFace belum dikonfigurasi.")
        val startTime = System.currentTimeMillis()

        val json = JSONObject()
        json.put("model", modelId)
        json.put("temperature", temperature)
        json.put("max_tokens", 4096)

        val messagesArray = buildMessagesArray(messages)
        json.put("messages", messagesArray)

        val url = URL(buildUrl())
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            doOutput = true
        }

        connection.outputStream.use { os ->
            OutputStreamWriter(os).use { writer ->
                writer.write(json.toString())
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
            return ChatResponse(text = "HuggingFace error (HTTP $responseCode): $responseBody", latencyMs = latency)
        }

        return try {
            val responseJson = JSONObject(responseBody)
            val choices = responseJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).getJSONObject("message")
                val text = message.optString("content", null)
                val usage = responseJson.optJSONObject("usage")?.let { u ->
                    TokenUsage(
                        promptTokens = u.optInt("prompt_tokens", 0),
                        completionTokens = u.optInt("completion_tokens", 0),
                        totalTokens = u.optInt("total_tokens", 0)
                    )
                }
                ChatResponse(text = text?.ifBlank { null }, usage = usage, latencyMs = latency)
            } else {
                ChatResponse(text = "(respons kosong dari HuggingFace)", latencyMs = latency)
            }
        } catch (e: Exception) {
            ChatResponse(text = "Parse error: ${e.message}", latencyMs = latency)
        }
    }

    override fun chatStream(messages: List<Message>): Flow<String> = flow {
        val key = apiKey ?: {
            emit("API key HuggingFace belum dikonfigurasi.")
            return@flow
        }()

        val json = JSONObject()
        json.put("model", modelId)
        json.put("temperature", temperature)
        json.put("stream", true)
        json.put("max_tokens", 4096)
        json.put("messages", buildMessagesArray(messages))

        val url = URL(buildUrl())
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            connectTimeout = timeoutMs.toInt()
            readTimeout = streamTimeoutMs.toInt()
            doOutput = true
        }

        connection.outputStream.use { os ->
            OutputStreamWriter(os).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }
        }

        if (connection.responseCode != 200) {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            emit("HuggingFace streaming error: $error")
            connection.disconnect()
            return@flow
        }

        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    try {
                        val event = JSONObject(data)
                        val choices = event.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            if (delta != null) {
                                val content = delta.optString("content", "")
                                if (content.isNotBlank()) emit(content)
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }
        connection.disconnect()
    }

    private fun buildMessagesArray(messages: List<Message>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            obj.put("role", msg.role)
            obj.put("content", msg.content)
            arr.put(obj)
        }
        return arr
    }
}
