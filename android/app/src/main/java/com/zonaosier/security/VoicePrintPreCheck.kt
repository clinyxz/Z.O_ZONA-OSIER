/**
 * ZONA-OSIER — Voice-Print Pre-Check (Layer 4).
 * 
 * Verifikasi speaker ringan menggunakan Picovoice Eagle sebagai pre-check
 * sebelum biometric prompt untuk perintah destruktif lewat suara.
 * 
 * ⚠️ LIMITASI: Picovoice Eagle free tier = 3 active users/bulan.
 * ⚠️ CRITICAL: Voice-print BUKAN pengganti BiometricPrompt.
 *    Selalu fallback ke BiometricPrompt jika Eagle gagal/timeout.
 * 
 * Arsitektur integrasi:
 *   Wake word → Eagle voice-print (~100-200ms, on-device)
 *     ├─ Match → Lanjut ke Intent Classifier (tanpa sentuh layar)
 *     └─ No match → Minta BiometricPrompt
 * 
 *   Untuk operasi destruktif:
 *     Voice-print match → TETAP minta BiometricPrompt → Baru eksekusi
 */
package com.zonaosier.security

import ai.picovoice.eagle.*
import com.zonaosier.BuildConfig
import com.zonaosier.memory.entity.AuditStatus
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Pre-check ringan menggunakan Picovoice Eagle.
 * Timeout 300ms agar tidak mengganggu UX percakapan real-time.
 */
class VoicePrintPreCheck(
    private val eagle: Eagle? = null
) {
    /**
     * Daftar profil suara yang terdaftar.
     * Key = speaker ID, Value = profile yang berisi embedding.
     */
    private val enrolledProfiles = mutableMapOf<String, EagleProfile>()

    /**
     * Coba enrol speaker dengan sampel audio.
     * Perlu beberapa sampel (3-5) dari berbagai environment.
     *
     * @param pcmData Audio PCM 16-bit mono, sample rate sesuai konfigurasi Eagle.
     * @return Speaker ID jika enrol berhasil.
     */
    fun enroll(pcmData: FloatArray): EnrollResult {
        val eagleInstance = eagle ?: return EnrollResult.Error("Eagle tidak diinisialisasi. Cek PICOVOICE_ACCESS_KEY di local.properties.")

        return try {
            val profile = eagleInstance.enroll(pcmData)
            val speakerId = UUID.randomUUID().toString()
            enrolledProfiles[speakerId] = profile
            EnrollResult.Success(speakerId, enrolledProfiles.size)
        } catch (e: EagleException) {
            EnrollResult.Error("Enroll gagal: ${e.message}")
        } catch (e: Exception) {
            EnrollResult.Error("Error: ${e.message}")
        }
    }

    /**
     * Periksa apakah sampel audio cocok dengan salah satu profil terdaftar.
     * Timeout 300ms — jika melewati, langsung fallback ke BiometricPrompt.
     *
     * @param pcmData Audio PCM 16-bit mono.
     * @return [PreCheckResult] yang menentukan langkah selanjutnya.
     */
    suspend fun verify(pcmData: FloatArray): PreCheckResult {
        // Jika Eagle tidak tersedia atau tidak ada profil, langsung fallback
        val eagleInstance = eagle ?: return PreCheckResult.FallbackToBiometric("Eagle tidak tersedia")
        if (enrolledProfiles.isEmpty()) return PreCheckResult.FallbackToBiometric("Tidak ada profil terdaftar")

        // Timeout 300ms — hard constraint untuk UX real-time
        return withTimeoutOrNull(TIMEOUT_MS) {
            val scores = eagleInstance.process(pcmData)

            if (scores == null || scores.isEmpty()) {
                return@withTimeoutOrNull PreCheckResult.FallbackToBiometric("Eagle.process() mengembalikan null")
            }

            // Cari skor tertinggi di semua profil
            var bestScore = 0f
            var bestSpeakerId: String? = null

            enrolledProfiles.entries.forEachIndexed { index, (speakerId, _) ->
                if (index < scores.size && scores[index] > bestScore) {
                    bestScore = scores[index]
                    bestSpeakerId = speakerId
                }
            }

            // Threshold dasar 0.5 — bisa di-tune per environment oleh RobustVoicePrint
            when {
                bestScore >= THRESHOLD_MATCH -> PreCheckResult.Match(bestScore, bestSpeakerId!!)
                bestScore >= THRESHOLD_TOLERANCE -> PreCheckResult.LowConfidence(bestScore, bestSpeakerId!!)
                else -> PreCheckResult.FallbackToBiometric("Score $bestScore di bawah threshold $THRESHOLD_TOLERANCE")
            }
        } ?: PreCheckResult.FallbackToBiometric("Timeout ${TIMEOUT_MS}ms tercapai")
    }

    /**
     * Cek apakah Eagle tersedia (key terkonfigurasi, SDK loaded).
     */
    fun isAvailable(): Boolean {
        return eagle != null && BuildConfig.PICOVOICE_ACCESS_KEY.isNotBlank()
    }

    /**
     * Jumlah profil yang terdaftar.
     * Free tier: maks 3 active users/bulan.
     */
    fun profileCount(): Int = enrolledProfiles.size

    // ==================== Result Types ====================

    sealed class EnrollResult {
 data class Success(val speakerId: String, val totalProfiles: Int) : EnrollResult()
        data class Error(val message: String) : EnrollResult()
    }

    sealed class PreCheckResult {
        /** Suara cocok dengan profil terdaftar. */
        data class Match(val score: Float, val speakerId: String) : PreCheckResult()

        /** Skor rendah — cocok tapi kurang yakin. Bisa lanjut atau fallback. */
        data class LowConfidence(val score: Float, val speakerId: String) : PreCheckResult()

        /** Tidak cocok atau Eagle gagal — WAJIB fallback ke BiometricPrompt. */
        data class FallbackToBiometric(val reason: String) : PreCheckResult()

        val isMatch: Boolean get() = this is Match
        val isFallback: Boolean get() = this is FallbackToBiometric
    }

    companion object {
        /** Timeout pre-check dalam milidetik. 300ms = hard constraint UX. */
        const val TIMEOUT_MS = 300L

        /** Threshold kecocokan — di atas ini dianggap match. */
        const val THRESHOLD_MATCH = 0.65f

        /** Threshold toleransi rendah — di antara TOLERANCE dan MATCH = low confidence. */
        const val THRESHOLD_TOLERANCE = 0.45f
    }
}
