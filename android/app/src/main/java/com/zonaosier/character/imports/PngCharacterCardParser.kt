/**
 * ZONA-OSIER — PNG Character Card Parser (V2/V3).
 * 
 * Membaca metadata karakter yang disematkan di dalam file PNG.
 * PNG Character Card adalah standar de-facto untuk berbagi karakter AI.
 * 
 * Format:
 * - V2: tEXt chunk dengan key "chara", value base64-encoded JSON.
 * - V3: tEXt chunk dengan key "ccv3", value raw UTF-8 JSON.
 *        atau zTXt chunk dengan key "ccv3", value zlib-compressed JSON.
 * 
 * ⚠️ MITIGASI DOS:
 * - MAX_CHUNK_DATA_SIZE = 1MB (metadata karakter biasanya < 100KB)
 * - MAX_INFLATED_SIZE = 2MB (cegah inflate bomb)
 * - Melempar SecurityException jika inflate melebihi batas
 * 
 * Referensi spesifikasi:
 * - V2: https://github.com/malfoyslastname/character-card-spec-v2
 * - V3: https://github.com/Chubio/Character-Card-Spec-V3
 */
package com.zonaosier.character.imports

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

class PngCharacterCardParser : CharacterParser {

    companion object {
        /** Chunk key untuk V2 Character Card. */
        const val CHUNK_CHARA = "chara"

        /** Chunk key untuk V3 Character Card. */
        const val CHUNK_CCV3 = "ccv3"

        /** PNG signature 8 byte. */
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

        /**
         * Batas maksimal ukuran chunk data.
         * Metadata karakter biasanya < 100KB.
         * 1MB sudah sangat longgar — mencegah DoS.
         */
        const val MAX_CHUNK_DATA_SIZE = 1 * 1024 * 1024  // 1MB

        /**
         * Batas maksimal ukuran setelah inflate.
         * Defense-in-depth terhadap inflate/zip bomb.
         */
        const val MAX_INFLATED_SIZE = 2 * 1024 * 1024  // 2MB

        /** Metadata chunk types yang perlu kita proses. */
        private val METADATA_CHUNK_TYPES = setOf("tEXt", "zTXt", "iTXt")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun canParse(uri: Uri, context: Context): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(8)
                val read = stream.read(header)
                read == 8 && header.contentEquals(PNG_SIGNATURE)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun parse(uri: Uri, context: Context): ParseResult {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val chunks = readPngChunks(stream)

            // Prioritas: V3 (ccv3) > V2 (chara)
            val v3Chunk = chunks.find { it.key == CHUNK_CCV3 }
            val v2Chunk = chunks.find { it.key == CHUNK_CHARA }

            val targetChunk = v3Chunk ?: v2Chunk
                ?: return ParseResult(
                    success = false,
                    error = "PNG tidak mengandung metadata Character Card (tEXt:chara atau ccv3)."
                )

            // Decode chunk data
            val jsonString = decodeChunkData(targetChunk)
                ?: return ParseResult(
                    success = false,
                    error = "Gagal mendekode metadata dari chunk '${targetChunk.key}'."
                )

            // Parse JSON
            val rootElement = try {
                json.parseToJsonElement(jsonString)
            } catch (e: Exception) {
                return ParseResult(
                    success = false,
                    error = "JSON tidak valid dalam metadata PNG: ${e.message}"
                )
            }

            // Navigasi struktur JSON (V2 membungkus dalam "data", V3 flat)
            val dataObj = when {
                rootElement is JsonObject && rootElement.containsKey("data") -> {
                    rootElement["data"]!!.jsonObject
                }
                rootElement is JsonObject -> rootElement
                else -> return ParseResult(
                    success = false,
                    error = "Metadata bukan JSON object."
                )
            }

            // Tentukan format
            val isV3 = v3Chunk != null
            val detectedFormat = when {
                isV3 && dataObj.containsKey("spec") -> "sillytavern_v3"
                isV3 -> "v3_png"
                else -> "v2_png"
            }

            // Extract dan simpan avatar
            val avatarBytes = AvatarHelper.extractBytes(uri, context)
            val avatarUri = AvatarHelper.saveToCache(avatarBytes, context)

            // Map ke CharacterCard
            val card = CharacterCardMapper.map(
                source = dataObj,
                sourceFormat = detectedFormat,
                avatarUri = avatarUri
            )

            // Warnings
            val warnings = mutableListOf<String>()
            if (avatarUri == null) {
                warnings.add("Avatar tidak berhasil disimpan ke cache.")
            }

            ParseResult(
                success = true,
                card = card,
                avatarBytes = avatarBytes,
                detectedFormat = detectedFormat,
                warnings = warnings
            )
        } ?: ParseResult(
            success = false,
            error = "Gagal membaca file PNG."
        )
    }

    // ==================== Chunk Decoding ====================

