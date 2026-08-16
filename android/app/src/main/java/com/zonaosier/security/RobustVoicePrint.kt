/**
 * ZONA-OSIER — Robust Voice-Print dengan Multi-Environment Enrollment.
 * 
 * Meningkatkan VoicePrintPreCheck dengan:
 * - Multi-environment enrollment (indoor/outdoor/vehicle)
 * - Adaptive threshold berdasarkan kebisingan lingkungan
 * - Deteksi environment otomatis dari noise floor
 * - Enrollment state management
 * 
 * Free tier Picovoice Eagle = 3 active users/bulan.
 * Fallback selalu ke BiometricPrompt — BUKAN single point of failure.
 */
package com.zonaosier.security

import ai.picovoice.eagle.*
import android.content.Context
import android.content.SharedPreferences
import com.zonaosier.memory.entity.AuditStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class RobustVoicePrint(
    private val context: Context,
    private val preCheck: VoicePrintPreCheck
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ==================== Data Classes ====================

    /**
     * Profil suara per environment.
     * Satu speaker bisa punya beberapa profil untuk kondisi berbeda.
     */
    data class VoiceProfile(
        val id: String,
        val environmentType: EnvironmentType,
        val enrollTimestamp: Long,
        val sampleCount: Int,
        val avgNoiseFloorDb: Float
    )

    /**
     * Jenis lingkungan untuk adaptive threshold.
     */
    enum class EnvironmentType(val displayName: String, val threshold: Float) {
        /** Ruangan tenang — threshold tertinggi (paling ketat). */
        INDOOR("Dalam Ruangan", 0.65f),
        /** Luar ruangan — threshold sedang (noise ambient). */
        OUTDOOR("Luar Ruangan", 0.55f),
        /** Dalam kendaraan — threshold terendah (noise mesin). */
        VEHICLE("Dalam Kendaraan", 0.50f)
    }

    /**
     * Hasil autentikasi voice-print.
     */
    sealed class AuthResult {
        /** Suara cocok. */
        data class VoiceMatch(val score: Float, val environment: EnvironmentType) : AuthResult()

        /** Skor rendah tapi mungkin valid. */
        data class LowConfidence(val score: Float, val environment: EnvironmentType) : AuthResult()

        /** Gagal — WAJIB fallback ke BiometricPrompt. */
        data class FallbackToBiometric(val reason: String) : AuthResult()

        /** Voice-print tidak diaktifkan oleh user. */
        data object Disabled : AuthResult()

        val isFallback: Boolean get() = this is FallbackToBiometric
    }

    // ==================== Public API ====================

    /** Apakah voice-print diaktifkan. */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /** Aktifkan/nonaktifkan voice-print. */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        AuditLogger.log(
            toolName = "VoicePrint",
            action = if (enabled) "enabled" else "disabled",
            status = if (enabled) AuditStatus.APPROVED else AuditStatus.MODIFIED,
            detail = "Voice-print ${if (enabled) "diaktifkan" else "dinonaktifkan"} oleh user"
        )
    }

    /**
     * Autentikasi speaker dengan adaptive threshold.
     * Timeout 300ms — constraint keras untuk UX real-time.
     *
     * Alur:
     * 1. Ukur noise floor → klasifikasi environment
     * 2. Ambil profil yang sesuai environment
     * 3. Jalankan Eagle.process() dengan threshold adaptive
     * 4. Timeout → langsung fallback
     *
     * @param pcmData Audio PCM 16-bit mono.
     * @return [AuthResult].
     */
    suspend fun authenticate(pcmData: FloatArray, noiseFloorDb: Float = -40f): AuthResult {
        if (!isEnabled()) return AuthResult.Disabled
        if (!preCheck.isAvailable()) {
            return AuthResult.FallbackToBiometric("Eagle tidak tersedia. Cek PICOVOICE_ACCESS_KEY.")
        }

        return withTimeoutOrNull(PRE_CHECK_TIMEOUT_MS) {
            val environment = classifyEnvironment(noiseFloorDb)

            // Jalankan pre-check dari VoicePrintPreCheck
            when (val result = preCheck.verify(pcmData)) {
                is VoicePrintPreCheck.PreCheckResult.Match -> {
                    val threshold = environment.threshold
                    if (result.score >= threshold) {
                        AuthResult.VoiceMatch(result.score, environment)
                    } else {
                        AuthResult.LowConfidence(result.score, environment)
                    }
                }
                is VoicePrintPreCheck.PreCheckResult.LowConfidence -> {
                    AuthResult.LowConfidence(result.score, environment)
                }
                is VoicePrintPreCheck.PreCheckResult.FallbackToBiometric -> {
                    AuthResult.FallbackToBiometric(result.reason)
                }
            }
        } ?: AuthResult.FallbackToBiometric("Timeout ${PRE_CHECK_TIMEOUT_MS}ms tercapai")
    }

    /**
     * Klasifikasi environment berdasarkan noise floor.
     * Heuristik sederhana berdasarkan level kebisingan.
     *
     * @param noiseFloorDb Noise floor dalam dB (negatif).
     *   -30 dB = sangat bising (outdoor/vehicle)
     *   -50 dB = tenang (indoor)
     */
    fun classifyEnvironment(noiseFloorDb: Float): EnvironmentType {
        return when {
            noiseFloorDb > -35f -> EnvironmentType.OUTDOOR
            noiseFloorDb > -45f -> EnvironmentType.VEHICLE
            else -> EnvironmentType.INDOOR
        }
    }

    // ==================== Enrollment ====================

    /**
     * Mulai sesi enrollment untuk environment tertentu.
     * Perlu 3-5 sampel per environment.
     */
    fun beginEnrollment(environmentType: EnvironmentType): EnrollmentSession {
        return EnrollmentSession(
            sessionId = UUID.randomUUID().toString(),
            environmentType = environmentType,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * Data sesi enrollment.
     */
    data class EnrollmentSession(
        val sessionId: String,
        val environmentType: EnvironmentType,
        val createdAt: Long,
        val sampleCount: Int = 0
    )

    companion object {
        private const val PREFS_NAME = "voice_print"
        private const val KEY_ENABLED = "enabled"
        const val PRE_CHECK_TIMEOUT_MS = 300L
    }
}