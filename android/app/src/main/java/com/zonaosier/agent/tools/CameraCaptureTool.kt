/**
 * ZONA-OSIER — CameraCaptureTool.
 * Mengambil foto via kamera.
 * Memerlukan CAMERA permission.
 */
package com.zonaosier.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.core.content.ContextCompat
import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import java.io.File

class CameraCaptureTool(private val context: Context) : Tool {

    override val name: String = "camera_capture"
    override val description: String =
        "Ambil foto via kamera. Tidak memerlukan argumen. Output: base64 JPEG."
    override val parameters: String = "{}"
    override val isDestructive: Boolean = false
    override val requiresBiometric: Boolean = false

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Error("Izin CAMERA belum diberikan.")
        }

        // Kamera capture memerlukan CameraX integration.
        // Untuk sekarang, cek ketersediaan dan kembalikan status.
        return ToolResult.Error(
            "Camera capture memerlukan integrasi CameraX (Tahap 6)."
        )
    }
}

/**
 * Cari lokasi device.
 */
class GetLocationTool(private val context: Context) : Tool {

    override val name: String = "get_location"
    override val description: String =
        "Dapatkan lokasi device saat ini. Tidak memerlukan argumen."
    override val parameters: String = "{}"
    override val isDestructive: Boolean = false
    override val requiresBiometric: Boolean = false

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        // Location memerlukan LocationManager integration.
        // Integrasi penuh di Tahap 6.
        return ToolResult.Error(
            "Location memerlukan integrasi LocationManager (Tahap 6)."
        )
    }
}
