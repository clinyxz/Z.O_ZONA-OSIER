/**
 * ZONA-OSIER — VADGatekeeper.
 * Voice Activity Detection menggunakan Silero VAD.
 * Mendeteksi apakah ada suara dalam audio stream.
 *
 * Library: gkonovalov/android-vad (Silero DNN model).
 * Model: Silero VAD — akurasi tinggi, API 24+.
 * Latensi: ~30-80ms pada mobile CPU.
 *
 * VAD digunakan sebagai gatekeeper sebelum STT:
 * - Tidak ada suara → skip STT (hemat CPU/baterai)
 * - Ada suara → teruskan ke STT router
 */
package com.zonaosier.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.gkonovalov.androidvad.Vad
import com.gkonovalov.androidvad.VadConfig
import com.gkonovalov.androidvad.config.FrameSize
import com.gkonovalov.androidvad.config.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Status VAD.
 */
enum class VadStatus {
    /** Tidak ada suara terdeteksi. */
    SILENCE,
    /** Suara terdeteksi — sedang berbicara. */
    SPEECH,
    /** Suara berhenti — akhir kalimat. */
    SPEECH_END
}
/**
 * Konfigurasi VAD Gatekeeper.
 */
data class VADConfig(
    val sampleRate: Int = 16000,
    val mode: Mode = Mode.VERY_AGGRESSIVE, // Paling sensitif untuk wake-word
    val speechThresholdMs: Long = 300L,   // Minimal durasi speech sebelum dianggap valid
    val silenceThresholdMs: Long = 600L,  // Durasi silence sebelum dianggap akhir kalimat
    val frameSize: FrameSize = FrameSize.FRAME_SIZE_480
)

class VADGatekeeper(context: Context, private val config: VADConfig = VADConfig()) {

    private val vad: Vad = Vad.create(
        context,
        VadConfig.builder()
            .setMode(config.mode)
            .setFrameSize(config.frameSize)
            .setSampleRate(config.sampleRate)
            .build()
    )

    private val _status = MutableStateFlow(VadStatus.SILENCE)
    val status: StateFlow<VadStatus> = _status.asStateFlow()

    /** Waktu terakhir speech terdeteksi. */
    private var lastSpeechTime: Long = 0L

    /** Waktu terakhir silence dimulai. */
    private var silenceStartTime: Long = 0L

    /** Apakah sedang dalam sesi speech aktif. */
    private var isInSpeechSession: Boolean = false

    /** Buffer audio selama speech aktif. */
    private val speechBuffer = mutableListOf<Short>()

    companion object {
        private const val BYTES_PER_SAMPLE = 2 // 16-bit PCM
    }

    /**
     * Proses satu frame audio.
     * @param audioData Frame audio 16-bit PCM.
     * @return true jika frame mengandung speech.
     */
    fun processFrame(audioData: ShortArray): Boolean {
        val isSpeech = vad.isSpeech(audioData)
        val now = System.currentTimeMillis()

        if (isSpeech) {
            lastSpeechTime = now
            speechBuffer.addAll(audioData.toList())

            if (!isInSpeechSession) {
                // Mulai sesi speech baru
                isInSpeechSession = true
                _status.value = VadStatus.SPEECH
            }

            // Reset silence timer
            silenceStartTime = 0L
        } else {
            // Silence
            if (isInSpeechSession) {
                if (silenceStartTime == 0L) {
                    silenceStartTime = now
                }

                val silenceDuration = now - silenceStartTime
                if (silenceDuration >= config.silenceThresholdMs) {
                    // Akhir kalimat
                    _status.value = VadStatus.SPEECH_END
                    isInSpeechSession = false
                    silenceStartTime = 0L
                    return false
                }

                // Masih dalam jendela silence — tetap buffer
                speechBuffer.addAll(audioData.toList())
            }
        }

        return isSpeech
    }

    /**
     * Proses byte array (convenience untuk AudioRecord).
     */
    fun processFrameBytes(audioData: ByteArray) {
        val shorts = ByteArrayToShortArray(audioData)
        processFrame(shorts)
    }

    /**
     * Ambil audio yang sudah terkumpul selama sesi speech.
     * @return ShortArray audio atau null jika terlalu pendek.
     */
    fun consumeSpeechAudio(): ShortArray? {
        val duration = lastSpeechTime - (silenceStartTime.takeIf { it > 0 } ?: System.currentTimeMillis())
        if (duration < config.speechThresholdMs && speechBuffer.size < config.sampleRate) {
            // Terlalu pendek — noise
            speechBuffer.clear()
            return null
        }

        val result = speechBuffer.toShortArray()
        speechBuffer.clear()
        isInSpeechSession = false
        _status.value = VadStatus.SILENCE
        return result
    }

    /**
     * Reset state.
     */
    fun reset() {
        speechBuffer.clear()
        isInSpeechSession = false
        lastSpeechTime = 0L
        silenceStartTime = 0L
        _status.value = VadStatus.SILENCE
    }

    /**
     * Konversi ByteArray (16-bit PCM) ke ShortArray.
     */
    private fun ByteArrayToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / BYTES_PER_SAMPLE)
        for (i in shorts.indices) {
            shorts[i] = (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8).toShort()
        }
        return shorts
    }

    /**
     * Buat AudioRecord yang kompatibel dengan VAD.
     */
    fun createAudioRecord(): AudioRecord {
        val bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )
    }

    /**
     * Cek apakah VAD tersedia.
     */
    val isAvailable: Boolean
        get() = try { vad != null; true } catch (_: Exception) { false }
}
