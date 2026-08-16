/**
 * ZONA-OSIER — AgentLoop.
 * Pola agent loop dengan iterasi tool-call, guard maxIterations,
 * dan injeksi system message dari karakter aktif.
 *
 * AgentLoop adalah inti reasoning agent:
 * 1. Terima input user
 * 2. Kirim ke LLM (via ModelClient)
 * 3. Jika LLM meminta tool call → eksekusi → kirim hasil ke LLM → ulang
 * 4. Jika LLM memberi text → emit sebagai Response
 * 5. Guard: maxIterations mencegah infinite loop
 */
package com.zonaosier.agent.impl

import com.zonaosier.agent.*
import com.zonaosier.security.AuditLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * @param modelClient Klien LLM (Groq, OpenRouter, Lokal, dll).
 * @param toolRegistry Registry tool yang sudah di-filter oleh karakter.
 * @param maxIterations Maksimal iterasi tool-call (default 10, 15 untuk LONG_DOCUMENT).
 * @param systemMessage System message dari CharacterOrchestrator (persona + contoh dialog).
 * @param conversationHistory Riwayat percakapan sebelumnya (sliding window).
 */
class AgentLoop(
    private val modelClient: ModelClient,
    private val toolRegistry: ToolRegistry,
    private val maxIterations: Int = 10,
    private val systemMessage: Message? = null,
    private val conversationHistory: List<Message> = emptyList()
) {

    companion object {
        private const val DANGEROUS_CHARS_REGEX = "[;|&\$`]"
        private const val MAX_TOOL_OUTPUT_LENGTH = 4000
    }

    /**
     * Jalankan agent loop.
     * @param userInput Input dari user (teks atau hasil STT).
     * @return Flow<AgentEvent> — UI berlangganan ini untuk real-time updates.
     */
    suspend fun run(userInput: String): Flow<AgentEvent> = flow {
        val conversation = mutableListOf<Message>()

        // Inject system message karakter jika ada
        if (systemMessage != null) {
            conversation.add(systemMessage)
        }

        // Tambahkan history percakapan sebelumnya (sliding window)
        conversation.addAll(conversationHistory)

        // Tambahkan input user saat ini
        conversation.add(Message(role = "user", content = userInput))

        var iteration = 0
        while (iteration < maxIterations) {
            emit(AgentEvent.Thinking)

            val startTime = System.currentTimeMillis()
            val response = try {
                modelClient.chat(conversation)
            } catch (e: Exception) {
                emit(AgentEvent.Error("LLM error: ${e.message}"))
                return@flow
            }
            val latency = System.currentTimeMillis() - startTime

            if (response.hasToolCalls()) {
                // LLM meminta tool calls
                val toolCalls = response.toolCalls!!
                emit(AgentEvent.ToolCallsDetected(toolCalls))

                // Eksekusi setiap tool call
                val results = toolCalls.map { toolCall ->
                    val sanitized = sanitizeToolCall(toolCall)
                    executeToolSafely(sanitized)
                }

                // Tambahkan assistant message dengan tool calls ke conversation
                conversation.add(
                    Message(
                        role = "assistant",
                        content = response.text ?: "",
                        toolCalls = toolCalls
                    )
                )

                // Tambahkan tool results ke conversation
                results.forEachIndexed { index, result ->
                    val outputText = when (result) {
                        is ToolResult.Success -> result.output.take(MAX_TOOL_OUTPUT_LENGTH)
                        is ToolResult.Error -> "Error: ${result.message}"
                    }
                    conversation.add(
                        Message(
                            role = "tool",
                            content = outputText,
                            toolCallId = toolCalls[index].id
                        )
                    )
                }

                emit(AgentEvent.ToolResults(results))

                // Log total tool execution time
                AuditLogger.log(
                    subsystem = "AgentLoop",
                    action = "tool_calls_batch",
                    result = "SUCCESS",
                    detail = "${toolCalls.size} tools executed in ${System.currentTimeMillis() - startTime}ms"
                )
            } else {
                // LLM memberi respons teks — selesai
                val text = response.text ?: "(respons kosong)"
                emit(AgentEvent.Response(text))
                break
            }

            iteration++
        }

        if (iteration >= maxIterations) {
            emit(
                AgentEvent.Error(
                    "Max iterations ($maxIterations) tercapai. " +
                    "Agent mungkin memerlukan lebih banyak langkah. " +
                    "Coba sederhanakan permintaan."
                )
            )
        }
    }

    /**
     * Jalankan agent loop dengan streaming response.
     * Menggunakan chatStream() untuk TTFA <800ms.
     */
    fun runStream(userInput: String): Flow<AgentEvent> = flow {
        val conversation = mutableListOf<Message>()

        if (systemMessage != null) {
            conversation.add(systemMessage)
        }
        conversation.addAll(conversationHistory)
        conversation.add(Message(role = "user", content = userInput))

        var iteration = 0
        while (iteration < maxIterations) {
            emit(AgentEvent.Thinking)

            if (iteration == 0) {
                // Iterasi pertama: coba streaming
                val responseBuilder = StringBuilder()
                val hasToolCalls = mutableListOf<Boolean>()
                hasToolCalls.add(false)

                modelClient.chatStream(conversation).collect { token ->
                    // TODO: Deteksi tool call dalam stream — kompleks, perlu state machine
                    // Untuk sekarang, kumpulkan token dulu
                    responseBuilder.append(token)
                }

                val fullResponse = responseBuilder.toString()
                if (fullResponse.isNotBlank()) {
                    emit(AgentEvent.Response(fullResponse))
                    break
                }
            } else {
                // Iterasi tool: gunakan non-streaming untuk parsing tool call
                val response = try {
                    modelClient.chat(conversation)
                } catch (e: Exception) {
                    emit(AgentEvent.Error("LLM error: ${e.message}"))
                    return@flow
                }

                if (response.hasToolCalls()) {
                    val toolCalls = response.toolCalls!!
                    emit(AgentEvent.ToolCallsDetected(toolCalls))

                    val results = toolCalls.map { toolCall ->
                        executeToolSafely(sanitizeToolCall(toolCall))
                    }

                    conversation.add(
                        Message(
                            role = "assistant",
                            content = response.text ?: "",
                            toolCalls = toolCalls
                        )
                    )

                    results.forEachIndexed { index, result ->
                        val outputText = when (result) {
                            is ToolResult.Success -> result.output.take(MAX_TOOL_OUTPUT_LENGTH)
                            is ToolResult.Error -> "Error: ${result.message}"
                        }
                        conversation.add(
                            Message(
                                role = "tool",
                                content = outputText,
                                toolCallId = toolCalls[index].id
                            )
                        )
                    }

                    emit(AgentEvent.ToolResults(results))
                } else {
                    emit(AgentEvent.Response(response.text ?: "(respons kosong)"))
                    break
                }
            }

            iteration++
        }

        if (iteration >= maxIterations) {
            emit(AgentEvent.Error("Max iterations ($maxIterations) tercapai."))
        }
    }

    /**
     * Layer 1: Sanitasi dasar tool call.
     * Hapus karakter berbahaya untuk mencegah shell injection dasar
     * sebelum kebijakan Layer 1-2 dieksekusi.
     */
    private fun sanitizeToolCall(toolCall: ToolCall): ToolCall {
        return toolCall.copy(
            arguments = toolCall.arguments.mapValues { (_, value) ->
                value.toString().replace(Regex(DANGEROUS_CHARS_REGEX), "")
            }
        )
    }

    /**
     * Eksekusi tool dengan error handling.
     * Jika registry adalah ToolRegistryImpl, gunakan executeSuspend().
     */
    private suspend fun executeToolSafely(toolCall: ToolCall): ToolResult {
        return try {
            if (toolRegistry is ToolRegistryImpl) {
                toolRegistry.executeSuspend(toolCall)
            } else {
                toolRegistry.execute(toolCall)
            }
        } catch (e: Exception) {
            ToolResult.Error("Tool execution failed: ${e.message}")
        }
    }
}
