/**
 * ZONA-OSIER — ShizukuController.
 * Eksekusi command via Shizuku (uid=2000, setara ADB).
 * Tanpa root — cukup untuk pm, am, svc, settings, appops,
 * virtual display, dan injeksi touch/key event.
 *
 * ✅ Shizuku v13.6.0 dikonfirmasi aktif per Agustus 2026.
 * Mendukung Android 16 QPR1, auto-start tanpa root di WiFi terpercaya (Android 13+).
 * Tidak tersedia di Play Store — wajib sideload dari GitHub/F-Droid.
 */
package com.zonaosier.system

import rikka.shizuku.Shizuku
import java.io.File

/**
 * Hasil eksekusi command Shizuku.
 */
sealed class ShizukuResult {
    data class Success(val output: String, val exitCode: Int = 0) : ShizukuResult()
    data class Error(val message: String) : ShizukuResult()
}

class ShizukuController {

    companion object {
        private const val SCREENSHOT_PATH = "/data/local/tmp/zo_screenshot.png"
        private const val TEMP_DIR = "/data/local/tmp/"
    }

    /**
     * Cek apakah Shizuku tersedia dan berjalan.
     */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    /**
     * Eksekusi command via Shizuku.
     */
    fun execute(command: List<String>): ShizukuResult {
        if (!isAvailable()) return ShizukuResult.Error("Shizuku not available")

        return try {
            val process = Shizuku.newProcess(command, null, null)
            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ShizukuResult.Success(output.ifBlank { "(success, no output)" }, exitCode)
            } else {
                ShizukuResult.Error("Exit $exitCode: ${error.ifBlank { "unknown error" }}")
            }
        } catch (e: Exception) {
            ShizukuResult.Error("Execution failed: ${e.message}")
        }
    }

    /**
     * Screenshot layar.
     */
    fun screenshot(): ByteArray? {
        val result = execute(listOf("/system/bin/screencap", "-p", SCREENSHOT_PATH))
        if (result is ShizukuResult.Error) return null

        return try {
            val file = File(SCREENSHOT_PATH)
            if (file.exists()) file.readBytes() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Screenshot ke file tertentu.
     */
    fun screenshotTo(path: String): ShizukuResult {
        return execute(listOf("/system/bin/screencap", "-p", path))
    }

    /**
     * Injeksi tap di koordinat (x, y).
     */
    fun tap(x: Int, y: Int): ShizukuResult {
        return execute(listOf("/system/bin/input", "tap", x.toString(), y.toString()))
    }

    /**
     * Injekti swipe.
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): ShizukuResult {
        return execute(listOf(
            "/system/bin/input", "swipe",
            x1.toString(), y1.toString(),
            x2.toString(), y2.toString(),
            durationMs.toString()
        ))
    }

    /**
     * Injeksi teks.
     */
    fun typeText(text: String): ShizukuResult {
        return execute(listOf("/system/bin/input", "text", text))
    }

    /**
     * Injeksi key event.
     */
    fun keyEvent(keyCode: Int): ShizukuResult {
        return execute(listOf("/system/bin/input", "keyevent", keyCode.toString()))
    }

    /**
     * Cek info package.
     */
    fun dumpPackage(packageName: String): ShizukuResult {
        return execute(listOf("dumpsys", "package", packageName))
    }

    /**
     * Force stop app.
     */
    fun forceStop(packageName: String): ShizukuResult {
        return execute(listOf("am", "force-stop", packageName))
    }

    /**
     * Start activity.
     */
    fun startActivity(component: String): ShizukuResult {
        return execute(listOf("am", "start", "-n", component))
    }

    /**
     * Broadcast intent.
     */
    fun sendBroadcast(action: String, extras: Map<String, String> = emptyMap()): ShizukuResult {
        val args = mutableListOf("am", "broadcast", "-a", action)
        extras.forEach { (key, value) ->
            args.addAll(listOf("--es", key, value))
        }
        return execute(args)
    }

    /**
     * Ambil setting sistem.
     */
    fun getSetting(namespace: String, key: String): ShizukuResult {
        return execute(listOf("settings", "get", namespace, key))
    }

    /**
     * Set setting sistem.
     */
    fun putSetting(namespace: String, key: String, value: String): ShizukuResult {
        return execute(listOf("settings", "put", namespace, key, value))
    }

    /**
     * Bersihkan file sementara.
     */
    fun cleanup() {
        execute(listOf("rm", "-f", SCREENSHOT_PATH))
    }
}