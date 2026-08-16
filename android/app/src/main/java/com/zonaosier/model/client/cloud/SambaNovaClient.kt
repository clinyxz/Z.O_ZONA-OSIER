/**
 * ZONA-OSIER — SambaNovaClient.
 * Klien LLM untuk SambaNova AI API.
 *
 * SambaNova menggunakan format OpenAI-compatible.
 * Model utama: Meta-Llama-3.3-70B-Instruct.
 *
 * ⚠️ TRIAL: $5 credit / 30 hari. Wajib track expiry.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.BuildConfig

class SambaNovaClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.SAMBANOVA_API_KEY.ifBlank { null }
) : BaseOpenAiCompatibleClient(
    modelId = modelId,
    fallbackModelId = fallbackModelId,
    temperature = temperature,
    config = ProviderEndpointConfig(
        baseUrl = "https://api.sambanova.ai/v1",
        defaultModel = "Meta-Llama-3.3-70B-Instruct",
        timeoutMs = 45_000,
        streamTimeoutMs = 90_000
    ),
    apiKey = apiKey
) {
    override val providerName: String = "SambaNova"
}