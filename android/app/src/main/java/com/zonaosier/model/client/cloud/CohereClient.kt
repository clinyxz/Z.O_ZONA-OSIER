/**
 * ZONA-OSIER — CohereClient.
 * Klien LLM untuk Cohere Command R+ API.
 *
 * Cohere memiliki format Messages API sendiri, namun juga menyediakan
 * endpoint OpenAI-compatible (/v2/chat/completions) untuk model terbaru.
 * Kita menggunakan endpoint v2 yang OpenAI-compatible.
 *
 * Model: command-r-plus, command-r
 * Keunggulan: RAG-native, 128K context window.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.BuildConfig

class CohereClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.COHERE_API_KEY.ifBlank { null }
) : BaseOpenAiCompatibleClient(
    modelId = modelId,
    fallbackModelId = fallbackModelId,
    temperature = temperature,
    config = ProviderEndpointConfig(
        baseUrl = "https://api.cohere.ai/v2",
        defaultModel = "command-r-plus",
        timeoutMs = 30_000,
        streamTimeoutMs = 90_000
    ),
    apiKey = apiKey
) {
    override val providerName: String = "Cohere"
}
