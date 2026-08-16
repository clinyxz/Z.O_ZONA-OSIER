/**
 * ZONA-OSIER — System Thinker Background Service.
 *
 * Menjalankan reasoning berat (7B-13B model lokal) di background.
 * Service ini menerima perintah START_THINKING dan STOP_THINKING via Intent.
 *
 * Arsitektur:
 * - Menggunakan coroutine scope dengan SupervisorJob agar error
 *   pada satu child tidak membatalkan seluruh scope.
 * - Loop reasoning berjalan hingga 20 iterasi (sesuai DualBrainOrchestrator).
 * - BatteryThermalGovernor memantau kondisi device; thinking di-pause
 *   jika thermal terlalu tinggi atau baterai kritis.
 * - Notifikasi dikirim ke CHANNEL_AGENT untuk transparansi ke user.
 * - START_STICKY memastikan service di-restart jika killed oleh OS.
 */
package com.zonaosier.brain

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.zonaosier.CHANNEL_AGENT
import com.zonaosier.governor.BatteryThermalGovernor
import kotlinx.coroutines.*

class SystemThinkerService : Service() {

    companion object {
        private const val TAG = "SystemThinkerSvc"

        /** Intent action: mulai loop reasoning. */
        const val ACTION_START_THINKING = "START_THINKING"

        /** Intent action: hentikan loop reasoning. */
        const val ACTION_STOP_THINKING = "STOP_THINKING"

        /** Notification ID untuk CHANNEL_AGENT. */
        private const val NOTIFICATION_ID = 100

        /** Maksimum iterasi reasoning per sesi (sesuai DualBrainOrchestrator.SYSTEM_THINKER_MAX_ITERATIONS). */
        private const val MAX_THINKING_ITERATIONS = 20

        /** Delay antar iterasi untuk simulasi reasoning (ms). */
        private const val ITERATION_DELAY_MS = 2000L

        /** Delay saat thinking di-pause karena thermal/battery (ms). */
        private const val GOVERNOR_PAUSE_DELAY_MS = 10000L
    }

    /** Coroutine scope khusus service. SupervisorJob agar satu child gagal tidak membatalkan yang lain. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Job aktif untuk thinking loop. Bisa di-cancel kapan saja. */
    private var thinkingJob: Job? = null

    /** Governor untuk memantau baterai dan termal. */
    private lateinit var governor: BatteryThermalGovernor

    /** Flag apakah model lokal sudah siap dipakai. */
    private var isModelLoaded: Boolean = false

    /** Notification manager untuk update notifikasi. */
    private lateinit var notificationManager: NotificationManager

    // ==================== Lifecycle ====================

