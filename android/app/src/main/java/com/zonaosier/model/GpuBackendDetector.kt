/**
 * ZONA-OSIER — GpuBackendDetector.
 * Mendeteksi ketersediaan GPU backend (OpenCL/Vulkan).
 *
 * ⚠️ Catatan kritis (v5.1.2):
 * Benchmark Reddit r/LocalLLaMA (Feb 2026) pada Snapdragon:
 * - CPU I8MM (ngl 0): 5-15 tok/s ← FAST
 * - GPU OpenCL (ngl 33): 0.5-5 tok/s ← 10x SLOWER!
 *
 * Kesimpulan: CPU I8MM adalah default produksi.
 * OpenCL GPU hanya fallback eksperimental.
 */
package com.zonaosier.model

import android.os.Build

data class GpuInfo(
    val backend: String,       // "cpu", "opencl", "vulkan"
    val deviceName: String?,
    val driverVersion: String?,
    val recommended: Boolean   // Apakah direkomendasikan untuk produksi?
)

class GpuBackendDetector {

    companion object {
        const val BACKEND_CPU = "cpu"
        const val BACKEND_OPENCL = "opencl"
        const val BACKEND_VULKAN = "vulkan"
    }

    /**
     * Deteksi GPU backend yang tersedia.
     */
    fun detect(): GpuInfo {
        // Cek Vulkan (lebih modern, lebih cepat dari OpenCL untuk inference)
        val hasVulkan = hasVulkanSupport()

        // Cek OpenCL
        val hasOpenCL = hasOpenCLSupport()

        return when {
            hasVulkan -> GpuInfo(
                backend = BACKEND_VULKAN,
                deviceName = getGpuName(),
                driverVersion = null,
                recommended = false // Vulkan belum stabil untuk llama.cpp Android
            )
            hasOpenCL -> GpuInfo(
                backend = BACKEND_OPENCL,
                deviceName = getGpuName(),
                driverVersion = null,
                recommended = false // OpenCL 10x slower di Snapdragon!
            )
            else -> GpuInfo(
                backend = BACKEND_CPU,
                deviceName = null,
                driverVersion = null,
                recommended = true // CPU I8MM = default produksi
            )
        }
    }

    /**
     * Cek apakah Vulkan tersedia.
     */
    private fun hasVulkanSupport(): Boolean {
        return try {
            // Cek apakah device punya Vulkan 1.0+
            val activityManager = Class.forName("android.app.ActivityManager")
                .getMethod("isVulkanSupported")
            false // Akan diimplementasikan dengan context
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Cek apakah OpenCL tersedia.
     */
    private fun hasOpenCLSupport(): Boolean {
        return try {
            // Cek /system/lib/libOpenCL.so atau libOpenCL.so
            val paths = listOf(
                "/system/lib/libOpenCL.so",
                "/system/lib64/libOpenCL.so",
                "/vendor/lib/libOpenCL.so",
                "/vendor/lib64/libOpenCL.so"
            )
            paths.any { java.io.File(it).exists() }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ambil nama GPU.
     */
    private fun getGpuName(): String? {
        return try {
            val props = java.util.Properties()
            java.io.FileInputStream("/system/build.prop").use { ps ->
                props.load(ps)
            }
            props.getProperty("ro.gfx.driver.0")
                ?: props.getProperty("ro.hardware.vulkan")
                ?: Build.HARDWARE
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Rekomendasi backend untuk model tertentu.
     */
    fun recommendBackend(modelSizeBillion: Float): String {
        val gpu = detect()

        // Untuk model besar (>3B), GPU mungkin membantu jika tersedia
        // Tapi benchmark menunjukkan CPU lebih cepat di Snapdragon
        // Jadi selalu rekomendasikan CPU (I8MM)
        return BACKEND_CPU
    }
}
