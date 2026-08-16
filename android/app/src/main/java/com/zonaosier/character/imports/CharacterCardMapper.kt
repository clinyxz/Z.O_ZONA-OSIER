/**
 * ZONA-OSIER — Character Card Mapper.
 * 
 * Menormalisasi field dari berbagai format karakter eksternal
 * ke satu entity CharacterCard Z.O yang seragam.
 * 
 * Mapping field:
 * ┌─────────────────────┬──────────────────────┬────────────────────┐
 * │ Format Eksternal     │ Field                │ → CharacterCard    │
 * ├─────────────────────┼──────────────────────┼────────────────────┤
 * │ SillyTavern V2/V3   │ data.name            │ name               │
 * │                     │ data.description     │ description        │
 * │                     │ data.personality     │ personality        │
 * │                     │ data.scenario        │ scenario           │
 * │                     │ data.first_mes       │ firstMessage       │
 * │                     │ data.mes_example     │ exampleDialogue    │
 * │                     │ data.creator_notes   │ creatorNotes       │
 * │                     │ data.tags            │ tags               │
 * ├─────────────────────┼──────────────────────┼────────────────────┤
 * │ ChatterUI           │ char_name            │ name               │
 * │                     │ char_persona         │ personaPrompt      │
 * │                     │ world_scenario       │ scenario           │
 * │                     │ char_greeting        │ firstMessage       │
 * │                     │ example_message      │ exampleDialogue    │
 * ├─────────────────────┼──────────────────────┼────────────────────┤
 * │ Character.AI        │ name                 │ name               │
 * │                     │ description          │ description        │
 * │                     │ greeting             │ firstMessage       │
 * ├─────────────────────┼──────────────────────┼────────────────────┤
 * │ Generic / Z.O       │ name                 │ name               │
 * │                     │ description / desc   │ description        │
 * │                     │ persona_prompt       │ personaPrompt      │
 * └─────────────────────┴──────────────────────┴────────────────────┘
 */
package com.zonaosier.character.imports

import com.zonaosier.memory.entity.*
import kotlinx.serialization.json.*

class CharacterCardMapper {

    companion object {

        /**
         * Map JsonObject dari format apapun ke CharacterCard.
         * Mencoba berbagai kemungkinan nama field.
         */
        fun map(
            source: JsonObject,
            sourceFormat: String,
            avatarUri: String? = null
        ): com.zonaosier.memory.entity.CharacterCard {
            // === Nama ===
            val name = source.getString("name")
                ?: source.getString("char_name")
                ?: "Karakter Tanpa Nama"

            // === Deskripsi ===
            val description = source.getString("description")
                ?: source.getString("desc")
                ?: source.getString("char_persona")
                ?: ""

            // === Personality ===
            val personality = source.getString("personality")
                ?: ""

            // === Scenario / World ===
            val scenario = source.getString("scenario")
                ?: source.getString("world_scenario")
                ?: source.getString("world_lore")
                ?: source.getString("creator_notes")?.take(200)

            // === First Message / Greeting ===
            val firstMessage = source.getString("first_mes")
                ?: source.getString("first_message")
                ?: source.getString("char_greeting")
                ?: source.getString("greeting")

            // === Example Dialogue ===
            val exampleDialogue = source.getString("mes_example")
                ?: source.getString("example_dialogue")
                ?: source.getString("example_message")

            // === Creator Notes (metadata, tidak dikirim ke LLM) ===
            val creatorNotes = source.getString("creator_notes")
                ?: source.getString("creator_comment")

            // === Tags ===
            val tags = source.getJsonArrayOrNull("tags")?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            } ?: emptyList()

            // === System Prompt (persona_prompt Z.O spesifik) ===
            val personaPrompt = source.getString("system_prompt")
                ?: source.getString("persona_prompt")

            // === Voice Tag ===
            val voiceTag = source.getString("voice_tag")?.let { tag ->
                if (tag in com.zonaosier.memory.entity.CharacterCard.VOICE_TAGS) tag else "default"
            } ?: "default"

            // === Category ===
            val category = source.getString("category")?.let { cat ->
                if (cat in com.zonaosier.memory.entity.CharacterCard.CATEGORIES) cat else null
            }

            // === Temperature (jika ada) ===
            val temperature = source.getString("temperature")?.toFloatOrNull()

            // === Build personaPrompt jika tidak ada di source ===
            val finalPersonaPrompt = if (!personaPrompt.isNullOrBlank()) {
                personaPrompt
            } else {
                buildDefaultPersona(name, description, personality, scenario)
            }

            // === Model Binding ===
            val modelBinding = buildModelBinding(source, temperature)

            // === Tool Policy ===
            val toolPolicy = buildToolPolicy(source)

            return com.zonaosier.memory.entity.CharacterCard(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                description = description.take(200),
                personaPrompt = finalPersonaPrompt,
                firstMessage = firstMessage,
                exampleDialogue = exampleDialogue,
                voiceTag = voiceTag,
                scenario = scenario,
                personality = personality,
                modelBinding = modelBinding,
                toolPolicy = toolPolicy,
                contextStrategy = ContextStrategy.STANDARD,
                memoryScope = MemoryScope.ISOLATED,
                avatarUri = avatarUri,
                sourceFormat = sourceFormat,
                category = category,
                isActive = false
            )
        }

