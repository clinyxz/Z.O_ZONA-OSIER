/**
 * ZONA-OSIER — MiniMaxCreditChecker.
 * Cek ketersediaan kredit MiniMax sebelum routing TTS.
 *
 * MiniMax Speech 2.8 = berbayar ($60-100/juta karakter).
 * Tidak ada tier gratis. Jika user tidak punya kredit,
 * langsung fallback ke ElevenLabs atau TTS lokal.
 */
package com.zonaosier.voice.tts

import android.content.Context
import com.zonaosier.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MiniMaxCreditChecker(context: Context) {

    private val prefs = context.getSharedPreferences("minimax_tts", Context.MODE_PRIVATE)
    private val apiKey: String? = BuildConfig.MINIMAX_API_KEY.ifBlank { null }

    companion object {
        private const val BASE_URL = "https://api.minimax.chat/v1"
        private const val CHECK_INTERVAL_MS = 60_000L // Cek setiap 1 menit
        private const val CREDIT_KEY = "has_credit"
        private const val LAST_CHECK_KEY = "last_check"
    }

    /**
     * Cek apakah ada kredit tersedia.
     * Menggunakan cache agar tidak spam API.
     */
    fun hasAvailableCredit(): Boolean {
        if (apiKey.isNullOrBlank()) return false

        val lastCheck = prefs.getLong(LAST_CHECK_KEY, 0)
        val now = System.currentTimeMillis()

        if (now - lastCheck < CHECK_INTERVAL_MS) {
            return prefs.getBoolean(CREDIT_KEY, false)
        }

        return false
    }

    /**
     * Cek kredit ke API (coroutine).
     */
    suspend fun checkCreditAsync(): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) return@withContext false

        try {
            val url = URL("$BASE_URL/text/chatcompletion_v2")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }

            // Kirim minimal request untuk cek auth
            val body = JSONObject().apply {
                put("model", "abab6.5s-chat")
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "test")
                    })
                })
                put("max_tokens", 1)
            }

            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            val hasCredit = responseCode != 401 && responseCode != 402 && responseCode != 403

            prefs.edit()
                .putBoolean(CREDIT_KEY, hasCredit)
                .putLong(LAST_CHECK_KEY, System.currentTimeMillis())
                .apply()

            hasCredit
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Invalidate cache (paksa recheck).
     */
    fun invalidateCache() {
        prefs.edit().remove(CREDIT_KEY).remove(LAST_CHECK_KEY).apply()
    }
}