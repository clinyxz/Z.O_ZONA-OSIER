/**
 * ZONA-OSIER — Biometric Tool Gate (Layer 5).
 * 
 * Meminta verifikasi biometrik (sidik jari/face) sebelum mengeksekusi
 * tool yang bersifat destruktif (isDestructive == true atau requiresBiometric == true).
 * 
 * Menggunakan BiometricPrompt (API 28+) dengan suspendCancellableCoroutine
 * agar bisa dipanggil dari coroutine tanpa blocking thread utama.
 * 
 * CRITICAL: BiometricPrompt BUKAN opsional untuk tool destruktif.
 * Voice-print (Picovoice Eagle) hanya speed bump, BUKAN pengganti.
 */
package com.zonaosier.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.zonaosier.security.AuditLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Gate yang meminta autentikasi biometrik sebelum operasi destruktif.
 * Harus diinisialisasi dengan Activity context (bukan Application).
 */
class BiometricToolGate(private val activity: FragmentActivity) {

    private val executor = ContextCompat.getMainExecutor(activity)

    /**
     * Cek apakah device mendukung biometrik.
     * @return true jika biometrik tersedia dan terdaftar.
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(activity)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Minta autentikasi biometrik untuk operasi tertentu.
     * Fungsi suspend — bisa dipanggil dari coroutine.
     *
     * @param toolName Nama tool yang meminta autentikasi (untuk label UI)
     * @param characterId ID karakter aktif (untuk audit log)
     * @return true jika autentikasi berhasil, false jika gagal/dibatalkan
     */
    suspend fun authenticate(toolName: String, characterId: String? = null): Boolean {
        // Log awal request
        AuditLogger.logPending(toolName, "biometric_request", characterId)

        val result = suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // Autentikasi berhasil
                    AuditLogger.log(
                        toolName = toolName,
                        action = "biometric_success",
                        status = com.zonaosier.memory.entity.AuditStatus.APPROVED,
                        detail = "Biometric authenticated for $toolName",
                        characterId = characterId
                    )
                    continuation.resume(true)
                }

                override fun onAuthenticationFailed() {
                    // Biometric tidak dikenali (bukan error sistem)
                    AuditLogger.log(
                        toolName = toolName,
                        action = "biometric_failed",
                        status = com.zonaosier.memory.entity.AuditStatus.REJECTED,
                        detail = "Biometric tidak dikenali untuk $toolName",
                        characterId = characterId
                    )
                    continuation.resume(false)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Error sistem (hardware error, user membatalkan, dll)
                    val isCancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                       errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON

                    AuditLogger.log(
                        toolName = toolName,
                        action = "biometric_error",
                        status = if (isCancelled) com.zonaosier.memory.entity.AuditStatus.REJECTED
                                else com.zonaosier.memory.entity.AuditStatus.ERROR,
                        detail = "Biometric error ($errorCode): $errString for $toolName",
                        characterId = characterId
                    )
                    continuation.resume(false)
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Konfirmasi Aksi: $toolName")
                .setSubtitle("Aksi ini memerlukan verifikasi biometrik")
                .setNegativeButtonText("Batal")
                .setConfirmationRequired(true)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()

            prompt.authenticate(promptInfo)
        }

        return result
    }

    companion object {
        /**
         * Cek apakah tool membutuhkan biometrik berdasarkan propertinya.
         * Dipanggil oleh tool executor sebelum invoke.
         */
        fun requiresBiometric(isDestructive: Boolean, requiresBiometric: Boolean): Boolean {
            return isDestructive || requiresBiometric
        }
    }
}
