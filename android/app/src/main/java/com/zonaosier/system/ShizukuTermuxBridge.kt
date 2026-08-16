/**
 * ZONA-OSIER — ShizukuTermuxBridge (rish Integration).
 * Menggabungkan privilese Shizuku (uid=2000, setara ADB) dengan userland Linux penuh
 * dari Termux dalam satu sesi shell.
 *
 * rish adalah skrip shell interface milik Shizuku yang dipanggil dari dalam Termux.
 * Perintah yang dikirim via rish dieksekusi dengan privilese ADB tanpa root.
 *
 * Arsitektur:
 *   Z.O App → TermuxExecutor (RUN_COMMAND) → Termux → rish → Shizuku (uid=2000)
 *   Atau:
 *   Z.O App → ShizukuController (langsung) → Shizuku (uid=2000)
 *
 * Bridge ini memilih jalur terbaik:
 *   - Jika Shizuku aktif → langsung via ShizukuController
 *   - Jika Shizuku tidak aktif tapi Termux ada → via rish di Termux
 *   - Jika keduanya tidak ada → fallback error
 *
 * Use case utama:
 *   - pm install/uninstall (manage packages)
 *   - am start/force-stop (manage activities)
 *   - settings put/get (system settings)
 *   - svc power/shutdown (power control)
 *   - dumpsys (system diagnostics)
 *   - input keyevent/tap/swipe (input injection)
 */
package com.zonaosier.system

import android.content.Context
import android.content.pm.PackageManager
import com.zonaosier.security.AuditLogger
import com.zonaosier.security.ShellSecurityPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hasil eksekusi bridge.
 */
sealed class BridgeResult {
    data class Success(val output: String, val exitCode: Int = 0, val via: String) : BridgeResult()
    data class Error(val message: String, val via: String? = null) : BridgeResult()
}

/**
 * Jalur eksekusi yang digunakan.
 */
enum class ExecutionPath {
    /** Langsung via Shizuku API. */
    SHIZUKU_DIRECT,
    /** Via Termux + rish (Shizuku di dalam Termux). */
    TERMUX_RISH,
    /** Via Termux biasa (tanpa privilese elevated). */
    TERMUX_PLAIN,
    /** Tidak ada jalur yang tersedia. */
    UNAVAILABLE
}

