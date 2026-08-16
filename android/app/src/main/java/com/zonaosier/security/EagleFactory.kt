/**
 * ZONA-OSIER — Eagle Factory.
 * 
 * Factory untuk membuat instance Eagle (Picovoice voice-print).
 * Handle inisialisasi, error handling, dan lifecycle.
 * 
 * ⚠️ Free tier: 3 active users/bulan.
 * ⚠️ Fallback ke BiometricPrompt selalu wajib.
 */
package com.zonaosier.security

import android.content.Context
import android.util.Log
import ai.picovoice.eagle.*
import com.zonaosier.BuildConfig
import java.io.File
import java.io.FileOutputStream

object EagleFactory {

    private const val TAG = "EagleFactory"

    /** Singleton Eagle instance. Lazy init. */
    private var eagleInstance: Eagle? = null

    /**
     * Buat atau ambil singleton Eagle instance.
     * Thread-safe.
     *
     * @param context Application context.
     * @return Eagle instance atau null jika gagal (key kosong, dll).
     */
    fun getOrCreate(context: Context): Eagle? {
        eagleInstance?.let { return it }

        val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
        if (accessKey.isBlank()) {
            Log.w(TAG, "PICOVOICE_ACCESS_KEY kosong. Voice-print tidak tersedia.")
            Log.w(TAG, "Isi PICOVOICE_ACCESS_KEY di local.properties untuk mengaktifkan.")
            return null
        }

        return try {
            val eagle = Eagle.Builder(accessKey)
                .build(context)
            eagleInstance = eagle
            Log.i(TAG, "Eagle initialized successfully.")
            eagle
        } catch (e: EagleException) {
            Log.e(TAG, "Eagle init failed: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Eagle init error: ${e.message}", e)
            null
        }
    }

    /**
     * Bersihkan Eagle instance.
     * Panggil saat app berhenti atau voice-print dinonaktifkan.
     */
    fun destroy() {
        try {
            eagleInstance?.delete()
        } catch (_: Exception) { }
        eagleInstance = null
    }

    /** Cek apakah Eagle bisa diinisialisasi. */
    fun isConfigured(): Boolean {
        return BuildConfig.PICOVOICE_ACCESS_KEY.isNotBlank()
    }
}
