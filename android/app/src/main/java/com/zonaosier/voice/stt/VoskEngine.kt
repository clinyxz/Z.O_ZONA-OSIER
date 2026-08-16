/**
 * ZONA-OSIER — VoskEngine.
 * STT On-Device menggunakan Vosk.
 * Model Bahasa Indonesia kecil (~50MB) dijalankan dalam
 * grammar mode untuk wake-word dan perintah sistem.
 *
 * Modes:
 * - GRAMMAR: Grammar-constrained, akurat untuk perintah sistem.
 * - FULL: Transkrip penuh untuk percakapan bebas.
 */
package com.zonaosier.voice.stt

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Hasil transkripsi dengan confidence.
 */
data class STTResult(
    val text: String,
    val confidence: Float,
    val isFinal: Boolean
)

/**
 * Mode operasi STT.
 */
enum class STTMode {
    /** Grammar-constrained untuk perintah sistem. */
    GRAMMAR,
    /** Transkrip penuh untuk percakapan. */
    FREE,
    /** Selalu gunakan cloud STT. */
    ALWAYS_CLOUD
}

class VoskEngine(context: Context) {

    companion object {
        private const val MODEL_DIR = "vosk-model-id"
        private const val SAMPLE_RATE = 16000f
    }

    private var model: Model? = null
    private var grammarRecognizer: Recognizer? = null
    private var fullRecognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    init {
        try {
            val modelPath = File(context.filesDir, MODEL_DIR)
            if (modelPath.exists()) {
                model = Model(modelPath.path)
                // Grammar recognizer — constrained ke perintah sistem
                grammarRecognizer = Recognizer(model, SAMPLE_RATE)
                // Full recognizer — transkrip bebas
                fullRecognizer = Recognizer(model, SAMPLE_RATE)
            }
        } catch (e: IOException) {
            // Model tidak tersedia
        }
    }

    val isModelLoaded: Boolean
        get() = model != null

    /**
     * Transkripsi mode grammar (perintah sistem).
     * Output sudah divalidasi terhadap grammar.
     */
    fun transcribeGrammar(audioData: ShortArray): STTResult {
        val recognizer = grammarRecognizer
            ?: return STTResult("", 0f, true)

        return try {
            recognizer.reset()
            recognizer.acceptWaveForm(audioData, SAMPLE_RATE)
            val result = recognizer.getResult()
            val json = org.json.JSONObject(result)
            val text = json.optString("text", "")
            STTResult(text = text, confidence = if (text.isNotBlank()) 0.95f else 0f, isFinal = true)
        } catch (e: Exception) {
            STTResult("", 0f, true)
        }
    }

    /**
     * Transkripsi mode penuh (percakapan bebas).
     */
    fun transcribeFull(audioData: ShortArray): STTResult {
        val recognizer = fullRecognizer
            ?: return STTResult("", 0f, true)

        return try {
            recognizer.reset()
            recognizer.acceptWaveForm(audioData, SAMPLE_RATE)
            val result = recognizer.getFinalResult()
            val json = org.json.JSONObject(result)
            val text = json.optString("text", "")
            STTResult(text = text, confidence = if (text.isNotBlank()) 0.8f else 0f, isFinal = true)
        } catch (e: Exception) {
            STTResult("", 0f, true)
        }
    }

    /**
     * Stream recognition dengan listener.
     */
    fun startListening(
        context: Context,
        listener: RecognitionListener
    ): Boolean {
        if (model == null) return false

        speechService = SpeechService(model, SAMPLE_RATE, listener)
        speechService?.startListening()
        return true
    }

    /**
     * Stop stream recognition.
     */
    fun stopListening() {
        try {
            speechService?.stop()
            speechService = null
        } catch (_: Exception) { }
    }

    /**
     * Set grammar untuk grammar mode.
     */
    fun setGrammar(grammar: String) {
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE, grammar)
            grammarRecognizer?.close()
            grammarRecognizer = recognizer
        } catch (_: Exception) { }
    }

    /**
     * Cleanup resources.
     */
    fun close() {
        stopListening()
        try { grammarRecognizer?.close() } catch (_: Exception) { }
        try { fullRecognizer?.close() } catch (_: Exception) { }
        try { model?.close() } catch (_: Exception) { }
        grammarRecognizer = null
        fullRecognizer = null
        model = null
    }
}
