/**
 * ZONA-OSIER — Character.AI Export ZIP Parser.
 * 
 * Character.AI memungkinkan export karakter sebagai ZIP file.
 * Struktur ZIP:
 *   - character.json (wajib — metadata karakter)
 *   - avatar.png (opsional — gambar karakter)
 *   - conversations/ (opsional — riwayat percakapan, tidak diimpor)
 * 
 * ⚠️ Mitigasi:
 * - MAX_ZIP_ENTRIES = 50 (cegah Zip Slip dengan entry berlebihan)
 * - Skip file > MAX_FILE_SIZE per entry
 * - Validasi path absolut (Zip Slip prevention)
 */
package com.zonaosier.character.imports

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class CharacterAiZipParser : CharacterParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        /** Maksimal entry dalam ZIP. */
        const val MAX_ZIP_ENTRIES = 50

        /** Maksimal ukuran satu file dalam ZIP. */
        const val MAX_FILE_SIZE = 10 * 1024 * 1024L  // 10MB

        /** Nama file JSON yang dicari. */
        private val CHARACTER_JSON_NAMES = setOf(
            "character.json",
            "Character.json",
            "CHARACTER.JSON"
        )

        /** Nama file avatar yang dicari. */
        private val AVATAR_NAMES = setOf(
            "avatar.png",
            "Avatar.png",
            "AVATAR.PNG",
            "avatar.jpg",
            "Avatar.jpg"
        )
    }

    override suspend fun canParse(uri: Uri, context: Context): Boolean {
        val fileName = uri.lastPathSegment?.lowercase() ?: return false
        return fileName.endsWith(".zip")
    }

    override suspend fun parse(uri: Uri, context: Context): ParseResult {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                parseZip(zip, context)
            }
        } ?: ParseResult(false, error = "Gagal membuka file ZIP.")
    }

    private fun parseZip(zip: ZipInputStream, context: Context): ParseResult {
        var characterJson: JsonObject? = null
        var avatarBytes: ByteArray? = null
        var entryCount = 0
        val warnings = mutableListOf<String>()

        var entry = zip.nextEntry
        while (entry != null) {
            entryCount++

            // Cegah Zip dengan entry berlebihan
            if (entryCount > MAX_ZIP_ENTRIES) {
                warnings.add("ZIP memiliki lebih dari $MAX_ZIP_ENTRIES entry — sisa diabaikan.")
                break
            }

            // Validasi path (Zip Slip prevention)
            if (isPathUnsafe(entry.name)) {
                warnings.add("Entry '${entry.name}' diabaikan — path tidak aman.")
                entry = zip.nextEntry
                continue
            }

            val entryName = entry.name.substringAfterLast("/")

            when {
                entryName in CHARACTER_JSON_NAMES -> {
                    val content = readZipEntry(zip, MAX_FILE_SIZE)
                        ?: return ParseResult(
                            success = false,
                            error = "Gagal membaca ${entry.name}."
                        )
                    characterJson = try {
                        json.parseToJsonElement(content).jsonObject
                    } catch (e: Exception) {
                        return ParseResult(
                            success = false,
                            error = "character.json tidak valid: ${e.message}"
                        )
                    }
                }

                entryName in AVATAR_NAMES -> {
                    avatarBytes = readZipEntryBytes(zip, MAX_FILE_SIZE)
                    if (avatarBytes == null) {
                        warnings.add("Avatar '${entry.name}' gagal dibaca.")
                    }
                }

                // Entry lain diabaikan (conversations/, dll)
            }

            entry = zip.nextEntry
        }

        // Validasi: character.json wajib ada
        if (characterJson == null) {
            return ParseResult(
                success = false,
                error = "character.json tidak ditemukan dalam ZIP. " +
                        "Pastikan export dari Character.AI benar."
            )
        }

        // Cek field wajib
        val hasName = characterJson.containsKey("name") ||
                characterJson.containsKey("title")
        if (!hasName) {
            return ParseResult(
                success = false,
                error = "character.json tidak mengandung field 'name' atau 'title'."
            )
        }

        // Normalisasi field khas Character.AI
        val normalizedJson = normalizeCaiFields(characterJson)

        // Simpan avatar jika ada
        val avatarUri = if (avatarBytes != null) {
            AvatarHelper.saveToCache(avatarBytes, context)
        } else null

        // Map ke CharacterCard
        val card = CharacterCardMapper.map(
            source = normalizedJson,
            sourceFormat = "cai_zip",
            avatarUri = avatarUri
        )

        if (avatarBytes != null && avatarUri == null) {
            warnings.add("Avatar ditemukan tapi gagal disimpan.")
        }

        return ParseResult(
            success = true,
            card = card,
            avatarBytes = avatarBytes,
            detectedFormat = "cai_zip",
            warnings = warnings
        )
    }

    /**
     * Normalisasi field khas Character.AI ke format yang dipahami mapper.
     * C.A.I menggunakan field: title, participant__name, greeting, dll.
     */
    private fun normalizeCaiFields(source: JsonObject): JsonObject {
        val mutable = source.toMutableMap()

        // C.A.I kadang pakai "title" bukan "name"
        if (!mutable.containsKey("name") && mutable.containsKey("title")) {
            mutable["name"] = mutable["title"]!!
        }

        // C.A.I kadang pakai "participant__name" bukan "name"
        if (!mutable.containsKey("name") && mutable.containsKey("participant__name")) {
            mutable["name"] = mutable["participant__name"]!!
        }

        // C.A.I: "greeting" → "first_message"
        if (!mutable.containsKey("first_mes") && mutable.containsKey("greeting")) {
            mutable["first_mes"] = mutable["greeting"]!!
        }

        // C.A.I: "description" tetap "description"
        // Tidak perlu mapping

        return JsonObject(mutable)
    }

    // ==================== ZIP Reading Helpers ====================

    /**
     * Baca entry ZIP sebagai String.
     * Batasi ukuran untuk mencegah OOM.
     */
    private fun readZipEntry(zip: ZipInputStream, maxSize: Long): String? {
        return try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var totalRead = 0L

            while (true) {
                val count = zip.read(buffer)
                if (count <= 0) break
                totalRead += count
                if (totalRead > maxSize) return null // File terlalu besar
                output.write(buffer, 0, count)
            }

            output.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Baca entry ZIP sebagai ByteArray.
     */
    private fun readZipEntryBytes(zip: ZipInputStream, maxSize: Long): ByteArray? {
        return try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var totalRead = 0L

            while (true) {
                val count = zip.read(buffer)
                if (count <= 0) break
                totalRead += count
                if (totalRead > maxSize) return null
                output.write(buffer, 0, count)
            }

            output.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cegah Zip Slip — validasi tidak ada path traversal.
     */
    private fun isPathUnsafe(entryName: String): Boolean {
        // Path absolut
        if (entryName.startsWith("/") || entryName.contains("..")) return true
        // Path traversal
        val normalized = java.io.File(entryName).canonicalPath
        val base = java.io.File("").canonicalPath
        return !normalized.startsWith(base)
    }
}
