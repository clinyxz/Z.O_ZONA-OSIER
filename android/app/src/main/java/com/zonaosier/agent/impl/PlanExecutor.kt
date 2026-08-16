/**
 * ZONA-OSIER — PlanExecutor.
 * Menjalankan rencana multi-langkah yang dihasilkan oleh SystemThinker.
 *
 * SystemThinker bisa menghasilkan rencana seperti:
 * 1. Ambil screenshot layar
 * 2. Analisis screenshot
 * 3. Tap tombol berdasarkan analisis
 * 4. Verifikasi hasil
 *
 * PlanExecutor mengeksekusi langkah-langkah ini secara berurutan,
 * dengan checkpoint setiap langkah.
 */
package com.zonaosier.agent.impl

import com.zonaosier.agent.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Satu langkah dalam rencana.
 */
data class PlanStep(
    val stepNumber: Int,
    val description: String,
    val toolName: String,
    val arguments: Map<String, Any>,
    val expectedResult: String? = null,
    val retryOnFailure: Boolean = false,
    val maxRetries: Int = 1
)

/**
 * Status eksekusi plan.
 */
enum class PlanStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PARTIAL
}

/**
 * Hasil eksekusi satu plan.
 */
data class PlanResult(
    val status: PlanStatus,
    val totalSteps: Int,
    val completedSteps: Int,
    val results: List<ToolResult>,
    val errorMessage: String? = null
)

class PlanExecutor(
    private val toolRegistry: com.zonaosier.agent.ToolRegistry
) {
    companion object {
        private const val MAX_PLAN_STEPS = 20
        private const val STEP_TIMEOUT_MS = 30_000L
    }

    /**
     * Jalankan serangkaian plan steps.
     * @return Flow<AgentEvent> untuk real-time progress.
     */
    suspend fun execute(steps: List<PlanStep>): Flow<AgentEvent> = flow {
        if (steps.size > MAX_PLAN_STEPS) {
            emit(AgentEvent.Error("Plan terlalu panjang: ${steps.size} langkah (maks $MAX_PLAN_STEPS)"))
            return@flow
        }

        val results = mutableListOf<ToolResult>()
        var completed = 0

        for ((index, step) in steps.withIndex()) {
            // Cek cancelled
            emit(AgentEvent.Progress(
                message = "Langkah ${step.stepNumber}/${steps.size}: ${step.description}",
                percent = ((index + 1) * 100) / steps.size
            ))

            var attempt = 0
            var success = false

            while (attempt <= step.maxRetries && !success) {
                attempt++
                try {
                    val toolCall = ToolCall(
                        id = "plan_${step.stepNumber}_attempt_$attempt",
                        name = step.toolName,
                        arguments = step.arguments
                    )

                    val result = if (toolRegistry is ToolRegistryImpl) {
                        toolRegistry.executeSuspend(toolCall)
                    } else {
                        toolRegistry.execute(toolCall)
                    }

                    when (result) {
                        is ToolResult.Success -> {
                            results.add(result)
                            completed++
                            success = true
                        }
                        is ToolResult.Error -> {
                            if (attempt > step.maxRetries || !step.retryOnFailure) {
                                results.add(result)
                                emit(AgentEvent.Error(
                                    "Gagal di langkah ${step.stepNumber}: ${result.message}"
                                ))
                                return@flow
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (attempt > step.maxRetries) {
                        emit(AgentEvent.Error("Timeout di langkah ${step.stepNumber}: ${e.message}"))
                        return@flow
                    }
                }
            }
        }

        emit(AgentEvent.Response(
            "Plan selesai: $completed/${steps.size} langkah berhasil."
        ))
    }

    /**
     * Parse rencana dari output LLM (JSON array of steps).
     */
    fun parsePlanFromLLM(llmOutput: String): List<PlanStep> {
        // Coba parse JSON array dari output LLM
        return try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(llmOutput)
            val array = json.jsonArray
            array.mapIndexed { index, element ->
                val obj = element.jsonObject
                PlanStep(
                    stepNumber = index + 1,
                    description = obj["description"]?.jsonPrimitive?.content ?: "Step ${index + 1}",
                    toolName = obj["tool"]?.jsonPrimitive?.content ?: "",
                    arguments = obj["arguments"]?.let { args ->
                        // Parse arguments map
                        val map = mutableMapOf<String, Any>()
                        args.jsonObject.forEach { (key, value) ->
                            map[key] = value.jsonPrimitive.content
                        }
                        map
                    } ?: emptyMap(),
                    expectedResult = obj["expected"]?.jsonPrimitive?.content,
                    retryOnFailure = obj["retry"]?.jsonPrimitive?.booleanOrNull ?: false,
                    maxRetries = obj["max_retries"]?.jsonPrimitive?.intOrNull ?: 1
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// Import untuk parsePlanFromLLM
import kotlinx.serialization.json.*
