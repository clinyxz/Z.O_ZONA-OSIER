/**
 * ZONA-OSIER — Memory Repository.
 * 
 * Layer abstraksi yang menggabungkan Room + ObjectBox
 * untuk operasi memori hybrid.
 * 
 * Arsitektur:
 * - Room: teks terstruktur, percakapan, audit log
 * - ObjectBox: HNSW vector index, pencarian semantik
 * - Hybrid query: cari teks (keyword) + vektor (semantik)
 * 
 * Scope:
 * - ISOLATED: memori terpisah per karakter (dipisahkan di query)
 * - SHARED: memori gabungan global
 */
package com.zonaosier.memory

import com.zonaosier.memory.dao.ConversationDao
import com.zonaosier.memory.dao.CharacterDao
import com.zonaosier.memory.entity.ConversationEntry
import com.zonaosier.memory.entity.MessageRole
import com.zonaosier.memory.entity.MemoryScope
import com.zonaosier.memory.entity.CharacterCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Hasil pencarian memori hybrid.
 * Menggabungkan hasil keyword search (Room) dan vector search (ObjectBox).
 */
data class MemorySearchResult(
    val entry: ConversationEntry,
    val vectorScore: Float? = null,
    val matchedBy: MatchType
) {
    enum class MatchType {
        KEYWORD,
        VECTOR,
        BOTH
    }
}

/**
 * Data class untuk metadata memori timeline (cache di Room).
 */
data class MemoryCommitMeta(
    val commitHash: String,
    val shortMessage: String,
    val author: String,
    val commitTime: Long,
    val diffStat: String // "+3 -1" format
)

class MemoryRepository(
    private val conversationDao: ConversationDao,
    private val characterDao: CharacterDao,
    private val vectorStore: VectorMemoryStore
) {

    // ==================== Konversi ====================

    /**
     * Simpan pesan percakapan.
     */
    suspend fun saveConversation(
        characterId: String?,
        role: MessageRole,
        content: String,
        tokenCount: Int? = null
    ): ConversationEntry {
        val entry = ConversationEntry(
            id = java.util.UUID.randomUUID().toString(),
            characterId = characterId,
            role = role,
            content = content,
            tokenCount = tokenCount
        )
        conversationDao.insert(entry)
        return entry
    }

    /**
     * Simpan pesan + embedding vektor.
     */
    suspend fun saveWithEmbedding(
        characterId: String?,
        role: MessageRole,
        content: String,
        embedding: FloatArray,
        sourceType: VectorSourceType = VectorSourceType.CONVERSATION
    ): ConversationEntry {
        val entry = saveConversation(characterId, role, content)
        vectorStore.store(entry.id, embedding, sourceType)
        return entry
    }

    /**
     * Simpan batch pesan + embedding.
     */
    suspend fun saveBatchWithEmbedding(
        items: List<ConversationEmbeddingItem>
    ) {
        val entries = items.map { item ->
            ConversationEntry(
                id = java.util.UUID.randomUUID().toString(),
                characterId = item.characterId,
                role = item.role,
                content = item.content,
                tokenCount = item.tokenCount
            )
        }
        conversationDao.insertAll(entries)

        val vectorItems = entries.zip(items).map { (entry, item) ->
            VectorMemoryStore.VectorMemoryItem(
                roomRefId = entry.id,
                vector = item.embedding,
                sourceType = item.sourceType
            )
        }
        vectorStore.storeBatch(vectorItems)
    }

    // ==================== Query ====================

    /**
     * Ambil N pesan terakhir untuk sliding window context.
     * Digunakan saat membangun prompt ke LLM.
     */
    suspend fun getRecentContext(
        characterId: String?,
        maxMessages: Int = 16
    ): List<ConversationEntry> {
        return if (characterId != null) {
            conversationDao.getLastN(characterId, maxMessages).reversed()
        } else {
            conversationDao.getGlobalOnce().takeLast(maxMessages)
        }
    }

    /**
     * Cari memori secara hybrid (keyword + vector).
     * 
     * @param query Teks query.
     * @param queryVector Embedding query.
     * @param characterId ID karakter (null = global).
     * @param topK Jumlah hasil vektor.
     * @return Daftar hasil terurut.
     */
    suspend fun hybridSearch(
        query: String,
        queryVector: FloatArray,
        characterId: String?,
        topK: Int = 5
    ): List<MemorySearchResult> {
        val results = mutableListOf<MemorySearchResult>()
        val vectorMatchIds = mutableSetOf<String>()

        // 1. Vector search (semantik)
        val vectorResults = vectorStore.search(queryVector, topK)
        for (vr in vectorResults) {
            val entry = conversationDao.getById(vr.roomRefId)
            if (entry != null) {
                // Filter by scope
                if (characterId != null && entry.characterId != characterId) continue
                results.add(MemorySearchResult(entry, vr.score, MemorySearchResult.MatchType.VECTOR))
                vectorMatchIds.add(entry.id)
            }
        }

        // 2. Keyword search (Room LIKE) — hanya jika query > 2 char
        if (query.length > 2) {
            val allEntries = if (characterId != null) {
                conversationDao.getByCharacterOnce(characterId)
            } else {
                conversationDao.getGlobalOnce()
            }
            val queryLower = query.lowercase()
            for (entry in allEntries) {
                if (entry.content.lowercase().contains(queryLower)) {
                    val matchType = if (entry.id in vectorMatchIds) {
                        MemorySearchResult.MatchType.BOTH
                    } else {
                        MemorySearchResult.MatchType.KEYWORD
                    }
                    if (results.none { it.entry.id == entry.id }) {
                        results.add(MemorySearchResult(entry, null, matchType))
                    }
                }
            }
        }

        // Sort: BOTH > VECTOR > KEYWORD, lalu by score
        return results.sortedWith(
            compareByDescending<MemorySearchResult> { it.matchedBy == MemorySearchResult.MatchType.BOTH }
                .thenByDescending { it.matchedBy == MemorySearchResult.MatchType.VECTOR }
                .thenByDescending { it.vectorScore ?: 0f }
        ).take(topK)
    }

    /**
     * Observe semua percakapan per karakter (Flow).
     */
    fun observeConversations(characterId: String?): Flow<List<ConversationEntry>> {
        return if (characterId != null) {
            conversationDao.getByCharacter(characterId)
        } else {
            conversationDao.getGlobal()
        }
    }

    /**
     * Hapus semua percakapan karakter.
     */
    suspend fun clearCharacterMemory(characterId: String) {
        // Hapus dari ObjectBox juga
        val entries = conversationDao.getByCharacterOnce(characterId)
        for (entry in entries) {
            vectorStore.deleteByRoomRef(entry.id)
        }
        conversationDao.deleteByCharacter(characterId)
    }

    // ==================== Data Classes ====================

    data class ConversationEmbeddingItem(
        val characterId: String?,
        val role: MessageRole,
        val content: String,
        val embedding: FloatArray,
        val tokenCount: Int? = null,
        val sourceType: VectorSourceType = VectorSourceType.CONVERSATION
    )
}