        /**
         * Bangun persona prompt default dari field terpisah.
         * Format: [KARAKTER], [KEPRIBADIAN], [SITUASI]
         */
        private fun buildDefaultPersona(
            name: String,
            description: String,
            personality: String,
            scenario: String?
        ): String {
            return buildString {
                if (description.isNotBlank()) {
                    appendLine("[KARAKTER]")
                    appendLine(description)
                    append()
                }
                if (personality.isNotBlank()) {
                    appendLine("\n[KEPRIBADIAN]")
                    appendLine(personality)
                    append()
                }
                if (!scenario.isNullOrBlank()) {
                    appendLine("\n[SITUASI]")
                    appendLine(scenario)
                }
                if (isBlank()) {
                    append("Kamu adalah $name.")
                }
            }.trim()
        }

        /**
         * Bangun ModelBinding dari JSON.
         * Jika ada spesifikasi model di source, gunakan.
         * Jika tidak, gunakan AUTO_TIER.
         */
        private fun buildModelBinding(
            source: JsonObject,
            temperature: Float?
        ): ModelBinding {
            val modeStr = source.getString("model_mode")
                ?: source.getString("mode")
            val modelId = source.getString("model_id")
                ?: source.getString("provider_model_id")

            val mode = if (modeStr != null) {
                ModelMode.entries.firstOrNull { it.name.equals(modeStr, ignoreCase = true) }
                    ?: ModelMode.AUTO_TIER
            } else {
                ModelMode.AUTO_TIER
            }

            return ModelBinding(
                mode = mode,
                providerModelId = modelId,
                fallbackModelId = "groq/llama-3.3-70b-versatile",
                temperature = temperature ?: 0.7f
            )
        }

        /**
         * Bangun ToolPolicy dari JSON.
         * Default: semua deny kecuali non-destruktif.
         */
        private fun buildToolPolicy(source: JsonObject): ToolPolicy {
            val allowShell = source.getBoolean("allow_shell")
                ?: source.getBoolean("allowShell")
                ?: false
            val allowSms = source.getBoolean("allow_sms")
                ?: source.getBoolean("allowSms")
                ?: false
            val allowCall = source.getBoolean("allow_call")
                ?: source.getBoolean("allowCall")
                ?: false
            val allowCamera = source.getBoolean("allow_camera")
                ?: source.getBoolean("allowCamera")
                ?: false
            val allowContacts = source.getBoolean("allow_contacts")
                ?: source.getBoolean("allowContacts")
                ?: false
            val allowScreenRead = source.getBoolean("allow_screen_read")
                ?: source.getBoolean("allowScreenRead")
                ?: true
            val allowWebFetch = source.getBoolean("allow_web_fetch")
                ?: source.getBoolean("allowWebFetch")
                ?: true
            val allowCalendar = source.getBoolean("allow_calendar")
                ?: source.getBoolean("allowCalendar")
                ?: true
            val allowMemory = source.getBoolean("allow_memory")
                ?: source.getBoolean("allowMemory")
                ?: true
            val allowShizuku = source.getBoolean("allow_shizuku")
                ?: source.getBoolean("allowShizuku")
                ?: false

            return ToolPolicy(
                allowShell = allowShell,
                allowSms = allowSms,
                allowCall = allowCall,
                allowCamera = allowCamera,
                allowContacts = allowContacts,
                allowScreenRead = allowScreenRead,
                allowWebFetch = allowWebFetch,
                allowCalendar = allowCalendar,
                allowMemory = allowMemory,
                allowShizuku = allowShizuku
            )
        }
    }
}

// ==================== JsonObject Extension Helpers ====================

/** Ambil string dari JsonObject, null-safe. */
private fun JsonObject.getString(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
}

/** Ambil boolean dari JsonObject, null-safe. */
private fun JsonObject.getBoolean(key: String): Boolean? {
    val element = this[key] ?: return null
    return element.jsonPrimitive.booleanOrNull
}

/** Ambil JsonArray dari JsonObject, null-safe. */
private fun JsonObject.getJsonArrayOrNull(key: String): JsonArray? {
    return this[key]?.jsonArrayOrNull
}
