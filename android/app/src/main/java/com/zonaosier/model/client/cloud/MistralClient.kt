/**
 * ZONA-OSIER — MistralClient.
 * Klien LLM untuk Mistral AI API.
 *
 * Mistral menggunakan format OpenAI-compatible (/v1/chat/completions).
 * Model utama: mistral-small-latest (cepat, murah), mistral-large-latest (kapable).
 * Juga mendukung Devstral untuk coding.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.BuildConfig

class MistralClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.MISTRAL_API_KEY.ifBlank { null }
) : BaseOpenAiCompatibleClient(
    modelId = modelId,
    fallbackModelId = fallbackModelId,
    temperature = temperature,
    config = ProviderEndpointConfig(
        baseUrl = "https://api.mistral.ai/v1",
        defaultModel = "mistral-small-latest",
        timeoutMs = 30_000,
        streamTimeoutMs = 90_000
    ),
    apiKey = apiKey
) {
    override val providerName: String = "Mistral"
}