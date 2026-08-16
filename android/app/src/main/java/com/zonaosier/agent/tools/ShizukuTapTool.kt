/**
 * ZONA-OSIER — ShizukuTapTool.
 * Injeksi touch event via Shizuku (tap di koordinat x,y).
 * Digunakan oleh SystemThinker untuk mengontrol UI apps lain.
 */
package com.zonaosier.agent.tools

import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import com.zonaosier.security.AuditLogger
import com.zonaosier.security.FreezeAgent
import rikka.shizuku.Shizuku

class ShizukuTapTool(private val freezeAgent: FreezeAgent) : Tool {

    override val name: String = "shizuku_tap"
    override val description: String =
        "Injeksi tap/tekan di koordinat layar. " +
        "Argumen: 'x' (int), 'y' (int). Koordinat dalam pixel absolut."
    override val parameters: String = """
        {
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "Koordinat X (pixel)"},
                "y": {"type": "integer", "description": "Koordinat Y (pixel)"}
            },
            "required": ["x", "y"]
        }
    """.trimIndent()
    override val isDestructive: Boolean = true
    override val requiresBiometric: Boolean = true

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        if (freezeAgent.isFrozen()) {
            return ToolResult.Error("Agent dibekukan.")
        }
        if (!Shizuku.pingBinder()) {
            return ToolResult.Error("Shizuku tidak tersedia.")
        }

        val x = (args["x"] as? Number)?.toInt()
            ?: return ToolResult.Error("Argumen 'x' wajib berupa integer.")
        val y = (args["y"] as? Number)?.toInt()
            ?: return ToolResult.Error("Argumen 'y' wajib berupa integer.")

        // Validasi range koordinat (asumsi max 4K)
        if (x < 0 || x > 4096 || y < 0 || y > 4096) {
            return ToolResult.Error("Koordinat di luar range: ($x, $y)")
        }

        return try {
            val process = Shizuku.newProcess(
                listOf("/system/bin/input", "tap", x.toString(), y.toString()),
                null, null
            )
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                AuditLogger.logShellApproved("shizuku_tap", "tap ($x, $y)")
                ToolResult.Success("Tap di ($x, $y) berhasil.")
            } else {
                val error = process.errorStream.bufferedReader().readText().trim()
                ToolResult.Error("Tap gagal: $error")
            }
        } catch (e: Exception) {
            ToolResult.Error("Gagal tap: ${e.message}")
        }
    }
}