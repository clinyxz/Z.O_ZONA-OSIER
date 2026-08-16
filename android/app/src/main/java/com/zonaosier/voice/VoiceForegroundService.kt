/**
 * ZONA-OSIER — Voice Foreground Service.
 * Menjalankan pipeline VAD → STT → LLM → TTS di foreground.
 */
package com.zonaosier.voice

import android.app.*
import android.content.Intent
import android.os.IBinder
import com.zonaosier.governor.BatteryThermalGovernor
import com.zonaosier.voice.stt.*
import com.zonaosier.voice.tts.*
import kotlinx.coroutines.*

class VoiceForegroundService : Service() {

    private var audioPipeline: AudioPipeline? = null
    private var vadGatekeeper: VADGatekeeper? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = createNotification("Memulai voice pipeline...")
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startPipeline()
            ACTION_STOP -> stopPipeline()
        }
        return START_STICKY
    }

    private fun startPipeline() {
        if (audioPipeline != null) return

        try {
            val governor = BatteryThermalGovernor(this)
            vadGatekeeper = VADGatekeeper(this)
            val voskEngine = VoskEngine(this)
            val googleCloudSTT = GoogleCloudSTT(
                com.zonaosier.BuildConfig.GOOGLE_CLOUD_STT_API_KEY.ifBlank { null }
            )
            val calibrationStore = CalibrationStore(this)
            val sttRouter = CalibratedSTTRouter(voskEngine, googleCloudSTT, calibrationStore)

            val miniMaxTts = MiniMaxTTS(this)
            val elevenLabs = ElevenLabsStream(this)
            val localTts = LocalTTS(this)
            val miniMaxCreditChecker = MiniMaxCreditChecker(this)
            val voiceRouter = VoiceRouter(miniMaxTts, elevenLabs, localTts, miniMaxCreditChecker)

            audioPipeline = AudioPipeline(
                context = this,
                vadGatekeeper = vadGatekeeper!!,
                sttRouter = sttRouter,
                voiceRouter = voiceRouter,
                governor = governor,
                scope = serviceScope
            )

            audioPipeline!!.start()
            updateNotification("Voice pipeline aktif — mendengarkan...")
        } catch (e: Exception) {
            updateNotification("Error: ${e.message}")
        }
    }

    private fun stopPipeline() {
        audioPipeline?.shutdown()
        audioPipeline = null
        vadGatekeeper = null
        updateNotification("Voice pipeline berhenti")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    private fun createNotification(text: String): Notification {
        val channel = NotificationChannel(
            CHANNEL_VOICE,
            "Layanan Suara",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_VOICE)
            .setContentTitle("ZONA-OSIER")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        stopPipeline()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.zonaosier.voice.START"
        const val ACTION_STOP = "com.zonaosier.voice.STOP"
        const val CHANNEL_VOICE = "zona_osier_voice"
    }
}
