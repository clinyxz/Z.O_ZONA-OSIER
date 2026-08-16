/**
 * ZONA-OSIER — NvidiaNimClient.
 * Klien LLM untuk NVIDIA NIM (NVIDIA Inference Microservices).
 *
 * NIM menyediakan inferensi GPU-optimized untuk model open-source.
 * Format API OpenAI-compatible.
 * Endpoint berbasis model: /v1/chat/completions
 *
 * Free tier terbatas, pay-per-use untuk production.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.BuildConfig

class NvidiaNimClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    /** NIM endpoint URL — bisa self-hosted atau cloud */
    private val nimBaseUrl: String = "https://integrate.api.nvidia.com/v1",
    apiKey: String? = BuildConfig.NVIDIA_API_KEY.ifBlank { null }
) : BaseOpenAiCompatibleClient(
    modelId = modelId,
    fallbackModelId = fallbackModelId,
    temperature = temperature,
    config = ProviderEndpointConfig(
        baseUrl = nimBaseUrl,
        defaultModel = "meta/llama-3.3-70b-instruct",
        timeoutMs = 30_000,
        streamTimeoutMs = 60_000
    ),
    apiKey = apiKey
) {
    override val providerName: String = "NVIDIA NIM"

    /**
     * NVIDIA NIM menggunakan header NVCF-LLM-API-KEY atau Bearer token.
     * Default: Bearer token (sama dengan OpenAI format).
     */
    override fun buildExtraHeaders(): Map<String, String> = mapOf(
        "NVCF-LLM-API-KEY" to (apiKey ?: "")
    )
}