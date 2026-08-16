/**
 * ZONA-OSIER — MultiProviderLLMRouter.
 * Router pusat untuk semua LLM provider Z.O.
 *
 * Tanggung jawab:
 * 1. Membuat dan cache ModelClient per provider (lazy, singleton per mode)
 * 2. Routing berdasarkan ModelMode dari CharacterCard/ModelBinding
 * 3. Fallback chain otomatis jika provider gagal (429, 5xx, timeout)
 * 4. Integrasi dengan ProviderQuotaRouter untuk passive health-check
 * 5. Integrasi dengan BatteryThermalGovernor untuk downscale
 * 6. Estimasi token untuk quota tracking
 * 7. Expose StateFlow untuk UI (provider aktif, status quota)
 *
 * Arsitektur routing:
 * ```
 * ModelBinding.mode → resolveClient(mode) → chat(messages)
 *   ├─ Jika gagal dan ada fallback → coba fallback model di provider sama
 *   ├─ Jika gagal total → fallback chain otomatis via ProviderQuotaRouter
 *   └─ Jika semua cloud gagal → fallback ke lokal (jika tersedia)
 * ```
 */
package com.zonaosier.model

import com.zonaosier.agent.*
import com.zonaosier.governor.BatteryThermalGovernor
import com.zonaosier.governor.ProviderQuotaRouter
import com.zonaosier.memory.entity.ModelBinding
import com.zonaosier.memory.entity.ModelMode
import com.zonaosier.model.client.*
import com.zonaosier.model.client.cloud.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Event yang di-emit oleh router untuk UI monitoring.
 */
sealed class RouterEvent {
    /** Provider dipilih untuk request */
    data class ProviderSelected(val mode: ModelMode, val modelId: String) : RouterEvent()

    /** Fallback ke provider lain */
    data class FallbackTriggered(val from: ModelMode, val to: ModelMode, val reason: String) : RouterEvent()

    /** Semua provider gagal */
    data class AllProvidersExhausted(val lastError: String) : RouterEvent()

    /** Request berhasil */
    data class RequestCompleted(val mode: ModelMode, val latencyMs: Long, val tokens: Int?) : RouterEvent()
}

/**
 * Status router untuk UI display.
 */
data class RouterStatus(
    val activeProvider: String? = null,
    val activeModel: String? = null,
    val isLocalMode: Boolean = false,
    val quotaSummary: Map<String, ProviderQuotaRouter.QuotaState> = emptyMap(),
    val lastError: String? = null
)

