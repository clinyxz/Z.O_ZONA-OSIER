/**
 * ZONA-OSIER — AudioPipeline.
 * Pipeline real-time duplex untuk voice interaction.
 *
 * Alur:
 * 1. AudioRecord capture → VADGatekeeper
 * 2. VAD: Speech? → STTRouter (Vosk/Google Cloud)
 * 3. STT: Transkrip → IntentClassifier
 *    ├─ Chat Intent    → AgentLoop → TTS → AudioTrack
 *    ├─ System Intent  → SystemThinker → Tool → Response
 *    └─ Info Request   → Query Web/Memori → Response
 * 4. TTS: Text → AudioTrack playback
 * 5. Barge-in: VAD detects user speech → stop TTS
 */
package com.zonaosier.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.zonaosier.agent.AgentEvent
import com.zonaosier.governor.BatteryThermalGovernor
import com.zonaosier.voice.stt.*
import com.zonaosier.voice.tts.IndonesianSentenceSplitter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * State pipeline.
 */
enum class PipelineState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

/**
 * Event dari pipeline ke UI.
 */
sealed class PipelineEvent {
    data class StateChanged(val newState: PipelineState) : PipelineEvent()
    data class Transcription(val text: String, val isFinal: Boolean) : PipelineEvent()
    data class IntentRecognized(val intent: IntentType, val text: String) : PipelineEvent()
    data class TTSAudioChunk(val audio: ByteArray, val isLast: Boolean) : PipelineEvent()
    data class BargeIn(val reason: String) : PipelineEvent()
    data class Error(val message: String) : PipelineEvent()
}

class AudioPipeline(
    private val context: Context,
    private val vadGatekeeper: VADGatekeeper,
    private val sttRouter: CalibratedSTTRouter,
    private val voiceRouter: VoiceRouter,
    private val governor: BatteryThermalGovernor,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events

    private val _state = MutableStateFlow(PipelineState.IDLE)
    val state: StateFlow<PipelineState> = _state

    private var audioRecord: AudioRecord? = null
    private var isRunning = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private var activeAudioTrack: AudioTrack? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE_FACTOR = 2
    }

    /**
     * Mulai pipeline (mikrofon capture → VAD → STT → LLM → TTS).
     */
    fun start() {
        if (isRunning.getAndSet(true)) return
        _state.value = PipelineState.LISTENING
        _events.tryEmit(PipelineEvent.StateChanged(PipelineState.LISTENING))

        scope.launch {
            val record = vadGatekeeper.createAudioRecord()
            audioRecord = record

            try {
                record.startRecording()
                val buffer = ShortArray(480) // 30ms frame @ 16kHz

                while (isRunning.get()) {
                    val readCount = record.read(buffer, 0, buffer.size)
                    if (readCount <= 0) continue

                    val frame = if (readCount == buffer.size) buffer else buffer.copyOf(readCount)
                    vadGatekeeper.processFrame(frame)

                    // Cek state VAD
                    when (vadGatekeeper.status.value) {
                        VadStatus.SPEECH_END -> {
                            val speechAudio = vadGatekeeper.consumeSpeechAudio()
                            if (speechAudio != null) {
                                processSpeechAudio(speechAudio)
                            }
                        }
                        VadStatus.SPEECH -> {
                            // Barge-in detection
                            if (isSpeaking.get()) {
                                handleBargeIn()
                            }
                        }
                        VadStatus.SILENCE -> { /* no-op */ }
                    }
                }
            } catch (_: SecurityException) {
                _events.tryEmit(PipelineEvent.Error("Permission RECORD_AUDIO tidak diberikan."))
                _state.value = PipelineState.ERROR
            } finally {
                record.stop()
                record.release()
                audioRecord = null
                isRunning.set(false)
            }
        }
    }

    /**
     * Proses audio yang sudah terkumpul.
     */
    private suspend fun processSpeechAudio(audio: ShortArray) {
        _state.value = PipelineState.PROCESSING
        _events.tryEmit(PipelineEvent.StateChanged(PipelineState.PROCESSING))

        // 1. STT
        val text = sttRouter.transcribe(audio, STTMode.FREE)
        if (text.isBlank()) {
            _state.value = PipelineState.LISTENING
            _events.tryEmit(PipelineEvent.StateChanged(PipelineState.LISTENING))
            return
        }

        _events.tryEmit(PipelineEvent.Transcription(text, true))

        // 2. Intent classification
        // (AgentLoop akan menangani processing)
        _events.tryEmit(PipelineEvent.IntentRecognized(IntentType.CHAT, text))
    }

    /**
     * Putar audio TTS (dipanggil dari AgentLoop response).
     */
    fun playTTS(text: String) {
        scope.launch {
            _state.value = PipelineState.SPEAKING
            _events.tryEmit(PipelineEvent.StateChanged(PipelineState.SPEAKING))
            isSpeaking.set(true)

            try {
                val isOnline = !governor.isOfflineMode()
                val needsExpressive = voiceRouter.isExpressiveTag(voiceRouter.getCurrentTag())
                val batterySaver = governor.isBatterySaver()

                // Sentence-level streaming untuk TTS lokal
                val route = voiceRouter.resolveRoute(isOnline, batterySaver, needsExpressive)
                val sentences = if (route == VoiceRoute.LOCAL) {
                    IndonesianSentenceSplitter.split(text, maxChars = 200)
                } else {
                    listOf(text)
                }

                for ((index, sentence) in sentences.withIndex()) {
                    if (!isSpeaking.get()) break // Barge-in

                    val audio = voiceRouter.synthesize(
                        text = sentence,
                        isOnline = isOnline,
                        batterySaver = batterySaver,
                        needsExpressive = needsExpressive
                    )

                    if (audio != null) {
                        playAudioBytes(audio)
                        _events.tryEmit(
                            PipelineEvent.TTSAudioChunk(
                                audio = audio,
                                isLast = index == sentences.size - 1
                            )
                        )
                    }
                }
            } finally {
                isSpeaking.set(false)
                _state.value = PipelineState.LISTENING
                _events.tryEmit(PipelineEvent.StateChanged(PipelineState.LISTENING))
            }
        }
    }

    /**
     * Putar PCM 16-bit audio ke speaker.
     */
    private fun playAudioBytes(pcmData: ByteArray) {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * BUFFER_SIZE_FACTOR)
            .build()

        activeAudioTrack = audioTrack
        audioTrack.play()
        audioTrack.write(pcmData, 0, pcmData.size)
        audioTrack.stop()
        audioTrack.release()
        activeAudioTrack = null
    }

    /**
     * Handle barge-in (user bicara saat TTS sedang berjalan).
     */
    private fun handleBargeIn() {
        _events.tryEmit(PipelineEvent.BargeIn("User detected speaking"))
        isSpeaking.set(false)
        activeAudioTrack?.stop()
        activeAudioTrack?.release()
        activeAudioTrack = null
    }

    /**
     * Stop pipeline.
     */
    fun stop() {
        isRunning.set(false)
        isSpeaking.set(false)
        activeAudioTrack?.stop()
        audioRecord?.stop()
        _state.value = PipelineState.IDLE
        _events.tryEmit(PipelineEvent.StateChanged(PipelineState.IDLE))
    }

    /**
     * Cleanup.
     */
    fun shutdown() {
        stop()
        scope.cancel()
    }
}
