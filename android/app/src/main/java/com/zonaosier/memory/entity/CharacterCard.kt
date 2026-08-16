/**
 * ZONA-OSIER — Entity Karakter.
 * Entitas sentral yang menggabungkan persona, model binding, voice tag, tool policy, dan memory scope.
 * Dapat dibuat manual, diimpor dari berbagai format eksternal, dan diekspor.
 *
 * FORMAT IMPORT DIDUKUNG:
 *   1. PNG Character Card V2 (tEXt chunk "chara", base64 JSON)
 *   2. PNG Character Card V3 (tEXt chunk "ccv3", raw UTF-8 JSON / zTXt compressed)
 *   3. JSON SillyTavern V2 ({"spec": "chara_card_v2", "data": {...}})
 *   4. JSON ChatterUI (flat: char_name, char_persona, ...)
 *   5. ZIP Character.AI export (berisi character.json)
 *   6. JSON Generic (minimal: name + description)
 *   7. Form manual Z.O (membuat dari nol)
 *
 * EXPORT FORMAT:
 *   - JSON internal Z.O (backup/transfer antar device)
 */
package com.zonaosier.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.zonaosier.memory.dao.Converters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Karakter ZONA-OSIER.
 * Setiap field dirancang agar bisa di-map dari berbagai format karakter eksternal.
 */
@Serializable
@Entity(tableName = "character_cards")
@TypeConverters(Converters::class)
data class CharacterCard(
    /** ID unik lokal (UUID) */
    @PrimaryKey
    @SerialName("id")
    val id: String,

    /** Nama karakter */
    @SerialName("name")
    val name: String,

    /**
     * Deskripsi / persona utama karakter.
     * Di-inject sebagai system message ke LLM.
     * Bahasa baku KBBI dengan serapan Sanskerta yang sudah lazim.
     */
    @SerialName("persona_prompt")
    val personaPrompt: String = "",

    /**
     * Deskripsi singkat (dari field "description" di format eksternal).
     * Digunakan untuk preview di daftar karakter.
     */
    @SerialName("description")
    val description: String = "",

    /**
     * Pesan pembuka / first message.
     * Ditampilkan saat karakter pertama kali diaktifkan.
     */
    @SerialName("first_message")
    val firstMessage: String? = null,

    /**
     * Contoh dialog (few-shot examples).
     * Format: "<user>...\n<char>..." per baris.
     * Di-inject ke system message jika ada.
     */
    @SerialName("example_dialogue")
    val exampleDialogue: String? = null,

    /**
     * Tag persona / kepribadian suara.
     * Nilai: "default", "tegas", "lembut", "ceria", "formal", "sabar", "misterius"
     * Digunakan oleh VoiceRouter untuk memilih voice ID di MiniMax/ElevenLabs.
     */
    @SerialName("voice_tag")
    val voiceTag: String = "default",

    /**
     * Skenario / konteks dunia karakter.
     * Dari field "scenario" atau "world_lore" di format eksternal.
     */
    @SerialName("scenario")
    val scenario: String? = null,

    /**
     * Kepribadian singkat (dari field "personality" di SillyTavern/ChatterUI).
     * Digunakan untuk meng-enrich personaPrompt jika personaPrompt kosong.
     */
    @SerialName("personality")
    val personality: String? = null,

    /**
     * Binding model AI — menentukan provider, model ID, dan parameter.
     * Setiap karakter bisa punya binding berbeda.
     * Mendukung 15+ pilihan provider/model.
     */
    @SerialName("model_binding")
    val modelBinding: ModelBinding = ModelBinding.DEFAULT,

    /**
     * Kebijakan akses tool per karakter.
     * Default: hanya tool non-destruktif.
     */
    @SerialName("tool_policy")
    val toolPolicy: ToolPolicy = ToolPolicy.DEFAULT,

    /**
     * Strategi konteks percakapan.
     */
    @SerialName("context_strategy")
    val contextStrategy: ContextStrategy = ContextStrategy.STANDARD,

    /**
     * Scope memori — isolated atau shared.
     */
    @SerialName("memory_scope")
    val memoryScope: MemoryScope = MemoryScope.ISOLATED,

    /**
     * URI avatar lokal (disimpan di internal cache).
     * Jika diimpor dari PNG, avatar di-extract dari gambar.
     */
    @SerialName("avatar_uri")
    val avatarUri: String? = null,

    /**
     * Format asal file impor.
     * Nilai: "sillytavern_v2", "chatterui", "character_ai", "generic", "manual", "zonaosier"
     */
    @SerialName("source_format")
    val sourceFormat: String = "manual",

    /**
     * Label kategori untuk organisasi (opsional).
     * Contoh: "asisten", "roleplay", "coding", "eksperimental"
     */
    @SerialName("category")
    val category: String? = null,

    /**
     * Apakah karakter ini sedang aktif.
     * Hanya satu karakter yang bisa aktif pada satu waktu.
     */
    @SerialName("is_active")
    val isActive: Boolean = false,

    /**
     * Timestamp pembuatan (epoch millis).
     */
    @SerialName("created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Timestamp update terakhir (epoch millis).
     */
    @SerialName("updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** Kategori-kategori yang tersedia */
        val CATEGORIES = listOf(
            "asisten", "roleplay", "coding", "eksperimental", "edukasi", "hiburan"
        )

        /** Voice tags yang tersedia */
        val VOICE_TAGS = listOf(
            "default", "tegas", "lembut", "ceria", "formal", "sabar", "misterius"
        )
    }

    /** Apakah karakter ini menggunakan model trial terbatas? */
    val isTrialProvider: Boolean
        get() = modelBinding.mode in ModelMode.TRIAL_MODES

    /** Apakah karakter ini menggunakan model lokal? */
    val isLocalModel: Boolean
        get() = modelBinding.mode.isLocal

    /** Tampilkan nama provider yang sedang digunakan */
    val providerDisplayName: String
        get() = modelBinding.mode.displayName
}