/**
 * ZONA-OSIER — GoogleAiStudioClient.
 * Klien LLM untuk Google AI Studio (Gemini API).
 *
 * Google AI Studio menggunakan format generateContent:
 * POST /v1beta/models/{model}:generateContent
 * POST /v1beta/models/{model}:streamGenerateContent
 *
 * Header: x-goog-api-key (BUKAN Authorization Bearer)
 *
 * Model: gemini-2.0-flash, gemini-1.5-flash, gemini-1.5-pro
 * Free tier: 250 RPD (requests per day) — sangat ketat!
 * ⚠️ Passive health-check wajib.
 *
 * Tool calling (function calling) menggunakan format Gemini API:
 * functionDeclarations, functionCall, functionResponse.
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

class GoogleAiStudioClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.GOOGLE_AI_STUDIO_API_KEY.ifBlank { null }
) : ModelClient {

    override val providerName: String = "Google AI Studio"
    override val modelId: String = modelId ?: "gemini-2.0-flash"
    override val isAvailable: Boolean = !apiKey.isNullOrBlank()

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta"
    private val timeoutMs = 45_000L
    private val streamTimeoutMs = 120_000L

    // ==================== Chat ====================

    override suspend fun chat(messages: List<Message>): ChatResponse {
        val key = apiKey ?: return ChatResponse(text = "API key Google AI Studio belum dikonfigurasi.")
        val startTime = System.currentTimeMillis()

        val requestBody = buildGeminiRequest(messages)
        val url = URL("$baseUrl/models/$modelId:generateContent?key=$key")

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            doOutput = true
        }

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
            return ChatResponse(text = "Google AI Studio error (HTTP $responseCode): $responseBody", latencyMs = latency)
        }

        return parseGeminiResponse(responseBody, latency)
    }

    // ==================== Streaming ====================

    override fun chatStream(messages: List<Message>): Flow<String> = flow {
        val key = apiKey ?: {
            emit("API key Google AI Studio belum dikonfigurasi.")
            return@flow
        }()

        val requestBody = buildGeminiRequest(messages)
        // Alt=SSE untuk streaming
        val url = URL("$baseUrl/models/$modelId:streamGenerateContent?alt=sse&key=$key")

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = timeoutMs.toInt()
            readTimeout = streamTimeoutMs.toInt()
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
            emit("Google AI Studio streaming error (HTTP ${connection.responseCode}): $error")
            connection.disconnect()
            return@flow
        }

        // Gemini SSE format: data: {candidates: [{content: {parts: [{text: "..."}]}}]}
        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (!currentLine.startsWith("data: ")) continue

                val data = currentLine.removePrefix("data: ").trim()
                try {
                    val json = JSONObject(data)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        if (content != null) {
                            val parts = content.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val text = parts.getJSONObject(i).optString("text", "")
                                    if (text.isNotBlank()) emit(text)
                                }
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        connection.disconnect()
    }

    // ==================== Request Building ====================

    /**
     * Konversi Message[] Z.O → Gemini format.
     * System prompt → systemInstruction.
     * Messages → contents[] dengan role "user"/"model".
     */
    private fun buildGeminiRequest(messages: List<Message>): String {
        val json = JSONObject()

        // Generation config
        val genConfig = JSONObject().apply {
            put("temperature", temperature)
            put("maxOutputTokens", 4096)
        }
        json.put("generationConfig", genConfig)

        // System instruction
        val systemMessages = messages.filter { it.role == "system" }
        if (systemMessages.isNotEmpty()) {
            val systemParts = JSONArray()
            for (sys in systemMessages) {
                systemParts.put(JSONObject().apply { put("text", sys.content) })
            }
            json.put("systemInstruction", JSONObject().apply {
                put("parts", systemParts)
            })
        }

        // Contents
        val contents = JSONArray()
        val nonSystemMessages = messages.filter { it.role != "system" }

        for (msg in nonSystemMessages) {
            val geminiRole = when (msg.role) {
                "assistant" -> "model"
                "tool" -> "user" // Gemini: tool result sent as user message
                else -> "user"
            }

            val parts = JSONArray()

            if (msg.role == "tool" && msg.toolCallId != null) {
                // Gemini functionResponse format
                parts.put(JSONObject().apply {
                    put("functionResponse", JSONObject().apply {
                        put("name", "") // Nama fungsi dari context
                        put("response", JSONObject().apply { put("content", msg.content) })
                    })
                })
            } else {
                parts.put(JSONObject().apply { put("text", msg.content) })
            }

            contents.put(JSONObject().apply {
                put("role", geminiRole)
                put("parts", parts)
            })
        }

        json.put("contents", contents)
        return json.toString()
    }

    // ==================== Response Parsing ====================

    private fun parseGeminiResponse(responseBody: String, latencyMs: Long): ChatResponse {
        return try {
            val json = JSONObject(responseBody)

            if (json.has("error")) {
                val errorMsg = json.getJSONObject("error").optString("message", "Unknown")
                return ChatResponse(text = "Google AI Studio error: $errorMsg", latencyMs = latencyMs)
            }

            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                // Check for finishReason SAFETY
                val finishReason = candidates?.optJSONObject(0)
                    ?.optString("finishReason", "")
                if (finishReason == "SAFETY") {
                    return ChatResponse(text = "(Respons diblokir oleh safety filter Gemini)", latencyMs = latencyMs)
                }
                return ChatResponse(text = "(respons kosong dari Google AI Studio)", latencyMs = latencyMs)
            }

            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val textBuilder = StringBuilder()

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    textBuilder.append(part.optString("text", ""))
                    // TODO: Parse functionCall dari Gemini format
                }
            }

            val usageMeta = json.optJSONObject("usageMetadata")
            val usage = usageMeta?.let { u ->
                TokenUsage(
                    promptTokens = u.optInt("promptTokenCount", 0),
                    completionTokens = u.optInt("candidatesTokenCount", 0),
                    totalTokens = u.optInt("totalTokenCount", 0)
                )
            }

            ChatResponse(
                text = textBuilder.toString().ifBlank { null },
                usage = usage,
                latencyMs = latencyMs
            )
        } catch (e: Exception) {
            ChatResponse(text = "Parse error: ${e.message}", latencyMs = latencyMs)
        }
    }
}