class ShizukuTermuxBridge(
    private val context: Context,
    private val shizukuController: ShizukuController,
    private val termuxExecutor: TermuxExecutor,
    private val shellSecurityPolicy: ShellSecurityPolicy
) {

    companion object {
        private const val TAG = "ShizukuTermuxBridge"
        private const val RISH_COMMAND = "rish"
        private const val RISH_PATH = "/data/data/com.termux/files/usr/bin/rish"
        private const val OUTPUT_TIMEOUT_MS = 10000L
    }

    /**
     * Cek jalur eksekusi yang tersedia.
     */
    fun getAvailablePath(): ExecutionPath {
        if (shizukuController.isAvailable()) return ExecutionPath.SHIZUKU_DIRECT

        val prereqs = termuxExecutor.checkPrerequisites()
        if (prereqs.termuxInstalled) {
            // Cek apakah rish tersedia di Termux
            if (isRishAvailable()) return ExecutionPath.TERMUX_RISH
            return ExecutionPath.TERMUX_PLAIN
        }

        return ExecutionPath.UNAVAILABLE
    }

    /**
     * Eksekusi command dengan jalur otomatis.
     * Prioritas: Shizuku Direct > Termux rish > Termux plain.
     *
     * @param command Command dan argumen (contoh: listOf("pm", "list", "packages", "-3"))
     * @param requireElevated Jika true, hanya Shizuku dan rish yang diizinkan.
     * @param timeoutMs Timeout eksekusi.
     */
    suspend fun execute(
        command: List<String>,
        requireElevated: Boolean = false,
        timeoutMs: Long = OUTPUT_TIMEOUT_MS
    ): BridgeResult {
        // Validasi keamanan (Layer 2)
        val validation = shellSecurityPolicy.validate(command)
        if (!validation.allowed) {
            AuditLogger.log(
                toolName = "ShizukuTermuxBridge",
                action = "EXEC_BLOCKED",
                status = "BLOCKED",
                detail = "Command blocked: ${validation.reason}"
            )
            return BridgeResult.Error(
                message = "Command ditolak oleh ShellSecurityPolicy: ${validation.reason}",
                via = "security_layer"
            )
        }

        // Freeze agent check (Layer 3)
        if (com.zonaosier.security.FreezeAgent.isFrozen(context)) {
            return BridgeResult.Error(
                message = "Agent dibekukan. Semua eksekusi command ditolak.",
                via = "freeze_layer"
            )
        }

        // Pilih jalur
        val path = getAvailablePath()

        return when {
            path == ExecutionPath.SHIZUKU_DIRECT -> {
                executeViaShizuku(command)
            }

            path == ExecutionPath.TERMUX_RISH && !requireElevated -> {
                executeViaRish(command)
            }

            path == ExecutionPath.TERMUX_RISH -> {
                executeViaRish(command)
            }

            path == ExecutionPath.TERMUX_PLAIN && !requireElevated -> {
                executeViaTermux(command)
            }

            else -> {
                BridgeResult.Error(
                    message = "Tidak ada jalur eksekusi tersedia. " +
                            "Instal Shizuku atau Termux (F-Droid) untuk fitur system.",
                    via = null
                )
            }
        }
    }

    /**
     * Eksekusi via Shizuku langsung.
     */
    private fun executeViaShizuku(command: List<String>): BridgeResult {
        return when (val result = shizukuController.execute(command)) {
            is ShizukuResult.Success -> BridgeResult.Success(
                output = result.output,
                exitCode = result.exitCode,
                via = "shizuku_direct"
            )
            is ShizukuResult.Error -> BridgeResult.Error(
                message = result.message,
                via = "shizuku_direct"
            )
        }
    }

    /**
     * Eksekusi via Termux + rish.
     * rish menghubungkan Termux ke Shizuku daemon.
     * Command dikirim sebagai: rish -c "command"
     */
    private fun executeViaRish(command: List<String>): BridgeResult {
        val fullCommand = command.joinToString(" ")
        val result = termuxExecutor.execute(RISH_COMMAND, arrayOf("-c", fullCommand))
        return if (result.success) {
            BridgeResult.Success(
                output = result.message,
                via = "termux_rish"
            )
        } else {
            BridgeResult.Error(
                message = result.message,
                via = "termux_rish"
            )
        }
    }

    /**
     * Eksekusi via Termux biasa (tanpa privilese elevated).
     */
    private fun executeViaTermux(command: List<String>): BridgeResult {
        val binary = command.firstOrNull() ?: return BridgeResult.Error(
            message = "Command kosong",
            via = "termux_plain"
        )
        val args = command.drop(1).toTypedArray()
        val result = termuxExecutor.execute(binary, args)
        return if (result.success) {
            BridgeResult.Success(
                output = result.message,
                via = "termux_plain"
            )
        } else {
            BridgeResult.Error(
                message = result.message,
                via = "termux_plain"
            )
        }
    }

    // ==================== Convenience Methods ====================

    /**
     * Ambil daftar package yang terinstal (user apps only).
     */
    suspend fun listUserPackages(): BridgeResult {
        return execute(listOf("pm", "list", "packages", "-3"))
    }

    /**
     * Cek info suatu package.
     */
    suspend fun getPackageInfo(packageName: String): BridgeResult {
        return execute(listOf("dumpsys", "package", packageName))
    }

    /**
     * Force stop suatu app.
     */
    suspend fun forceStopApp(packageName: String): BridgeResult {
        return execute(listOf("am", "force-stop", packageName))
    }

    /**
     * Start activity.
     */
    suspend fun startActivity(component: String): BridgeResult {
        return execute(listOf("am", "start", "-n", component))
    }

    /**
     * Ambil system setting.
     */
    suspend fun getSystemSetting(namespace: String, key: String): BridgeResult {
        return execute(listOf("settings", "get", namespace, key))
    }

    /**
     * Set system setting.
     */
    suspend fun setSystemSetting(namespace: String, key: String, value: String): BridgeResult {
        return execute(listOf("settings", "put", namespace, key, value), requireElevated = true)
    }

    /**
     * Lock screen.
     */
    suspend fun lockScreen(): BridgeResult {
        return execute(listOf("input", "keyevent", "26"), requireElevated = true)
    }

    /**
     * Screenshot layar.
     */
    suspend fun takeScreenshot(): BridgeResult {
        return execute(listOf("screencap", "-p", "/data/local/tmp/zo_bridge_screenshot.png"))
    }

    /**
     * Ambil info battery via dumpsys.
     */
    suspend fun getBatteryInfo(): BridgeResult {
        return execute(listOf("dumpsys", "battery"))
    }

    /**
     * Ambil info thermal.
     */
    suspend fun getThermalInfo(): BridgeResult {
        return execute(listOf("dumpsys", "thermalservice"))
    }

    // ==================== Internal ====================

    /**
     * Cek apakah rish tersedia di Termux.
     */
    private fun isRishAvailable(): Boolean {
        // Tidak bisa langsung cek filesystem Termux dari luar.
        // Asumsi: jika Shizuku terinstal, rish ada.
        return try {
            rikka.shizuku.Shizuku.pingBinder()
            true
        } catch (_: Exception) {
            false
        }
    }
}
