/**
 * ZONA-OSIER — Tipe data inti Agent System.
 * Message, ToolCall, ToolResult, AgentEvent, dan ModelClient interface.
 */
package com.zonaosier.agent

import kotlinx.coroutines.flow.Flow

// ==================== Message ====================

/**
 * Pesan dalam percakapan agent.
 * Digunakan oleh AgentLoop untuk membangun konteks ke LLM.
 */
data class Message(
    val role: String,    // "system", "user", "assistant", "tool"
    val content: String,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
)

// ==================== ToolCall ====================

/**
 * Representasi tool call dari respons LLM.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any>
)

// ==================== ToolResult ====================

/**
 * Hasil eksekusi tool.
 */
sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Error(val message: String) : ToolResult()
}

// ==================== AgentEvent ====================

/**
 * Event yang di-emit oleh AgentLoop selama eksekusi.
 * UI berlangganan Flow ini untuk menampilkan state secara real-time.
 */
sealed class AgentEvent {
    /** Agent sedang memproses (thinking). */
    data object Thinking : AgentEvent()

    /** LLM mengembalikan tool calls — sebelum dieksekusi. */
    data class ToolCallsDetected(val calls: List<ToolCall>) : AgentEvent()

    /** Hasil eksekusi tool (satu per tool call). */
    data class ToolResults(val results: List<ToolResult>) : AgentEvent()

    /** Respons akhir dari LLM (text, bukan tool call). */
    data class Response(val text: String) : AgentEvent()

    /** Error — termasuk max iterations. */
    data class Error(val message: String) : AgentEvent()

    /** Progress indikasi opsional (misal: chunk processing). */
    data class Progress(val message: String, val percent: Int? = null) : AgentEvent()
}

// ==================== ModelClient Interface ====================

/**
 * Abstraksi klien LLM.
 * Setiap provider (Groq, OpenRouter, Lokal, dll) mengimplementasikan interface ini.
 */
interface ModelClient {

    /**
     * Kirim percakapan dan dapatkan respons.
     * @param messages Daftar pesan (system, user, assistant, tool).
     * @return [ChatResponse] berisi text dan/atau tool calls.
     */
    suspend fun chat(messages: List<Message>): ChatResponse

    /**
     * Kirim percakapan dengan streaming.
     * @param messages Daftar pesan.
     * @return Flow token-by-token.
     */
    fun chatStream(messages: List<Message>): Flow<String>

    /** Nama provider untuk display. */
    val providerName: String

    /** Model ID yang sedang digunakan. */
    val modelId: String

    /** Apakah client ini siap digunakan. */
    val isAvailable: Boolean
}

/**
 * Respons dari LLM.
 */
data class ChatResponse(
    /** Teks respons (null jika hanya tool calls). */
    val text: String? = null,

    /** Tool calls yang diminta LLM (null jika hanya text). */
    val toolCalls: List<ToolCall>? = null,

    /** Token usage (jika tersedia dari provider). */
    val usage: TokenUsage? = null,

    /** Latensi total dalam milidetik. */
    val latencyMs: Long = 0L
) {
    fun hasToolCalls(): Boolean = !toolCalls.isNullOrEmpty()
}

/**
 * Token usage dari respons provider.
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

// ==================== Tool Interface ====================

/**
 * Interface dasar untuk semua tool yang bisa dipanggil agent.
 */
interface Tool {
    /** Nama unik tool (digunakan dalam function calling LLM). */
    val name: String

    /** Deskripsi tool (diberikan ke LLM sebagai function description). */
    val description: String

    /** Parameter schema JSON untuk function calling. */
    val parameters: String

    /** Apakah tool ini bersifat destruktif (butuh biometric). */
    val isDestructive: Boolean

    /** Apakah tool ini butuh verifikasi biometrik. */
    val requiresBiometric: Boolean

    /**
     * Eksekusi tool.
     * @param args Argumen dari LLM tool call.
     * @return [ToolResult] success atau error.
     */
    suspend fun execute(args: Map<String, Any>): ToolResult
}

// ==================== ToolRegistry Interface ====================

/**
 * Registry untuk semua tool yang tersedia.
 * Di-wrap oleh [FilteredToolRegistry] untuk policy per karakter.
 */
interface ToolRegistry {

    /** Daftar semua tool yang terdaftar. */
    fun getTools(): List<Tool>

    /** Cari tool by name. */
    fun getTool(name: String): Tool?

    /** Eksekusi tool call. */
    fun execute(toolCall: ToolCall): ToolResult

    /**
     * Ambil daftar tool sebagai format function calling untuk LLM.
     * Format: [{"type": "function", "function": {"name": ..., "description": ..., "parameters": ...}}]
     */
    fun getToolDefinitions(): List<Map<String, Any>>
}
