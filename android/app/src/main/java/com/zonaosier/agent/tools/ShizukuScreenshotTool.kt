/**
 * ZONA-OSIER — ShizukuScreenshotTool.
 * Ambil screenshot layar via Shizuku.
 * Output: base64 PNG (untuk dikirim ke model vision).
 */
package com.zonaosier.agent.tools

import android.graphics.BitmapFactory
import android.util.Base64
import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import com.zonaosier.security.AuditLogger
import com.zonaosier.security.FreezeAgent
import rikka.shizuku.Shizuku
import java.io.File

class ShizukuScreenshotTool(
    private val cacheDir: File,
    private val freezeAgent: FreezeAgent
) : Tool {

    companion object {
        private const val SCREENSHOT_PATH = "/data/local/tmp/zo_screenshot.png"
        private const val MAX_SCREENSHOT_SIZE_BYTES = 5 * 1024 * 1024 // 5MB
    }

    override val name: String = "shizuku_screenshot"
    override val description: String =
        "Ambil screenshot layar via Shizuku. Output: base64 encoded PNG."
    override val parameters: String = "{}"
    override val isDestructive: Boolean = false
    override val requiresBiometric: Boolean = false

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        if (freezeAgent.isFrozen()) {
            return ToolResult.Error("Agent dibekukan.")
        }
        if (!Shizuku.pingBinder()) {
            return ToolResult.Error("Shizuku tidak tersedia.")
        }

        return try {
            // 1. Ambil screenshot
            val screencap = Shizuku.newProcess(
                listOf("/system/bin/screencap", "-p", SCREENSHOT_PATH),
                null, null
            )
            screencap.waitFor()

            // 2. Baca file screenshot
            val screenshotFile = File(SCREENSHOT_PATH)
            if (!screenshotFile.exists()) {
                return ToolResult.Error("Screenshot file tidak ditemukan.")
            }

            val bytes = screenshotFile.readBytes()
            if (bytes.size > MAX_SCREENSHOT_SIZE_BYTES) {
                return ToolResult.Error("Screenshot terlalu besar: ${bytes.size} bytes.")
            }

            // 3. Encode ke base64
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            // 4. Simpan ke cache lokal
            val localCopy = File(cacheDir, "screenshots")
            localCopy.mkdirs()
            File(localCopy, "screen_${System.currentTimeMillis()}.png").writeBytes(bytes)

            AuditLogger.logShellApproved("shizuku_screenshot", "Screenshot ${bytes.size} bytes")
            ToolResult.Success(base64)
        } catch (e: Exception) {
            ToolResult.Error("Gagal screenshot: ${e.message}")
        }
    }
}