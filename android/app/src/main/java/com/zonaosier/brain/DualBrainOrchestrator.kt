/**
 * ZONA-OSIER — DualBrainOrchestrator.
 * Separation of concerns antara reasoning berat (background) dan respons cepat (foreground).
 *
 * Arsitektur Dual-Brain:
 * - SYSTEM THINKER (Background Daemon): Model cloud besar/lokal, planning, system exec.
 *   Trigger: Event, Jadwal. Latensi 2-10 detik OK.
 * - VOICE ASSISTANT (Foreground Interface): Model Groq (cepat)/lokal, percakapan real-time.
 *   Trigger: Wake word, Tap user. Target TTFA <800ms.
 *
 * Kedua jalur mendapat persona, model binding, dan tool policy dari
 * CharacterOrchestrator yang sama.
 */
package com.zonaosier.brain

import android.content.Context
import android.content.Intent
import com.zonaosier.agent.*
import com.zonaosier.agent.impl.AgentLoop
import com.zonaosier.memory.entity.CharacterCard
import com.zonaosier.memory.entity.ModelMode
import com.zonaosier.model.ModelTierSelector
import com.zonaosier.security.FreezeAgent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Jalur (brain) yang digunakan untuk respons.
 */
enum class BrainLane {
    /** Foreground — percakapan real-time, cepat. */
    VOICE_ASSISTANT,
    /** Background — planning, system tasks, reasoning berat. */
    SYSTEM_THINKER
}

/**
 * Request ke salah satu brain lane.
 */
data class BrainRequest(
    val lane: BrainLane,
    val input: String,
    val characterId: String? = null,
    val priority: Int = 0 // Higher = lebih diprioritaskan
)

/**
 * Result dari brain lane.
 */
data class BrainResult(
    val lane: BrainLane,
    val events: Flow<AgentEvent>,
    val characterId: String?
null
)

