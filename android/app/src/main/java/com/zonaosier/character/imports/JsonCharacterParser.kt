/**
 * ZONA-OSIER — JSON Character Parser.
 * 
 * Mendeteksi otomatis format JSON:
 * 1. SillyTavern V2: {"spec": "chara_card_v2", "data": {...}}
 * 2. ChatterUI: flat {"char_name": ..., "char_persona": ...}
 * 3. Z.O Internal: {"id": ..., "persona_prompt": ..., ...}
 * 4. Generic: minimal {"name": ..., "description": ...}
 * 
 * Semua format di-normalize ke CharacterCard via CharacterCardMapper.
 */
package com.zonaosier.character.imports

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.*

class JsonCharacterParser : CharacterParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override suspend fun canParse(uri: Uri, context: Context): Boolean {
        val fileName = uri.lastPathSegment?.lowercase() ?: return false
        return fileName.endsWith(".json")
    }

    override suspend fun parse(uri: Uri, context: Context): ParseResult {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText(MAX_JSON_SIZE)
            } ?: return ParseResult(false, error = "Gagal membaca file JSON.")

            if (content.isBlank()) {
                return ParseResult(false, error = "File JSON kosong.")
            }

            val root = json.parseToJsonElement(content)
            if (root !is JsonObject) {
                return ParseResult(false, error = "Root element bukan JSON object.")
            }

            // Deteksi format
            val detectedFormat = detectFormat(root)
            val warnings = mutableListOf<String>()

            // Navigasi ke data object
            val dataObj = when (detectedFormat) {
                "sillytavern_v2" -> {
                    val data = root["data"]
                    if (data == null || data !is JsonObject) {
                        return ParseResult(
                            success = false,
                            error = "SillyTavern V2: field 'data' tidak ditemukan atau bukan object."
                        )
                    }
                    warnings.add("SillyTavern V2: field spesifik ST mungkin tidak ada di Z.O.")
                    data
                }
                "zonaosier" -> {
                    // Z.O internal — cek apakah ada avatar terpisah
                    root
                }
                else -> {
                    // ChatterUI / Generic — flat
                    root
                }
            }

            // Cek field wajib minimal
            val hasName = dataObj.containsKey("name") ||
                    dataObj.containsKey("char_name")
            if (!hasName) {
                return ParseResult(
                    success = false,
                    error = "JSON tidak mengandung field 'name' atau 'char_name'."
                )
            }

            // Map ke CharacterCard
            val card = CharacterCardMapper.map(
                source = dataObj,
                sourceFormat = detectedFormat,
                avatarUri = null
            )

            ParseResult(
                success = true,
                card = card,
                detectedFormat = detectedFormat,
                warnings = warnings
            )

        } catch (e: kotlinx.serialization.json.JsonException) {
            ParseResult(false, error = "JSON parsing error: ${e.message}")
        } catch (e: Exception) {
            ParseResult(false, error = "Error: ${e.message}")
        }
    }

    /**
     * Deteksi format JSON berdasarkan struktur.
     */
    private fun detectFormat(root: JsonObject): String {
        // SillyTavern V2/V3: spec field
        val spec = root["spec"]?.jsonPrimitive?.contentOrNull
        if (spec != null) {
            return when {
                spec.contains("chara_card_v2") -> "sillytavern_v2"
                spec.contains("chara_card_v3") -> "sillytavern_v3"
                else -> "sillytavern_v2" // Asumsi V2 jika spec ada
            }
        }

        // Z.O Internal: punya id + persona_prompt + model_binding
        if (root.containsKey("persona_prompt") &&
            root.containsKey("model_binding")) {
            return "zonaosier"
        }

        // ChatterUI: field khas ChatterUI
        if (root.containsKey("char_name") ||
            root.containsKey("char_persona") ||
            root.containsKey("char_greeting")) {
            return "chatterui"
        }

        // Character.AI export (non-ZIP, langsung JSON)
        if (root.containsKey("participant__name") ||
            root.containsKey("external_id")) {
            return "character_ai"
        }

        // Generic: minimal name + description
        if (root.containsKey("name") || root.containsKey("description")) {
            return "generic"
        }

        return "unknown"
    }

    companion object {
        /** Batas ukuran JSON file — 5MB lebih dari cukup untuk karakter. */
        const val MAX_JSON_SIZE = 5 * 1024 * 1024L
    }
}