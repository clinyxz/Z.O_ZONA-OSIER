/**
 * ZONA-OSIER — ScreenReadTool.
 * Membaca konten layar via AccessibilityService.
 * Menyediakan node tree dari layar saat ini.
 */
package com.zonaosier.agent.tools

import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import com.zonaosier.security.AuditLogger

/**
 * Interface untuk mendapatkan node tree dari AccessibilityService.
 * Di-bind saat service berjalan.
 */
interface ScreenReader {
    fun readScreen(): String?
    fun isAvailable(): Boolean
}

class ScreenReadTool(private val screenReader: ScreenReader) : Tool {

    companion object {
        private const val MAX_OUTPUT_LENGTH = 8000
    }

    override val name: String = "screen_read"
    override val description: String =
        "Baca konten layar saat ini via Accessibility Service. " +
        "Tidak memerlukan argumen. Output: node tree teks dari layar."
    override val parameters: String = "{}"
    override val isDestructive: Boolean = false
    override val requiresBiometric: Boolean = false

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        if (!screenReader.isAvailable()) {
            return ToolResult.Error(
                "AccessibilityService tidak berjalan. " +
                "Aktifkan di Settings → Accessibility → ZONA-OSIER."
            )
        }

        val content = screenReader.readScreen()
        if (content.isNullOrBlank()) {
            return ToolResult.Success("(Layar kosong — mungkin Canvas/Custom View)")
        }

        AuditLogger.log("ScreenReadTool", "read", "SUCCESS", "${content.length} chars")
        return ToolResult.Success(
            if (content.length > MAX_OUTPUT_LENGTH) {
                content.take(MAX_OUTPUT_LENGTH) + "\n...(truncated)"
            } else {
                content
            }
        )
    }
}
