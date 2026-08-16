/**
 * ZONA-OSIER — ObjectBox Vector Memory Entity.
 * 
 * Menyimpan embedding vektor untuk pencarian HNSW.
 * ObjectBox v4.0+ mendukung HNSW indexing on-device.
 * 
 * Arsitektur Hybrid:
 * - Room = system of record (teks terstruktur, percakapan, audit)
 * - ObjectBox = HNSW vector index (pencarian semantik / RAG)
 * - Vector merujuk ke Room row ID via roomRefId
 * 
 * ⚠️ JANGAN simpan embedding vektor di Git (GitHub-as-Cloud).
 *    Hanya simpan teks. Embedding bisa diregenerasi on-demand.
 */
package com.zonaosier.memory

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Entitas vektor untuk pencarian HNSW.
 * Setiap baris menyimpan satu embedding yang merujuk ke data di Room.
 */
@Entity
data class VectorMemoryEntity(
    /** ObjectBox auto-generated ID. */
    @Id var id: Long = 0L,

    /** ID row di Room (ConversationEntry.id atau memory item ID). */
    @Index var roomRefId: String = "",

    /** Tipe sumber data. */
    var sourceType: VectorSourceType = VectorSourceType.CONVERSATION,

    /**
     * Embedding vektor. Dimensi tergantung model embedding yang digunakan.
     * Contoh: 384-dim (all-MiniLM-L6-v2), 768-dim (bge-small), dll.
     * 
     * ObjectBox menyimpan FloatArray sebagai blob.
     */
    var vector: FloatArray = floatArrayOf(),

    /** Timestamp pembuatan. */
    var createdAt: Long = System.currentTimeMillis(),

    /** Metadata tambahan (opsional). */
    var metadata: String = ""
) {
    companion object {
        /** Ukuran vektor default — sesuaikan dengan model embedding. */
        const val DEFAULT_DIMENSION = 384
    }
}

/**
 * Tipe sumber data vektor.
 */
enum class VectorSourceType {
    /** Dari percakapan (user/assistant message). */
    CONVERSATION,

    /** Dari memori eksplicit (user menambahkan fakta). */
    EXPLICIT_MEMORY,

    /** Dari personality extraction (AI mengekstrak preferensi user). */
    PERSONALITY,

    /** Dari dokumen yang di-import. */
    DOCUMENT
}
