/**
 * ZONA-OSIER — LocalModelClient.
 * Klien LLM untuk inferensi model lokal (Adhi/Madya/Alit).
 *
 * Menggunakan llama.cpp atau MLC-LLM sebagai backend.
 * Konfigurasi CPU I8MM sebagai default produksi.
 * OpenCL GPU hanya sebagai fallback eksperimental.
 */
package com.zonaosier.model.client

import com.zonaosier.agent.*
import com.zonaosier.memory.entity.ModelMode
import com.zonaosier.model.ModelTierSelector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Konfigurasi untuk local inference.
 */
data class LocalInferenceConfig(
    val contextSize: Int = 4096,
    val batchSize: Int = 512,
    val threads: Int = 4,
    val gpuLayers: Int = 0 // 0 = CPU only (I8MM default)
)

class LocalModelClient(
    private val tierSelector: ModelTierSelector,
    private val forcedTier: ModelMode? = null,
    private val temperature: Float = 0.7f,
    private val config: LocalInferenceConfig = LocalInferenceConfig()
) : ModelClient {

    override val providerName: String = "Local"
    override val modelId: String
    override val isAvailable: Boolean
        get() = tierSelector.isModelLoaded()

    init {
        val tier = forcedTier?.let { tierSelector.resolveTier(it) }
            ?: tierSelector.selectAutoTier()
        modelId = tierSelector.getTierInfo(tier).filePath
    }

    override suspend fun chat(messages: List<Message>): ChatResponse {
        val modelPath = tierSelector.getLoadedModelPath()
        if (modelPath == null) {
            return ChatResponse(
                text = "Model lokal belum di-load. Unduh model terlebih dahulu di Settings."
            )
        }

        val startTime = System.currentTimeMillis()

        // TODO: Implementasi inferensi via llama.cpp / MLC-LLM
        // Untuk sekarang, kembalikan placeholder
        val prompt = messages.joinToString("\n") { "${it.role}: ${it.content}" }
        val responseText = "[Local model inference — belum diimplementasikan. Model: $modelPath]"

        return ChatResponse(
            text = responseText,
            latencyMs = System.currentTimeMillis() - startTime
        )
    }

    override fun chatStream(messages: List<Message>): Flow<String> = flow {
        val modelPath = tierSelector.getLoadedModelPath()
        if (modelPath == null) {
            emit("Model lokal belum di-load.")
            return@flow
        }

        // TODO: Implementasi streaming via llama.cpp
        emit("[Local model streaming — belum diimplementasikan]")
    }
}
