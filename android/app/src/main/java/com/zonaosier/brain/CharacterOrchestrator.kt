/**
 * ZONA-OSIER — CharacterOrchestrator (v5.0).
 * Layer Integrasi Utama yang menghubungkan karakter aktif dengan
 * seluruh subsistem: AgentLoop, VoiceRouter, ModelTierSelector, ToolRegistry.
 *
 * Alur aktivasi karakter:
 * 1. Pengguna memilih/berganti karakter dari UI (Home/Chat drawer).
 * 2. CharacterOrchestrator.activate(card) dipanggil.
 * 3. VoiceRouter menerima setPersonaTag(card.voiceTag).
 * 4. ModelTierSelector melakukan preloadForTier() jika karakter memakai model lokal.
 * 5. Semua percakapan baru menggunakan AgentLoop dengan systemMessage enriched.
 * 6. ToolRegistry di-filter berdasarkan card.toolPolicy.
 */
package com.zonaosier.brain

import com.zonaosier.agent.*
import com.zonaosier.agent.impl.AgentLoop
import com.zonaosier.agent.impl.ToolRegistryImpl
import com.zonaosier.agent.impl.filterByPolicy
import com.zonaosier.memory.dao.ConversationDao
import com.zonaosier.memory.entity.*
import com.zonaosier.model.ModelTierSelector
import com.zonaosier.model.client.LocalModelClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Event yang di-broadcast oleh CharacterOrchestrator ke UI.
 */
sealed class CharacterEvent {
    data class CharacterActivated(val card: CharacterCard) : CharacterEvent()
    data class CharacterDeactivated(val card: CharacterCard?) : CharacterEvent()
    data class ModelPreloading(val status: String) : CharacterEvent()
}

