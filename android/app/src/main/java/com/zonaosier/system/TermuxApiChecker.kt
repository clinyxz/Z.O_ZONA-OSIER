/**
 * ZONA-OSIER — TermuxApiChecker.
 * Verifikasi prasyarat Termux + Termux:API.
 * Dipanggil saat onboarding untuk memastikan semua dependency terpenuhi.
 */
package com.zonaosier.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Status onboarding untuk Termux.
 */
data class OnboardingCheckResult(
    val termuxInstalled: Boolean,
    val termuxApiInstalled: Boolean,
    val shizukuAvailable: Boolean,
    val recordAudioGranted: Boolean,
    val notificationAccessGranted: Boolean,
    val callScreeningGranted: Boolean
) {
    val voiceReady: Boolean
        get() = termuxInstalled && recordAudioGranted

    val systemReady: Boolean
        get() = shizukuAvailable || termuxInstalled

    val allCritical: Boolean
        get() = termuxInstalled && recordAudioGranted

    val missingItems: List<String>
        get() = buildList {
            if (!termuxInstalled) add("Termux (F-Droid)")
            if (!termuxApiInstalled) add("Termux:API (F-Droid)")
            if (!shizukuAvailable) add("Shizuku (sideload)")
            if (!recordAudioGranted) add("Izin Mikrofon")
            if (!notificationAccessGranted) add("Akses Notifikasi")
            if (!callScreeningGranted) add("Call Screening")
        }
}

class TermuxApiChecker(private val context: Context) {

    /**
     * Jalankan semua pengecekan onboarding.
     */
    fun runFullCheck(): OnboardingCheckResult {
        return OnboardingCheckResult(
            termuxInstalled = isPackageInstalled("com.termux"),
            termuxApiInstalled = isPackageInstalled("com.termux.api"),
            shizukuAvailable = checkShizuku(),
            recordAudioGranted = checkPermission(android.Manifest.permission.RECORD_AUDIO),
            notificationAccessGranted = checkNotificationAccess(),
            callScreeningGranted = checkCallScreening()
        )
    }

    /**
     * Buka halaman Notification Access Settings.
     * WAJIB: Tidak bisa via runtime permission dialog.
     */
    fun openNotificationAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Request Call Screening role.
     */
    fun requestCallScreeningRole(requestCode: Int) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
                ?: return
            if (!roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
    /**
     * Buka halaman App Settings untuk Z.O.
     */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Cek apakah package terinstal.
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        }
    }

    /**
     * Cek Shizuku availability.
     */
    private fun checkShizuku(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
        }
    }

    /**
     * Cek permission.
     */
    private fun checkPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Cek Notification Listener access.
     */
    private fun checkNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        return flat.contains(context.packageName)
    }

    /**
     * Cek Call Screening role.
     */
    private fun checkCallScreening(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            ?: return false
        return roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
    }

    companion object {
        /**
         * URL download Shizuku (GitHub release).
         */
        const val SHIZUKU_DOWNLOAD_URL = "https://github.com/RikkaApps/Shizuku/releases/latest"

        /**
         * URL download Termux F-Droid.
         */
        const val TERMUX_FDROID_URL = "https://f-droid.org/packages/com.termux/"

        /**
         * URL download Termux:API F-Droid.
         */
        const val TERMUX_API_FDROID_URL = "https://f-droid.org/packages/com.termux.api/"
    }
}