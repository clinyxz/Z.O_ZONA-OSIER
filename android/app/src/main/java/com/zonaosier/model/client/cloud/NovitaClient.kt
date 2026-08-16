/**
 * ZONA-OSIER — NovitaClient.
 * Klien LLM untuk Novita AI API.
 *
 * Novita menggunakan format OpenAI-compatible.
 * Mendukung berbagai model open-source (Llama, Qwen, Mixtral, dll).
 * Pricing pay-per-request, cocok untuk burst usage.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.BuildConfig

class NovitaClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.NOVITA_API_KEY.ifBlank { null }
) : BaseOpenAiCompatibleClient(
    modelId = modelId,
    fallbackModelId = fallbackModelId,
    temperature = temperature,
    config = ProviderEndpointConfig(
        baseUrl = "https://api.novita.ai/v3/openai",
        defaultModel = "meta-llama/llama-3.3-70b-instruct",
        timeoutMs = 30_000,
        streamTimeoutMs = 60_000
    ),
    apiKey = apiKey
) {
    override val providerName: String = "Novita AI"
}