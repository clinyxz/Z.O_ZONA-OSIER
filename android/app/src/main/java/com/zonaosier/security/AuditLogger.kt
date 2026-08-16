/**
 * ZONA-OSIER — Audit Logger (Layer 6).
 * 
 * Mencatat SETIAP eksekusi tool (lolos maupun ditolak) ke Room DB.
 * - Append-only — tidak ada update atau delete publik.
 * - Terenkripsi di disk (SQLCipher / AndroidX Security Crypto di level DB).
 * - Setiap entry memiliki characterId untuk audit per-karakter.
 * - Validity labeling: ✅ APPROVED, 🔧 MODIFIED, ⚠️ REJECTED, ❌ ERROR.
 * 
 * Dipanggil oleh semua 5 lapisan keamanan lainnya:
 *   - AgentLoop.sanitizeToolCall → log sanitasi
 *   - ShellSecurityPolicy → log validasi shell
 *   - FilteredToolRegistry → log filter karakter
 *   - VoicePrintPreCheck → log voice match/reject
 *   - BiometricToolGate → log biometric result
 */
package com.zonaosier.security

import com.zonaosier.ZonaOsierApp
import com.zonaosier.memory.dao.AuditDao
import com.zonaosier.memory.entity.AuditEntry
import com.zonaosier.memory.entity.AuditStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

object AuditLogger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Catat entry audit ke Room DB.
     * Thread-safe, non-blocking (fire-and-forget via coroutine scope).
     *
     * @param toolName Tool yang dipanggil.
     * @param action Aksi spesifik (validate, execute, biometric_request, freeze, dll).
     * @param status Status validasi.
     * @param detail Detail pesan.
     * @param characterId ID karakter aktif (bisa null untuk aksi global).
     */
    fun log(
        toolName: String,
        action: String,
        status: AuditStatus,
        detail: String,
        characterId: String? = null
    ) {
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            toolName = toolName,
            action = action,
            status = status,
            detail = detail,
            characterId = characterId,
            timestamp = System.currentTimeMillis()
        )
        scope.launch {
            try {
                getDao().insert(entry)
            } catch (_: Exception) {
                // Jangan crash app jika audit log gagal.
                // Fallback: log ke logcat.
                android.util.Log.w("AuditLogger", "Failed to insert audit: $detail")
            }
        }
    }

    /**
     * Log status pending — untuk tracking request yang belum selesai.
     */
    fun logPending(
        toolName: String,
        action: String,
        characterId: String? = null
    ) {
        log(
            toolName = toolName,
            action = action,
            status = AuditStatus.MODIFIED,
            detail = "Pending — menunggu respons",
            characterId = characterId
        )
    }

    /**
     * Log shell command yang ditolak oleh ShellSecurityPolicy.
     */
    fun logShellRejected(command: List<String>, reason: String, characterId: String? = null) {
        log(
            toolName = "ShellSecurityPolicy",
            action = "validate_rejected",
            status = AuditStatus.REJECTED,
            detail = "Ditolak: $reason | Command: ${command.joinToString(" ")}",
            characterId = characterId
        )
    }

    /**
     * Log shell command yang disetujui.
     */
    fun logShellApproved(command: List<String>, characterId: String? = null) {
        log(
            toolName = "ShellSecurityPolicy",
            action = "validate_approved",
            status = AuditStatus.APPROVED,
            detail = "Disetujui: ${command.joinToString(" ")}",
            characterId = characterId
        )
    }

    /**
     * Log tool yang ditolak oleh FilteredToolRegistry (kebijakan karakter).
     */
    fun logToolFiltered(toolName: String, characterId: String) {
        log(
            toolName = "FilteredToolRegistry",
            action = "tool_filtered",
            status = AuditStatus.REJECTED,
            detail = "Tool '$toolName' tidak diizinkan oleh kebijakan karakter $characterId",
            characterId = characterId
        )
    }

    /**
     * Log voice-print result.
     */
    fun logVoicePrint(score: Float?, fallback: Boolean, characterId: String? = null) {
        val status = if (score != null && score >= VoicePrintPreCheck.THRESHOLD_MATCH) {
            AuditStatus.APPROVED
        } else if (fallback) {
            AuditStatus.MODIFIED
        } else {
            AuditStatus.REJECTED
        }
        log(
            toolName = "VoicePrint",
            action = "verify",
            status = status,
            detail = "Score: ${score ?: "null"}, Fallback: $fallback",
            characterId = characterId
        )
    }

    // ==================== Internal ====================

    private fun getDao(): AuditDao {
        return ZonaOsierApp.instance.database.auditDao()
    }
}
