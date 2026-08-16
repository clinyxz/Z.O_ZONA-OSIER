/**
 * ZONA-OSIER — OpenRouterCloudClient.
 * Versi OpenRouterClient yang extends BaseOpenAiCompatibleClient.
 *
 * OpenRouter menyediakan routing gratis (1M req/bulan)
 * ke berbagai provider (Anthropic, OpenAI, Google, dll).
 * BYOK: user punya API key provider lain.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.BuildConfig

class OpenRouterCloudClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.OPENROUTER_API_KEY.ifBlank { null }
) : BaseOpenAiCompatibleClient(
    modelId = modelId,
    fallbackModelId = fallbackModelId,
    temperature = temperature,
    config = ProviderEndpointConfig(
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "meta-llama/llama-3.3-70b-instruct",
        timeoutMs = 45_000,
        streamTimeoutMs = 90_000
    ),
    apiKey = apiKey
) {
    override val providerName: String = "OpenRouter"

    /**
     * OpenRouter butuh header referer untuk analitik.
     */
    override fun buildExtraHeaders(): Map<String, String> = mapOf(
        "HTTP-Referer" to "https://zona-osier.app",
        "X-Title" to "ZONA-OSIER"
    )
}