/**
 * ZONA-OSIER — Tool Security Executor.
 * 
 * Menggabungkan seluruh 6 lapisan keamanan dalam satu alur eksekusi:
 * 
 *   Layer 1: Input Sanitization (hapus ;|&$) — di AgentLoop
 *   Layer 2: ShellSecurityPolicy (validate command) — di sini
 *   Layer 3: FilteredToolRegistry (character policy) — sebelum masuk sini
 *   Layer 4: Voice-Print Pre-Check (Picovoice Eagle)
 *   Layer 5: BiometricPrompt (operasi destruktif)
 *   Layer 6: AuditLogger (catat semua)
 * 
 * Setiap tool call MELEWATI executor ini sebelum dieksekusi.
 */
package com.zonaosier.security

import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolCall
import com.zonaosier.agent.ToolResult
import com.zonaosier.memory.entity.AuditStatus
import kotlinx.coroutines.coroutineScope

/**
 * Executor yang menjalankan tool dengan 6 lapisan keamanan.
 * 
 * Alur untuk setiap tool call:
 * 1. Cek FreezeAgent — jika frozen, tolak semua
 * 2. Cek ShellSecurityPolicy — jika tool adalah shell, validasi command
 * 3. Voice-Print Pre-Check (opsional, jika voiceprint aktif)
 * 4. BiometricPrompt — jika tool isDestructive/requiresBiometric
 * 5. Eksekusi tool
 * 6. Catat ke AuditLog
 */
class ToolSecurityExecutor(
    private val freezeAgent: FreezeAgent,
    private val shellPolicy: ShellSecurityPolicy,
    private val voicePrint: RobustVoicePrint? = null,
    private val biometricGate: BiometricToolGate? = null
) {

    /**
     * Eksekusi tool call dengan semua lapisan keamanan.
     *
     * @param tool Tool yang akan dieksekusi.
     * @param toolCall Data tool call dari LLM.
     * @param pcmData Audio PCM untuk voice-print (opsional).
     * @param characterId ID karakter aktif.
     * @return [ToolResult].
     */
    suspend fun execute(
        tool: Tool,
        toolCall: ToolCall,
        pcmData: FloatArray? = null,
        characterId: String? = null
    ): ToolResult = coroutineScope {
        // ===== Layer 0: Freeze Check =====
        if (freezeAgent.isFrozen()) {
            AuditLogger.log(
                toolName = tool.name,
                action = "execute",
                status = AuditStatus.REJECTED,
                detail = "Agent dibekukan (FreezeAgent aktif)",
                characterId = characterId
            )
            return@coroutineScope ToolResult.Error("Agent sedang dibekukan. Unfreeze terlebih dahulu.")
        }

        // ===== Layer 2: Shell Security Policy =====
        if (tool.name in SHELL_TOOLS) {
            val command = toolCall.arguments["command"] as? List<*>
            if (command != null) {
                val cmdStrings = command.filterIsInstance<String>()
                when (val result = shellPolicy.validate(cmdStrings)) {
                    is ShellSecurityPolicy.ValidationResult.Rejected -> {
                        AuditLogger.logShellRejected(cmdStrings, result.rejectionReason ?: "Unknown", characterId)
                        return@coroutineScope ToolResult.Error("Perintah shell ditolak: ${result.rejectionReason}")
                    }
                    is ShellSecurityPolicy.ValidationResult.Approved -> {
                        AuditLogger.logShellApproved(cmdStrings, characterId)
                    }
                }
            }
        }

        // ===== Layer 4: Voice-Print Pre-Check (opsional) =====
        if (voicePrint != null && pcmData != null && voicePrint.isEnabled()) {
            val voiceResult = voicePrint.authenticate(pcmData)
            AuditLogger.logVoicePrint(
                score = (voiceResult as? RobustVoicePrint.AuthResult.VoiceMatch)?.score,
                fallback = voiceResult.isFallback,
                characterId = characterId
            )
            // Voice-print BUKAN pengganti biometric — hanya log, lanjut ke layer 5
        }

        // ===== Layer 5: Biometric Gate (hanya untuk tool destruktif) =====
        if (BiometricToolGate.requiresBiometric(tool.isDestructive, tool.requiresBiometric)) {
            if (biometricGate == null) {
                AuditLogger.log(
                    toolName = tool.name,
                    action = "execute",
                    status = AuditStatus.REJECTED,
                    detail = "Tool destruktif tapi BiometricGate tidak tersedia",
                    characterId = characterId
                )
                return@coroutineScope ToolResult.Error("Operasi destruktif memerlukan verifikasi biometrik, tapi gate tidak tersedia.")
            }

            val authenticated = biometricGate.authenticate(tool.name, characterId)
            if (!authenticated) {
                // Sudah di-log oleh BiometricGate
                return@coroutineScope ToolResult.Error("Verifikasi biometrik gagal atau dibatalkan.")
            }
        }

        // ===== Eksekusi Tool =====
        try {
            val result = tool.execute(toolCall.arguments)

            // ===== Layer 6: Audit Log (result) =====
            AuditLogger.log(
                toolName = tool.name,
                action = "execute",
                status = when (result) {
                    is ToolResult.Success -> AuditStatus.APPROVED
                    is ToolResult.Error -> AuditStatus.ERROR
                },
                detail = when (result) {
                    is ToolResult.Success -> "Output: ${result.output.take(500)}"
                    is ToolResult.Error -> result.message
                },
                characterId = characterId
            )

            result
        } catch (e: Exception) {
            AuditLogger.log(
                toolName = tool.name,
                action = "execute",
                status = AuditStatus.ERROR,
                detail = "Exception: ${e.message}",
                characterId = characterId
            )
            ToolResult.Error("Gagal mengeksekusi ${tool.name}: ${e.message}")
        }
    }

    companion object {
        /** Tool yang menggunakan shell execution dan butuh ShellSecurityPolicy. */
        private val SHELL_TOOLS = setOf(
            "termux_exec", "shizuku_shell"
        )
    }
}
