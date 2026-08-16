/**
 * ZONA-OSIER — DeepSeekClient.
 * Klien LLM untuk DeepSeek API.
 *
 * DeepSeek menggunakan format OpenAI-compatible (/chat/completions).
 * Model utama: deepseek-chat, deepseek-reasoner.
 *
 * ⚠️ TRIAL: 5 juta token / 30 hari. Wajib track expiry di ProviderQuotaRouter.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.BuildConfig

class DeepSeekClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.DEEPSEEK_API_KEY.ifBlank { null }
) : BaseOpenAiCompatibleClient(
    modelId = modelId,
    fallbackModelId = fallbackModelId,
    temperature = temperature,
    config = ProviderEndpointConfig(
        baseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        timeoutMs = 45_000,
        streamTimeoutMs = 90_000
    ),
    apiKey = apiKey
) {
    override val providerName: String = "DeepSeek"
}
