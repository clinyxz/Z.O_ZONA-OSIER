/**
 * ZONA-OSIER — Konfigurasi binding model per karakter.
 * Menentukan provider, model ID, dan parameter inferensi.
 *
 * BERFUNGSI SEBAGAI KATALOG PILIHAN AI:
 * - Setiap karakter bisa memilih provider & model berbeda
 * - Mendukung cloud (10+ provider) dan lokal (3 tier)
 * - Fallback model otomatis jika primer gagal
 * - Temperature dan max token bisa dikustomisasi
 */
package com.zonaosier.memory.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelBinding(
    /** Mode utama — lihat [ModelMode] untuk semua pilihan */
    @SerialName("mode")
    val mode: ModelMode = ModelMode.AUTO_TIER,

    /**
     * ID model spesifik di provider.
     * Contoh:
     *   - Groq: "llama-3.3-70b-versatile"
     *   - OpenRouter: "meta-llama/llama-3.1-8b-instruct:free"
     *   - Google AI: "gemini-2.0-flash"
     *   - DeepSeek: "deepseek-chat"
     *   - Mistral: "mistral-small-latest"
     *   - Cerebras: "llama-3.3-70b"
     *   - SambaNova: "Meta-Llama-3.3-70B-Instruct"
     *   - Lokal: nama file GGUF ("phi-4-q4_k_m.gguf")
     */
    @SerialName("provider_model_id")
    val providerModelId: String = "",

    /**
     * ID model fallback jika primer gagal.
     * Jika kosong, gunakan default provider fallback.
     */
    @SerialName("fallback_model_id")
    val fallbackModelId: String = "",

    /** Temperature inferensi (0.0 - 2.0). Default 0.7 */
    @SerialName("temperature")
    val temperature: Float = 0.7f,

    /** Maksimum token output per respons */
    @SerialName("max_tokens")
    val maxTokens: Int = 2048,

    /**
     * Untuk model lokal: path ke file GGUF relatif terhadap assets/models/
     * Contoh: "adhi/phi-4-q4_k_m.gguf"
     */
    @SerialName("local_model_path")
    val localModelPath: String = ""
) {
    companion object {
        /** Default binding — auto tier, temperature standar */
        val DEFAULT = ModelBinding()

        /** Preset Groq cepat — untuk Voice Assistant */
        val GROQ_FAST = ModelBinding(
            mode = ModelMode.ONLINE_GROQ,
            providerModelId = "llama-3.3-70b-versatile",
            fallbackModelId = "llama-3.1-8b-instant",
            temperature = 0.7f,
            maxTokens = 1024
        )

        /** Preset OpenRouter free */
        val OPENROUTER_FREE = ModelBinding(
            mode = ModelMode.ONLINE_CLOUD,
            providerModelId = "",
            temperature = 0.7f,
            maxTokens = 2048
        )

        /** Preset Google AI Studio */
        val GOOGLE_AI = ModelBinding(
            mode = ModelMode.ONLINE_GOOGLE_AI,
            providerModelId = "gemini-2.0-flash",
            fallbackModelId = "gemini-1.5-flash",
            temperature = 0.7f,
            maxTokens = 2048
        )

        /** Preset model lokal — auto tier */
        val LOCAL_AUTO = ModelBinding(
            mode = ModelMode.AUTO_TIER,
            temperature = 0.8f,
            maxTokens = 2048
        )

        // ---- Trial Providers (wajib track expiry) ----

        /** Preset DeepSeek — trial 5 juta token / 30 hari */
        val DEEPSEEK_TRIAL = ModelBinding(
            mode = ModelMode.ONLINE_DEEPSEEK,
            providerModelId = "deepseek-chat",
            fallbackModelId = "deepseek-reasoner",
            temperature = 0.7f,
            maxTokens = 4096
        )

        /** Preset Cerebras — trial $5 / 30 hari, RPM ~5 */
        val CEREBRAS_TRIAL = ModelBinding(
            mode = ModelMode.ONLINE_CEREBRAS,
            providerModelId = "llama-3.3-70b",
            temperature = 0.7f,
            maxTokens = 2048
        )

        /** Preset SambaNova — trial $5 / 30 hari */
        val SAMBA_NOVA_TRIAL = ModelBinding(
            mode = ModelMode.ONLINE_SAMBA_NOVA,
            providerModelId = "Meta-Llama-3.3-70B-Instruct",
            temperature = 0.7f,
            maxTokens = 2048
        )
    }
}