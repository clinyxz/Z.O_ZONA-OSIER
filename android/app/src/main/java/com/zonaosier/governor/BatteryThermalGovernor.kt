/**
 * ZONA-OSIER — BatteryThermalGovernor.
 * Memantau kondisi baterai dan termal device.
 * Dipakai oleh ModelTierSelector dan VoiceRouter.
 *
 * Thermal status levels (PowerManager):
 * - THERMAL_STATUS_NONE (0): Normal
 * - THERMAL_STATUS_LIGHT (1): Ringan
 * - THERMAL_STATUS_MODERATE (2): Sedang → trigger downscale
 * - THERMAL_STATUS_SEVERE (3): Berat → trigger throttle
 * - THERMAL_STATUS_CRITICAL (4): Kritis → stop all inference
 * - THERMAL_STATUS_EMERGENCY (5): Darurat → shutdown
 */
package com.zonaosier.governor

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GovernorState(
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val isPowerSaveMode: Boolean = false,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val isOnline: Boolean = true,
    val shouldDownscale: Boolean = false,
    val shouldThrottle: Boolean = false,
    val shouldStop: Boolean = false
)

class BatteryThermalGovernor(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _state = MutableStateFlow(GovernorState())
    val state: StateFlow<GovernorState> = _state.asStateFlow()

    init {
        // Register thermal status listener (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.addThermalStatusListener { status ->
                updateState()
            }
        }
    }

    /**
     * Update state. Dipanggil secara berkala atau saat event.
     */
    fun updateState() {
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val isCharging = batteryManager?.isCharging ?: false
        val isPowerSave = powerManager.isPowerSaveMode
        val thermalStatus = getThermalStatus()
        val isOnline = checkOnlineStatus()

        val newState = GovernorState(
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSave,
            thermalStatus = thermalStatus,
            isOnline = isOnline,
            shouldDownscale = batteryLevel < 20 ||
                thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
                !isOnline,
            shouldThrottle = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
            shouldStop = thermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY
        )
        _state.value = newState
    }

    /**
     * Apakah perlu mendownscale (Adhi→Madya, cloud→local)?
     */
    fun shouldDownscale(): Boolean {
        val s = _state.value
        return s.batteryLevel < 20 ||
            s.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
            !s.isOnline
    }

    fun isBatteryLow(): Boolean =
        (batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100) < 20

    fun isThermalModerateOrAbove(): Boolean =
        _state.value.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    fun isBatterySaver(): Boolean = powerManager.isPowerSaveMode

    fun isThermalThrottling(): Boolean =
        _state.value.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

    fun isOfflineMode(): Boolean = !_state.value.isOnline

    fun isCharging(): Boolean = _state.value.isCharging

    /**
     * Cek thermal status.
     */
    private fun getThermalStatus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
    }

    /**
     * Cek koneksi internet.
     */
    private fun checkOnlineStatus(): Boolean {
        if (connectivityManager == null) return false
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Rekomendasi berdasarkan state saat ini.
     */
    fun getRecommendation(): String {
        val s = _state.value
        return when {
            s.shouldStop -> "EMERGENCY: Hentikan semua inferensi segera."
            s.shouldThrottle -> "THROTTLE: Turunkan ke model terkecil atau hentikan sementara."
            s.shouldDownscale -> "DOWNSCALE: Turunkan tier model atau beralih ke cloud."
            s.isBatterySaver -> "SAVER: Battery saver aktif. Gunakan model paling kecil atau cloud."
            !s.isOnline -> "OFFLINE: Tidak ada koneksi. Gunakan model lokal."
            else -> "OK: Semua sistem normal."
        }
    }
}