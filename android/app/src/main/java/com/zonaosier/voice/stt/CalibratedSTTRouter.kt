/**
 * ZONA-OSIER — CalibratedSTTRouter.
 * Router STT yang memilih antara Vosk on-device dan Google Cloud STT.
 * Kalibrasi per-user: confidence threshold disesuaikan berdasarkan
 * perbandingan hasil Vosk vs Cloud.
 *
 * Strategi:
 * - GRAMMAR: Selalu Vosk (grammar-constrained)
 * - FREE: Vosk dulu, fallback ke cloud jika confidence < threshold
 * - ALWAYS_CLOUD: Langsung ke Google Cloud STT
 */
package com.zonaosier.voice.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.prefs.Preferences

/**
 * Store untuk menyimpan threshold kalibrasi per-user.
 */
class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("stt_calibration", Context.MODE_PRIVATE)

    /**
     * Ambil threshold kalibrasi.
     * Default 0.7 — Vosk harus confidence >= 0.7 sebelum diterima.
     */
    fun getThreshold(): Float {
        return prefs.getFloat("confidence_threshold", 0.7f)
    }

    /**
     * Update threshold berdasarkan perbandingan Vosk vs Cloud.
     */
    fun update(voskConfidence: Float, cloudConfidence: Float) {
        // Jika Vosk confidence mendekati cloud, turunkan threshold
        // Jika Vosk confidence jauh lebih rendah, naikkan threshold
        val currentThreshold = getThreshold()
        val diff = cloudConfidence - voskConfidence

        val newThreshold = when {
            diff < 0.1f -> (currentThreshold - 0.02f).coerceAtLeast(0.5f)  // Vosk bagus
            diff > 0.3f -> (currentThreshold + 0.05f).coerceAtMost(0.95f) // Vosk jelek
            else -> currentThreshold
        }

        prefs.edit().putFloat("confidence_threshold", newThreshold).apply()
    }

    fun getCalibrationCount(): Int {
        return prefs.getInt("calibration_count", 0)
    }

    fun incrementCalibrationCount() {
        val count = getCalibrationCount() + 1
        prefs.edit().putInt("calibration_count", count).apply()
    }
}

/**
 * Google Cloud STT (cloud fallback).
 */
class GoogleCloudSTT(private val apiKey: String?) {

    companion object {
        private const val BASE_URL = "https://speech.googleapis.com/v1/speech:recognize"
    }

    val isAvailable: Boolean
        get() = !apiKey.isNullOrBlank()

    /**
     * Transkripsi audio via Google Cloud Speech-to-Text.
     */
    suspend fun transcribe(audioData: ShortArray, language: String = "id-ID"): STTResult {
        if (apiKey.isNullOrBlank()) {
            return STTResult("", 0f, true)
        }

        return withContext(Dispatchers.IO) {
            try {
                // Convert ShortArray to base64 WAV
                val wavBytes = shortArrayToWav(audioData, 16000)
                val base64Audio = android.util.Base64.encodeToString(wavBytes, android.util.Base64.NO_WRAP)

                val requestBody = buildGoogleSTTRequest(base64Audio, language)
                val response = doPost(requestBody)

                parseGoogleSTTResponse(response)
            } catch (e: Exception) {
                STTResult("", 0f, true)
            }
        }
    }

    private fun buildGoogleSTTRequest(base64Audio: String, language: String): String {
        val json = org.json.JSONObject()
        val config = org.json.JSONObject().apply {
            put("encoding", "LINEAR16")
            put("sampleRateHertz", 16000)
            put("languageCode", language)
            put("enableAutomaticPunctuation", true)
        }
        val audio = org.json.JSONObject().apply {
            put("content", base64Audio)
        }
        json.put("config", config)
        json.put("audio", audio)
        return json.toString()
    }

    private fun doPost(body: String): String {
        val url = java.net.URL("$BASE_URL?key=$apiKey")
        val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
        }

        connection.outputStream.use { os ->
            java.io.OutputStreamWriter(os).use { writer ->
                writer.write(body)
                writer.flush()
            }
        }

