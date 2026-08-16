/**
 * ZONA-OSIER — BaseOpenAiCompatibleClient.
 * Base class untuk semua provider yang menggunakan API format OpenAI-compatible.
 *
 * 9 dari 12 cloud provider Z.O menggunakan format ini (chat/completions + SSE streaming).
 * Hanya Anthropic (Messages API) dan Google AI Studio (generateContent) yang berbeda.
 * Cohere (Chat API) juga berbeda tapi mendukung mode OpenAI-compatible.
 *
 * Fitur:
 * - Shared HTTP POST + SSE streaming logic
 * - Automatic retry with exponential backoff (max 2 retries)
 * - Rate limit header parsing (passive health-check)
 * - Tool call parsing (function calling)
 * - Request/response logging (opt-in)
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.agent.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Konfigurasi dasar untuk OpenAI-compatible provider.
 */
open class ProviderEndpointConfig(
    val baseUrl: String,
    val defaultModel: String,
    val timeoutMs: Long = 30_000,
    val streamTimeoutMs: Long = 60_000,
    val maxRetries: Int = 2,
    /** Header tambahan yang spesifik per provider (dipanggil di subclass) */
    val extraHeaders: Map<String, String> = emptyMap()
)

/**
 * Base class untuk provider OpenAI-compatible.
 * Subclass hanya perlu override [buildExtraBody] jika ada field unik.
 */
