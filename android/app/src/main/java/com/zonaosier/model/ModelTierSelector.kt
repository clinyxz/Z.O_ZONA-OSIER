/**
 * ZONA-OSIER — ModelTierSelector.
 * Menentukan tier model lokal berdasarkan kapasitas hardware.
 *
 * Tiers:
 * - ADHI (Besar): 7B Q4, butuh ~4.5GB RAM, CPU I8MM, 5-15 tok/s
 * - MADYA (Sedang): 2-3B Q4, butuh ~2GB RAM, CPU I8MM, 15-30 tok/s
 * - ALIT (Kecil): 0.5-1B Q4, butuh ~0.5GB RAM, CPU I8MM, 30-60 tok/s
 * - AUTO: Pilih otomatis berdasarkan baterai/termal/RAM
 *
 * ⚠️ CPU I8MM (5-15 tok/s) adalah default jalur produksi.
 * OpenCL GPU (0.5-5 tok/s) hanya sebagai fallback eksperimental.
 */
package com.zonaosier.model

import android.content.Context
import android.os.Build
import com.zonaosier.governor.BatteryThermalGovernor
import com.zonaosier.memory.entity.ModelMode
import java.io.File

data class ModelTier(
    val mode: ModelMode,
    val displayName: String,
    val modelSize: String,
    val estimatedRamMB: Int,
    val estimatedTokensPerSec: Float,
    val quantization: String
) {
    val filePath: String
        get() = when (mode) {
            ModelMode.LOCAL_ADHI -> "models/adhi-7b-q4.gguf"
            ModelMode.LOCAL_MADYA -> "models/madya-3b-q4.gguf"
            ModelMode.LOCAL_ALIT -> "models/alit-1b-q4.gguf"
            else -> ""
        }
}

class ModelTierSelector(
    private val context: Context,
    private val governor: BatteryThermalGovernor,
    private val modelsDir: File = File(context.filesDir, "models")
) {
    companion object {
        // Threshold RAM untuk tier selection (free RAM, bukan total)
        private const val RAM_THRESHOLD_ADHI_MB = 5000L  // Butuh ~5GB free
        private const val RAM_THRESHOLD_MADYA_MB = 2500L // Butuh ~2.5GB free

        // GPU Backend detection
        const val GPU_BACKEND_OPENCL = "opencl"
        const val GPU_BACKEND_NONE = "cpu"
    }

    /** Tier yang sedang aktif/dipilih. */
    @Volatile
    private var activeTier: ModelMode = ModelMode.AUTO_TIER

    /** Model file yang sedang di-load. */
    @Volatile
    private var loadedModelPath: String? = null

    /**
     * Pilih tier otomatis berdasarkan kondisi device saat ini.
     */
    fun selectAutoTier(): ModelMode {
        // 1. Cek thermal — jika severe, turun ke Alit
        if (governor.isThermalThrottling()) {
            return ModelMode.LOCAL_ALIT
        }

        // 2. Cek battery saver
        if (governor.isBatterySaver()) {
            return ModelMode.LOCAL_ALIT
        }

        // 3. Cek battery low
        if (governor.isBatteryLow()) {
            return ModelMode.LOCAL_ALIT
        }

        // 4. Cek thermal moderate
        if (governor.isThermalModerateOrAbove()) {
            return ModelMode.LOCAL_MADYA
        }

        // 5. Cek RAM tersedia
        val freeRam = getFreeRamMB()
        return when {
            freeRam >= RAM_THRESHOLD_ADHI_MB -> ModelMode.LOCAL_ADHI
            freeRam >= RAM_THRESHOLD_MADYA_MB -> ModelMode.LOCAL_MADYA
            else -> ModelMode.LOCAL_ALIT
        }
    }

    /**
     * Resolve tier — jika AUTO, pilih otomatis.
     */
    fun resolveTier(requested: ModelMode): ModelMode {
        return if (requested == ModelMode.AUTO_TIER) {
            selectAutoTier().also { activeTier = it }
        } else {
            activeTier = requested
            requested
        }
    }

    /**
     * Preload model untuk tier tertentu.
     * Dipanggil saat karakter di-activate.
     */
    fun preloadForTier(mode: ModelMode) {
        val tier = resolveTier(mode)
        val tierInfo = getTierInfo(tier)
        val modelFile = File(modelsDir, tierInfo.filePath.removePrefix("models/"))

        if (!modelFile.exists()) {
            // Model belum diunduh
            return
        }

        // TODO: Implementasi model loading via llama.cpp / MLC-LLM
        // Ini hanya menandai model sebagai "loaded"
        loadedModelPath = modelFile.absolutePath
    }

    /**
     * Cek apakah model sudah di-load.
     */
    fun isModelLoaded(): Boolean = loadedModelPath != null

    /**
     * Dapatkan path model yang sudah di-load.
     */
    fun getLoadedModelPath(): String? = loadedModelPath

    /**
     * Unload model (bebaskan RAM).
     */
    fun unloadModel() {
        loadedModelPath = null
    }

    /**
     * Dapatkan info tier.
     */
    fun getTierInfo(mode: ModelMode): ModelTier {
        return when (mode) {
            ModelMode.LOCAL_ADHI -> ModelTier(
                mode = ModelMode.LOCAL_ADHI,
                displayName = "Adhi (7B Q4)",
                modelSize = "7B parameters",
                estimatedRamMB = 4500,
                estimatedTokensPerSec = 8f,
                quantization = "Q4_K_M"
            )
            ModelMode.LOCAL_MADYA -> ModelTier(
                mode = ModelMode.LOCAL_MADYA,
                displayName = "Madya (3B Q4)",
                modelSize = "3B parameters",
                estimatedRamMB = 2000,
                estimatedTokensPerSec = 20f,
                quantization = "Q4_K_M"
            )
            ModelMode.LOCAL_ALIT -> ModelTier(
                mode = ModelMode.LOCAL_ALIT,
                displayName = "Alit (1B Q4)",
                modelSize = "1B parameters",
                estimatedRamMB = 600,
                estimatedTokensPerSec = 40f,
                quantization = "Q4_K_M"
            )
            else -> ModelTier(
                mode = ModelMode.AUTO_TIER,
                displayName = "Auto",
                modelSize = "Auto",
                estimatedRamMB = 0,
                estimatedTokensPerSec = 0f,
                quantization = "-"
            )
        }
    }

    /**
     * Cek apakah GPU backend tersedia.
     */
    fun detectGpuBackend(): String {
        // TODO: Implementasi deteksi OpenCL/Vulkan
        // Untuk sekarang, default ke CPU (I8MM lebih cepat dari OpenCL di Snapdragon)
        return GPU_BACKEND_NONE
    }

    /**
     * Dapatkan free RAM dalam MB.
     */
    private fun getFreeRamMB(): Long {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMem = runtime.totalMemory() - runtime.freeMemory()
            val maxMem = runtime.maxMemory()
            // Free RAM dari perspektif process
            (maxMem - usedMem) / (1024 * 1024)
        } catch (e: Exception) {
            2048L // Fallback 2GB
        }
    }

    /**
     * Daftar semua tier yang tersedia.
     */
    fun getAvailableTiers(): List<ModelTier> {
        return listOf(
            getTierInfo(ModelMode.LOCAL_ADHI),
            getTierInfo(ModelMode.LOCAL_MADYA),
            getTierInfo(ModelMode.LOCAL_ALIT)
        ).filter { tier ->
            val modelFile = File(modelsDir, tier.filePath.removePrefix("models/"))
            modelFile.exists()
        }
    }
}