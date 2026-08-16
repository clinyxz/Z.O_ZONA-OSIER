/**
 * ZONA-OSIER — CloudflareWorkersAiClient.
 * Klien LLM untuk Cloudflare Workers AI.
 *
 * Endpoint berbeda: /client/v4/accounts/{account_id}/ai/v1/chat/completions
 * Memerlukan Account ID sebagai bagian dari URL.
 * Model dijalankan di edge Cloudflare (latensi rendah untuk Asia).
 *
 * Pricing: pay-per-use, tidak ada free tier terbatas.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

class CloudflareWorkersAiClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    /** Account ID Cloudflare — wajib di settings */
    private val accountId: String = BuildConfig.CLOUDFLARE_ACCOUNT_ID.ifBlank { "" },
    apiKey: String? = BuildConfig.CLOUDFLARE_API_TOKEN.ifBlank { null }
) : BaseOpenAiCompatibleClient(
    modelId = modelId,
    fallbackModelId = fallbackModelId,
    temperature = temperature,
    config = ProviderEndpointConfig(
        // Account ID disisipkan di baseUrl — override di buildUrl
        baseUrl = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1",
        defaultModel = "@cf/meta/llama-3.3-70b-instruct",
        timeoutMs = 30_000,
        streamTimeoutMs = 60_000
    ),
    apiKey = apiKey
) {
    override val providerName: String = "Cloudflare Workers AI"

    override val isAvailable: Boolean
        get() = !apiKey.isNullOrBlank() && accountId.isNotBlank()

    /**
     * Cloudflare menggunakan Bearer token (API token), bukan API key.
     * Header Authorization sama formatnya (Bearer xxx).
     */
    override fun buildExtraHeaders(): Map<String, String> = mapOf(
        "CF-Account-ID" to accountId
    )
}
