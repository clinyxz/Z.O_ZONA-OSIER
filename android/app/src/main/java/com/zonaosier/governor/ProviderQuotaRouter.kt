/**
 * ZONA-OSIER — ProviderQuotaRouter.
 * Melacak quota provider dan melakukan routing otomatis.
 *
 * Fallback chain (v5.1.2):
 * 1. OpenRouter (BYOK, routing gratis)
 * 2. Groq (300-800 tok/s, TPM 6K-30K)
 * 3. Google AI Studio (Preview, 250 RPD)
 * 4. SambaNova (trial, $5 credit, 30 hari)
 * 5. Novita AI
 *
 * Trial providers dengan timer expiry:
 * - DeepSeek: 30 hari (5 juta token)
 * - Cerebras: 30 hari ($5 credit)
 * - SambaNova: 30 hari ($5 credit)
 *
 * ⚠️ GitHub Models dihapus — retired 30 Juli 2026.
 * ⚠️ Gunakan passive health-check (pantau respons request aktual user).
 */
package com.zonaosier.governor

import com.zonaosier.memory.entity.ModelMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Status quota provider.
 */
data class QuotaState(
    val remainingRequests: Int = -1,       // -1 = unknown
    val remainingTokens: Int = -1,         // -1 = unknown
    val remainingTokensPerMinute: Int = -1, // TPM, -1 = unknown
    val resetTime: Long = 0,               // Timestamp reset
    val expiryTime: Long? = null           // Timer expiry untuk trial
) {
    /**
     * Apakah provider ini sudah expired (trial)?
     */
    fun isExpired(): Boolean {
        if (expiryTime == null) return false
        return System.currentTimeMillis() > expiryTime
    }

    /**
     * Apakah provider ini masih punya quota?
     */
    fun hasQuota(estimatedPromptTokens: Int = 1000): Boolean {
        if (isExpired()) return false
        if (remainingRequests in 1..4) return false // < 5 request tersisa
        if (remainingTokens in 1..999) return false // < 1000 token tersisa
        if (remainingTokensPerMinute > 0 && estimatedPromptTokens * 3 > remainingTokensPerMinute) return false
        return true
    }

    /**
     * Hitung effective throughput berdasarkan TPM.
     */
    fun calculateEffectiveThroughput(promptTokens: Int): Int {
        if (remainingTokensPerMinute <= 0) return remainingRequests
        val maxByTpm = remainingTokensPerMinute / (promptTokens * 2) // *2 untuk input+output
        return minOf(remainingRequests, maxByTpm)
    }
}

/**
 * Konfigurasi provider untuk routing.
 */
data class ProviderConfig(
    val mode: ModelMode,
    val displayName: String,
    val priority: Int,                // Lower = higher priority
    val isTrial: Boolean = false,     // Apakah trial dengan expiry?
    val trialDurationDays: Int? = null,
    val maxRpm: Int = -1,             // -1 = unknown
    val maxTpm: Int = -1
)

class ProviderQuotaRouter {

    private val quotaStates = ConcurrentHashMap<ModelMode, QuotaState>()
    private val providerConfigs = ConcurrentHashMap<ModelMode, ProviderConfig>()

    init {
        registerDefaultProviders()
    }

    /**
     * Daftar provider default dan konfigurasi mereka.
     */
    private fun registerDefaultProviders() {
        // Fallback chain (prioritas)
        val providers = listOf(
            ProviderConfig(
                mode = ModelMode.ONLINE_CLOUD,
                displayName = "OpenRouter",
                priority = 1,
                maxTpm = 200_000
            ),
            ProviderConfig(
                mode = ModelMode.ONLINE_GROQ,
                displayName = "Groq",
                priority = 2,
                maxTpm = 30_000
            ),
            ProviderConfig(
                mode = ModelMode.ONLINE_GOOGLE_AI,
                displayName = "Google AI Studio",
                priority = 3,
                maxRpm = 250
            ),
            ProviderConfig(
                mode = ModelMode.ONLINE_SAMBA_NOVA,
                displayName = "SambaNova",
                priority = 4,
                isTrial = true,
                trialDurationDays = 30
            ),
            ProviderConfig(
                mode = ModelMode.ONLINE_NOVITA,
                displayName = "Novita AI",
                priority = 5
            ),
            // Trial providers
            ProviderConfig(
                mode = ModelMode.ONLINE_DEEPSEEK,
                displayName = "DeepSeek (Trial)",
                priority = 10,
                isTrial = true,
                trialDurationDays = 30
            ),
            ProviderConfig(
                mode = ModelMode.ONLINE_CEREBRAS,
                displayName = "Cerebras (Trial)",
                priority = 11,
                isTrial = true,
                trialDurationDays = 30
            ),
            ProviderConfig(
                mode = ModelMode.ONLINE_MISTRAL,
                displayName = "Mistral",
                priority = 6
            ),
            ProviderConfig(
                mode = ModelMode.ONLINE_COHERE,
                displayName = "Cohere",
                priority = 7
            ),
            ProviderConfig(
                mode = ModelMode.ONLINE_CLOUDFLARE,
                displayName = "Cloudflare Workers AI",
                priority = 8
            ),
            ProviderConfig(
                mode = ModelMode.ONLINE_HUGGINGFACE,
                displayName = "HuggingFace",
                priority = 9
            ),
            ProviderConfig(
                mode = ModelMode.ONLINE_NVIDIA,
                displayName = "NVIDIA NIM",
                priority = 12
            )
        )

        providers.forEach { config ->
            providerConfigs[config.mode] = config
            quotaStates[config.mode] = QuotaState()
        }
    }