        return if (connection.responseCode == 200) {
            connection.inputStream.bufferedReader().readText()
        } else {
            "{}"
        }
    }

    private fun parseGoogleSTTResponse(response: String): STTResult {
        return try {
            val json = org.json.JSONObject(response)
            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) {
                return STTResult("", 0f, true)
            }
            val alternative = results.getJSONObject(0)
                .getJSONArray("alternatives").getJSONObject(0)
            val text = alternative.getString("transcript")
            val confidence = alternative.optDouble("confidence", 0.0).toFloat()
            STTResult(text = text, confidence = confidence, isFinal = true)
        } catch (e: Exception) {
            STTResult("", 0f, true)
        }
    }

    /**
     * Konversi ShortArray ke WAV bytes.
     */
    private fun shortArrayToWav(audio: ShortArray, sampleRate: Int): ByteArray {
        val dataLength = audio.size * 2
        val totalLength = 44 + dataLength
        val wav = ByteArray(totalLength)

        // WAV header
        wav[0] = 'R'.code.toByte(); wav[1] = 'I'.code.toByte()
        wav[2] = 'F'.code.toByte(); wav[3] = 'F'.code.toByte()
        intToBytes(36 + dataLength, wav, 4)
        wav[8] = 'W'.code.toByte(); wav[9] = 'A'.code.toByte()
        wav[10] = 'V'.code.toByte(); wav[11] = 'E'.code.toByte()
        wav[12] = 'f'.code.toByte(); wav[13] = 'm'.code.toByte()
        wav[14] = 't'.code.toByte(); wav[15] = ' '.code.toByte()
        intToBytes(16, wav, 16)
        // PCM format
        wav[20] = 1; wav[21] = 0 // PCM
        wav[22] = 1; wav[23] = 0 // Mono
        intToBytes(sampleRate, wav, 24)
        intToBytes(sampleRate * 2, wav, 28) // Byte rate
        wav[32] = 2; wav[33] = 0 // Block align
        wav[34] = 16; wav[35] = 0 // Bits per sample
        intToBytes(dataLength, wav, 40)

        for (i in audio.indices) {
            val sample = audio[i].toInt()
            wav[44 + i * 2] = (sample and 0xFF).toByte()
            wav[44 + i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        return wav
    }

    private fun intToBytes(value: Int, array: ByteArray, offset: Int) {
        array[offset] = (value and 0xFF).toByte()
        array[offset + 1] = ((value shr 8) and 0xFF).toByte()
        array[offset + 2] = ((value shr 16) and 0xFF).toByte()
        array[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}

class CalibratedSTTRouter(
    private val vosk: VoskEngine,
    private val googleCloud: GoogleCloudSTT,
    private val calibrationStore: CalibrationStore
) {
    /**
     * Transkripsi audio berdasarkan mode.
     */
    suspend fun transcribe(audio: ShortArray, mode: STTMode): String {
        return when (mode) {
            STTMode.GRAMMAR -> vosk.transcribeGrammar(audio).text
            STTMode.FREE -> {
                val voskResult = vosk.transcribeFull(audio)
                val userThreshold = calibrationStore.getThreshold()

                if (voskResult.confidence >= userThreshold) {
                    voskResult.text
                } else {
                    // Fallback ke cloud
                    val cloudResult = googleCloud.transcribe(audio)
                    calibrationStore.update(voskResult.confidence, cloudResult.confidence)
                    calibrationStore.incrementCalibrationCount()
                    cloudResult.text
                }
            }
            STTMode.ALWAYS_CLOUD -> googleCloud.transcribe(audio).text
        }
    }

    /**
     * Tentukan mode STT berdasarkan intent.
     */
    fun resolveMode(isWakeWord: Boolean, intentType: IntentType): STTMode {
        return when {
            isWakeWord -> STTMode.GRAMMAR
            intentType == IntentType.COMMAND -> STTMode.GRAMMAR
            intentType == IntentType.CHAT -> STTMode.FREE
            else -> STTMode.FREE
        }
    }
}

/**
 * Klasifikasi intent dari teks hasil STT.
 */
enum class IntentType {
    CHAT,
    COMMAND,
    SYSTEM,
    INFO_REQUEST
}

/**
 * Klasifikasi intent sederhana.
 */
class IntentClassifier(private val matcher: FuzzyCommandMatcher) {

    fun classify(text: String): IntentType {
        val match = matcher.match(text)
        return when (match?.command) {
            "wake", "stop", "freeze" -> IntentType.COMMAND
            "send_sms", "place_call", "set_alarm", "screen_read" -> IntentType.COMMAND
            "web_fetch", "weather", "time" -> IntentType.INFO_REQUEST
            else -> IntentType.CHAT
        }
    }
}