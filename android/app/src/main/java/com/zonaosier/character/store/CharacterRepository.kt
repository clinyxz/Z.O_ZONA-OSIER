/**
 * ZONA-OSIER — Character Repository.
 * 
 * Layer data untuk operasi CRUD karakter.
 * Menggabungkan Room DAO dengan logika bisnis.
 */
package com.zonaosier.character.store

import com.zonaosier.memory.dao.CharacterDao
import com.zonaosier.memory.entity.CharacterCard
import com.zonaosier.security.AuditLogger
import com.zonaosier.memory.entity.AuditStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository untuk operasi karakter.
 * Semua aksi dicatat ke audit log.
 */
class CharacterRepository(private val characterDao: CharacterDao) {

    /** Observe semua karakter. */
    fun observeAll(): Flow<List<CharacterCard>> = characterDao.getAllCharacters()

    /** Ambil semua karakter sekali. */
    suspend fun getAll(): List<CharacterCard> = characterDao.getAllCharactersOnce()

    /** Ambil karakter aktif. */
    suspend fun getActiveCharacter(): CharacterCard? = characterDao.getActiveCharacter()

    /** Observe karakter aktif (reactive). */
    fun observeActiveCharacter(): Flow<CharacterCard?> = characterDao.observeActiveCharacter()

    /** Ambil karakter berdasarkan ID. */
    suspend fun getById(id: String): CharacterCard? = characterDao.getCharacterById(id)

    /**
     * Simpan karakter baru.
     */
    suspend fun create(card: CharacterCard): CharacterCard {
        characterDao.upsert(card)
        AuditLogger.log(
            toolName = "CharacterStore",
            action = "create",
            status = AuditStatus.APPROVED,
            detail = "Karakter '${card.name}' (${card.id}) dibuat, format: ${card.sourceFormat}"
        )
        return card
    }

    /**
     * Update karakter.
     */
    suspend fun update(card: CharacterCard) {
        val updated = card.copy(updatedAt = System.currentTimeMillis())
        characterDao.update(updated)
        AuditLogger.log(
            toolName = "CharacterStore",
            action = "update",
            status = AuditStatus.MODIFIED,
            detail = "Karakter '${card.name}' (${card.id}) diperbarui"
        )
    }

    /**
     * Aktifkan karakter. Nonaktifkan semua karakter lain.
     */
    suspend fun activate(id: String) {
        characterDao.activateCharacter(id)
        val card = characterDao.getCharacterById(id)
        AuditLogger.log(
            toolName = "CharacterStore",
            action = "activate",
            status = AuditStatus.APPROVED,
            detail = "Karakter '${card?.name}' (${id}) diaktifkan"
        )
    }

    /**
     * Hapus karakter.
     */
    suspend fun delete(id: String) {
        val card = characterDao.getCharacterById(id)
        characterDao.deleteById(id)
        AuditLogger.log(
            toolName = "CharacterStore",
            action = "delete",
            status = AuditStatus.REJECTED,
            detail = "Karakter '${card?.name}' (${id}) dihapus",
            characterId = id
        )
    }

    /**
     * Export karakter ke JSON string.
     * Format internal Z.O — bisa diimpor di device lain.
     */
    fun exportToJson(card: CharacterCard): String {
        val gson = com.google.gson.Gson()
        return gson.toJson(card)
    }

    /**
     * Import karakter dari JSON string.
     * Memvalidasi field wajib.
     */
    fun importFromJson(json: String): ImportResult {
        val gson = com.google.gson.Gson()
        return try {
            val card = gson.fromJson(json, CharacterCard::class.java)
            if (card.name.isBlank()) {
                ImportResult.Error("Nama karakter tidak boleh kosong.")
            } else if (card.id.isBlank()) {
                // Generate ID baru jika tidak ada
                val newCard = card.copy(id = java.util.UUID.randomUUID().toString())
                ImportResult.Success(newCard)
            } else {
                ImportResult.Success(card)
            }
        } catch (e: Exception) {
            ImportResult.Error("Gagal mem-parse JSON: ${e.message}")
        }
    }

    sealed class ImportResult {
        data class Success(val card: CharacterCard) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }
}
