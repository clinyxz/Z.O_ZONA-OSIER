/**
 * ZONA-OSIER — ModelRegistry.
 * Katalog pusat semua model yang tersedia (lokal + cloud).
 *
 * Menyediakan:
 * - Daftar model per provider dengan metadata
 * - Pencarian model berdasarkan nama/provider
 * - Rekomendasi model berdasarkan kebutuhan (speed, quality, cost)
 * - StateFlow untuk UI binding
 *
 * Data diambil dari catalog statis + data dinamis (model lokal terunduh).
 */
package com.zonaosier.model

import com.zonaosier.memory.entity.ModelMode
import kotlinx.coroutines.flow.*

/**
 * Metadata satu model.
 */
data class ModelEntry(
    val id: String,                    // Unique ID
    val displayName: String,           // Nama display
    val providerName: String,          // Nama provider
    val mode: ModelMode,               // Mode/model
    val description: String,           // Deskripsi
    val contextWindow: Int,            // Context window (token)
    val maxOutputTokens: Int,          // Max output token
    val speed: SpeedCategory,          // Kategori kecepatan
    val quality: QualityCategory,      // Kategori kualitas
    val costCategory: CostCategory,    // Kategori biaya
    val isAvailable: Boolean = false,  // Apakah tersedia (API key / terunduh)
    val isDefault: Boolean = false,    // Apakah default untuk provider ini
    val requiresApiKey: Boolean = true, // Butuh API key?
    val specialNotes: String? = null   // Catatan khusus
) {
    enum class SpeedCategory { VERY_FAST, FAST, MEDIUM, SLOW }
    enum class QualityCategory { HIGH, MEDIUM, LOW }
    enum class CostCategory { FREE, TRIAL, PAID, LOCAL }
}

