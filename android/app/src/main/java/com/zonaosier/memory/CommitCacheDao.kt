/**
 * ZONA-OSIER — Commit Cache DAO.
 *
 * Room Data Access Object untuk tabel [commit_cache].
 * Digunakan oleh GitHubSyncManager untuk menyimpan metadata commit
 * secara lokal, menghindari pemanggilan `git log` setiap kali
 * user membuka Memory Timeline.
 *
 * Fitur:
 * - insertAll dengan REPLACE: upsert untuk refresh cache setelah sync.
 * - Pagination: getByPage() untuk lazy loading.
 * - getLatest(): 20 entry terbaru untuk initial load.
 * - getCount(): untuk menentukan apakah perlu load lebih.
 * - deleteAll(): bersihkan cache sebelum sync ulang.
 *
 * Entity: [CommitCacheEntry] (didefinisikan di MemoryTimelineCache.kt).
 *
 * TODO (Tahap 9): Daftarkan DAO ini di ZonaDatabase dan
 *   tambahkan CommitCacheEntry ke daftar entities.
 */
package com.zonaosier.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommitCacheDao {

    /**
     * Sisipkan daftar entry commit ke cache.
     * Menggunakan REPLACE strategy: jika commitHash sudah ada, data akan di-update.
     * Digunakan saat refresh cache setelah sync selesai.
     *
     * @param entries Daftar CommitCacheEntry yang akan disisipkan.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<CommitCacheEntry>)

    /**
     * Ambil entry commit berdasarkan halaman (pagination).
     * Entry diurutkan berdasarkan commitTime DESC (terbaru dulu).
     *
     * @param page Nomor halaman (0-indexed).
     * @param limit Jumlah item per halaman.
     * @return Flow dari daftar CommitCacheEntry pada halaman tersebut.
     */
    @Query("SELECT * FROM commit_cache WHERE page = :page ORDER BY commitTime DESC LIMIT :limit")
    fun getByPage(page: Int, limit: Int): Flow<List<CommitCacheEntry>>

    /**
     * Ambil 20 entry commit terbaru.
     * Digunakan untuk initial load di Memory Timeline.
     *
     * @return Flow dari 20 CommitCacheEntry terbaru.
     */
    @Query("SELECT * FROM commit_cache ORDER BY commitTime DESC LIMIT 20")
    fun getLatest(): Flow<List<CommitCacheEntry>>

    /**
     * Hapus semua entry dari cache.
     * Dipanggil sebelum refresh cache saat sync selesai,
     * agar data lama yang sudah tidak ada di repo terhapus.
     */
    @Query("DELETE FROM commit_cache")
    suspend fun deleteAll()

    /**
     * Hitung total entry di cache.
     * Digunakan untuk menentukan apakah perlu memuat lebih (infinite scroll).
     *
     * @return Flow jumlah total entry.
     */
    @Query("SELECT COUNT(*) FROM commit_cache")
    fun getCount(): Flow<Int>
}