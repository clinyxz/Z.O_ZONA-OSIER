/**
 * ZONA-OSIER — Freeze Agent (Kill-Switch).
 * 
 * Satu tombol darurat yang mencabut seluruh elevated access sekaligus:
 * - Shizuku binding (shell, tap, screenshot)
 * - Termux executor
 * - AccessibilityService (screen read, gesture)
 * - SMS (send/read)
 * - Kamera (face recognition)
 * 
 * ⚠️ freeze() TIDAK mencabut izin runtime secara permanen.
 * Hanya memblokir akses pada sesi berjalan.
 * Izin dikembalikan saat unfreeze().
 * 
 * Semua komponen yang menjalankan operasi elevated WAJIB
 * mengecek isFrozen() sebelum setiap eksekusi.
 */
package com.zonaosier.security

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.zonaosier.ZonaOsierApp
import com.zonaosier.memory.entity.AuditStatus

class FreezeAgent(private val context: Context) {

    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Bekukan semua akses elevated.
     * Dipanggil dari UI (tombol darurat) atau voice command.
     */
    fun freeze() {
        prefs.edit().putBoolean(KEY_FROZEN, true).apply()
        prefs.edit().putLong(KEY_FROZEN_AT, System.currentTimeMillis()).apply()

        // 1. Hentikan AccessibilityService
        stopAccessibilityService()

        // 2. Flag is_frozen — ShizukuController mengecek ini
        //    sebelum setiap ShizukuBinder.transact()
        // (Tidak perlu memanggil Shizuku.unbind — cukup flag)

        // 3. Flag is_frozen — TermuxExecutor mengecek ini
        //    sebelum setiap intent ke Termux

        // 4. Catat ke audit log
        AuditLogger.log(
            toolName = "FreezeAgent",
            action = "freeze",
            status = AuditStatus.MODIFIED,
            detail = "User triggered freeze — semua akses elevated dicabut",
            characterId = null
        )
    }

    /**
     * Kembalikan semua akses elevated.
     * User harus mengkonfirmasi melalui biometrik (dipanggil dari UI).
     */
    fun unfreeze() {
        prefs.edit().putBoolean(KEY_FROZEN, false).apply()
        prefs.edit().putLong(KEY_FROZEN_AT, 0L).apply()

        // Restart AccessibilityService jika sebelumnya aktif
        if (prefs.getBoolean(KEY_ACCESSIBILITY_WAS_ACTIVE, false)) {
            startAccessibilityService()
            prefs.edit().putBoolean(KEY_ACCESSIBILITY_WAS_ACTIVE, false).apply()
        }

        AuditLogger.log(
            toolName = "FreezeAgent",
            action = "unfreeze",
            status = AuditStatus.APPROVED,
            detail = "User triggered unfreeze — akses elevated dikembalikan",
            characterId = null
        )
    }

    /**
     * Cek apakah agent sedang dibekukan.
     * WAJIB dipanggil oleh semua komponen elevated sebelum eksekusi.
     */
    fun isFrozen(): Boolean = prefs.getBoolean(KEY_FROZEN, false)

    /**
     * Waktu freeze terakhir (epoch millis). 0 jika tidak pernah freeze.
     */
    fun frozenAt(): Long = prefs.getLong(KEY_FROZEN_AT, 0L)

    /**
     * Durasi freeze dalam detik. 0 jika tidak freeze.
     */
    fun frozenDurationSeconds(): Long {
        val at = frozenAt()
        if (at == 0L) return 0L
        return (System.currentTimeMillis() - at) / 1000
    }

    // ==================== Internal ====================

    private fun stopAccessibilityService() {
        // Simpan state sebelum stop
        prefs.edit().putBoolean(KEY_ACCESSIBILITY_WAS_ACTIVE, isAccessibilityRunning()).apply()

        try {
            val intent = Intent(context, com.zonaosier.system.ZonaAccessibilityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            // Service sendiri akan cek isFrozen() dan berhenti
        } catch (_: Exception) {
            // Service tidak berjalan — tidak masalah
        }
    }

    private fun startAccessibilityService() {
        // AccessibilityService tidak bisa di-start via intent biasa.
        // User harus mengaktifkan ulang dari Settings.
        // Di sini kita hanya mencatat bahwa seharusnya aktif.
    }

    /**
     * Cek apakah AccessibilityService sedang berjalan.
     */
    private fun isAccessibilityRunning(): Boolean {
        // Implementasi: cek melalui Settings.Secure
        val prefString = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_accessibility_services"
        ) ?: return false
        return prefString.contains(context.packageName)
    }

    companion object {
        private const val PREFS_NAME = "freeze_state"
        private const val KEY_FROZEN = "is_frozen"
        private const val KEY_FROZEN_AT = "frozen_at"
        private const val KEY_ACCESSIBILITY_WAS_ACTIVE = "accessibility_was_active"

        /**
         * Static check — dipanggil dari ShizukuTermuxBridge, SystemStatusMonitor,
         * dan komponen lain yang tidak punya instance FreezeAgent.
         */
        fun isFrozen(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_FROZEN, false)
        }
    }
}
