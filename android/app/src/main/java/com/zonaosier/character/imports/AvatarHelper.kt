/**
 * ZONA-OSIER — Avatar Helper.
 * 
 * Utilitas untuk mengekstrak dan menyimpan avatar karakter.
 * Avatar disimpan di internal cache directory.
 * 
 * Cleanup otomatis saat karakter dihapus (dipanggil dari CharacterRepository).
 */
package com.zonaosier.character.imports

import android.content.Context
import android.net.Uri
import java.io.File

object AvatarHelper {

    /** Nama direktori cache untuk avatar. */
    private const val AVATAR_DIR = "character_avatars"

    /** Maksimal ukuran avatar (2MB). */
    private const val MAX_AVATAR_SIZE = 2 * 1024 * 1024

    /**
     * Simpan avatar bytes ke cache.
     * 
     * @param avatarBytes Byte gambar avatar.
     * @param context Context.
     * @return Absolute path file yang disimpan, atau null jika gagal.
     */
    fun saveToCache(avatarBytes: ByteArray?, context: Context): String? {
        if (avatarBytes == null || avatarBytes.isEmpty()) return null
        if (avatarBytes.size > MAX_AVATAR_SIZE) return null

        val dir = File(context.cacheDir, AVATAR_DIR)
        if (!dir.exists()) dir.mkdirs()

        val fileName = "${java.util.UUID.randomUUID()}.png"
        val file = File(dir, fileName)

        return try {
            file.writeBytes(avatarBytes)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Ekstrak byte dari URI.
     * Membatasi ukuran baca.
     */
    fun extractBytes(uri: Uri, context: Context): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArrayOutputStream()
                val tmp = ByteArray(4096)
                var totalRead = 0L

                while (true) {
                    val count = stream.read(tmp)
                    if (count <= 0) break
                    totalRead += count
                    if (totalRead > MAX_AVATAR_SIZE) return null
                    buffer.write(tmp, 0, count)
                }

                buffer.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Hapus file avatar dari cache.
     */
    fun deleteFromCache(avatarUri: String?) {
        if (avatarUri.isNullOrBlank()) return
        try {
            File(avatarUri).delete()
        } catch (_: Exception) { }
    }

    /**
     * Bersihkan semua avatar di cache.
     */
    fun clearAllCache(context: Context) {
        val dir = File(context.cacheDir, AVATAR_DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Hitung total ukuran cache avatar.
     */
    fun getCacheSize(context: Context): Long {
        val dir = File(context.cacheDir, AVATAR_DIR)
        if (!dir.exists()) return 0L
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Cek apakah URI avatar masih valid (file ada).
     */
    fun isAvatarValid(avatarUri: String?): Boolean {
        if (avatarUri.isNullOrBlank()) return false
        return File(avatarUri).exists()
    }
}