/**
 * ZONA-OSIER — Memory Timeline Cache (Room DB).
 * 
 * Caching metadata commit dari JGit ke Room DB.
 * Tujuannya menghindari pemanggilan git log setiap kali
 * user membuka Memory Timeline.
 * 
 * Lazy loading: pagination 20 item per halaman.
 */
package com.zonaosier.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cache metadata commit di Room.
 * Di-update setelah setiap sync.
 */
@Entity(tableName = "commit_cache")
data class CommitCacheEntry(
    @PrimaryKey val commitHash: String,
    val shortMessage: String,
    val author: String,
    val commitTime: Long,
    val diffStat: String,
    /** Halaman di mana entry ini muncul (untuk pagination). */
    val page: Int = 0
)

/**
 * DAO untuk commit cache.
 * Bisa dimasukkan ke ZonaDatabase nanti.
 */
// Untuk sekarang, diakses langsung oleh GitHubSyncManager.
// Setelah Tahap 9, pindahkan ke ZonaDatabase.