    override fun onCreate() {
        super.onCreate()
        governor = BatteryThermalGovernor(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        Log.d(TAG, "SystemThinkerService onCreate")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand — action=${intent?.action}")

        when (intent?.action) {
            ACTION_START_THINKING -> startThinking()
            ACTION_STOP_THINKING -> stopThinking()
            else -> Log.w(TAG, "Aksi tidak dikenali: ${intent?.action}")
        }

        // START_STICKY: OS akan restart service jika di-kill.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — membatalkan semua coroutine")
        stopThinking()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ==================== Thinking Control ====================

    /**
     * Memulai thinking loop di coroutine terpisah.
     * Jika sudah berjalan, tidak menduplikasi.
     */
    private fun startThinking() {
        // Cegah duplikasi job jika sudah berjalan
        if (thinkingJob?.isActive == true) {
            Log.d(TAG, "Thinking loop sudah berjalan, abaikan.")
            return
        }

        thinkingJob = serviceScope.launch {
            runThinkingLoop()
        }

        Log.d(TAG, "Thinking loop dimulai.")
    }

    /**
     * Menghentikan thinking loop dan membersihkan resource.
     */
    private fun stopThinking() {
        thinkingJob?.cancel()
        thinkingJob = null
        updateNotification("System Thinker berhenti.")
        Log.d(TAG, "Thinking loop dihentikan.")
    }

    // ==================== Core Thinking Loop ====================

    /**
     * Loop utama reasoning.
     *
     * Alur:
     * 1. Cek apakah model lokal sudah loaded.
     * 2. Periksa BatteryThermalGovernor — pause jika perlu.
     * 3. Jalankan satu iterasi reasoning (simulasi).
     * 4. Update notifikasi dengan progress.
     * 5. Ulangi hingga MAX_THINKING_ITERATIONS atau job di-cancel.
     */
    private suspend fun runThinkingLoop() {
        // Simulasi load model lokal
        isModelLoaded = simulateModelLoad()
        if (!isModelLoaded) {
            updateNotification("System Thinker: gagal memuat model lokal.")
            Log.e(TAG, "Model lokal gagal dimuat.")
            return
        }

        updateNotification("System Thinker: model siap, mulai reasoning…")
        Log.d(TAG, "Model lokal berhasil dimuat, memulai reasoning loop.")

        for (iteration in 1..MAX_THINKING_ITERATIONS) {
            // Cek apakah job sudah di-cancel
            ensureActive()

            // Periksa kondisi governor sebelum reasoning
            governor.updateState()
            if (shouldPauseThinking()) {
                updateNotification(
                    "System Thinker: di-pause — ${governor.getRecommendation()}"
                )
                Log.w(TAG, "Thinking di-pause: ${governor.getRecommendation()}")

                // Tunggu dan cek lagi
                delay(GOVERNOR_PAUSE_DELAY_MS)
                governor.updateState()
                if (shouldPauseThinking()) {
                    // Jika masih perlu pause, hentikan loop
                    Log.w(TAG, "Masih perlu pause setelah tunggu, menghentikan loop.")
                    updateNotification("System Thinker: dihentikan — kondisi device tidak memungkinkan.")
                    break
                }
                // Lanjutkan jika kondisi sudah membaik
                updateNotification("System Thinker: melanjutkan reasoning…")
            }

            // Cek lagi sebelum iterasi
            ensureActive()

            // Jalankan satu iterasi reasoning
            val result = runSingleReasoningIteration(iteration)

            // Update notifikasi dengan progress
            updateNotification(
                "System Thinker: iterasi $iteration/$MAX_THINKING_ITERATIONS — $result"
            )
            Log.d(TAG, "Iterasi $iteration/$MAX_THINKING_ITERATIONS selesai: $result")

            // Delay antar iterasi
            delay(ITERATION_DELAY_MS)
        }

        // Loop selesai — semua iterasi selesai
        updateNotification("System Thinker: reasoning selesai ($MAX_THINKING_ITERATIONS iterasi).")
        Log.d(TAG, "Thinking loop selesai — semua $MAX_THINKING_ITERATIONS iterasi selesai.")
    }

    /**
     * Simulasi satu iterasi reasoning pada model lokal.
     * Di produksi, ini akan memanggil LocalModelClient.chat().
     *
     * @param iteration Nomor iterasi saat ini.
     * @return Ringkasan hasil reasoning.
     */
    private suspend fun runSingleReasoningIteration(iteration: Int): String {
        // Simulasi reasoning — di produksi akan memanggil model lokal
        return try {
            // TODO: Ganti dengan panggilan ke LocalModelClient.chat()
            //      atau AgentLoop.run() untuk reasoning multi-step.
            delay(500) // Simulasi latensi model
            "reasoning OK (token ~${iteration * 128})"
        } catch (e: CancellationException) {
            throw e // Jangan swallow CancellationException
        } catch (e: Exception) {
            Log.e(TAG, "Error pada iterasi $iteration", e)
            "error: ${e.message}"
        }
    }

    /**
     * Simulasi pemuatan model lokal.
     * Di produksi, ini akan memuat model dari storage (llama.cpp / MLC).
     *
     * @return true jika model berhasil dimuat.
     */
    private suspend fun simulateModelLoad(): Boolean {
        return try {
            // TODO: Implementasi pemuatan model lokal sebenarnya.
            //      Contoh: LocalModelClient.loadModel("mistral-7b-instruct")
            delay(1000) // Simulasi waktu load
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memuat model lokal", e)
            false
        }
    }

    // ==================== Governor Checks ====================

    /**
     * Cek apakah thinking harus di-pause berdasarkan governor state.
     * Pause jika: thermal severe/critical/emergency, battery saver aktif,
     * atau governor menandakan shouldStop.
     *
     * @return true jika thinking harus di-pause.
     */
    private fun shouldPauseThinking(): Boolean {
        val state = governor.state.value
        return state.shouldStop || state.shouldThrottle || state.isPowerSaveMode
    }

    // ==================== Notification Helpers ====================

    /**
     * Update atau posting notifikasi baru ke CHANNEL_AGENT.
     *
     * @param text Isi teks notifikasi.
     */
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Buat objek Notification untuk CHANNEL_AGENT.
     *
     * @param text Isi konten notifikasi.
     * @return Instance Notification.
     */
    private fun createNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_AGENT)
            .setContentTitle("ZONA-OSIER · System Thinker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    /**
     * Helper untuk memastikan coroutine masih aktif.
     * Melempar CancellationException jika sudah di-cancel.
     */
    private suspend fun ensureActive() {
        yield() // Memberikan kesempatan cancellation check
    }
}