class ModelRegistry(
    private val downloadManager: ModelDownloadManager
) {
    private val _entries = MutableStateFlow<List<ModelEntry>>(emptyList())
    val entries: StateFlow<List<ModelEntry>> = _entries.asStateFlow()

    init {
        refresh()
    }

    /**
     * Refresh daftar model — gabung catalog statis + model lokal terunduh.
     */
    fun refresh() {
        val cloudModels = CATALOG_CLOUD_MODELS
        val localModels = buildLocalEntries()
        _entries.value = cloudModels + localModels
    }

    /**
     * Cari model berdasarkan ID.
     */
    fun findModel(modelId: String): ModelEntry? {
        return _entries.value.find { it.id == modelId }
    }

    /**
     * Daftar model untuk provider tertentu.
     */
    fun getModelsForProvider(mode: ModelMode): List<ModelEntry> {
        return _entries.value.filter { it.mode == mode }
    }

    /**
     * Daftar model yang tersedia.
     */
    fun getAvailableModels(): List<ModelEntry> {
        return _entries.value.filter { it.isAvailable }
    }

    /**
     * Daftar model lokal yang terunduh.
     */
    fun getDownloadedLocalModels(): List<ModelEntry> {
        return _entries.value.filter { it.mode.isLocal && it.isAvailable }
    }

    /**
     * Rekomendasi model untuk use case tertentu.
     */
    fun recommendFor(useCase: UseCase): List<ModelEntry> {
        return when (useCase) {
            UseCase.VOICE_REALTIME -> _entries.value.filter {
                it.speed == ModelEntry.SpeedCategory.VERY_FAST &&
                (it.mode == ModelMode.ONLINE_GROQ || it.mode == ModelMode.ONLINE_CEREBRAS)
            }.sortedBy { it.costCategory }

            UseCase.VOICE_EXPRESSIVE -> _entries.value.filter {
                it.quality == ModelEntry.QualityCategory.HIGH && !it.mode.isLocal
            }.sortedBy { it.costCategory }

            UseCase.CHAT_QUALITY -> _entries.value.filter {
                it.quality == ModelEntry.QualityCategory.HIGH
            }.sortedBy { it.costCategory }

            UseCase.CHAT_FAST -> _entries.value.filter {
                it.speed in listOf(ModelEntry.SpeedCategory.VERY_FAST, ModelEntry.SpeedCategory.FAST)
            }.sortedBy { it.costCategory }

            UseCase.OFFLINE_PRIVATE -> _entries.value.filter {
                it.mode.isLocal && it.isAvailable
            }

            UseCase.SYSTEM_THINKER -> _entries.value.filter {
                it.quality == ModelEntry.QualityCategory.HIGH &&
                it.contextWindow >= 8000 &&
                !it.mode.isLocal
            }

            UseCase.CODING_ASSISTANT -> _entries.value.filter {
                it.quality == ModelEntry.QualityCategory.HIGH
            }.sortedBy { it.costCategory }

            UseCase.BUDGET_FREE -> _entries.value.filter {
                it.costCategory == ModelEntry.CostCategory.FREE && it.isAvailable
            }
        }
    }

    // ==================== Local Model Entries ====================

    private fun buildLocalEntries(): List<ModelEntry> {
        return downloadManager.getDownloadedModels().map { local ->
            val tierInfo = when (local.tier) {
                ModelMode.LOCAL_ADHI -> Triple(
                    "Adhi (Lokal)",
                    8192,
                    ModelEntry.SpeedCategory.SLOW
                )
                ModelMode.LOCAL_MADYA -> Triple(
                    "Madya (Lokal)",
                    4096,
                    ModelEntry.SpeedCategory.MEDIUM
                )
                ModelMode.LOCAL_ALIT -> Triple(
                    "Alit (Lokal)",
                    2048,
                    ModelEntry.SpeedCategory.FAST
                )
                else -> Triple("Lokal", 4096, ModelEntry.SpeedCategory.MEDIUM)
            }

            ModelEntry(
                id = local.id,
                displayName = local.displayName,
                providerName = tierInfo.first,
                mode = local.tier,
                description = "Model lokal ${local.fileSizeBytes / 1_048_576}MB",
                contextWindow = tierInfo.second,
                maxOutputTokens = 2048,
                speed = tierInfo.third,
                quality = ModelEntry.QualityCategory.MEDIUM,
                costCategory = ModelEntry.CostCategory.LOCAL,
                isAvailable = true,
                isDefault = true,
                requiresApiKey = false,
                specialNotes = "Jalur ${local.tier.displayName}. File: ${local.filePath}"
            )
        }
    }

    enum class UseCase {
        VOICE_REALTIME,     // Butuh TTFA <800ms (Groq, Cerebras)
        VOICE_EXPRESSIVE,   // Butuh kualitas tinggi + streaming
        CHAT_QUALITY,       // Prioritas kualitas jawaban
        CHAT_FAST,          // Prioritas kecepatan
        OFFLINE_PRIVATE,    // Harus lokal, privasi penuh
        SYSTEM_THINKER,     // Butuh konteks panjang, tool calling
        CODING_ASSISTANT,   // Butuh kualitas tinggi untuk kode
        BUDGET_FREE         // Gratis saja
    }

    companion object {
        /**
         * Catalog model cloud — statis.
         * Availability di-update runtime berdasarkan API key.
         */
        val CATALOG_CLOUD_MODELS: List<ModelEntry> = listOf(
            // === OpenRouter ===
            ModelEntry(
                id = "openrouter_llama3_3_70b",
                displayName = "Llama 3.3 70B Instruct",
                providerName = "OpenRouter",
                mode = ModelMode.ONLINE_CLOUD,
                description = "Model besar, kualitas tinggi. BYOK routing gratis.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.FREE,
                isDefault = true,
                specialNotes = "1M request/bulan routing gratis. Biaya inference tetap dikenakan provider."
            ),
            ModelEntry(
                id = "openrouter_llama3_1_8b_free",
                displayName = "Llama 3.1 8B Instruct (Free)",
                providerName = "OpenRouter",
                mode = ModelMode.ONLINE_CLOUD,
                description = "Model kecil, sepenuhnya gratis.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.VERY_FAST,
                quality = ModelEntry.QualityCategory.MEDIUM,
                costCategory = ModelEntry.CostCategory.FREE,
                specialNotes = "Model free di OpenRouter. Mungkin queue saat high load."
            ),

            // === Groq ===
            ModelEntry(
                id = "groq_llama3_3_70b",
                displayName = "Llama 3.3 70B Versatile",
                providerName = "Groq",
                mode = ModelMode.ONLINE_GROQ,
                description = "LPU inference 300-800 tok/s. TTFA sangat cepat.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.VERY_FAST,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.FREE,
                isDefault = true,
                specialNotes = "TPM 6K-30K. Ideal untuk Voice Assistant real-time."
            ),
            ModelEntry(
                id = "groq_llama3_1_8b",
                displayName = "Llama 3.1 8B Instant",
                providerName = "Groq",
                mode = ModelMode.ONLINE_GROQ,
                description = "Model kecil di LPU, ultra-cepat.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.VERY_FAST,
                quality = ModelEntry.QualityCategory.MEDIUM,
                costCategory = ModelEntry.CostCategory.FREE,
                specialNotes = "TPM lebih tinggi karena model kecil."
            ),

            // === Google AI Studio ===
            ModelEntry(
                id = "google_gemini2_flash",
                displayName = "Gemini 2.0 Flash",
                providerName = "Google AI Studio",
                mode = ModelMode.ONLINE_GOOGLE_AI,
                description = "Model Google cepat, 1M context window.",
                contextWindow = 1_000_000,
                maxOutputTokens = 8192,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.FREE,
                isDefault = true,
                specialNotes = "250 RPD (free tier). Passive health-check wajib."
            ),
            ModelEntry(
                id = "google_gemini1_5_flash",
                displayName = "Gemini 1.5 Flash",
                providerName = "Google AI Studio",
                mode = ModelMode.ONLINE_GOOGLE_AI,
                description = "Model Google stabil, 1M context window.",
                contextWindow = 1_000_000,
                maxOutputTokens = 8192,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.MEDIUM,
                costCategory = ModelEntry.CostCategory.FREE,
                specialNotes = "Fallback Gemini 2.0 Flash."
            ),

            // === DeepSeek (Trial) ===
            ModelEntry(
                id = "deepseek_chat",
                displayName = "DeepSeek Chat",
                providerName = "DeepSeek",
                mode = ModelMode.ONLINE_DEEPSEEK,
                description = "Model Cina, kualitas tinggi, murah.",
                contextWindow = 64_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.TRIAL,
                isDefault = true,
                specialNotes = "Trial: 5 juta token / 30 hari. Expiry otomatis."
            ),
            ModelEntry(
                id = "deepseek_reasoner",
                displayName = "DeepSeek Reasoner (R1)",
                providerName = "DeepSeek",
                mode = ModelMode.ONLINE_DEEPSEEK,
                description = "Reasoning model, chain-of-thought.",
                contextWindow = 64_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.SLOW,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.TRIAL,
                specialNotes = "Lebih lambat tapi lebih akurat untuk reasoning."
            ),

            // === Cerebras (Trial) ===
            ModelEntry(
                id = "cerebras_llama3_3_70b",
                displayName = "Llama 3.3 70B (Cerebras LPU)",
                providerName = "Cerebras",
                mode = ModelMode.ONLINE_CEREBRAS,
                description = "LPU inference ultra-cepat (~3000+ tok/s output).",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.VERY_FAST,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.TRIAL,
                isDefault = true,
                specialNotes = "Trial: $5 credit / 30 hari. RPM ~5, sangat ketat!"
            ),

            // === SambaNova (Trial) ===
            ModelEntry(
                id = "sambanova_llama3_3_70b",
                displayName = "Meta Llama 3.3 70B (SambaNova)",
                providerName = "SambaNova",
                mode = ModelMode.ONLINE_SAMBA_NOVA,
                description = "Llama 3.3 70B via SambaNova inference.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.TRIAL,
                isDefault = true,
                specialNotes = "Trial: $5 credit / 30 hari."
            ),

            // === Mistral ===
            ModelEntry(
                id = "mistral_small",
                displayName = "Mistral Small 3.1",
                providerName = "Mistral",
                mode = ModelMode.ONLINE_MISTRAL,
                description = "Model kecil-cepat dari Mistral AI.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.MEDIUM,
                costCategory = ModelEntry.CostCategory.PAID,
                isDefault = true
            ),
            ModelEntry(
                id = "mistral_devstral",
                displayName = "Devstral (Coding)",
                providerName = "Mistral",
                mode = ModelMode.ONLINE_MISTRAL,
                description = "Model khusus coding dari Mistral.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.PAID,
                specialNotes = "Optimal untuk code generation dan review."
            ),

            // === Cohere ===
            ModelEntry(
                id = "cohere_command_r_plus",
                displayName = "Command R+",
                providerName = "Cohere",
                mode = ModelMode.ONLINE_COHERE,
                description = "Model RAG-native, 128K context.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.MEDIUM,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.PAID,
                isDefault = true,
                specialNotes = "RAG-native. Cocok untuk percakapan dengan memori panjang."
            ),

            // === Cloudflare Workers AI ===
            ModelEntry(
                id = "cf_llama3_3_70b",
                displayName = "Llama 3.3 70B (Cloudflare Edge)",
                providerName = "Cloudflare Workers AI",
                mode = ModelMode.ONLINE_CLOUDFLARE,
                description = "Model di edge Cloudflare, latensi rendah Asia.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.MEDIUM,
                costCategory = ModelEntry.CostCategory.PAID,
                isDefault = true,
                specialNotes = "Butuh Account ID. Inference di edge CDN."
            ),

            // === HuggingFace ===
            ModelEntry(
                id = "hf_llama3_3_70b",
                displayName = "Llama 3.3 70B (HuggingFace)",
                providerName = "HuggingFace",
                mode = ModelMode.ONLINE_HUGGINGFACE,
                description = "Model dari HuggingFace Inference API.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.MEDIUM,
                quality = ModelEntry.QualityCategory.MEDIUM,
                costCategory = ModelEntry.CostCategory.FREE,
                isDefault = true,
                specialNotes = "Free tier: 1-5 RPM. Passive health-check wajib."
            ),

            // === NVIDIA NIM ===
            ModelEntry(
                id = "nvidia_llama3_3_70b",
                displayName = "Llama 3.3 70B (NVIDIA NIM)",
                providerName = "NVIDIA NIM",
                mode = ModelMode.ONLINE_NVIDIA,
                description = "GPU-optimized inference via NVIDIA.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.PAID,
                isDefault = true,
                specialNotes = "Self-hosted atau cloud NIM."
            ),

            // === Anthropic (via OpenRouter BYOK) ===
            ModelEntry(
                id = "anthropic_claude_sonnet_4",
                displayName = "Claude Sonnet 4",
                providerName = "Anthropic",
                mode = ModelMode.ONLINE_CLOUD, // Via OpenRouter BYOK
                description = "Model Claude 4 terbaru dari Anthropic.",
                contextWindow = 200_000,
                maxOutputTokens = 8192,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.HIGH,
                costCategory = ModelEntry.CostCategory.PAID,
                specialNotes = "Gunakan via OpenRouter BYOK. Biaya inference ke Anthropic."
            ),
            ModelEntry(
                id = "anthropic_claude_haiku_4",
                displayName = "Claude Haiku 4",
                providerName = "Anthropic",
                mode = ModelMode.ONLINE_CLOUD,
                description = "Model Claude cepat dan murah.",
                contextWindow = 200_000,
                maxOutputTokens = 8192,
                speed = ModelEntry.SpeedCategory.VERY_FAST,
                quality = ModelEntry.QualityCategory.MEDIUM,
                costCategory = ModelEntry.CostCategory.PAID,
                specialNotes = "Gunakan via OpenRouter BYOK."
            ),

            // === Novita AI ===
            ModelEntry(
                id = "novita_llama3_3_70b",
                displayName = "Llama 3.3 70B (Novita)",
                providerName = "Novita AI",
                mode = ModelMode.ONLINE_NOVITA,
                description = "Pay-per-request, berbagai model tersedia.",
                contextWindow = 128_000,
                maxOutputTokens = 4096,
                speed = ModelEntry.SpeedCategory.FAST,
                quality = ModelEntry.QualityCategory.MEDIUM,
                costCategory = ModelEntry.CostCategory.PAID,
                isDefault = true
            )
        )
    }
}