    /**
     * Decode chunk data.
     * - Jika compressed (zTXt): inflate via zlib.
     * - Jika plain (tEXt): gunakan langsung.
     * - Coba base64 decode (V2 spec: chara value base64-encoded).
     */
    private fun decodeChunkData(chunk: PngChunk): String? {
        return try {
            val rawBytes = if (chunk.compressed) {
                safeInflate(chunk.data)
                    ?: return null
            } else {
                chunk.data
            }

            val rawString = String(rawBytes, Charsets.UTF_8)

            // Coba base64 decode (V2 spec)
            try {
                val decoded = android.util.Base64.decode(rawString, android.util.Base64.DEFAULT)
                String(decoded, Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                // Bukan base64 — gunakan raw string (V3 spec)
                rawString
            }
        } catch (e: SecurityException) {
            // Inflate bomb detected
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Inflate zlib data dengan batas keamanan.
     * Melempar SecurityException jika melebihi MAX_INFLATED_SIZE.
     */
    private fun safeInflate(compressedData: ByteArray): ByteArray? {
        return try {
            Inflater().use { inflater ->
                inflater.setInput(compressedData)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)

                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    output.write(buffer, 0, count)

                    // Defense-in-depth: cek ukuran inflated
                    if (output.size() > MAX_INFLATED_SIZE) {
                        throw SecurityException(
                            "Inflate bomb detected: " +
                            "compressed=${compressedData.size} bytes, " +
                            "inflated>${MAX_INFLATED_SIZE / 1024}KB"
                        )
                    }
                }

                output.toByteArray()
            }
        } catch (e: SecurityException) {
            throw e // Re-throw untuk ditangkap caller
        } catch (_: Exception) {
            null
        }
    }

    // ==================== PNG Chunk Reading ====================

    /**
     * Baca semua metadata chunks dari PNG stream.
     * Hanya membaca tEXt, zTXt, iTXt — skip chunk lainnya (IDAT, PLTE, dll).
     */
    private fun readPngChunks(stream: java.io.InputStream): List<PngChunk> {
        val chunks = mutableListOf<PngChunk>()

        // Skip PNG signature (8 bytes)
        skipExactly(stream, 8)

        while (true) {
            // Baca chunk length (4 bytes big-endian)
            val lengthBytes = ByteArray(4)
            if (stream.read(lengthBytes) != 4) break

            val length = intFromBigEndian(lengthBytes)
            if (length < 0) break

            // Baca chunk type (4 bytes)
            val typeBytes = ByteArray(4)
            if (stream.read(typeBytes) != 4) break
            val type = String(typeBytes, Charsets.UTF_8)

            // IEND — stop
            if (type == "IEND") break

            // Skip non-metadata chunks
            if (type !in METADATA_CHUNK_TYPES) {
                skipExactly(stream, length.toLong() + 4) // data + CRC
                continue
            }

            // Security: skip oversized chunks
            if (length > MAX_CHUNK_DATA_SIZE) {
                skipExactly(stream, length.toLong() + 4)
                continue
            }

            // Baca chunk data
            val data = ByteArray(length)
            val totalRead = stream.read(data)
            if (totalRead < 0) break

            // Skip CRC (4 bytes)
            skipExactly(stream, 4)

            // Parse berdasarkan tipe
            when (type) {
                "tEXt" -> {
                    val nullIndex = data.indexOf(0.toByte())
                    if (nullIndex >= 0 && nullIndex < data.size) {
                        val key = String(data, 0, nullIndex, Charsets.UTF_8)
                        val value = data.copyOfRange(nullIndex + 1, data.size)
                        chunks.add(PngChunk(key = key, data = value, compressed = false))
                    }
                }
                "zTXt" -> {
                    // Format: keyword\0compression_method\compressed_data
                    val nullIndex = data.indexOf(0.toByte())
                    if (nullIndex >= 0 && nullIndex < data.size - 2) {
                        val key = String(data, 0, nullIndex, Charsets.UTF_8)
                        // Skip null byte (1) + compression method (1) = offset +2
                        val compressedData = data.copyOfRange(nullIndex + 2, data.size)
                        chunks.add(PngChunk(key = key, data = compressedData, compressed = true))
                    }
                }
                // iTXt: tidak diimplementasikan (jarang dipakai untuk char card)
            }
        }

        return chunks
    }

    /**
     * Konversi 4 bytes big-endian ke Int.
     */
    private fun intFromBigEndian(bytes: ByteArray): Int {
        return (bytes[0].toInt() and 0xFF shl 24) or
               (bytes[1].toInt() and 0xFF shl 16) or
               (bytes[2].toInt() and 0xFF shl 8) or
               (bytes[3].toInt() and 0xFF)
    }

    /**
     * Skip n bytes dari stream. Handle partial reads.
     */
    private fun skipExactly(stream: java.io.InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    /**
     * Data class untuk PNG chunk yang sudah diparse.
     */
    private data class PngChunk(
        val key: String,
        val data: ByteArray,
        val compressed: Boolean
    )
}
