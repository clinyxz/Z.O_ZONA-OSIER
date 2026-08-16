/**
 * ZONA-OSIER — CerebrasClient.
 * Klien LLM untuk Cerebras Inference API.
 *
 * Cerebras menggunakan format OpenAI-compatible.
 * Keunggulan: inferensi LPU ultra-cepat (~3000+ tok/s untuk output).
 *
 * ⚠️ TRIAL: $5 credit / 30 hari. RPM ~5, sangat ketat.
 * ⚠️ Passive health-check WAJIB — tidak boleh active ping.
 *
 * Catatan: Cerebras butuh stream_options: {include_usage: true}
 * untuk mendapatkan token usage di mode streaming.
 */
package com.zonaosier.model.client.cloud

import com.zonaosier.BuildConfig
import org.json.JSONObject

class CerebrasClient(
    modelId: String? = null,
    fallbackModelId: String? = null,
    temperature: Float = 0.7f,
    apiKey: String? = BuildConfig.CEREBRAS_API_KEY.ifBlank { null }
) : BaseOpenAiCompatibleClient(
    modelId = modelId,
    fallbackModelId = fallbackModelId,
    temperature = temperature,
    config = ProviderEndpointConfig(
        baseUrl = "https://api.cerebras.ai/v1",
        defaultModel = "llama-3.3-70b",
        timeoutMs = 30_000,
        streamTimeoutMs = 60_000
    ),
    apiKey = apiKey
) {
    override val providerName: String = "Cerebras"

    /**
     * Cerebras butuh stream_options untuk include usage di streaming.
     */
    override fun buildExtraBody(): JSONObject? {
        val extra = JSONObject()
        extra.put("stream_options", JSONObject().apply {
            put("include_usage", true)
        })
        return extra
    }
}
