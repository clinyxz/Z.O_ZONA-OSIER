/**
 * ZONA-OSIER — FilteredToolRegistry (v5.0).
 * Layer Character Policy: memfilter tool berdasarkan ToolPolicy karakter aktif.
 * Menggunakan Delegation Pattern (ToolRegistry by base).
 */
package com.zonaosier.agent.impl

import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolCall
import com.zonaosier.agent.ToolRegistry
import com.zonaosier.agent.ToolResult
import com.zonaosier.memory.entity.ToolPolicy

/**
 * Wrapper di atas ToolRegistry yang memfilter tool berdasarkan kebijakan karakter.
 * Karakter dengan allowShell=false tidak akan pernah melihat tool shell,
 * meski pengguna secara global telah mengizinkan Shizuku.
 *
 * Urutan lapisan keamanan:
 * 1. Input Sanitization (hapus ;|&$) — di AgentLoop.sanitizeToolCall()
 * 2. Argument-level Policy (ShellSecurityPolicy array-only) — di ToolSecurityExecutor
 * 3. Character Tool Filter (FilteredToolRegistry) — THIS CLASS
 * 4. Voice-Print Pre-Check (Picovoice Eagle)
 * 5. Biometric Prompt (operasi destruktif)
 * 6. Audit Log (Room DB)
 */
class FilteredToolRegistry(
    private val base: ToolRegistry,
    private val policy: ToolPolicy
) : ToolRegistry {

    /**
     * Set tool yang diizinkan berdasarkan policy karakter.
     * Lazy agar hanya dihitung sekali per karakter aktif.
     */
    private val allowedTools: Set<String> by lazy {
        val tools = mutableSetOf<String>()

        // Tool non-destruktif selalu tersedia
        tools.addAll(
            listOf(
                "screen_read",
                "personality_extract",
                "web_fetch",
                "face_recognize",
                "memory_search",
                "memory_store",
                "set_alarm",
                "create_calendar_event"
            )
        )

        // Shell-related tools — hanya jika allowShell
        if (policy.allowShell) {
            tools.addAll(
                listOf(
                    "termux_exec",
                    "shizuku_shell",
                    "shizuku_tap",
                    "shizuku_screenshot"
                )
            )
        }

        // SMS
        if (policy.allowSms) {
            tools.add("send_sms")
        }

        // Phone Call
        if (policy.allowCall) {
            tools.add("place_call")
        }

        // Camera
        if (policy.allowCamera) {
            tools.add("camera_capture")
        }

        // Location
        if (policy.allowLocation) {
            tools.add("get_location")
        }

        // Web browsing
        if (policy.allowWeb) {
            // web_fetch sudah di default, tapi web_browse bisa ditambah
            tools.add("web_browse")
        }

        // Screen Read
        if (policy.allowScreenRead) {
            // screen_read sudah di default
        }

        // Memory
        if (policy.allowMemory) {
            // memory_search dan memory_store sudah di default
        }

        // Personality adjustment
        if (policy.allowPersonality) {
            tools.add("personality_adjust")
        }

        tools
    }

    override fun getTools(): List<Tool> =
        base.getTools().filter { it.name in allowedTools }

    override fun getTool(name: String): Tool? {
        if (name !in allowedTools) return null
        return base.getTool(name)
    }

    override fun execute(toolCall: ToolCall): ToolResult {
        val tool = getTool(toolCall.name)
            ?: return ToolResult.Error(
                "Tool '${toolCall.name}' tidak diizinkan oleh kebijakan karakter ini."
            )
        return base.execute(toolCall)
    }

    override fun getToolDefinitions(): List<Map<String, Any>> =
        getTools().map { tool ->
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
 * Extension function untuk membuat FilteredToolRegistry dari ToolRegistry.
 */
fun ToolRegistry.filterByPolicy(policy: ToolPolicy): FilteredToolRegistry =
    FilteredToolRegistry(this, policy)
