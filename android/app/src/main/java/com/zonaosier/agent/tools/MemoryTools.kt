/**
 * ZONA-OSIER — Memory Search & Store Tools.
 * Pencarian dan penyimpanan memori personal.
 *
 * MemorySearchTool: Pencarian keyword fallback (vector search
 *   memerlukan embedding yang belum tersedia di semua path).
 * MemoryStoreTool: Simpan memori percakapan ke Room.
 */
package com.zonaosier.agent.tools

import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import com.zonaosier.memory.MemoryRepository
import com.zonaosier.memory.entity.MessageRole

/**
 * Cari memori personal.
 * Menggunakan getRecentContext sebagai keyword fallback.
 * Vector search via hybridSearch memerlukan embedding.
 */
class MemorySearchTool(
    private val memoryRepository: MemoryRepository,
    private val activeCharacterId: String? = null
) : Tool {

    override val name: String = "memory_search"
    override val description: String =
        "Cari memori personal pengguna. " +
        "Argumen: 'query' (string, pencarian keyword). Mengembalikan hasil terkait."
    override val parameters: String = """
        {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Pencarian keyword"},
                "limit": {"type": "integer", "description": "Maksimal hasil (default 5)"}
            },
            "required": ["query"]
        }
    """.trimIndent()
    override val isDestructive: Boolean = false
    override val requiresBiometric: Boolean = false

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val query = args["query"]?.toString()?.trim()
            ?: return ToolResult.Error("Argumen 'query' wajib diisi.")
        val limit = (args["limit"] as? Number)?.toInt() ?: 5

        return try {
            // Ambil recent context dan filter by query keyword
            val allEntries = memoryRepository.getRecentContext(
                characterId = activeCharacterId,
                maxMessages = 50
            )

            val queryLower = query.lowercase()
            val matched = allEntries
                .filter { it.content.lowercase().contains(queryLower) }
                .take(limit)

            if (matched.isEmpty()) {
                ToolResult.Success("Tidak ditemukan memori terkait '$query'.")
            } else {
                val formatted = matched.joinToString("\n---\n") { entry ->
                    "[${entry.role}] ${entry.content}"
                }
                ToolResult.Success(formatted)
            }
        } catch (e: Exception) {
            ToolResult.Error("Gagal mencari memori: ${e.message}")
        }
    }
}

/**
 * Simpan memori personal ke Room.
 */
class MemoryStoreTool(
    private val memoryRepository: MemoryRepository,
    private val activeCharacterId: String? = null
) : Tool {

    override val name: String = "memory_store"
    override val description: String =
        "Simpan informasi ke memori personal. " +
        "Argumen: 'content' (string, informasi yang disimpan), " +
        "'category' (string opsional, kategori memori)."
    override val parameters: String = """
        {
            "type": "object",
            "properties": {
                "content": {"type": "string", "description": "Informasi yang disimpan"},
                "category": {"type": "string", "description": "Kategori (opsional)"}
            },
            "required": ["content"]
        }
    """.trimIndent()
    override val isDestructive: Boolean = false
    override val requiresBiometric: Boolean = false

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val content = args["content"]?.toString()?.trim()
            ?: return ToolResult.Error("Argumen 'content' wajib diisi.")
        val category = args["category"]?.toString()?.trim()

        if (content.length < 3) {
            return ToolResult.Error("Konten terlalu pendek (min 3 karakter).")
        }
        if (content.length > 5000) {
            return ToolResult.Error("Konten terlalu panjang (max 5000 karakter).")
        }

        return try {
            val entry = memoryRepository.saveConversation(
                characterId = activeCharacterId,
                role = MessageRole.ASSISTANT,
                content = "[MEMORY]${if (!category.isNullOrBlank()) " [$category]" else ""} $content"
            )
            ToolResult.Success("Memori disimpan (id: ${entry.id}): ${content.take(100)}")
        } catch (e: Exception) {
            ToolResult.Error("Gagal menyimpan memori: ${e.message}")
        }
    }
}