abstract class BaseOpenAiCompatibleClient(
    private val modelId: String? = null,
    private val fallbackModelId: String? = null,
    private val temperature: Float = 0.7f,
    protected val config: ProviderEndpointConfig,
    protected val apiKey: String? = null
) : ModelClient {

    override val isAvailable: Boolean get() = !apiKey.isNullOrBlank()
    override val modelId: String = modelId ?: config.defaultModel

    /**
     * Provider-specific body fields. Override di subclass jika perlu.
     * Contoh: Cerebras butuh "stream_options": {"include_usage": true}
     */
    protected open fun buildExtraBody(): JSONObject? = null

    /**
     * Provider-specific headers. Override di subclass jika perlu.
     */
    protected open fun buildExtraHeaders(): Map<String, String> = config.extraHeaders

    /**
     * Parse response body. Override jika response format berbeda.
     */
    protected open fun parseResponseBody(responseBody: String, latencyMs: Long): ChatResponse {
        return try {
            val json = JSONObject(responseBody)
            parseChatResponse(json, latencyMs)
        } catch (e: Exception) {
            ChatResponse(text = "Parse error: ${e.message}", latencyMs = latencyMs)
        }
    }

    // ==================== Core Chat ====================

    override suspend fun chat(messages: List<Message>): ChatResponse {
        val key = apiKey ?: return ChatResponse(text = "API key ${providerName} belum dikonfigurasi.")
        val startTime = System.currentTimeMillis()

        val requestBody = buildRequestBody(messages, streaming = false)
        var lastError = ""

        // Retry loop dengan exponential backoff
        for (attempt in 0..config.maxRetries) {
            if (attempt > 0) {
                val delayMs = (1000L * (1L shl attempt)).coerceAtMost(8000L)
                kotlinx.coroutines.delay(delayMs)
            }

            val (responseCode, responseBody, responseHeaders) = doPostRequest(requestBody, key)

            when {
                responseCode == 200 -> {
                    val latency = System.currentTimeMillis() - startTime
                    return parseResponseBody(responseBody, latency)
                }
                responseCode in listOf(401, 402, 403) -> {
                    // Auth/payment error — jangan retry
                    return ChatResponse(
                        text = "$providerName error (HTTP $responseCode): API key invalid atau kredit habis.",
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                }
                responseCode == 429 -> {
                    lastError = "$providerName rate limited (429)."
                    // Retry setelah backoff, kecuali attempt terakhir
                    continue
                }
                responseCode in 500..599 -> {
                    lastError = "$providerName server error (HTTP $responseCode)."
                    continue
                }
                else -> {
                    lastError = "$providerName error (HTTP $responseCode): $responseBody"
                    continue
                }
            }
        }

        return ChatResponse(
            text = lastError.ifBlank { "$providerName gagal setelah ${config.maxRetries + 1} percobaan." },
            latencyMs = System.currentTimeMillis() - startTime
        )
    }

    // ==================== Streaming ====================

    override fun chatStream(messages: List<Message>): Flow<String> = flow {
        val key = apiKey ?: {
            emit("API key ${providerName} belum dikonfigurasi.")
            return@flow
        }()

        val requestBody = buildRequestBody(messages, streaming = true)
        val url = URL("${config.baseUrl}/chat/completions")

        val connection = createConnection(url, key)
        connection.outputStream.use { os ->
            OutputStreamWriter(os).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }
        }

        if (connection.responseCode != 200) {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            emit("$providerName streaming error (HTTP ${connection.responseCode}): $error")
            connection.disconnect()
            return@flow
        }

        // Parse SSE stream
        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (!currentLine.startsWith("data: ")) continue

                val data = currentLine.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices")
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

        connection.disconnect()
    }

    // ==================== Request Building ====================

    protected fun buildRequestBody(messages: List<Message>, streaming: Boolean): String {
        val json = JSONObject()
        json.put("model", modelId)
        json.put("temperature", temperature)
        json.put("stream", streaming)
        json.put("max_tokens", 4096)

        // Extra body fields dari subclass
        buildExtraBody()?.let { extra ->
            extra.keys().forEach { key -> json.put(key, extra[key]) }
        }

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = buildMessageObject(msg)
            messagesArray.put(msgObj)
        }
        json.put("messages", messagesArray)

        return json.toString()
    }

    protected fun buildMessageObject(msg: Message): JSONObject {
        val msgObj = JSONObject()
        msgObj.put("role", msg.role)
        msgObj.put("content", msg.content)

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

        if (msg.toolCallId != null) {
            msgObj.put("tool_call_id", msg.toolCallId)
        }

        return msgObj
    }

    // ==================== HTTP ====================

    protected fun createConnection(url: URL, key: String): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            connectTimeout = config.timeoutMs.toInt()
            readTimeout = config.streamTimeoutMs.toInt()
            doOutput = true

            // Extra headers dari subclass
            buildExtraHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
        }
    }

    protected fun doPostRequest(
        body: String,
        key: String
    ): Triple<Int, String, Map<String, String>> {
        val url = URL("${config.baseUrl}/chat/completions")
        val connection = createConnection(url, key)

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
            connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
        }

        // Collect response headers untuk passive health-check
        val headers = mutableMapOf<String, String>()
        for (i in 0 until connection.headerFields.size) {
            val headerKey = connection.headerFields.keys().elementAtOrNull(i) ?: continue
            val headerValue = connection.headerFields.values().elementAtOrNull(i)?.firstOrNull() ?: continue
            headers[headerKey] = headerValue
        }

        connection.disconnect()
        return Triple(responseCode, responseBody, headers)
    }

    // ==================== Response Parsing ====================

    protected fun parseChatResponse(json: JSONObject, latencyMs: Long): ChatResponse {
        if (json.has("error")) {
            val errorMsg = json.getJSONObject("error").optString("message", "Unknown error")
            return ChatResponse(text = "$providerName error: $errorMsg", latencyMs = latencyMs)
        }

        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return ChatResponse(text = "(respons kosong dari $providerName)", latencyMs = latencyMs)
        }

        val message = choices.getJSONObject(0).getJSONObject("message")
        val text = message.optString("content", null)
        val toolCalls = parseToolCalls(message.optJSONArray("tool_calls"))

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

    protected fun parseToolCalls(toolCallsArray: JSONArray?): List<ToolCall>? {
        if (toolCallsArray == null) return null
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until toolCallsArray.length()) {
            val tc = toolCallsArray.getJSONObject(i)
            val func = tc.getJSONObject("function")
            val argsStr = func.optString("arguments", "{}")
            val argsMap = mutableMapOf<String, Any>()
            try {
                val argsJson = JSONObject(argsStr)
                argsJson.keys().forEach { key -> argsMap[key] = argsJson.get(key) }
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

    companion object {
        /** Header keys yang perlu di-track untuk passive health-check */
        val QUOTA_HEADER_KEYS = setOf(
            "x-ratelimit-remaining-requests",
            "x-ratelimit-remaining-tokens",
            "x-ratelimit-remaining-tokens-per-minute",
            "x-ratelimit-reset",
            "x-ratelimit-limit-requests",
            "x-ratelimit-limit-tokens"
        )
    }
}
