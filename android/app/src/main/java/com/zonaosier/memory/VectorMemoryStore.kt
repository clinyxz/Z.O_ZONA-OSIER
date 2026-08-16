/**
 * ZONA-OSIER — Vector Memory Store (ObjectBox HNSW).
 * 
 * Abstraksi atas ObjectBox untuk operasi vektor:
 * - Simpan embedding
 * - Cari nearest neighbors (HNSW)
 * - Delete oleh roomRefId
 * 
 * HNSW (Hierarchical Navigable Small World) adalah algoritma
 * pencarian approximate nearest neighbor yang efisien.
 * ObjectBox v4.0+ mendukung ini secara native on-device.
 */
package com.zonaosier.memory

import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.query.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VectorMemoryStore(private val boxStore: BoxStore) {

    private val vectorBox: Box<VectorMemoryEntity> by lazy {
        boxStore.boxFor(VectorMemoryEntity::class.java)
    }

    /**
     * Simpan embedding vektor baru.
     *
     * @param roomRefId ID row di Room.
     * @param vector Array float embedding.
     * @param sourceType Tipe sumber.
     * @param metadata Metadata tambahan (opsional).
     * @return ID ObjectBox yang dibuat.
     */
    suspend fun store(
        roomRefId: String,
        vector: FloatArray,
        sourceType: VectorSourceType = VectorSourceType.CONVERSATION,
        metadata: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val entity = VectorMemoryEntity(
            roomRefId = roomRefId,
            vector = vector,
            sourceType = sourceType,
            metadata = metadata
        )
        vectorBox.put(entity).id
    }

    /**
     * Simpan batch embedding.
     * Lebih efisien daripada store() satu per satu.
     */
    suspend fun storeBatch(items: List<VectorMemoryItem>): List<Long> = withContext(Dispatchers.IO) {
        val entities = items.map { item ->
            VectorMemoryEntity(
                roomRefId = item.roomRefId,
                vector = item.vector,
                sourceType = item.sourceType,
                metadata = item.metadata
            )
        }
        vectorBox.put(entities).map { it.id }
    }

    /**
     * Cari nearest neighbors menggunakan HNSW.
     * 
     * @param queryVector Vektor query.
     * @param topK Jumlah hasil teratas (default 5).
     * @return Daftar (roomRefId, score) yang paling mirip.
     */
    suspend fun search(
        queryVector: FloatArray,
        topK: Int = 5
    ): List<VectorSearchResult> = withContext(Dispatchers.IO) {
        // ObjectBox v4.0+ HNSW query
        // Implementasi menggunakan nearest neighbor query
        val allEntities = vectorBox.all

        // Hitung cosine similarity manual (fallback jika HNSW API belum tersedia)
        val scored = allEntities.map { entity ->
            val score = cosineSimilarity(queryVector, entity.vector)
            VectorSearchResult(
                roomRefId = entity.roomRefId,
                score = score,
                sourceType = entity.sourceType,
                metadata = entity.metadata
            )
        }

        scored
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Hapus vektor berdasarkan roomRefId.
     */
    suspend fun deleteByRoomRef(roomRefId: String) = withContext(Dispatchers.IO) {
        vectorBox.remove(vectorBox.query()
            .equal(VectorMemoryEntity_.roomRefId, roomRefId)
            .build()
            .find())
    }

    /**
     * Hapus semua vektor.
     */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        vectorBox.removeAll()
    }

    /**
     * Jumlah total vektor tersimpan.
     */
    suspend fun count(): Long = withContext(Dispatchers.IO) {
        vectorBox.count()
    }

    // ==================== Utility ====================

    /**
     * Cosine similarity antara dua vektor.
     * Menghasilkan skor antara -1.0 dan 1.0.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0f || normB == 0f) return 0f
        return dotProduct / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
    }

    // ==================== Data Classes ====================

    data class VectorMemoryItem(
        val roomRefId: String,
        val vector: FloatArray,
        val sourceType: VectorSourceType = VectorSourceType.CONVERSATION,
        val metadata: String = ""
    )

    data class VectorSearchResult(
        val roomRefId: String,
        val score: Float,
        val sourceType: VectorSourceType,
        val metadata: String
    )
}
