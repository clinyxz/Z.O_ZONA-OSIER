/**
 * ZONA-OSIER — GitHub Sync Service.
 *
 * Foreground service untuk sinkronisasi memori ke repository privat GitHub.
 * Service ini dijalankan sebagai foreground agar tidak di-kill oleh OS
 * selama proses sync berlangsung.
 *
 * Alur:
 * 1. Terima Intent dengan action SYNC_MEMORY atau SYNC_STATUS.
 * 2. Tampilkan notifikasi foreground di CHANNEL_SYNC.
 * 3. Jalankan sync via GitHubSyncManager di coroutine scope.
 * 4. Update notifikasi berdasarkan hasil (sukses / gagal).
 * 5. Stop foreground dan self setelah selesai.
 *
 * ⚠️ GitHubSyncManager harus di-inisialisasi dengan context, ConversationDao,
 *    dan AESEncryptor. Karena service tidak bisa menerima constructor args,
 *    diakses via singleton pattern pada GitHubSyncManager atau di-init
 *    dari Application.
 */
package com.zonaosier.memory

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.zonaosier.CHANNEL_SYNC
import kotlinx.coroutines.*

/**
 * Intent actions yang didukung oleh GitHubSyncService.
 */
object GitHubSyncActions {
    /** Mulai sinkronisasi memori ke GitHub. */
    const val SYNC_MEMORY = "SYNC_MEMORY"

    /** Cek status sinkronisasi terakhir. */
    const val SYNC_STATUS = "SYNC_STATUS"
}

class GitHubSyncService : Service() {

    companion object {
        private const val TAG = "GitHubSyncSvc"

        /** Foreground notification ID. */
        const val NOTIFICATION_ID = 2

        /** ID extra untuk karakter spesifik (opsional). */
        const val EXTRA_CHARACTER_ID = "character_id"
    }

    /** Coroutine scope khusus service. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Job aktif untuk sync. */
    private var syncJob: Job? = null

    /** Notification manager. */
    private lateinit var notificationManager: NotificationManager

    /** Instance GitHubSyncManager — lazy di-init saat dibutuhkan. */
    private val syncManager: GitHubSyncManager? by lazy {
        try {
            GitHubSyncManager(
                context = this,
                conversationDao = com.zonaosier.ZonaOsierApp.instance.database.conversationDao(),
                encryptor = AESEncryptor(this)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menginisialisasi GitHubSyncManager", e)
            null
        }
    }

    // ==================== Lifecycle ====================

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        Log.d(TAG, "GitHubSyncService onCreate")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand — action=$action")

        when (action) {
            GitHubSyncActions.SYNC_MEMORY -> handleSyncMemory(intent)
            GitHubSyncActions.SYNC_STATUS -> handleSyncStatus()
            else -> {
                Log.w(TAG, "Aksi tidak dikenali: $action")
                stopForegroundAndSelf()
            }
        }

        // Tidak sticky — service hanya berjalan saat ada pekerjaan.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — membatalkan semua coroutine")
        syncJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ==================== Action Handlers ====================

    /**
     * Menangani aksi SYNC_MEMORY.
     * Memulai foreground service, lalu menjalankan sync di coroutine.
     *
     * @param intent Intent yang berisi optional EXTRA_CHARACTER_ID.
     */
    private fun handleSyncMemory(intent: Intent?) {
        // Tampilkan notifikasi foreground segera
        val characterId = intent?.getStringExtra(EXTRA_CHARACTER_ID)
        val targetLabel = if (characterId.isNullOrBlank()) "semua memori" else "karakter $characterId"
        val initialNotification = createNotification("Memulai sinkronisasi $targetLabel…")
        startForeground(NOTIFICATION_ID, initialNotification)

        // Cegah duplikasi job
        if (syncJob?.isActive == true) {
            Log.d(TAG, "Sync sudah berjalan, abaikan permintaan baru.")
            updateNotification("Sinkronisasi sedang berjalan…")
            return
        }

        // Jalankan sync di coroutine
        syncJob = serviceScope.launch {
            performSync(characterId)
        }
    }

    /**
     * Menangani aksi SYNC_STATUS.
     * Menampilkan status terakhir (konfigurasi) via notifikasi.
     */
    private fun handleSyncStatus() {
        // Juga mulai foreground untuk status cek
        val initialNotification = createNotification("Mengecek status sinkronisasi…")
        startForeground(NOTIFICATION_ID, initialNotification)

        serviceScope.launch {
            val manager = syncManager
            val statusText = if (manager == null) {
                "GitHubSyncManager tidak tersedia."
            } else if (manager.isConfigured()) {
                "GitHub sync terkonfigurasi dan siap."
            } else {
                "GitHub sync belum dikonfigurasi. Isi GITHUB_SYNC_TOKEN dan GITHUB_SYNC_REPO."
            }

            updateNotification(statusText)
            Log.d(TAG, "Status: $statusText")

            // Tunggu sebentar agar user bisa membaca, lalu stop
            delay(3000)
            stopForegroundAndSelf()
        }
    }

    // ==================== Core Sync Logic ====================

    /**
     * Menjalankan sinkronisasi memori melalui GitHubSyncManager.
     *
     * @param characterId ID karakter spesifik (null = sync global).
     */
    private suspend fun performSync(characterId: String?) {
        val manager = syncManager
        if (manager == null) {
            updateNotification("Gagal: GitHubSyncManager tidak tersedia.")
            Log.e(TAG, "syncManager null — tidak bisa menjalankan sync.")
            delay(2000)
            stopForegroundAndSelf()
            return
        }

        updateNotification("Menyinkronkan memori ke GitHub…")
        Log.d(TAG, "Memulai sync ke GitHub (characterId=$characterId)")

        val result = try {
            manager.syncMemory(characterId)
        } catch (e: CancellationException) {
            Log.w(TAG, "Sync dibatalkan.")
            updateNotification("Sinkronisasi dibatalkan.")
            stopForegroundAndSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Sync gagal dengan exception", e)
            GitHubSyncManager.SyncResult.Error("Exception: ${e.message}")
        }

        // Handle hasil sync
        when (result) {
            is GitHubSyncManager.SyncResult.Success -> {
                val message = result.message
                updateNotification("✅ $message")
                Log.d(TAG, "Sync sukses: $message")
            }
            is GitHubSyncManager.SyncResult.Error -> {
                val message = result.message
                updateNotification("❌ $message")
                Log.e(TAG, "Sync gagal: $message")
            }
        }

        // Beri waktu user membaca notifikasi sebelum stop
        delay(2000)
        stopForegroundAndSelf()
    }

    // ==================== Notification Helpers ====================

    /**
     * Update notifikasi foreground.
     *
     * @param text Isi teks notifikasi.
     */
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Buat objek Notification untuk CHANNEL_SYNC.
     *
     * @param text Isi konten notifikasi.
     * @return Instance Notification.
     */
    private fun createNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_SYNC)
            .setContentTitle("ZONA-OSIER · Sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setProgress(0, 0, true) // Indeterminate progress
            .build()
    }

    /**
     * Hentikan foreground notification dan service itu sendiri.
     */
    private fun stopForegroundAndSelf() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground error", e)
        }
        stopSelf()
    }
}
