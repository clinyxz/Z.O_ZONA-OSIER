/**
 * ZONA-OSIER — MiniMaxTTS.
 * TTS premium MiniMax Speech 2.8.
 * Mendukung sound-tag, variasi emosi ekspresif.
 *
 * Biaya: $60/M karakter (Turbo), $100/M karakter (HD).
 * Voice cloning: $1.50/suara.
 *
 * ⚠️ BUKAN free tier — wajib cek kredit sebelum routing.
 */
package com.zonaosier.voice.tts

import android.content.Context
import com.zonaosier.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Voice ID per persona tag.
 */
data class VoiceIdMapping(
    val tag: String,
    val miniMaxVoiceId: String?,
    val elevenLabsVoiceId: String?,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f
)

/**
 * Daftar voice ID default per persona tag.
 */
object VoiceTagDefaults {
    val mappings = listOf(
        VoiceIdMapping("default", "male-qn-qingse", "pNInz6obpgDQGcFmaJgB", 1.0f, 1.0f),
        VoiceIdMapping("tenang", "male-qn-jingying", "pNInz6obpgDQGcFmaJgB", 0.85f, 0.9f),
        VoiceIdMapping("tegas", "male-qn-biaozhun", "onwK4e9ZLuTAKqWW03F9", 1.15f, 1.05f),
        VoiceIdMapping("ekspresif", "female-shaonv", "Xb7hH8MSUJpSbSDYk0k2", 1.0f, 1.1f),
        VoiceIdMapping("naratif", "female-yujie", "pNInz6obpgDQGcFmaJgB", 0.9f, 0.95f),
        VoiceIdMapping("melodius", null, null, 1.0f, 1.0f)
    )

    fun getMapping(tag: String): VoiceIdMapping {
        return mappings.firstOrNull { it.tag == tag } ?: mappings[0]
    }
}

class MiniMaxTTS(context: Context) {

    private val apiKey: String? = BuildConfig.MINIMAX_API_KEY.ifBlank { null }
    private var currentVoiceId: String? = null

    companion object {
        private const val BASE_URL = "https://api.minimax.chat/v1/t2a_v2"
        private const val TURBO_MODEL = "speech-02-turbo"
        private const val HD_MODEL = "speech-02-hd"
    }

    val isAvailable: Boolean
        get() = !apiKey.isNullOrBlank()

    /**
     * Pilih voice ID berdasarkan persona tag.
     */
    fun selectVoiceForTag(tag: String) {
        currentVoiceId = VoiceTagDefaults.getMapping(tag).miniMaxVoiceId
    }

    /**
     * Sintesis teks ke audio bytes (PCM 16-bit).
     */
    suspend fun synthesize(text: String): ByteArray? = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) return@withContext null

        try {
            val voiceId = currentVoiceId ?: VoiceTagDefaults.getMapping("default").miniMaxVoiceId
                ?: return@withContext null

            val mapping = VoiceTagDefaults.mappings.firstOrNull { it.miniMaxVoiceId == voiceId }

            val json = JSONObject().apply {
                put("model", TURBO_MODEL)
                put("text", text)
                put("stream", false)
                put("voice_setting", JSONObject().apply {
                    put("voice_id", voiceId)
                    mapping?.let { m ->
                        put("speed", m.speed)
                        put("pitch", m.pitch)
                    }
                })
                put("audio_setting", JSONObject().apply {
                    put("sample_rate", 16000)
                    put("bitrate", 128000)
                    put("format", "pcm")
                })
            }

            val url = URL(BASE_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 15_000
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
                // Parse response — binary audio
                connection.inputStream.readBytes()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sintesis streaming.
     */
    fun synthesizeStream(text: String): Flow<ByteArray> = flow {
        // TODO: Implementasi streaming endpoint jika tersedia
        val result = synthesize(text)
        if (result != null) {
            emit(result)
        }
    }
}