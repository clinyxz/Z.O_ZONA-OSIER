/**
 * ZONA-OSIER — ElevenLabsStream.
 * TTS streaming via ElevenLabs Flash v2.5 WebSocket.
 * ~75ms TTFA (inference only), end-to-end SE Asia ~100-150ms.
 * Tier gratis: 10.000 kredit/bulan (~10 menit audio).
 */
package com.zonaosier.voice.tts

import android.content.Context
import com.zonaosier.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import okhttp3.*
import okio.ByteString

class ElevenLabsStream(context: Context) {

    private val apiKey: String? = BuildConfig.ELEVENLABS_API_KEY.ifBlank { null }
    private var currentVoiceId: String? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val BASE_URL = "https://api.elevenlabs.io/v1"
        private const val WS_URL = "wss://api.elevenlabs.io/v1/text-to-speech/"
        private const val MODEL_ID = "eleven_flash_v2_5" // Flash v2.5 for low latency
    }

    val isAvailable: Boolean
        get() = !apiKey.isNullOrBlank()

    /**
     * Pilih voice ID berdasarkan persona tag.
     */
    fun selectVoiceForTag(tag: String) {
        currentVoiceId = VoiceTagDefaults.getMapping(tag).elevenLabsVoiceId
    }

    /**
     * Sintesis teks ke audio bytes (HTTP POST).
     */
    suspend fun synthesize(text: String): ByteArray? = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) return@withContext null
        val voiceId = currentVoiceId ?: VoiceTagDefaults.getMapping("default").elevenLabsVoiceId
            ?: return@withContext null

        try {
            val json = JSONObject().apply {
                put("text", text)
                put("model_id", MODEL_ID)
                put("voice_settings", JSONObject().apply {
                    val mapping = VoiceTagDefaults.mappings.firstOrNull { it.elevenLabsVoiceId == voiceId }
                    mapping?.let { m ->
                        put("stability", 0.5f)
                        put("similarity_boost", 0.75f)
                        put("speed", m.speed)
                    }
                })
            }

            val url = URL("$BASE_URL/text-to-speech/$voiceId")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("xi-api-key", apiKey)
                connectTimeout = 10_000
                readTimeout = 30_000
                doOutput = true
            }

            connection.outputStream.use { os ->
                OutputStreamWriter(os).use { writer ->
                    writer.write(json.toString())
                    writer.flush()
                }
            }

            if (connection.responseCode == 200) {
                connection.inputStream.readBytes()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sintesis streaming via WebSocket.
     */
    fun synthesizeStream(text: String): Flow<ByteArray> = flow {
        if (apiKey.isNullOrBlank()) return@flow
        val voiceId = currentVoiceId ?: return@flow

        val request = Request.Builder()
            .url("$WS_URL$voiceId?model_id=$MODEL_ID")
            .addHeader("xi-api-key", apiKey)
            .build()

        // WebSocket streaming
        // Note: Full WebSocket implementation memerlukan OkHttp WebSocket listener
        // Untuk sekarang, fallback ke HTTP
        val result = synthesize(text)
        if (result != null) {
            emit(result)
        }
    }

    /**
     * Ambil daftar voice yang tersedia.
     */
    suspend fun getVoices(): List<ElevenLabsVoice> = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) return@withContext emptyList()

        try {
            val url = URL("$BASE_URL/voices")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("xi-api-key", apiKey)
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val voices = json.getJSONArray("voices")
                (0 until voices.length()).map { i ->
                    val v = voices.getJSONObject(i)
                    ElevenLabsVoice(
                        id = v.getString("voice_id"),
                        name = v.getString("name"),
                        category = v.optString("category", ""),
                        labels = v.optJSONObject("labels")?.let { labels ->
                            labels.keys().asSequence().map { k -> k to labels.getString(k) }.toMap()
                        } ?: emptyMap()
                    )
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

data class ElevenLabsVoice(
    val id: String,
    val name: String,
    val category: String,
    val labels: Map<String, String>
)