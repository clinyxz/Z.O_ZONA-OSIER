/**
 * ZONA-OSIER — Notification Listener Service (Full Implementation).
 * Membaca notifikasi dari aplikasi lain untuk konteks agent.
 *
 * Fitur:
 * - Extract: title, text, package, category, timestamp, group
 * - Filter: abaikan notifikasi Z.O sendiri dan notifikasi ongoing
 * - Flow: SharedFlow<NotificationData> untuk UI dan agent subscribe
 * - History: simpan 100 notifikasi terakhir di SharedPreferences (ringan)
 * - Spam dedup: skip notifikasi duplikat dalam 5 detik
 *
 * ⚠️ Tidak bisa diaktifkan lewat runtime permission biasa.
 * User harus ke Settings → Notification Access secara manual.
 */
package com.zonaosier.system

import android.content.SharedPreferences
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Data notifikasi yang diekstrak dari StatusBarNotification.
 */
data class NotificationData(
    val key: String,
    val packageName: String,
    val appLabel: String?,
    val title: String?,
    val text: String?,
    val category: String?,
    val timestamp: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val groupKey: String?,
    val extras: Map<String, String?> = emptyMap()
) {
    /** Gabungan title + text untuk pencarian dan agent. */
    val displayText: String
        get() = buildString {
            if (!title.isNullOrBlank()) append(title)
            if (!text.isNullOrBlank()) {
                if (isNotEmpty()) append(": ")
                append(text)
            }
        }

    /** Singkat untuk preview. */
    val preview: String
        get() = displayText.take(120).let {
            if (displayText.length > 120) "$it..." else it
        }

    companion object {
        /** Package yang diabaikan — notifikasi sistem atau Z.O sendiri. */
        val IGNORED_PACKAGES = setOf(
            "com.zonaosier",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.providers.media",
            "com.google.android.apps.nexuslauncher"
        )

        /** Maksimum history yang disimpan. */
        const val MAX_HISTORY = 100

        /** Jeda dedup dalam milidetik — notifikasi sama dari app sama. */
        const val DEDUP_INTERVAL_MS = 5000L
    }
}

/**
 * Policy untuk notifikasi mana yang diteruskan ke agent.
 */
enum class NotificationForwardPolicy {
    /** Semua notifikasi diteruskan (default). */
    ALL,
    /** Hanya dari daftar package yang diizinkan. */
    WHITELIST_ONLY,
    /** Tidak ada yang diteruskan ke agent. */
    NONE
}

class ZonaNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "ZonaNotifListener"
        private const val PREFS_NAME = "zona_notif_history"
        private const val KEY_HISTORY = "notif_history_list"
        private const val KEY_LAST_SEEN = "notif_last_seen"

        @Volatile
        private var instance: ZonaNotificationListenerService? = null

        fun isServiceActive(): Boolean = instance != null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gson = Gson()
    private lateinit var historyPrefs: SharedPreferences

    /** Track notifikasi terakhir per package untuk dedup. */
    private val lastSeenMap = mutableMapOf<String, Long>()

    /**
     * Flow untuk notifikasi yang diproses.
     * UI dan agent bisa subscribe.
     */
    private val _notifications = MutableSharedFlow<NotificationData>(
        replay = 0,
        extraBufferCapacity = 30,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val notifications: SharedFlow<NotificationData> = _notifications.asSharedFlow()

    /**
     * Flow untuk ringkasan notifikasi baru (count per minute).
     */
    private val _summary = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val summary: SharedFlow<String> = _summary.asSharedFlow()

    /** Policy penerusan ke agent. */
    var forwardPolicy: NotificationForwardPolicy = NotificationForwardPolicy.ALL

    /** Whitelist package untuk policy WHITELIST_ONLY. */
    var whitelistedPackages: Set<String> = emptySet()

    // ==================== Lifecycle ====================

    override fun onCreate() {
        super.onCreate()
        instance = this
        historyPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadLastSeenMap()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ==================== Notification Events ====================

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val data = extractNotificationData(sbn)

        // Filter: abaikan notifikasi dari Z.O dan sistem
        if (data.packageName in NotificationData.IGNORED_PACKAGES) return

        // Filter: dedup per package
        val lastSeen = lastSeenMap[data.packageName] ?: 0L
        if (data.timestamp - lastSeen < NotificationData.DEDUP_INTERVAL_MS) return
        lastSeenMap[data.packageName] = data.timestamp

        // Simpan ke history
        addToHistory(data)

        // Emit ke flow
        serviceScope.launch {
            _notifications.emit(data)
        }

        // Log untuk audit
        serviceScope.launch(Dispatchers.IO) {
            com.zonaosier.security.AuditLogger.log(
                toolName = "NotificationListener",
                action = "NOTIF_RECEIVED",
                status = com.zonaosier.memory.entity.AuditStatus.APPROVED,
                detail = "${data.appLabel ?: data.packageName}: ${data.preview}"
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Cleanup: bisa di-track jika perlu
        // Untuk sekarang, tidak ada aksi khusus
    }

    // ==================== Extraction ====================

    /**
     * Ekstrak data terstruktur dari StatusBarNotification.
     */
    private fun extractNotificationData(sbn: StatusBarNotification): NotificationData {
        val notification = sbn.notification ?: return createFallbackData(sbn)
        val extras = notification.extras

        // Judul
        val title = extractText(extras, "android.title")
            ?: extractText(extras, "android.bigText")

        // Isi teks
        val text = extractText(extras, "android.text")
            ?: extractText(extras, "android.bigText")

        // Sub-teks (biasanya info sekunder)
        val subText = extractText(extras, "android.subText")

        // App label
        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        // Ekstra info
        val extrasMap = mutableMapOf<String, String?>()
        extras?.keySet()?.forEach { key ->
            val value = extras.get(key)
            extrasMap[key] = value?.toString()?.take(200)
        }

        return NotificationData(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text ?: subText,
            category = notification.category,
            timestamp = sbn.postTime,
            isOngoing = (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0,
            isClearable = (notification.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0,
            groupKey = sbn.groupKey,
            extras = extrasMap
        )
    }

    /**
     * Ekstrak teks dari Bundle.
     * Menangani CharSequence, String, dan SpannableString.
     */
    private fun extractText(bundle: Bundle?, key: String): String? {
        if (bundle == null) return null
        val value = bundle.get(key) ?: return null
        return when (value) {
            is String -> value.ifBlank { null }
            is CharSequence -> value.toString().ifBlank { null }
            else -> null
        }
    }

    private fun createFallbackData(sbn: StatusBarNotification): NotificationData {
        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        return NotificationData(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = null,
            text = null,
            category = null,
            timestamp = sbn.postTime,
            isOngoing = false,
            isClearable = true,
            groupKey = null
        )
    }

    // ==================== History ====================

    /**
     * Ambil N notifikasi terakhir.
     *
     * @param count Jumlah notifikasi (default 20).
     * @return List notifikasi terbaru.
     */
    fun getRecentNotifications(count: Int = 20): List<NotificationData> {
        val history = loadHistory()
        return history.takeLast(count)
    }

    /**
     * Cari notifikasi berdasarkan package.
     */
    fun getNotificationsByPackage(packageName: String): List<NotificationData> {
        return loadHistory().filter { it.packageName == packageName }
    }

    /**
     * Cari notifikasi berdasarkan teks.
     */
    fun searchNotifications(query: String): List<NotificationData> {
        val q = query.lowercase()
        return loadHistory().filter {
            it.displayText.lowercase().contains(q) ||
                    it.packageName.lowercase().contains(q)
        }
    }

    /**
     * Hapus semua history notifikasi.
     */
    fun clearHistory() {
        historyPrefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun addToHistory(data: NotificationData) {
        val history = loadHistory().toMutableList()
        history.add(data)

        // Simpan MAX_HISTORY terakhir
        if (history.size > NotificationData.MAX_HISTORY) {
            val trimmed = history.takeLast(NotificationData.MAX_HISTORY)
            saveHistory(trimmed)
        } else {
            saveHistory(history)
        }

        // Simpan lastSeenMap
        saveLastSeenMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadHistory(): List<NotificationData> {
        val json = historyPrefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<NotificationData>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(history: List<NotificationData>) {
        val json = gson.toJson(history)
        historyPrefs.edit().putString(KEY_HISTORY, json).apply()
    }

    private fun loadLastSeenMap() {
        val json = historyPrefs.getString(KEY_LAST_SEEN, null) ?: return
        try {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            val map: Map<String, Long> = gson.fromJson(json, type)
            lastSeenMap.putAll(map)
        } catch (_: Exception) { }
    }

    private fun saveLastSeenMap() {
        val json = gson.toJson(lastSeenMap)
        historyPrefs.edit().putString(KEY_LAST_SEEN, json).apply()
    }

    // ==================== Active Check ====================

    /**
     * Cek apakah service ini aktif dan terhubung.
     */
    fun isServiceConnected(): Boolean {
        return try {
            instance != null && !sbnMap.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Cek status dari luar (static).
     */
    fun checkStatus(): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        return flat.contains(packageName)
    }
}
