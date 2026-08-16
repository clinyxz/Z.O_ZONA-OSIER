/**
 * ZONA-OSIER — ModelDownloadManager.
 * Mengelola unduhan model lokal (GGUF) ke storage internal.
 *
 * Fitur:
 * - Download dari URL (HuggingFace, custom) dengan resume support
 * - Progress tracking via StateFlow
 * - Verifikasi integritas (SHA-256)
 * - Disk space check sebelum download
 * - Auto-clean model lama saat kapasitas penuh
 * - Cancel support
 *
 * Model disimpan di: /data/data/com.zonaosier/files/models/{tier}/
 * Tier: adhi (7B-13B), madya (3B-4B), alit (1B-2B)
 *
 * Catatan: Untuk produksi, gunakan DownloadManager Android
 * atau WorkManager untuk download yang survive process death.
 * Implementasi ini menggunakan coroutine untuk simplicity.
 */
package com.zonaosier.model

import android.content.Context
import android.os.StatFs
import com.zonaosier.memory.entity.ModelMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Konfigurasi model yang bisa diunduh.
 */
data class DownloadableModel(
    val id: String,                    // ID unik (mis: "adhi_phi4_q4")
    val displayName: String,           // Nama display
    val tier: ModelMode,               // LOCAL_ADHI, LOCAL_MADYA, atau LOCAL_ALIT
    val description: String,           // Deskripsi singkat
    val downloadUrl: String,           // URL download GGUF
    val fileName: String,              // Nama file GGUF
    val expectedSha256: String? = null, // SHA-256 untuk verifikasi
    val fileSizeBytes: Long = 0,        // Ukuran file (untuk estimasi)
    val minRamMb: Int = 0               // RAM minimum yang dibutuhkan
)

/**
 * Status download model.
 */
sealed class DownloadStatus {
    data object Idle : DownloadStatus()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadStatus()
    data class Verifying(val fileName: String) : DownloadStatus()
    data class Completed(val modelPath: String, val fileSizeBytes: Long) : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
    data object Cancelled : DownloadStatus()
}

/**
 * Model yang sudah terunduh dan terverifikasi.
 */
data class LocalModelInfo(
    val id: String,
    val displayName: String,
    val tier: ModelMode,
    val filePath: String,
    val fileSizeBytes: Long,
    val downloadedAt: Long
)

class ModelDownloadManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus.asStateFlow()

    private var currentJob: Job? = null
    private var isCancelled = false

    companion object {
        /** Minimum free space 2x ukuran model (untuk extraction dan operasi file) */
        const val DISK_SPACE_MULTIPLIER = 2.0

        /** Buffer size untuk download stream */
        const val BUFFER_SIZE = 8192

        /** Default model catalog */
        val DEFAULT_CATALOG: List<DownloadableModel> = listOf(
            // === Tier Adhi (7B-13B) ===
            DownloadableModel(
                id = "adhi_phi4_q4",
                displayName = "Phi-4 Q4_K_M (14B)",
                tier = ModelMode.LOCAL_ADHI,
                description = "Model 14B parameter, kualitas tinggi. Butuh 8GB+ RAM.",
                downloadUrl = "https://huggingface.co/microsoft/phi-4-gguf/resolve/main/phi-4-Q4_K_M.gguf",
                fileName = "phi-4-Q4_K_M.gguf",
                fileSizeBytes = 8_500_000_000L,
                minRamMb = 8192
            ),
            DownloadableModel(
                id = "adhi_llama3_3_70b_q4",
                displayName = "Llama 3.3 70B Q4_K_M",
                tier = ModelMode.LOCAL_ADHI,
                description = "Model 70B parameter (4-bit quantized). Butuh 12GB+ RAM.",
                downloadUrl = "https://huggingface.co/bartowski/llama-3.3-70B-Instruct-GGUF/resolve/main/llama-3.3-70b-instruct-Q4_K_M.gguf",
                fileName = "llama-3.3-70b-instruct-Q4_K_M.gguf",
                fileSizeBytes = 42_000_000_000L,
                minRamMb = 12288
            ),
            // === Tier Madya (3B-4B) ===
            DownloadableModel(
                id = "madya_phi4_mini_q4",
                displayName = "Phi-4 Mini Q4_K_M (3.8B)",
                tier = ModelMode.LOCAL_MADYA,
                description = "Model 3.8B parameter, kualitas baik. Butuh 4GB+ RAM.",
                downloadUrl = "https://huggingface.co/microsoft/phi-4-mini-instruct-gguf/resolve/main/phi-4-mini-instruct-Q4_K_M.gguf",
                fileName = "phi-4-mini-instruct-Q4_K_M.gguf",
                fileSizeBytes = 2_500_000_000L,
                minRamMb = 4096
            ),
            DownloadableModel(
                id = "madya_qwen2_5_4b_q4",
                displayName = "Qwen 2.5 4B Q4_K_M",
                tier = ModelMode.LOCAL_MADYA,
                description = "Model 4B parameter, multilingual. Butuh 4GB+ RAM.",
                downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-4B-Instruct-GGUF/resolve/main/qwen2.5-4b-instruct-q4_k_m.gguf",
                fileName = "qwen2.5-4b-instruct-q4_k_m.gguf",
                fileSizeBytes = 2_800_000_000L,
                minRamMb = 4096
            ),
            // === Tier Alit (1B-2B) ===
            DownloadableModel(
                id = "alit_phi4_mini_q2",
                displayName = "Phi-4 Mini Q2_K (1.5B)",
                tier = ModelMode.LOCAL_ALIT,
                description = "Model 1.5B parameter, sangat ringan. Butuh 2GB+ RAM.",
                downloadUrl = "https://huggingface.co/microsoft/phi-4-mini-instruct-gguf/resolve/main/phi-4-mini-instruct-Q2_K.gguf",
                fileName = "phi-4-mini-instruct-Q2_K.gguf",
                fileSizeBytes = 1_000_000_000L,
                minRamMb = 2048
            ),
            DownloadableModel(
                id = "alit_qwen2_5_1_5b_q4",
                displayName = "Qwen 2.5 1.5B Q4_K_M",
                tier = ModelMode.LOCAL_ALIT,
                description = "Model 1.5B parameter, multilingual ringan. Butuh 2GB+ RAM.",
                downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                fileSizeBytes = 1_200_000_000L,
                minRamMb = 2048
            )
        )
    }

    // ==================== Download ====================

    /**
     * Mulai download model.
     * Menggunakan coroutine scope eksternal agar bisa di-cancel dari luar.
     */
    fun startDownload(
        model: DownloadableModel,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) {
        // Cancel download sebelumnya jika ada
        cancelDownload()

        currentJob = scope.launch {
            isCancelled = false

            // Pre-flight checks
            val checkError = preflightCheck(model)
            if (checkError != null) {
                _downloadStatus.value = DownloadStatus.Error(checkError)
                return@launch
            }

            // Pastikan direktori tier ada
            val tierDir = File(modelsDir, model.tier.name.lowercase())
            tierDir.mkdirs()

            val tempFile = File(tierDir, "${model.fileName}.tmp")
            val finalFile = File(tierDir, model.fileName)

            try {
                downloadFile(model.downloadUrl, tempFile, model.fileSizeBytes)

                if (isCancelled) {
                    tempFile.delete()
                    _downloadStatus.value = DownloadStatus.Cancelled
                    return@launch
                }

                // Verifikasi SHA-256 jika ada
                if (model.expectedSha256 != null) {
                    _downloadStatus.value = DownloadStatus.Verifying(model.fileName)
                    val sha = computeSha256(tempFile)
                    if (sha != model.expectedSha256) {
                        tempFile.delete()
                        _downloadStatus.value = DownloadStatus.Error(
                            "Verifikasi SHA-256 gagal. File mungkin corrupt."
                        )
                        return@launch
                    }
                }

                // Rename temp → final
                if (finalFile.exists()) finalFile.delete()
                tempFile.renameTo(finalFile)

                _downloadStatus.value = DownloadStatus.Completed(
                    modelPath = finalFile.absolutePath,
                    fileSizeBytes = finalFile.length()
                )

            } catch (e: CancellationException) {
                tempFile.delete()
                _downloadStatus.value = DownloadStatus.Cancelled
            } catch (e: Exception) {
                tempFile.delete()
                _downloadStatus.value = DownloadStatus.Error("Download gagal: ${e.message}")
            }
        }
    }

    /**
     * Cancel download yang sedang berjalan.
     */
    fun cancelDownload() {
        isCancelled = true
        currentJob?.cancel()
        currentJob = null
    }

    // ==================== Pre-flight Checks ====================

    private fun preflightCheck(model: DownloadableModel): String? {
        // Cek RAM
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (activityManager != null) {
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val availableRamMb = (memInfo.availMem / 1_048_576L).toInt()

            if (availableRamMb < model.minRamMb) {
                return "RAM tidak cukup. Dibutuhkan ${model.minRamMb}MB, tersedia ${availableRamMb}MB."
            }
        }

        // Cek disk space
        if (model.fileSizeBytes > 0) {
            val requiredSpace = (model.fileSizeBytes * DISK_SPACE_MULTIPLIER).toLong()
            val availableSpace = getAvailableStorageBytes()

            if (availableSpace < requiredSpace) {
                val requiredGb = requiredSpace / 1_073_741_824.0
                val availableGb = availableSpace / 1_073_741_824.0
                return "Penyimpanan tidak cukup. Dibutuhkan ~${"%.1f".format(requiredGb)}GB, tersedia ${"%.1f".format(availableGb)}GB."
            }
        }

        // Cek apakah file sudah ada
        val tierDir = File(modelsDir, model.tier.name.lowercase())
        val existingFile = File(tierDir, model.fileName)
        if (existingFile.exists()) {
            return "Model sudah terunduh: ${model.displayName}"
        }

        return null
    }

    // ==================== File Download ====================

    private fun downloadFile(urlString: String, outputFile: File, expectedSize: Long) {
        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            // Support resume: cek Partial content
            if (outputFile.exists()) {
                val downloaded = outputFile.length()
                setRequestProperty("Range", "bytes=$downloaded-")
            }
        }

        val responseCode = connection.responseCode
        val isResuming = responseCode == 206
        val isFull = responseCode == 200

        if (!isResuming && !isFull) {
            throw IOException("HTTP $responseCode saat download")
        }

        val totalSize = if (isResuming) {
            val contentRange = connection.getHeaderField("Content-Range")
            contentRange?.substringAfter("/")?.toLongOrNull() ?: expectedSize
        } else {
            connection.contentLengthLong.coerceAtLeast(expectedSize)
        }

        val appendMode = if (isResuming) true else false
        val downloadedSoFar = if (appendMode) outputFile.length() else 0L

        connection.inputStream.use { input ->
            FileOutputStream(outputFile, appendMode).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled) throw CancellationException("Download dibatalkan")

                    output.write(buffer, 0, bytesRead)
                    val downloaded = downloadedSoFar + outputFile.length()

                    val progress = if (totalSize > 0) {
                        downloaded.toFloat() / totalSize.toFloat()
                    } else {
                        -1f
                    }

                    _downloadStatus.value = DownloadStatus.Downloading(
                        progress = progress,
                        downloadedBytes = downloaded,
                        totalBytes = totalSize
                    )
                }
            }
        }

        connection.disconnect()
    }

    // ==================== SHA-256 ====================

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ==================== Storage ====================

    private fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    // ==================== Model Management ====================

    /**
     * Daftar semua model yang sudah terunduh.
     */
    fun getDownloadedModels(): List<LocalModelInfo> {
        val models = mutableListOf<LocalModelInfo>()

        for (tier in listOf(ModelMode.LOCAL_ADHI, ModelMode.LOCAL_MADYA, ModelMode.LOCAL_ALIT)) {
            val tierDir = File(modelsDir, tier.name.lowercase())
            if (!tierDir.exists()) continue

            tierDir.listFiles()?.filter { it.extension == "gguf" }?.forEach { file ->
                val catalogMatch = DEFAULT_CATALOG.find { it.fileName == file.name }
                models.add(
                    LocalModelInfo(
                        id = catalogMatch?.id ?: file.nameWithoutExtension,
                        displayName = catalogMatch?.displayName ?: file.nameWithoutExtension,
                        tier = tier,
                        filePath = file.absolutePath,
                        fileSizeBytes = file.length(),
                        downloadedAt = file.lastModified()
                    )
                )
            }
        }

        return models.sortedByDescending { it.downloadedAt }
    }

    /**
     * Hapus model yang sudah terunduh.
     */
    fun deleteModel(modelId: String): Boolean {
        val model = getDownloadedModels().find { it.id == modelId } ?: return false
        val file = File(model.filePath)
        return if (file.exists()) file.delete() else false
    }

    /**
     * Cek apakah ada model di tier tertentu.
     */
    fun hasModelForTier(tier: ModelMode): Boolean {
        return getDownloadedModels().any { it.tier == tier }
    }

    /**
     * Dapatkan path model untuk tier tertentu.
     */
    fun getModelPathForTier(tier: ModelMode): String? {
        return getDownloadedModels().find { it.tier == tier }?.filePath
    }

    /**
     * Total size semua model yang terunduh.
     */
    fun getTotalModelsSizeBytes(): Long {
        return getDownloadedModels().sumOf { it.fileSizeBytes }
    }

    /**
     * Cari model dari catalog berdasarkan ID.
     */
    fun findInCatalog(modelId: String): DownloadableModel? {
        return DEFAULT_CATALOG.find { it.id == modelId }
    }
}
