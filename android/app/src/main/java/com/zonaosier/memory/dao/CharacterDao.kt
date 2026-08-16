/**
 * ZONA-OSIER — DAO untuk operasi CRUD CharacterCard.
 */
package com.zonaosier.memory.dao

import androidx.room.*
import com.zonaosier.memory.entity.CharacterCard
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {

    @Query("SELECT * FROM character_cards ORDER BY updated_at DESC")
    fun getAllCharacters(): Flow<List<CharacterCard>>

    @Query("SELECT * FROM character_cards ORDER BY updated_at DESC")
    suspend fun getAllCharactersOnce(): List<CharacterCard>

    @Query("SELECT * FROM character_cards WHERE id = :id")
    suspend fun getCharacterById(id: String): CharacterCard?

    @Query("SELECT * FROM character_cards WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveCharacter(): CharacterCard?

    @Query("SELECT * FROM character_cards WHERE is_active = 1 LIMIT 1")
    fun observeActiveCharacter(): Flow<CharacterCard?>

    @Query("SELECT * FROM character_cards WHERE category = :category ORDER BY updated_at DESC")
    fun getCharactersByCategory(category: String): Flow<List<CharacterCard>>

    @Query("SELECT * FROM character_cards WHERE source_format = :format ORDER BY updated_at DESC")
    fun getCharactersBySourceFormat(format: String): Flow<List<CharacterCard>>

    @Query("UPDATE character_cards SET is_active = 0 WHERE is_active = 1")
    suspend fun deactivateAll()

    @Query("UPDATE character_cards SET is_active = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(character: CharacterCard)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(characters: List<CharacterCard>)

    @Update
    suspend fun update(character: CharacterCard)

    @Query("DELETE FROM character_cards WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM character_cards")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM character_cards")
    suspend fun count(): Int

    /**
     * Set satu karakter aktif dan nonaktifkan semua lainnya.
     * Atomik — dipanggil dalam satu transaksi.
     */
    @Transaction
    suspend fun activateCharacter(id: String) {
        deactivateAll()
        setActive(id, true)
    }
}