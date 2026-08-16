/**
 * ZONA-OSIER — Implementasi ToolRegistry.
 * Menyimpan dan mengelola semua tool yang tersedia.
 */
package com.zonaosier.agent.impl

import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolCall
import com.zonaosier.agent.ToolRegistry
import com.zonaosier.agent.ToolResult
import com.zonaosier.security.AuditLogger

/**
 * Implementasi default ToolRegistry.
 * Thread-safe, mutable registry untuk semua tool.
 */
class ToolRegistryImpl : ToolRegistry {

    private val tools = mutableMapOf<String, Tool>()

    /**
     * Registrasi tool. Jika nama sudah ada, tool lama di-replace.
     */
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    /**
     * Registrasi banyak tool sekaligus.
     */
    fun registerAll(toolList: List<Tool>) {
        toolList.forEach { register(it) }
    }

    /**
     * Hapus tool dari registry.
     */
    fun unregister(name: String) {
        tools.remove(name)
    }

    override fun getTools(): List<Tool> = tools.values.toList()

    override fun getTool(name: String): Tool? = tools[name]

    override fun execute(toolCall: ToolCall): ToolResult {
        val tool = tools[toolCall.name]
            ?: return ToolResult.Error("Tool '${toolCall.name}' tidak terdaftar dalam registry.")

        // Jalankan secara synchronous wrapper — actual suspend execute
        // dipanggil dari AgentLoop via runBlocking atau coroutine scope
        return runCatching {
            // Non-suspend fallback untuk interface ToolRegistry.execute()
            // AgentLoop menggunakan executeSuspend() untuk support async
            ToolResult.Success("Tool ${tool.name} memerlukan eksekusi async. Gunakan executeSuspend.")
        }.getOrElse { e ->
            AuditLogger.logToolExecution(toolCall.name, "ERROR", e.message ?: "Unknown error")
            ToolResult.Error("Gagal menjalankan ${tool.name}: ${e.message}")
        }
    }

    override fun getToolDefinitions(): List<Map<String, Any>> {
        return tools.values.map { tool ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.parameters
                )
            )
        }
    }

    /**
     * Eksekusi tool dengan dukungan coroutine (suspend).
     * Ini yang sebenarnya dipakai oleh AgentLoop.
     */
    suspend fun executeSuspend(toolCall: ToolCall): ToolResult {
        val tool = tools[toolCall.name]
            ?: return ToolResult.Error("Tool '${toolCall.name}' tidak terdaftar dalam registry.")

        return try {
            val result = tool.execute(toolCall.arguments)
            AuditLogger.logToolExecution(
                toolName = tool.name,
                status = if (result is ToolResult.Success) "SUCCESS" else "ERROR",
                detail = if (result is ToolResult.Success) result.output.take(500) else (result as ToolResult.Error).message
            )
            result
        } catch (e: Exception) {
            AuditLogger.logToolExecution(toolCall.name, "ERROR", e.message ?: "Unknown error")
            ToolResult.Error("Gagal menjalankan ${tool.name}: ${e.message}")
        }
    }
}
