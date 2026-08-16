/**
 * ZONA-OSIER — DAO untuk operasi percakapan.
 */
package com.zonaosier.memory.dao

import androidx.room.*
import com.zonaosier.memory.entity.ConversationEntry
import com.zonaosier.memory.entity.MessageRole
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversation_entries WHERE character_id = :characterId ORDER BY timestamp ASC")
    fun getByCharacter(characterId: String): Flow<List<ConversationEntry>>

    @Query("SELECT * FROM conversation_entries WHERE character_id = :characterId ORDER BY timestamp ASC")
    suspend fun getByCharacterOnce(characterId: String): List<ConversationEntry>

    @Query("SELECT * FROM conversation_entries WHERE character_id IS NULL ORDER BY timestamp ASC")
    fun getGlobal(): Flow<List<ConversationEntry>>

    @Query("SELECT * FROM conversation_entries WHERE character_id IS NULL ORDER BY timestamp ASC")
    suspend fun getGlobalOnce(): List<ConversationEntry>

    @Query("SELECT * FROM conversation_entries WHERE id = :id")
    suspend fun getById(id: String): ConversationEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ConversationEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ConversationEntry>)

    @Query("DELETE FROM conversation_entries WHERE character_id = :characterId")
    suspend fun deleteByCharacter(characterId: String)

    @Query("DELETE FROM conversation_entries WHERE character_id IS NULL")
    suspend fun deleteGlobal()

    /**
     * Ambil N entry terakhir untuk sliding window.
     * Digunakan untuk membangun context window sebelum kirim ke LLM.
     */
    @Query("SELECT * FROM conversation_entries WHERE character_id = :characterId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastN(characterId: String, limit: Int): List<ConversationEntry>

    @Query("SELECT COUNT(*) FROM conversation_entries WHERE character_id = :characterId")
    suspend fun countByCharacter(characterId: String): Int

    @Query("SELECT SUM(CASE WHEN role = 'user' THEN 1 ELSE 0 END) FROM conversation_entries WHERE character_id = :characterId")
    suspend fun userMessageCount(characterId: String): Int?
}
