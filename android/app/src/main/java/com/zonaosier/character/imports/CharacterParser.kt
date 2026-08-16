/**
 * ZONA-OSIER — Character Parser Interface & Registry.
 * 
 * Arsitektur parser:
 * 1. User memilih file (DocumentPicker: image/*, application/json, application/zip)
 * 2. CharacterParserRegistry mencoba setiap parser secara berurutan
 * 3. Parser pertama yang canParse() == true akan digunakan
 * 4. Result: ParseResult (sukses dengan CharacterCard, atau error)
 * 
 * Format didukung:
 * - PNG Character Card V2 (tEXt chunk "chara", base64-encoded JSON)
 * - PNG Character Card V3 (tEXt "ccv3" / zTXt compressed JSON)
 * - JSON SillyTavern V2 ({"spec": "chara_card_v2", "data": {...}})
 * - JSON ChatterUI (flat: char_name, char_persona, ...)
 * - JSON Generic (minimal: name + description)
 * - ZIP Character.AI export (berisi character.json)
 */
package com.zonaosier.character.imports

import android.content.Context
import android.net.Uri

/**
 * Hasil parsing karakter.
 */
data class ParseResult(
    val success: Boolean,
    val card: com.zonaosier.memory.entity.CharacterCard? = null,
    val avatarBytes: ByteArray? = null,
    val detectedFormat: String? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList()
) {
    /** Gabungan error dan warnings untuk UI. */
    val displayMessage: String
        get() = when {
            success -> "Karakter '${card?.name}' berhasil diparse (${detectedFormat})"
            else -> error ?: "Format tidak dikenali."
        }
}

/**
 * Interface dasar parser karakter.
 * Setiap format (PNG, JSON, ZIP) mengimplementasikan ini.
 */
interface CharacterParser {
    
    /**
     * Cek apakah parser ini bisa menangani file di URI.
     * Harus non-blocking dan cepat (cek header/mime saja).
     */
    suspend fun canParse(uri: Uri, context: Context): Boolean
    
    /**
     * Parse file di URI menjadi CharacterCard.
     * Dipanggil hanya jika canParse() == true.
     */
    suspend fun parse(uri: Uri, context: Context): ParseResult
}

/**
 * Registry parser — auto-detect format dan parse.
 * Urutan penting: PNG dicek duluan karena file .png bisa
 * juga berisi JSON di metadata chunk.
 */
object CharacterParserRegistry {
    
    private val parsers: List<CharacterParser> by lazy {
        listOf(
            PngCharacterCardParser(),
            JsonCharacterParser(),
            CharacterAiZipParser()
        )
    }
    
    /**
     * Auto-detect format dan parse.
     * Mencoba setiap parser berurutan.
     * 
     * @return ParseResult — sukses dengan CharacterCard atau error.
     */
    suspend fun autoDetectAndParse(uri: Uri, context: Context): ParseResult {
        for (parser in parsers) {
            try {
                if (parser.canParse(uri, context)) {
                    val result = parser.parse(uri, context)
                    if (result.success) return result
                    // Parser bisa parse tapi gagal — lanjut ke berikutnya
                }
            } catch (e: Exception) {
                // Parser error — lanjut ke berikutnya
                continue
            }
        }
        
        return ParseResult(
            success = false,
            error = "Format tidak dikenali. Didukung: PNG Character Card (V2/V3), " +
                    "JSON (SillyTavern/ChatterUI/Generic), ZIP (Character.AI export)"
        )
    }
    
    /**
     * Daftar format yang didukung (untuk UI filter DocumentPicker).
     */
    val supportedMimeTypes: List<String>
        get() = listOf("image/png", "application/json", "application/zip", "*/*")
    
    /**
     * Daftar ekstensi file yang didukung.
     */
    val supportedExtensions: List<String>
        get() = listOf(".png", ".json", ".zip")
}
