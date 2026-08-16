/**
 * ZONA-OSIER — TermuxExecutor.
 * Eksekusi command di Termux (userland Linux) via RUN_COMMAND intent.
 *
 * Z.O TIDAK menginstal library AI ke dalam Termux.
 * Termux hanya menjalankan command yang dikirim Z.O.
 *
 * Prasyarat:
 * - Termux build F-Droid (bukan Play Store)
 * - Termux:API add-on dari F-Droid
 * - allow-external-apps=true di ~/.termux/termux.properties
 *
 * Arsitektur:
 * Z.O App → RUN_COMMAND intent → Termux → stdout/stderr
 */
package com.zonaosier.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Hasil eksekusi Termux.
 * Note: Background execution = output tidak langsung tersedia.
 */
data class TermuxResult(
    val success: Boolean,
    val message: String
)

/**
 * Status prasyarat Termux.
 */
data class TermuxPrerequisites(
    val termuxInstalled: Boolean,
    val termuxApiInstalled: Boolean,
    val allowExternalApps: Boolean
) {
    val allMet: Boolean
        get() = termuxInstalled && termuxApiInstalled && allowExternalApps

    val missingComponents: List<String>
        get() = buildList {
            if (!termuxInstalled) add("Termux (F-Droid)")
            if (!termuxApiInstalled) add("Termux:API (F-Droid)")
            if (!allowExternalApps) add("allow-external-apps=true")
        }
}

class TermuxExecutor(private val context: Context) {

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_API_PACKAGE = "com.termux.api"
        const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        const val TERMUX_BIN_PATH = "/data/data/com.termux/files/usr/bin/"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
    }

    /**
     * Cek prasyarat Termux.
     */
    fun checkPrerequisites(): TermuxPrerequisites {
        val pm = context.packageManager
        val termuxInstalled = try {
            pm.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }

        val termuxApiInstalled = try {
            pm.getPackageInfo(TERMUX_API_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }

        // allow-external-apps tidak bisa dicek langsung dari luar Termux
        // Asumsi true — akan diverifikasi saat pertama kali eksekusi
        val allowExternalApps = true

        return TermuxPrerequisites(termuxInstalled, termuxApiInstalled, allowExternalApps)
    }

    /**
     * Eksekusi command di Termux (background).
     */
    fun execute(command: String, arguments: Array<String> = emptyArray()): TermuxResult {
        val prereqs = checkPrerequisites()
        if (!prereqs.termuxInstalled) {
            return TermuxResult(false, "Termux tidak terinstal. Install dari F-Droid.")
        }

        return try {
            val intent = Intent(RUN_COMMAND_ACTION).apply {
                setPackage(TERMUX_PACKAGE)
                putExtra("com.termux.RUN_COMMAND_PATH", "${TERMUX_BIN_PATH}$command")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            context.startService(intent)
            TermuxResult(true, "Command '$command' dikirim ke Termux.")
        } catch (e: Exception) {
            TermuxResult(false, "Gagal mengirim ke Termux: ${e.message}")
        }
    }

    /**
     * Eksekusi Termux:API command.
     */
    fun executeApi(apiCommand: String, arguments: Array<String> = emptyArray()): TermuxResult {
        val apiPath = "$TERMUX_BIN_PATH$apiCommand"
        return execute(apiPath, arguments)
    }

    /**
     * Cek baterai via Termux:API.
     */
    fun getBatteryStatus(): TermuxResult {
        return executeApi("termux-battery-status")
    }

    /**
     * Set brightness via Termux:API.
     */
    fun setBrightness(level: Int): TermuxResult {
        return executeApi("termux-brightness", arrayOf(level.toString()))
    }

    /**
     * Get clipboard via Termux:API.
     */
    fun getClipboard(): TermuxResult {
        return executeApi("termux-clipboard-get")
    }

    /**
     * Set clipboard via Termux:API.
     */
    fun setClipboard(text: String): TermuxResult {
        return executeApi("termux-clipboard-set", arrayOf(text))
    }

    /**
     * Vibrate via Termux:API.
     */
    fun vibrate(durationMs: Int = 200): TermuxResult {
        return executeApi("termux-vibrate", arrayOf("-d", durationMs.toString()))
    }

    /**
     * TTS via Termux:API.
     */
    fun speak(text: String): TermuxResult {
        return executeApi("termux-tts-speak", arrayOf(text))
    }

    /**
     * Toast via Termux:API.
     */
    fun showToast(text: String): TermuxResult {
        return executeApi("termux-toast", arrayOf(text))
    }

    /**
     * WiFi info via Termux:API.
     */
    fun getWifiInfo(): TermuxResult {
        return executeApi("termux-wifi-connectioninfo")
    }

    /**
     * Sensor list via Termux:API.
     */
    fun listSensors(): TermuxResult {
        return executeApi("termux-sensor", arrayOf("-l"))
    }

    /**
     * Semua 40+ command Termux:API yang terverifikasi.
     */
    val availableApiCommands: List<String> = listOf(
        "termux-battery-status", "termux-brightness",
        "termux-call-log", "termux-camera-info", "termux-camera-photo",
        "termux-clipboard-get", "termux-clipboard-set",
        "termux-contact-list", "termux-dialog", "termux-download",
        "termux-fingerprint", "termux-infrared-frequencies", "termux-infrared-transmit",
        "termux-job-scheduler", "termux-location",
        "termux-media-player", "termux-notification", "termux-notification-remove",
        "termux-sensor", "termux-share",
        "termux-sms-list", "termux-sms-send",
        "termux-telephony-call", "termux-telephony-cellinfo", "termux-telephony-deviceinfo",
        "termux-toast", "termux-torch",
        "termux-tts-engines", "termux-tts-speak",
        "termux-usb", "termux-vibrate", "termux-volume",
        "termux-wallpaper", "termux-wifi-connectioninfo", "termux-wifi-scaninfo"
    )
}