class MultiProviderLLMRouter(
    private val quotaRouter: ProviderQuotaRouter,
    private val governor: BatteryThermalGovernor,
    private val localModelClient: LocalModelClient,
    private val tierSelector: ModelTierSelector
) {
    // ==================== Client Cache ====================

    private val clientCache = ConcurrentHashMap<ModelMode, ModelClient>()
    private val _events = MutableSharedFlow<RouterEvent>(extraBufferCapacity = 32)
    private val _status = MutableStateFlow(RouterStatus())

    /** Flow event untuk UI monitoring */
    val events: SharedFlow<RouterEvent> = _events.asSharedFlow()

    /** Flow status untuk UI display */
    val status: StateFlow<RouterStatus> = _status.asStateFlow()

    // ==================== Client Factory ====================

    /**
     * Buat atau ambil cached client untuk mode tertentu.
     * Lazy creation — client hanya dibuat saat pertama kali dipakai.
     */
    fun resolveClient(mode: ModelMode, binding: ModelBinding? = null): ModelClient? {
        // Check cache dulu
        clientCache[mode]?.let { return it }

        // Lazy create client berdasarkan mode
        val client: ModelClient? = when (mode) {
            ModelMode.LOCAL_ADHI,
            ModelMode.LOCAL_MADYA,
            ModelMode.LOCAL_ALIT,
            ModelMode.AUTO_TIER -> {
                // Gunakan shared local client, override tier jika perlu
                if (mode != ModelMode.AUTO_TIER) {
                    LocalModelClient(tierSelector, forcedTier = mode)
                } else {
                    localModelClient
                }
            }

            ModelMode.ONLINE_CLOUD -> createOpenRouterClient(binding)
            ModelMode.ONLINE_GROQ -> createGroqClient(binding)
            ModelMode.ONLINE_GOOGLE_AI -> GoogleAiStudioClient(
                modelId = binding?.providerModelId
            )
            ModelMode.ONLINE_DEEPSEEK -> DeepSeekClient(
                modelId = binding?.providerModelId,
                fallbackModelId = binding?.fallbackModelId,
                temperature = binding?.temperature ?: 0.7f
            )
            ModelMode.ONLINE_CEREBRAS -> CerebrasClient(
                modelId = binding?.providerModelId,
                temperature = binding?.temperature ?: 0.7f
            )
            ModelMode.ONLINE_SAMBA_NOVA -> SambaNovaClient(
                modelId = binding?.providerModelId,
                temperature = binding?.temperature ?: 0.7f
            )
            ModelMode.ONLINE_MISTRAL -> MistralClient(
                modelId = binding?.providerModelId,
                temperature = binding?.temperature ?: 0.7f
            )
            ModelMode.ONLINE_COHERE -> CohereClient(
                modelId = binding?.providerModelId,
                temperature = binding?.temperature ?: 0.7f
            )
            ModelMode.ONLINE_CLOUDFLARE -> CloudflareWorkersAiClient(
                modelId = binding?.providerModelId,
                temperature = binding?.temperature ?: 0.7f
            )
            ModelMode.ONLINE_HUGGINGFACE -> HuggingFaceClient(
                modelId = binding?.providerModelId,
                temperature = binding?.temperature ?: 0.7f
            )
            ModelMode.ONLINE_NVIDIA -> NvidiaNimClient(
                modelId = binding?.providerModelId,
                temperature = binding?.temperature ?: 0.7f
            )
        }

        // Cache jika valid
        if (client != null) {
            clientCache[mode] = client
        }

        return client
    }

    /**
     * Buat OpenRouter client dengan binding.
     */
    private fun createOpenRouterClient(binding: ModelBinding?): ModelClient {
        return OpenRouterCloudClient(
            modelId = binding?.providerModelId,
            fallbackModelId = binding?.fallbackModelId,
            temperature = binding?.temperature ?: 0.7f
        )
    }

    /**
     * Buat Groq client dengan binding.
     */
    private fun createGroqClient(binding: ModelBinding?): ModelClient {
        return GroqCloudClient(
            modelId = binding?.providerModelId,
            fallbackModelId = binding?.fallbackModelId,
            temperature = binding?.temperature ?: 0.7f
        )
    }

    // ==================== Routing ====================

    /**
     * Route request ke provider yang sesuai dengan fallback otomatis.
     *
     * Algoritma:
     * 1. Coba provider utama dari binding
     * 2. Jika gagal, coba fallback model di provider yang sama (jika ada)
     * 3. Jika masih gagal, jalankan fallback chain via quota router
     * 4. Jika semua cloud gagal, coba lokal
     * 5. Jika lokal juga gagal, kembalikan error
     */
    suspend fun route(
        messages: List<Message>,
        binding: ModelBinding,
        toolDefinitions: List<Map<String, Any>>? = null
    ): ChatResponse {
        // Estimasi token untuk quota check
        val estimatedTokens = estimateTokens(messages)
        val primaryMode = binding.mode

        // Update status
        _status.update { it.copy(isLocalMode = primaryMode.isLocal) }

        // === Step 1: Coba provider utama ===
        val primaryClient = resolveClient(primaryMode, binding)
        if (primaryClient != null && primaryClient.isAvailable) {
            _events.emit(RouterEvent.ProviderSelected(primaryMode, primaryClient.modelId))
            _status.update { it.copy(activeProvider = primaryClient.providerName, activeModel = primaryClient.modelId) }

            val result = primaryClient.chat(messages)
            if (!isRetryableError(result)) {
                // Berhasil atau permanent error
                recordResult(primaryMode, result, estimatedTokens)
                return result
            }

            // Primary gagal — coba fallback model di provider yang sama
            if (binding.fallbackModelId.isNotBlank()) {
                // Buat client dengan fallback model ID
                val fallbackClient = resolveClient(primaryMode, binding.copy(providerModelId = binding.fallbackModelId))
                if (fallbackClient != null && fallbackClient.isAvailable) {
                    val fallbackResult = fallbackClient.chat(messages)
                    if (!isRetryableError(fallbackResult)) {
                        recordResult(primaryMode, fallbackResult, estimatedTokens)
                        return fallbackResult
                    }
                }
            }
        }

        // === Step 2: Fallback chain otomatis (hanya untuk cloud mode) ===
        if (!primaryMode.isLocal) {
            val fallbackModes = quotaRouter.getAllProviders()
                .map { it.mode }
                .filter { it != primaryMode && it in ModelMode.CLOUD_MODES }

            for (fallbackMode in fallbackModes) {
                if (!quotaRouter.isAvailable(fallbackMode, estimatedTokens)) continue

                val fallbackClient = resolveClient(fallbackMode)
                if (fallbackClient == null || !fallbackClient.isAvailable) continue

                _events.emit(RouterEvent.FallbackTriggered(
                    from = primaryMode,
                    to = fallbackMode,
                    reason = "Primary ${primaryMode.displayName} gagal"
                ))

                val result = fallbackClient.chat(messages)
                if (!isRetryableError(result)) {
                    recordResult(fallbackMode, result, estimatedTokens)
                    _status.update {
                        it.copy(activeProvider = fallbackClient.providerName, activeModel = fallbackClient.modelId)
                    }
                    return result
                }

                quotaRouter.recordFailure(fallbackMode, 500)
            }
        }

        // === Step 3: Fallback ke lokal (jika cloud semua gagal dan model lokal tersedia) ===
        if (!primaryMode.isLocal && tierSelector.isModelLoaded()) {
            _events.emit(RouterEvent.FallbackTriggered(
                from = primaryMode,
                to = ModelMode.AUTO_TIER,
                reason = "Semua cloud provider gagal"
            ))

            val localResult = localModelClient.chat(messages)
            _status.update { it.copy(activeProvider = "Local", activeModel = localModelClient.modelId, isLocalMode = true) }
            return localResult
        }

        // === Step 4: Semua gagal ===
        val errorMsg = if (primaryClient?.isAvailable == true) {
            "Semua provider gagal. Coba lagi nanti atau periksa koneksi internet."
        } else {
            "Provider ${primaryMode.displayName} tidak tersedia. Periksa API key di Settings."
        }

        _events.emit(RouterEvent.AllProvidersExhausted(errorMsg))
        _status.update { it.copy(lastError = errorMsg) }

        return ChatResponse(text = errorMsg)
    }

    /**
     * Route streaming — tidak mendukung fallback otomatis.
     * Streaming ke satu provider saja. Jika gagal, kembalikan error.
     */
    fun routeStream(
        messages: List<Message>,
        binding: ModelBinding
    ): Flow<String> {
        val client = resolveClient(binding.mode, binding)
        if (client == null || !client.isAvailable) {
            return kotlinx.coroutines.flow.flow {
                emit("Provider ${binding.mode.displayName} tidak tersedia.")
            }
        }
        _status.update { it.copy(activeProvider = client.providerName, activeModel = client.modelId, isLocalMode = binding.mode.isLocal) }
        return client.chatStream(messages)
    }

    // ==================== Helpers ====================

    /**
     * Cek apakah error bisa di-retry (429, 5xx, timeout).
     * Permanent error (401, 402, 403, parse error) tidak di-retry.
     */
    private fun isRetryableError(response: ChatResponse): Boolean {
        val text = response.text ?: return false
        return when {
            text.contains("rate limit", ignoreCase = true) -> true
            text.contains("429", ignoreCase = true) -> true
            text.contains("server error", ignoreCase = true) -> true
            text.contains("HTTP 5", ignoreCase = true) -> true
            text.contains("timeout", ignoreCase = true) -> true
            text.contains("Connection reset", ignoreCase = true) -> true
            // Permanent errors — jangan retry
            text.contains("API key invalid", ignoreCase = true) -> false
            text.contains("kredit habis", ignoreCase = true) -> false
            text.contains("401", ignoreCase = true) -> false
            text.contains("402", ignoreCase = true) -> false
            text.contains("403", ignoreCase = true) -> false
            else -> false
        }
    }

    /**
     * Record hasil request ke quota router untuk passive health-check.
     */
    private fun recordResult(mode: ModelMode, response: ChatResponse, estimatedTokens: Int) {
        val tokensUsed = response.usage?.totalTokens ?: estimatedTokens
        if (response.text != null && !isRetryableError(response)) {
            quotaRouter.recordSuccess(mode, tokensUsed)
            _events.emit(RouterEvent.RequestCompleted(mode, response.latencyMs, tokensUsed))
            _status.update { it.copy(lastError = null) }
        } else {
            quotaRouter.recordFailure(mode, 500)
            _status.update { it.copy(lastError = response.text) }
        }
    }

    /**
     * Estimasi jumlah token dari daftar pesan.
     * Rule of thumb: ~1 token per 4 karakter (untuk bahasa Inggris),
     * ~1 token per 2-3 karakter (untuk Bahasa Indonesia/CJK).
     * Kita gunakan rata-rata konservatif: 1 token per 3 karakter.
     */
    private fun estimateTokens(messages: List<Message>): Int {
        val totalChars = messages.sumOf { it.content.length }
        return (totalChars / 3).coerceAtLeast(100)
    }

    /**
     * Pre-warm client cache untuk provider yang punya API key.
     * Dipanggil saat startup agar client sudah siap saat pertama request.
     */
    fun prewarmClients() {
        for (mode in ModelMode.entries) {
            val client = resolveClient(mode)
            if (client?.isAvailable == true) {
                // Client sudah di-cache dan siap
            }
        }
        refreshStatus()
    }

    /**
     * Refresh status quota dari quota router.
     */
    fun refreshStatus() {
        val quotaMap = quotaRouter.getAllQuotaStatus().mapKeys { it.key.displayName }
        _status.update { it.copy(quotaSummary = quotaMap) }
    }

    /**
     * Invalidate cache — dipanggil saat API key berubah di Settings.
     */
    fun invalidateCache() {
        clientCache.clear()
        _status.update { RouterStatus() }
    }

    /**
     * Cek apakah ada provider yang tersedia (minimal satu).
     */
    fun hasAnyProviderAvailable(): Boolean {
        return ModelMode.entries.any { mode ->
            val client = resolveClient(mode)
            client?.isAvailable == true && if (!mode.isLocal) quotaRouter.isAvailable(mode) else true
        }
    }

    /**
     * Daftar provider yang tersedia beserta statusnya.
     */
    fun getAvailableProviders(): List<ProviderStatus> {
        return ModelMode.entries.mapNotNull { mode ->
            val client = resolveClient(mode) ?: return@mapNotNull null
            val available = client.isAvailable && if (!mode.isLocal) quotaRouter.isAvailable(mode) else true
            ProviderStatus(
                mode = mode,
                providerName = client.providerName,
                modelId = client.modelId,
                isAvailable = available,
                isLocal = mode.isLocal,
                quotaState = if (!mode.isLocal) quotaRouter.getAllQuotaStatus()[mode] else null
            )
        }
    }
}

/**
 * Status satu provider untuk UI display.
 */
data class ProviderStatus(
    val mode: ModelMode,
    val providerName: String,
    val modelId: String,
    val isAvailable: Boolean,
    val isLocal: Boolean,
    val quotaState: ProviderQuotaRouter.QuotaState?
)
