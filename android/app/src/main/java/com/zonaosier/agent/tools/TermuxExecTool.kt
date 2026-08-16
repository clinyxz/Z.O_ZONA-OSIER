/**
 * ZONA-OSIER — TermuxExecTool.
 * Eksekusi command di Termux (userland Linux) via RUN_COMMAND intent.
 * Command wajib melewati ShellSecurityPolicy (Layer 2) sebelum eksekusi.
 */
package com.zonaosier.agent.tools

import android.content.Context
import android.content.Intent
import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import com.zonaosier.security.AuditLogger
import com.zonaosier.security.ShellSecurityPolicy

class TermuxExecTool(private val context: Context) : Tool {

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        const val TERMUX_BIN_PATH = "/data/data/com.termux/files/usr/bin/"
    }

    override val name: String = "termux_exec"
    override val description: String =
        "Eksekusi command di Termux (userland Linux). " +
        "Argumen: 'command' (string, nama binary), 'args' (array string, argumen). " +
        "Command wajib melewati ShellSecurityPolicy."
    override val parameters: String = """
        {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Nama binary/command"},
                "args": {"type": "array", "items": {"type": "string"}, "description": "Argumen command"}
            },
            "required": ["command"]
        }
    """.trimIndent()
    override val isDestructive: Boolean = true
    override val requiresBiometric: Boolean = true

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val command = args["command"]?.toString()?.trim()
            ?: return ToolResult.Error("Argumen 'command' wajib diisi.")

        val argList = when (val a = args["args"]) {
            is List<*> -> a.map { it.toString() }
            is String -> listOf(a)
            else -> emptyList()
        }

        // Bangun full command list untuk validasi
        val fullCommand = listOf(command) + argList

        // Layer 2: ShellSecurityPolicy validation
        val validationResult = ShellSecurityPolicy.validate(fullCommand)
        if (validationResult is ShellSecurityPolicy.ValidationResult.Rejected) {
            AuditLogger.logShellRejected("termux_exec", validationResult.reason)
            return ToolResult.Error("Shell policy: ${validationResult.reason}")
        }

        // Eksekusi via Termux RUN_COMMAND intent
        return try {
            val intent = Intent(RUN_COMMAND_ACTION).apply {
                setPackage(TERMUX_PACKAGE)
                putExtra("com.termux.RUN_COMMAND_PATH", "${TERMUX_BIN_PATH}$command")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", argList.toTypedArray())
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            context.startService(intent)

            AuditLogger.logShellApproved("termux_exec", fullCommand.joinToString(" "))
            ToolResult.Success("Command '$command' dikirim ke Termux. (Background execution — output tidak langsung tersedia)")
        } catch (e: Exception) {
            if (e is android.content.ActivityNotFoundException) {
                AuditLogger.logShellRejected("termux_exec", "Termux tidak terinstal")
                return ToolResult.Error(
                    "Termux tidak terinstal atau tidak bisa menerima RUN_COMMAND intent. " +
                    "Pastikan Termux (F-Droid) terinstal dan allow-external-apps=true."
                )
            }
            AuditLogger.logShellRejected("termux_exec", "Exception: ${e.message}")
            ToolResult.Error("Gagal mengirim command ke Termux: ${e.message}")
        }
    }
}
