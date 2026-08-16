/**
 * ZONA-OSIER — Mode model yang digunakan karakter.
 * Setiap karakter bisa bind ke mode berbeda.
 */
package com.zonaosier.memory.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ModelMode(val displayName: String, val isLocal: Boolean) {
    /** Model lokal tier Adhi (~7B-13B parameter, butuh 6GB+ RAM) */
    @SerialName("local_adhi")
    LOCAL_ADHI("Adhi (Lokal 7B-13B)", isLocal = true),

    /** Model lokal tier Madya (~3B-4B parameter, butuh 4GB+ RAM) */
    @SerialName("local_madya")
    LOCAL_MADYA("Madya (Lokal 3B-4B)", isLocal = true),

    /** Model lokal tier Alit (~1B-2B parameter, butuh 2GB+ RAM) */
    @SerialName("local_alit")
    LOCAL_ALIT("Alit (Lokal 1B-2B)", isLocal = true),

    /** Otomatis pilih tier berdasarkan RAM device saat runtime */
    @SerialName("auto_tier")
    AUTO_TIER("Otomatis (deteksi RAM)", isLocal = true),

    /** Provider cloud via OpenRouter — free models, BYOK */
    @SerialName("online_openrouter")
    ONLINE_CLOUD("OpenRouter (Cloud)", isLocal = false),

    /** Groq — LPU inference, 300-800 tok/s, TTFA cepat */
    @SerialName("online_groq")
    ONLINE_GROQ("Groq (Cloud)", isLocal = false),

    /** Google AI Studio — Gemini Flash, free 250 RPD */
    @SerialName("online_google_ai")
    ONLINE_GOOGLE_AI("Google AI Studio (Cloud)", isLocal = false),

    /** DeepSeek — 5 juta token / 30 hari (TRIAL TERBATAS) */
    @SerialName("online_deepseek")
    ONLINE_DEEPSEEK("DeepSeek (Cloud)", isLocal = false),

    /** Cerebras — $5 credit / 30 hari (TRIAL TERBATAS) */
    @SerialName("online_cerebras")
    ONLINE_CEREBRAS("Cerebras (Cloud)", isLocal = false),

    /** SambaNova — $5 credit / 30 hari (TRIAL TERBATAS) */
    @SerialName("online_sambanova")
    ONLINE_SAMBA_NOVA("SambaNova (Cloud)", isLocal = false),

    /** Mistral — Mistral Small 3.1 / Devstral */
    @SerialName("online_mistral")
    ONLINE_MISTRAL("Mistral (Cloud)", isLocal = false),

    /** Cohere — Command R+ */
    @SerialName("online_cohere")
    ONLINE_COHERE("Cohere (Cloud)", isLocal = false),

    /** Cloudflare AI Workers */
    @SerialName("online_cloudflare")
    ONLINE_CLOUDFLARE("Cloudflare AI (Cloud)", isLocal = false),

    /** HuggingFace Inference API */
    @SerialName("online_huggingface")
    ONLINE_HUGGINGFACE("HuggingFace (Cloud)", isLocal = false),

    /** NVIDIA NIM */
    @SerialName("online_nvidia")
    ONLINE_NVIDIA("NVIDIA NIM (Cloud)", isLocal = false),

    /** Novita AI */
    @SerialName("online_novita")
    ONLINE_NOVITA("Novita AI (Cloud)", isLocal = false);

    companion object {
        /** Semua mode yang bersifat trial terbatas — wajib track expiry */
        val TRIAL_MODES: Set<ModelMode> = setOf(ONLINE_DEEPSEEK, ONLINE_CEREBRAS, ONLINE_SAMBA_NOVA)

        /** Semua mode cloud (non-lokal) */
        val CLOUD_MODES: Set<ModelMode> = entries.filter { !it.isLocal }.toSet()

        /** Semua mode lokal */
        val LOCAL_MODES: Set<ModelMode> = entries.filter { it.isLocal }.toSet()
    }
}