    /**
     * Pilih provider terbaik untuk request saat ini.
     * @param estimatedPromptTokens Estimasi token input.
     * @return ModelMode provider terbaik, atau null jika semua tidak tersedia.
     */
    fun selectBestProvider(estimatedPromptTokens: Int = 1000): ModelMode? {
        return providerConfigs.values
            .filter { it.mode in ModelMode.CLOUD_MODES }
            .sortedBy { it.priority }
            .firstOrNull { config ->
                val quota = quotaStates[config.mode] ?: QuotaState()
                quota.hasQuota(estimatedPromptTokens)
            }?.mode
    }

    /**
     * Pilih provider terbaik dari subset tertentu.
     */
    fun selectFrom(modes: List<ModelMode>, estimatedPromptTokens: Int = 1000): ModelMode? {
        return modes.mapNotNull { mode ->
            val config = providerConfigs[mode] ?: return@mapNotNull null
            val quota = quotaStates[mode] ?: QuotaState()
            if (quota.hasQuota(estimatedPromptTokens)) mode else null
        }.minByOrNull { (providerConfigs[it]?.priority ?: Int.MAX_VALUE) }
    }

    /**
     * Update quota state dari response HTTP.
     * Passive health-check: memperbarui state dari respons request aktual.
     */
    fun updateFromResponse(mode: ModelMode, headers: Map<String, String>) {
        val current = quotaStates[mode] ?: QuotaState()
        val updated = current.copy(
            remainingRequests = headers["x-ratelimit-remaining-requests"]?.toIntOrNull()
                ?: current.remainingRequests,
            remainingTokens = headers["x-ratelimit-remaining-tokens"]?.toIntOrNull()
                ?: current.remainingTokens,
            remainingTokensPerMinute = headers["x-ratelimit-remaining-tokens-per-minute"]?.toIntOrNull()
                ?: current.remainingTokensPerMinute,
            resetTime = headers["x-ratelimit-reset"]?.toLongOrNull()
                ?: current.resetTime
        )
        quotaStates[mode] = updated
    }

    /**
     * Set trial expiry untuk provider.
     * Dipanggil saat user memasukkan API key trial.
     */
    fun setTrialExpiry(mode: ModelMode, activationTimestamp: Long) {
        val config = providerConfigs[mode]
        if (config?.isTrial == true && config.trialDurationDays != null) {
            val expiryMs = activationTimestamp + (config.trialDurationDays * 24 * 60 * 60 * 1000L)
            val current = quotaStates[mode] ?: QuotaState()
            quotaStates[mode] = current.copy(expiryTime = expiryMs)
        }
    }

    /**
     * Record request berhasil (passive tracking).
     */
    fun recordSuccess(mode: ModelMode, tokensUsed: Int) {
        val current = quotaStates[mode] ?: return
        quotaStates[mode] = current.copy(
            remainingRequests = if (current.remainingRequests > 0) current.remainingRequests - 1 else -1,
            remainingTokens = if (current.remainingTokens > 0) current.remainingTokens - tokensUsed else -1
        )
    }

    /**
     * Record request gagal.
     */
    fun recordFailure(mode: ModelMode, statusCode: Int) {
        when (statusCode) {
            429 -> {
                // Rate limited
                val current = quotaStates[mode] ?: QuotaState()
                quotaStates[mode] = current.copy(
                    remainingRequests = 0,
                    resetTime = System.currentTimeMillis() + 60_000 // Reset 1 menit
                )
            }
            402, 403 -> {
                // Payment required / Forbidden → kemungkinan trial expired
                val current = quotaStates[mode] ?: QuotaState()
                quotaStates[mode] = current.copy(
                    remainingRequests = 0,
                    remainingTokens = 0,
                    expiryTime = 0 // Expired
                )
            }
        }
    }

    /**
     * Ambil status quota semua provider.
     */
    fun getAllQuotaStatus(): Map<ModelMode, QuotaState> {
        return quotaStates.toMap()
    }

    /**
     * Ambil konfigurasi provider.
     */
    fun getProviderConfig(mode: ModelMode): ProviderConfig? = providerConfigs[mode]

    /**
     * Daftar semua provider yang terdaftar.
     */
    fun getAllProviders(): List<ProviderConfig> =
        providerConfigs.values.sortedBy { it.priority }

    /**
     * Cek apakah provider tertentu available (punya quota dan belum expired).
     */
    fun isAvailable(mode: ModelMode, estimatedTokens: Int = 1000): Boolean {
        val quota = quotaStates[mode] ?: return false
        return quota.hasQuota(estimatedTokens) && !quota.isExpired()
    }
}
