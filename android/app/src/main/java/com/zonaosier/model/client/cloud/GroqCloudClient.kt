/**
 * ZONA-OSIER — GroqCloudClient.
 * Versi GroqClient yang extends BaseOpenAiCompatibleClient.
 * Mengurangi duplikasi kode dibanding GroqClient asli.
 *
 * Provider utama untuk Voice Assistant (foreground, real-time).
 * TTFA <800ms, 300-800 tok/s.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.BuildConfig

class GroqCloudClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.GROQ_API_KEY.ifBlank { null }
) : BaseOpenAiCompatibleClient(
    modelId = modelId,
    fallbackModelId = fallbackModelId,
    temperature = temperature,
    config = ProviderEndpointConfig(
        baseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        timeoutMs = 30_000,
        streamTimeoutMs = 60_000
    ),
    apiKey = apiKey
) {
    override val providerName: String = "Groq"
}