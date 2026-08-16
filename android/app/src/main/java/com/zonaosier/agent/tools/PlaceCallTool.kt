/**
 * ZONA-OSIER — PlaceCallTool.
 * Melakukan panggilan telepon.
 * Memerlukan CALL_PHONE permission.
 * Destructive = true → wajib biometric.
 */
package com.zonaosier.agent.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import com.zonaosier.security.AuditLogger

class PlaceCallTool(private val context: Context) : Tool {

    override val name: String = "place_call"
    override val description: String =
        "Melakukan panggilan telepon ke nomor. " +
        "Argumen: 'phone' (string, nomor tujuan)."
    override val parameters: String = """
        {
            "type": "object",
            "properties": {
                "phone": {"type": "string", "description": "Nomor telepon tujuan"}
            },
            "required": ["phone"]
        }
    """.trimIndent()
    override val isDestructive: Boolean = true
    override val requiresBiometric: Boolean = true

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AuditLogger.logShellRejected("place_call", "Permission CALL_PHONE tidak diberikan")
            return ToolResult.Error("Izin CALL_PHONE belum diberikan.")
        }

        val phone = args["phone"]?.toString()?.trim()
            ?: return ToolResult.Error("Argumen 'phone' wajib diisi.")

        val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
        if (cleanPhone.length < 8 || cleanPhone.length > 15) {
            return ToolResult.Error("Nomor telepon tidak valid: $phone")
        }

        return try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$cleanPhone")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)

            AuditLogger.logShellApproved("place_call", "Call ke $cleanPhone")
            ToolResult.Success("Memulai panggilan ke $cleanPhone")
        } catch (e: Exception) {
            AuditLogger.logShellRejected("place_call", "Exception: ${e.message}")
            ToolResult.Error("Gagal melakukan panggilan: ${e.message}")
        }
    }
}
