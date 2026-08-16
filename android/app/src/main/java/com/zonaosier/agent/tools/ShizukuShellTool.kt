/**
 * ZONA-OSIER — ShizukuShellTool.
 * Eksekusi command shell via Shizuku (uid=2000, setara ADB).
 * Command wajib melewati ShellSecurityPolicy.
 */
package com.zonaosier.agent.tools

import android.content.Context
import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import com.zonaosier.security.AuditLogger
import com.zonaosier.security.FreezeAgent
import com.zonaosier.security.ShellSecurityPolicy
import rikka.shizuku.Shizuku

class ShizukuShellTool(
    private val context: Context,
    private val freezeAgent: FreezeAgent
) : Tool {

    override val name: String = "shizuku_shell"
    override val description: String =
        "Eksekusi command shell via Shizuku (setara ADB, tanpa root). " +
        "Argumen: 'command' (string), 'args' (array string). " +
        "Binary wajib di allowlist: ls, cat, cp, mv, pm, am, svc, settings, appops."
    override val parameters: String = """
        {\n            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Nama binary"},
                "args": {"type": "array", "items": {"type": "string"}, "description": "Argumen"}
            },
            "required": ["command"]
        }
    """.trimIndent()
    override val isDestructive: Boolean = true
    override val requiresBiometric: Boolean = true

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        // Cek freeze
        if (freezeAgent.isFrozen()) {
            AuditLogger.logShellRejected("shizuku_shell", "Agent dibekukan (freeze active)")
            return ToolResult.Error("Agent sedang dibekukan. Unfreeze terlebih dahulu.")
        }

        // Cek Shizuku availability
        if (!Shizuku.pingBinder()) {
            return ToolResult.Error(
                "Shizuku tidak tersedia. Pastikan Shizuku aktif."
            )
        }

        val command = args["command"]?.toString()?.trim()
            ?: return ToolResult.Error("Argumen 'command' wajib diisi.")

        val argList = when (val a = args["args"]) {
            is List<*> -> a.map { it.toString() }
            is String -> listOf(a)
            else -> emptyList()
        }

        val fullCommand = listOf(command) + argList

        // Layer 2: ShellSecurityPolicy
        val validationResult = ShellSecurityPolicy.validate(fullCommand)
        if (validationResult is ShellSecurityPolicy.ValidationResult.Rejected) {
            AuditLogger.logShellRejected("shizuku_shell", validationResult.reason)
            return ToolResult.Error("Shell policy: ${validationResult.reason}")
        }

        // Eksekusi via Shizuku
        return try {
            val process = Shizuku.newProcess(fullCommand, null, null)
            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                AuditLogger.logShellApproved("shizuku_shell", fullCommand.joinToString(" "))
                ToolResult.Success(output.ifBlank { "(success, no output)" })
            } else {
                AuditLogger.logShellRejected("shizuku_shell", "Exit $exitCode: $error")
                ToolResult.Error("Command gagal (exit $exitCode): $error")
            }
        } catch (e: Exception) {
            AuditLogger.logShellRejected("shizuku_shell", "Exception: ${e.message}")
            ToolResult.Error("Gagal menjalankan via Shizuku: ${e.message}")
        }
    }
}