/**
 * ZONA-OSIER — DAO untuk audit log.
 * Append-only — tidak ada update atau delete publik.
 */
package com.zonaosier.memory.dao

import androidx.room.*
import com.zonaosier.memory.entity.AuditEntry
import com.zonaosier.memory.entity.AuditStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditEntry)

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<AuditEntry>>

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentOnce(limit: Int = 100): List<AuditEntry>

    @Query("SELECT * FROM audit_log WHERE character_id = :characterId ORDER BY timestamp DESC LIMIT :limit")
    fun getByCharacter(characterId: String, limit: Int = 100): Flow<List<AuditEntry>>

    @Query("SELECT * FROM audit_log WHERE tool_name = :toolName ORDER BY timestamp DESC LIMIT :limit")
    fun getByTool(toolName: String, limit: Int = 50): Flow<List<AuditEntry>>

    @Query("SELECT * FROM audit_log WHERE status = :status ORDER BY timestamp DESC LIMIT :limit")
    fun getByStatus(status: AuditStatus, limit: Int = 50): Flow<List<AuditEntry>>

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM audit_log WHERE timestamp >= :since")
    suspend fun countSince(since: Long): Int

    /**
     * Cleanup lama — hapus entry lebih tua dari N hari.
     * Dipanggil oleh maintenance scheduler.
     */
    @Query("DELETE FROM audit_log WHERE timestamp < :before")
    suspend fun pruneOlderThan(before: Long): Int
}
