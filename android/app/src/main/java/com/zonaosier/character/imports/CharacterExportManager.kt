/**
 * ZONA-OSIER — Character Export Manager.
 * 
 * Export karakter ke format yang bisa diimpor kembali.
 * Format export:
 * 1. JSON internal Z.O (backup/transfer antar device)
 * 2. PNG Character Card V2 (compatible dengan SillyTavern)
 * 
 * Semua export diproses on-device. Tidak ada upload ke server.
 */
package com.zonaosier.character.imports

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.zonaosier.memory.entity.CharacterCard
import com.zonaosier.security.AuditLogger
import com.zonaosier.memory.entity.AuditStatus
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hasil operasi export.
 */
sealed class ExportResult {
    data class Success(val uri: android.net.Uri, val format: String) : ExportResult()
    data class Error(val message: String) : ExportResult()
}

/**
 * Format export yang didukung.
 */
enum class ExportFormat(val label: String, val extension: String) {
    ZO_JSON("Z.O Internal", ".json"),
    SILLYTAVERN_V2("SillyTavern V2", ".png"),
    JSON_GENERIC("Generic JSON", ".json")
}

class CharacterExportManager(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create()

    /**
     * Export satu karakter.
     *
     * @param card Karakter yang akan diexport.
     * @param format Format export.
     * @return ExportResult dengan URI file.
     */
    fun exportCharacter(card: CharacterCard, format: ExportFormat): ExportResult {
        return when (format) {
            ExportFormat.ZO_JSON -> exportZoJson(card)
            ExportFormat.JSON_GENERIC -> exportGenericJson(card)
            ExportFormat.SILLYTAVERN_V2 -> {
                // PNG V2 export memerlukan penulisan chunk PNG
                // Implementasi lanjutan — untuk sekarang export sebagai JSON
                exportGenericJson(card)
            }
        }
    }

    /**
     * Batch export beberapa karakter ke satu ZIP.
     *
     * @param cards Daftar karakter.
     * @return ExportResult dengan URI file ZIP.
     */
    fun exportBatch(cards: List<CharacterCard>): ExportResult {
        if (cards.isEmpty()) {
            return ExportResult.Error("Tidak ada karakter untuk diexport.")
        }

        return try {
            val file = File(context.cacheDir, "zona_export_${System.currentTimeMillis()}.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
                cards.forEach { card ->
                    val json = gson.toJson(card)
                    val entry = ZipEntry("${sanitizeFileName(card.name)}.json")
                    zip.putNextEntry(entry)
                    zip.write(json.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }

            val uri = android.net.Uri.fromFile(file)
            AuditLogger.log(
                toolName = "CharacterExport",
                action = "batch_export",
                status = AuditStatus.APPROVED,
                detail = "${cards.size} karakter diekspor ke ZIP"
            )
            ExportResult.Success(uri, "ZIP batch")
        } catch (e: Exception) {
            ExportResult.Error("Gagal membuat ZIP: ${e.message}")
        }
    }

    /**
     * Export sebagai Z.O internal JSON.
     * Memuat semua field CharacterCard.
     */
    private fun exportZoJson(card: CharacterCard): ExportResult {
        return try {
            val json = gson.toJson(card)
            val file = File(
                context.cacheDir,
                "zona_${sanitizeFileName(card.name)}_${System.currentTimeMillis()}.json"
            )
            file.writeText(json, Charsets.UTF_8)

            val uri = android.net.Uri.fromFile(file)
            AuditLogger.log(
                toolName = "CharacterExport",
                action = "export_zo_json",
                status = AuditStatus.APPROVED,
                detail = "Karakter '${card.name}' diekspor sebagai Z.O JSON"
            )
            ExportResult.Success(uri, "Z.O JSON")
        } catch (e: Exception) {
            ExportResult.Error("Gagal export: ${e.message}")
        }
    }

    /**
     * Export sebagai Generic JSON.
     * Format sederhana yang bisa dibaca parser lain.
     */
    private fun exportGenericJson(card: CharacterCard): ExportResult {
        return try {
            val generic = linkedMapOf<String, Any?>(
                "name" to card.name,
                "description" to card.description,
                "persona_prompt" to card.personaPrompt,
                "personality" to card.personality,
                "scenario" to card.scenario,
                "first_message" to card.firstMessage,
                "example_dialogue" to card.exampleDialogue,
                "voice_tag" to card.voiceTag,
                "category" to card.category,
                "tags" to emptyList<String>(),
                "source_format" to "zonaosier",
                "exported_at" to System.currentTimeMillis()
            )

            val json = gson.toJson(generic)
            val file = File(
                context.cacheDir,
                "${sanitizeFileName(card.name)}_${System.currentTimeMillis()}.json"
            )
            file.writeText(json, Charsets.UTF_8)

            val uri = android.net.Uri.fromFile(file)
            ExportResult.Success(uri, "Generic JSON")
        } catch (e: Exception) {
            ExportResult.Error("Gagal export: ${e.message}")
        }
    }

    /**
     * Sanitasi nama file — hapus karakter berbahaya.
     */
    private fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[^a-zA-Z0-9_\\-\\s]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(50)
    }
}