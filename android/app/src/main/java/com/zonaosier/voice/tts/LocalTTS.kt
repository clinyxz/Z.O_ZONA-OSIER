/**
 * ZONA-OSIER — LocalTTS.
 * TTS neural offline menggunakan sherpa-onnx (SupertonicTTS 3 / VITS/Piper).
 *
 * Benchmark:
 * - M4 Pro CPU: RTF 0.018-0.023
 * - Snapdragon 8 Gen 3: RTF << 0.05
 *
 * Mitigasi latensi:
 * - Sentence-level streaming via IndonesianSentenceSplitter
 * - Pre-warming saat startup
 * - Profiling runtime via TTSLatencyProfiler
 */
package com.zonaosier.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Konfigurasi TTS lokal.
 */
data class LocalTTSConfig(
    val modelDir: String = "sherpa-onnx-id",
    val sampleRate: Int = 16000,
    val speed: Float = 1.0f,
    val speakerId: Int = 0,
    val maxNumSentences: Int = 1
)

/**
 * Hasil sintesis audio.
 */
data class SynthesisResult(
    val audio: FloatArray,
    val sampleRate: Int,
    val generationTimeMs: Long
)

class LocalTTS(context: Context, private val config: LocalTTSConfig = LocalTTSConfig()) {

    private var nativeHandle: Long = 0
    private var isInitialized = false

    init {
        try {
            // Load native library
            System.loadLibrary("sherpa-onnx-jni")
            nativeHandle = createNativeTTS(
                modelPath = "", // Akan di-set saat model diunduh
                lexiconPath = "",
                tokensPath = "",
                dataDir = ""
            )
            isInitialized = nativeHandle != 0L
        } catch (_: UnsatisfiedLinkError) {
            // sherpa-onnx AAR belum diimport
            isInitialized = false
        } catch (_: Exception) {
            isInitialized = false
        }
    }

    val isAvailable: Boolean
        get() = isInitialized

    /**
     * Sintesis teks ke audio samples.
     * @return FloatArray samples atau empty jika gagal.
     */
    fun synthesize(text: String): SynthesisResult {
        if (!isInitialized || text.isBlank()) {
            return SynthesisResult(floatArrayOf(), config.sampleRate, 0)
        }

        val startTime = System.nanoTime()
        val samples = generateNative(nativeHandle, text, config.speakerId, config.speed)
        val genTime = (System.nanoTime() - startTime) / 1_000_000

        return SynthesisResult(
            audio = samples ?: floatArrayOf(),
            sampleRate = config.sampleRate,
            generationTimeMs = genTime
        )
    }

    /**
     * Sintesis dan langsung putar ke AudioTrack.
     * Blocking — panggil dari coroutine.
     */
    fun synthesizeAndPlay(text: String, onDone: (() -> Unit)? = null) {
        val result = synthesize(text)
        if (result.audio.isEmpty()) {
            onDone?.invoke()
            return
        }

        playAudio(result.audio, result.sampleRate, onDone)
    }

    /**
     * Putar FloatArray audio ke speaker.
     */
    private fun playAudio(samples: FloatArray, sampleRate: Int, onDone: (() -> Unit)?) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .build()

        audioTrack.play()

        // Convert float [-1, 1] ke 16-bit PCM
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            val s = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            pcm[i] = s.toShort()
        }

        audioTrack.write(pcm, 0, pcm.size)
        audioTrack.stop()
        audioTrack.release()
        onDone?.invoke()
    }

    /**
     * Cleanup native resources.
     */
    fun close() {
        if (nativeHandle != 0L) {
            destroyNativeTTS(nativeHandle)
            nativeHandle = 0L
        }
        isInitialized = false
    }

    // --- Native methods (placeholder — actual JNI di AAR sherpa-onnx) ---
    private external fun createNativeTTS(
        modelPath: String, lexiconPath: String,
        tokensPath: String, dataDir: String
    ): Long

    private external fun generateNative(
        handle: Long, text: String, sid: Int, speed: Float
    ): FloatArray?

    private external fun destroyNativeTTS(handle: Long)
}

class TTSLatencyProfiler(private val tts: LocalTTS) {

    data class ProfileResult(
        val textLength: Int,
        val audioDurationMs: Long,
        val synthesisTimeMs: Long,
        val rtf: Float
    )

    fun profile(text: String): ProfileResult {
        val result = tts.synthesize(text)
        val audioDuration = (result.audio.size / 16000.0 * 1000).toLong()
        val rtf = if (audioDuration > 0) {
            result.generationTimeMs.toFloat() / audioDuration.toFloat()
        } else 0f

        return ProfileResult(
            textLength = text.length,
            audioDurationMs = audioDuration,
            synthesisTimeMs = result.generationTimeMs,
            rtf = rtf
        )
    }

    /**
     * Cek apakah sentence-level streaming diperlukan.
     * Jika RTF > 0.1, TTS tidak cukup cepat untuk real-time.
     */
    fun shouldUseSentenceStreaming(): Boolean {
        val baseline = profile("Ini adalah kalimat uji untuk mengukur performa.")
        return baseline.rtf > 0.1f
    }
}