class DualBrainOrchestrator(
    private val context: Context,
    private val characterOrchestrator: CharacterOrchestrator,
    private val modelTierSelector: ModelTierSelector,
    private val freezeAgent: FreezeAgent,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private const val VOICE_ASSISTANT_MAX_ITERATIONS = 5
        private const val SYSTEM_THINKER_MAX_ITERATIONS = 20
        private const val MAX_CONCURRENT_VOICE = 2
        private const val MAX_CONCURRENT_SYSTEM = 3
    }

    // Queue untuk masing-masing lane
    private val voiceQueue = MutableSharedFlow<BrainRequest>(extraBufferCapacity = 64)
    private val systemQueue = MutableSharedFlow<BrainRequest>(extraBufferCapacity = 64)

    // Active job tracking
    private val activeVoiceJobs = mutableMapOf<String, Job>()
    private val activeSystemJobs = mutableMapOf<String, Job>()

    private var voiceJobCount = 0
    private var systemJobCount = 0

    init {
        // Mulai consumer untuk masing-masing queue
        scope.launch {
            voiceQueue.collect { request ->
                processVoiceRequest(request)
            }
        }
        scope.launch {
            systemQueue.collect { request ->
                processSystemRequest(request)
            }
        }
    }

    /**
     * Kirim request ke VOICE_ASSISTANT lane (foreground, cepat).
     */
    suspend fun submitVoiceRequest(input: String, characterId: String? = null): Flow<AgentEvent> {
        val id = java.util.UUID.randomUUID().toString()
        val eventFlow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)

        val request = BrainRequest(
            lane = BrainLane.VOICE_ASSISTANT,
            input = input,
            characterId = characterId,
            priority = 10
        )

        // Jalankan langsung jika slot tersedia
        if (voiceJobCount < MAX_CONCURRENT_VOICE) {
            activeVoiceJobs[id] = scope.launch {
                try {
                    val agentEvents = processVoiceRequestInternal(request)
                    agentEvents.collect { event ->
                        eventFlow.emit(event)
                    }
                } finally {
                    voiceJobCount--
                    activeVoiceJobs.remove(id)
                }
            }
            voiceJobCount++
        } else {
            // Queue
            voiceQueue.emit(request)
        }

        return eventFlow
    }

    /**
     * Kirim request ke SYSTEM_THINKER lane (background, reasoning berat).
     */
    suspend fun submitSystemRequest(input: String, characterId: String? = null): Flow<AgentEvent> {
        val id = java.util.UUID.randomUUID().toString()
        val eventFlow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)

        val request = BrainRequest(
            lane = BrainLane.SYSTEM_THINKER,
            input = input,
            characterId = characterId
        )

        if (systemJobCount < MAX_CONCURRENT_SYSTEM) {
            activeSystemJobs[id] = scope.launch {
                try {
                    val agentEvents = processSystemRequestInternal(request)
                    agentEvents.collect { event ->
                        eventFlow.emit(event)
                    }
                } finally {
                    systemJobCount--
                    activeSystemJobs.remove(id)
                }
            }
            systemJobCount++
        } else {
            systemQueue.emit(request)
        }

        return eventFlow
    }

    /**
     * Proses request voice assistant.
     * Menggunakan model cepat (Groq) dan max iterations rendah.
     */
    private suspend fun processVoiceRequest(request: BrainRequest) {
        if (freezeAgent.isFrozen()) {
            // Emit error ke subscriber
            return
        }
        processVoiceRequestInternal(request).collect {} // Drain
    }

    private suspend fun processVoiceRequestInternal(request: BrainRequest): Flow<AgentEvent> {
        val card = request.characterId?.let {
            // TODO: Load character from DB
            characterOrchestrator.getActiveCharacter()
        } ?: characterOrchestrator.getActiveCharacter()

        val systemMessage = card?.let { buildVoiceAssistantSystemMessage(it) }

        // Untuk voice assistant, gunakan model cepat
        val modelClient = resolveFastModelClient(card?.modelBinding)

        val loop = AgentLoop(
            modelClient = modelClient,
            toolRegistry = characterOrchestrator.isToolAllowed("screen_read").let {
                // Voice assistant hanya perlu tool non-destruktif
                com.zonaosier.agent.impl.ToolRegistryImpl()
            },
            maxIterations = VOICE_ASSISTANT_MAX_ITERATIONS,
            systemMessage = systemMessage?.let { Message(role = "system", content = it) }
        )

        return loop.run(request.input)
    }

    /**
     * Proses request system thinker.
     * Menggunakan model besar dan max iterations tinggi.
     */
    private suspend fun processSystemRequest(request: BrainRequest) {
        if (freezeAgent.isFrozen()) return
        processSystemRequestInternal(request).collect {} // Drain
    }

    private suspend fun processSystemRequestInternal(request: BrainRequest): Flow<AgentEvent> {
        val card = request.characterId?.let {
            characterOrchestrator.getActiveCharacter()
        } ?: characterOrchestrator.getActiveCharacter()

        val agentLoop = try {
            characterOrchestrator.createAgentLoopForActive()
        } catch (e: IllegalStateException) {
            return flowOf(AgentEvent.Error(e.message ?: "Tidak ada karakter aktif"))
        }

        return agentLoop.run(request.input)
    }

    /**
     * Bangun system message untuk Voice Assistant lane.
     * Lebih ringkas dari system thinker.
     */
    private fun buildVoiceAssistantSystemMessage(card: CharacterCard): String {
        return buildString {
            appendLine(card.personaPrompt)
            appendLine()
            appendLine("[MODE VOICE ASSISTANT]")
            appendLine("Kamu merespons percakapan real-time via suara.")
            appendLine("Jawab singkat, jelas, dan langsung.")
            appendLine("Hindari jawaban panjang jika tidak diminta.")
            if (!card.exampleDialogue.isNullOrBlank()) {
                appendLine()
                appendLine("[CONTOH DIALOG]")
                appendLine(card.exampleDialogue)
            }
        }.trim()
    }

    /**
     * Resolve model cepat untuk voice assistant.
     */
    private fun resolveFastModelClient(binding: com.zonaosier.memory.entity.ModelBinding?): ModelClient {
        // Prioritas: Groq (cepat) → model lokal kecil → fallback
        return when {
            binding != null && binding.mode == ModelMode.ONLINE_GROQ -> {
                object : ModelClient {
                    override val providerName = "Groq (Voice)"
                    override val modelId = binding.providerModelId ?: "llama-3.3-70b-versatile"
                    override val isAvailable = true
                    override suspend fun chat(messages: List<Message>): ChatResponse {
                        // TODO: Implementasi GroqClient di Tahap 8
                        return ChatResponse(text = "[Groq Voice - belum diimplementasikan]")
                    }
                    override fun chatStream(messages: List<Message>) =
                        flowOf("[Groq Voice streaming - belum diimplementasikan]")
                }
            }
            else -> {
                com.zonaosier.model.client.LocalModelClient(
                    tierSelector = modelTierSelector,
                    temperature = binding?.temperature ?: 0.7f
                )
            }
        }
    }

    /**
     * Cancel semua active jobs.
     */
    fun cancelAll() {
        activeVoiceJobs.values.forEach { it.cancel() }
        activeSystemJobs.values.forEach { it.cancel() }
        activeVoiceJobs.clear()
        activeSystemJobs.clear()
        voiceJobCount = 0
        systemJobCount = 0
    }

    /**
     * Cleanup resources.
     */
    fun shutdown() {
        cancelAll()
        scope.cancel()
    }
}