class CharacterOrchestrator(
    private val modelTierSelector: ModelTierSelector,
    private val baseToolRegistry: ToolRegistryImpl,
    private val conversationDao: ConversationDao,
    private val documentContextManager: DocumentContextManager? = null
) {
    private var activeCard: CharacterCard? = null

    /**
     * Flow event untuk UI berlangganan perubahan karakter aktif.
     */
    private val _events = MutableSharedFlow<CharacterEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<CharacterEvent> = _events

    /**
     * Aktifkan karakter.
     * Dipanggil saat user memilih karakter dari drawer atau import.
     */
    fun activate(card: CharacterCard) {
        activeCard = card

        // 1. Voice persona tag injection
        // voiceRouter.setPersonaTag(card.voiceTag)
        // (VoiceRouter akan di-inject saat integrasi voice pipeline di Tahap 5)

        // 2. Model preloading untuk tier lokal
        if (card.modelBinding.mode in setOf(
                ModelMode.LOCAL_ADHI, ModelMode.LOCAL_MADYA,
                ModelMode.LOCAL_ALIT, ModelMode.AUTO_TIER
            )
        ) {
            _events.tryEmit(CharacterEvent.ModelPreloading("Preloading model untuk ${card.modelBinding.mode}..."))
            modelTierSelector.preloadForTier(card.modelBinding.mode)
            _events.tryEmit(CharacterEvent.ModelPreloading("Model siap."))
        }

        // 3. Broadcast ke UI
        _events.tryEmit(CharacterEvent.CharacterActivated(card))
    }

    /**
     * Buat AgentLoop untuk karakter aktif.
     * AgentLoop ini sudah dilengkapi:
     * - System message enriched dari personaPrompt + exampleDialogue
     * - FilteredToolRegistry berdasarkan toolPolicy karakter
     * - ModelClient sesuai modelBinding karakter
     * - Conversation history dari Room DB (sliding window)
     */
    suspend fun createAgentLoopForActive(): AgentLoop {
        val card = activeCard
            ?: throw IllegalStateException("Tidak ada karakter aktif. Panggil activate() terlebih dahulu.")

        // 1. Filter tool registry berdasarkan kebijakan karakter
        val filteredRegistry = baseToolRegistry.filterByPolicy(card.toolPolicy)

        // 2. Resolve model client
        val modelClient = resolveModelClient(card.modelBinding)

        // 3. Bangun system message enriched dari karakter
        val systemContent = buildSystemMessage(card)

        // 4. Ambil conversation history (sliding window)
        val history = loadConversationHistory(card.id)

        // 5. Tentukan max iterations berdasarkan context strategy
        val maxIter = when (card.contextStrategy) {
            ContextStrategy.LONG_DOCUMENT, ContextStrategy.TASK -> 15
            else -> 10
        }

        return AgentLoop(
            modelClient = modelClient,
            toolRegistry = filteredRegistry,
            systemMessage = Message(role = "system", content = systemContent),
            conversationHistory = history,
            maxIterations = maxIter
        )
    }

    /**
     * Buat system message dari data karakter.
     */
    private fun buildSystemMessage(card: CharacterCard): String {
        return buildString {
            appendLine(card.personaPrompt)

            // Mode dokumen panjang
            if (card.contextStrategy == ContextStrategy.LONG_DOCUMENT) {
                appendLine()
                appendLine("[MODE DOKUMEN PANJANG]")
                appendLine("Dokumen akan disajikan dalam chunk. Jaga kontinuitas antar chunk.")
            }

            // Mode tugas
            if (card.contextStrategy == ContextStrategy.TASK) {
                appendLine()
                appendLine("[MODE TUGAS]")
                appendLine("Fokus pada penyelesaian tugas langkah demi langkah.")
            }

            // Contoh dialog (few-shot)
            if (!card.exampleDialogue.isNullOrBlank()) {
                appendLine()
                appendLine("[CONTOH DIALOG]")
                appendLine(card.exampleDialogue)
            }

            // Tool yang tersedia
            val toolNames = baseToolRegistry.filterByPolicy(card.toolPolicy).getTools().map { it.name }
            if (toolNames.isNotEmpty()) {
                appendLine()
                appendLine("[TOOL YANG TERSEDIA]")
                appendLine(toolNames.joinToString(", "))
            }
        }.trim()
    }

    /**
     * Muat riwayat percakapan dari Room DB.
     * Menggunakan sliding window berdasarkan contextStrategy.
     */
    private suspend fun loadConversationHistory(characterId: String): List<Message> {
        val maxEntries = 50 // Default sliding window
        val entries = conversationDao.getLastN(characterId, maxEntries)
        return entries.map { entry ->
            Message(
                role = entry.role,
                content = entry.content
            )
        }
    }

    /**
     * Resolve ModelClient berdasarkan ModelBinding karakter.
     */
    private fun resolveModelClient(binding: ModelBinding): ModelClient {
        // TODO: Implementasi full provider routing di Tahap 8
        // Untuk sekarang, kembalikan client berdasarkan mode
        return when (binding.mode) {
            ModelMode.LOCAL_ADHI,
            ModelMode.LOCAL_MADYA,
            ModelMode.LOCAL_ALIT,
            ModelMode.AUTO_TIER -> LocalModelClient(
                tierSelector = modelTierSelector,
                forcedTier = binding.mode.takeIf { it != ModelMode.AUTO_TIER },
                temperature = binding.temperature
            )
            // Provider online akan diimplementasikan di Tahap 8
            else -> object : ModelClient {
                override val providerName = binding.mode.name
                override val modelId = binding.providerModelId ?: "unknown"
                override val isAvailable = false
                override suspend fun chat(messages: List<Message>): ChatResponse {
                    return ChatResponse(
                        text = "Provider ${binding.mode} belum diimplementasikan (Tahap 8)."
                    )
                }
                override fun chatStream(messages: List<Message>) =
                    kotlinx.coroutines.flow.flowOf("Provider belum tersedia.")
            }
        }
    }

    /**
     * Ambil pesan pertama karakter (greeting).
     */
    fun getFirstMessage(): String? = activeCard?.firstMessage

    /**
     * Ambil karakter yang sedang aktif.
     */
    fun getActiveCharacter(): CharacterCard? = activeCard

    /**
     * Cek apakah tool tertentu diizinkan oleh karakter aktif.
     */
    fun isToolAllowed(toolName: String): Boolean {
        val card = activeCard ?: return false
        val filtered = baseToolRegistry.filterByPolicy(card.toolPolicy)
        return filtered.getTools().any { it.name == toolName }
    }

    /**
     * Deaktifkan karakter (kembali ke mode default Z.O).
     */
    fun deactivate() {
        val prev = activeCard
        activeCard = null
        // voiceRouter.setPersonaTag("default") // Tahap 5
        _events.tryEmit(CharacterEvent.CharacterDeactivated(prev))
    }

    /**
     * Cek apakah ada karakter aktif.
     */
    fun hasActiveCharacter(): Boolean = activeCard != null
}

/**
 * Manager konteks dokumen panjang.
 * Digunakan oleh CharacterOrchestrator saat contextStrategy == LONG_DOCUMENT.
 */
open class DocumentContextManager {
    /**
     * Split dokumen panjang menjadi chunk untuk processing bertahap.
     */
    fun chunkDocument(content: String, maxChunkSize: Int = 3000): List<String> {
        if (content.length <= maxChunkSize) return listOf(content)

        val chunks = mutableListOf<String>()
        var remaining = content
        while (remaining.isNotBlank()) {
            val chunk = remaining.take(maxChunkSize)
            chunks.add(chunk)
            remaining = remaining.drop(maxChunkSize)
        }
        return chunks
    }
}
