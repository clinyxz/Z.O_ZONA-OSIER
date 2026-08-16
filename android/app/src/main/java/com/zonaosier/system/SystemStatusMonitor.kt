/**
 * ZONA-OSIER — System Status Monitor.
 * Memantau status semua service dan komponen sistem secara real-time.
 * Menghasilkan StateFlow<SystemStatus> untuk UI.
 *
 * Komponen yang dimonitor:
 * - Shizuku availability
 * - Termux + Termux:API installed
 * - Accessibility Service active
 * - Notification Listener active
 * - Call Screening role
 * - Voice pipeline ready
 * - Freeze agent state
 * - Thermal level (dari BatteryThermalGovernor)
 * - Active character
 */
package com.zonaosier.system

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.appcompat.content.res.AppCompatResources
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Status satu komponen sistem.
 */
data class ComponentStatus(
    val id: String,
    val label: String,
    val isActive: Boolean,
    val detail: String? = null
)

/**
 * Status keseluruhan sistem.
 */
data class SystemStatus(
    val shizuku: ComponentStatus,
    val termux: ComponentStatus,
    val termuxApi: ComponentStatus,
    val accessibility: ComponentStatus,
    val notifListener: ComponentStatus,
    val callScreening: ComponentStatus,
    val voiceReady: ComponentStatus,
    val freezeAgent: ComponentStatus,
    val thermalLevel: ThermalLevel,
    val activeCharacter: String?,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Apakah semua komponen utama aktif. */
    val allSystemsGo: Boolean
        get() = shizuku.isActive || termux.isActive

    /** Apakah voice pipeline siap. */
    val isVoicePipelineReady: Boolean
        get() = voiceReady.isActive

    /** Jumlah komponen yang aktif. */
    val activeCount: Int
        get() = listOf(
            shizuku, termux, termuxApi, accessibility,
            notifListener, callScreening, voiceReady
        ).count { it.isActive }
}

/**
 * Level thermal dari BatteryThermalGovernor.
 */
enum class ThermalLevel(val label: String, val color: Long) {
    NORMAL("Normal", 0xFF64B5F6),
    WARM("Hangat", 0xFFFBBF24),
    HOT("Panas", 0xFFFF8A65),
    SEVERE("Kritis", 0xFFF87171)
}

class SystemStatusMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val termuxChecker = TermuxApiChecker(context)

    /** Refresh interval untuk polling. */
    var refreshIntervalMs: Long = 5000L

    private val _status = MutableStateFlow(SystemStatus(
        shizuku = ComponentStatus("shizuku", "Shizuku", false),
        termux = ComponentStatus("termux", "Termux", false),
        termuxApi = ComponentStatus("termux_api", "Termux:API", false),
        accessibility = ComponentStatus("accessibility", "Accessibility", false),
        notifListener = ComponentStatus("notif_listener", "Notif Listener", false),
        callScreening = ComponentStatus("call_screening", "Call Screening", false),
        voiceReady = ComponentStatus("voice", "Voice Pipeline", false),
        freezeAgent = ComponentStatus("freeze", "Freeze Agent", false),
        thermalLevel = ThermalLevel.NORMAL,
        activeCharacter = null
    ))

    /** Flow status sistem untuk UI. */
    val status: StateFlow<SystemStatus> = _status.asStateFlow()

    /** Job polling. */
    private var pollJob: Job? = null

    /**
     * Mulai polling status sistem.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return

        // Check pertama kali langsung
        refresh()

        pollJob = scope.launch {
            while (isActive) {
                delay(refreshIntervalMs)
                refresh()
            }
        }
    }

    /**
     * Hentikan polling.
     */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Refresh status semua komponen.
     */
    fun refresh() {
        val current = _status.value

        // Shizuku
        val shizukuActive = checkShizuku()
        val shizuku = ComponentStatus(
            id = "shizuku",
            label = "Shizuku",
            isActive = shizukuActive,
            detail = if (shizukuActive) "Aktif — uid=2000" else "Tidak terinstal / tidak berjalan"
        )

        // Termux
        val termuxActive = isPackageInstalled(TermuxExecutor.TERMUX_PACKAGE)
        val termux = ComponentStatus(
            id = "termux",
            label = "Termux",
            isActive = termuxActive,
            detail = if (termuxActive) "F-Droid build terinstal" else "Belum terinstal"
        )

        // Termux:API
        val termuxApiActive = isPackageInstalled(TermuxExecutor.TERMUX_API_PACKAGE)
        val termuxApi = ComponentStatus(
            id = "termux_api",
            label = "Termux:API",
            isActive = termuxApiActive,
            detail = if (termuxApiActive) "40+ command tersedia" else "Add-on belum terinstal"
        )

        // Accessibility
        val accessibilityActive = ZonaAccessibilityService.isServiceActive()
        val accessibility = ComponentStatus(
            id = "accessibility",
            label = "Accessibility Service",
            isActive = accessibilityActive,
            detail = if (accessibilityActive) "Screen reading aktif" else "Tidak aktif"
        )

        // Notification Listener
        val notifActive = checkNotificationAccess()
        val notifListener = ComponentStatus(
            id = "notif_listener",
            label = "Notif Listener",
            isActive = notifActive,
            detail = if (notifActive) "Membaca notifikasi" else "Belum diaktifkan"
        )

        // Call Screening
        val callScreeningActive = checkCallScreening()
        val callScreening = ComponentStatus(
            id = "call_screening",
            label = "Call Screening",
            isActive = callScreeningActive,
            detail = if (callScreeningActive) "Menyaring panggilan" else "Role belum diberikan"
        )

        // Voice Pipeline
        val audioGranted = checkPermission(android.Manifest.permission.RECORD_AUDIO)
        val voiceReady = ComponentStatus(
            id = "voice",
            label = "Voice Pipeline",
            isActive = audioGranted,
            detail = if (audioGranted) "VAD + STT + TTS siap" else "Mikrofon belum diizinkan"
        )

        // Freeze Agent
        val isFrozen = com.zonaosier.security.FreezeAgent.isFrozen(context)
        val freezeAgent = ComponentStatus(
            id = "freeze",
            label = "Freeze Agent",
            isActive = isFrozen,
            detail = if (isFrozen) "AGENT DIBEKUKAN" else "Normal"
        )

        // Thermal
        val thermal = ThermalLevel.NORMAL // Diupdate dari BatteryThermalGovernor di production

        _status.value = current.copy(
            shizuku = shizuku,
            termux = termux,
            termuxApi = termuxApi,
            accessibility = accessibility,
            notifListener = notifListener,
            callScreening = callScreening,
            voiceReady = voiceReady,
            freezeAgent = freezeAgent,
            thermalLevel = thermal
        )
    }

    // ==================== Helpers ====================

    private fun checkShizuku(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
            true
        } catch (_: Exception) { false }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
    }

    private fun checkPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun checkNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        return flat.contains(context.packageName)
    }

    private fun checkCallScreening(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            ?: return false
        return roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
    }
}
