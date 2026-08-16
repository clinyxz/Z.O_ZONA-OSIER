/**
 * ZONA-OSIER — VoiceRouter.
 * Router suara 3-jalur dengan persona awareness.
 *
 * Jalur:
 * 1. LIVE_MINIMAX: MiniMax Speech 2.8 (premium, berbayar, sound-tag, ekspresif)
 * 2. LIVE_ELEVENLABS: ElevenLabs Flash v2.5 (streaming, ~75ms TTFA, 10K kredit gratis/bulan)
 * 3. LOCAL: sherpa-onnx (offline, gratis, RTF 0.018-0.023)
 *
 * Persona tag mempengaruhi:
 * - Voice ID selection (tag "tenang" → voice ID berbeda dari "tegas")
 * - TTS parameter (speed, pitch, energy)
 * - Fallback priority
 *
 * ⚠️ MiniMax BUKAN free tier — wajib cek kredit.
 */
package com.zonaosier.voice

import com.zonaosier.voice.tts.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Jalur TTS yang dipilih.
 */
enum class VoiceRoute {
    /** MiniMax online (premium). */
    LIVE_MINIMAX,
    /** ElevenLabs online (streaming). */
    LIVE_ELEVENLABS,
    /** TTS lokal sherpa-onnx (offline). */
    LOCAL
}

/**
 * Konfigurasi audio output.
 */
data class AudioOutputConfig(
    val sampleRate: Int = 16000,
    val channelConfig: Int = android.media.AudioFormat.CHANNEL_OUT_MONO,
    val audioFormat: Int = android.media.AudioFormat.ENCODING_PCM_16BIT,
    val streamType: Int = android.media.AudioManager.STREAM_MUSIC
)

class VoiceRouter(
    private val miniMaxClient: MiniMaxTTS? = null,
    private val elevenLabsClient: ElevenLabsStream? = null,
    private val localTts: LocalTTS? = null,
    private val miniMaxCreditChecker: MiniMaxCreditChecker? = null
) {
    private var currentPersonaTag: String = "default"
    private var currentRoute: VoiceRoute = VoiceRoute.LOCAL

    /**
     * Set persona tag dari CharacterOrchestrator.
     */
    fun setPersonaTag(tag: String) {
        currentPersonaTag = tag
        // Propagate ke client TTS untuk voice ID selection
        miniMaxClient?.selectVoiceForTag(tag)
        elevenLabsClient?.selectVoiceForTag(tag)
    }

    fun getCurrentTag(): String = currentPersonaTag
    fun getCurrentRoute(): VoiceRoute = currentRoute

    /**
     * Tentukan jalur TTS terbaik.
     * v5.1.2-Revised: Cek kredit MiniMax terlebih dahulu.
     */
    fun resolveRoute(isOnline: Boolean, batterySaver: Boolean, needsExpressive: Boolean): VoiceRoute {
        val hasMiniMaxCredit = miniMaxCreditChecker?.hasAvailableCredit() ?: false

        val route = when {
            batterySaver || !isOnline -> VoiceRoute.LOCAL
            needsExpressive && miniMaxClient != null && hasMiniMaxCredit -> VoiceRoute.LIVE_MINIMAX
            elevenLabsClient != null -> VoiceRoute.LIVE_ELEVENLABS
            else -> VoiceRoute.LOCAL
        }

        currentRoute = route
        return route
    }

    /**
     * Sintesis teks ke audio.
     * Otomatis memilih jalur berdasarkan kondisi.
     *
     * @return ByteArray PCM audio atau null.
     */
    suspend fun synthesize(
        text: String,
        isOnline: Boolean = true,
        batterySaver: Boolean = false,
        needsExpressive: Boolean = false
    ): ByteArray? {
        val route = resolveRoute(isOnline, batterySaver, needsExpressive)

        return when (route) {
            VoiceRoute.LIVE_MINIMAX -> miniMaxClient?.synthesize(text)
            VoiceRoute.LIVE_ELEVENLABS -> elevenLabsClient?.synthesize(text)
            VoiceRoute.LOCAL -> {
                val result = localTts?.synthesize(text)
                // Convert FloatArray ke PCM 16-bit
                result?.let { floatToPcm16(it.audio, it.sampleRate) }
            }
        }
    }

    /**
     * Sintesis streaming.
     */
    fun synthesizeStream(
        text: String,
        isOnline: Boolean = true,
        batterySaver: Boolean = false,
        needsExpressive: Boolean = false
    ): Flow<ByteArray> = flow {
        val route = resolveRoute(isOnline, batterySaver, needsExpressive)

        when (route) {
            VoiceRoute.LIVE_MINIMAX -> {
                miniMaxClient?.synthesizeStream(text)?.collect { emit(it) }
            }
            VoiceRoute.LIVE_ELEVENLABS -> {
                elevenLabsClient?.synthesizeStream(text)?.collect { emit(it) }
            }
            VoiceRoute.LOCAL -> {
                val result = localTts?.synthesize(text)
                if (result != null) {
                    emit(floatToPcm16(result.audio, result.sampleRate))
                }
            }
        }
    }

    /**
     * Konversi FloatArray [-1, 1] ke PCM 16-bit ByteArray.
     */
    private fun floatToPcm16(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (s and 0xFF).toByte()
            pcm[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return pcm
    }

    /**
     * Cek apakah persona tag membutuhkan TTS ekspresif.
     */
    fun isExpressiveTag(tag: String): Boolean {
        return tag in setOf("ekspresif", "melodius")
    }
}