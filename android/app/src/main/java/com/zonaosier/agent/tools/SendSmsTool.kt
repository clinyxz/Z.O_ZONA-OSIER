/**
 * ZONA-OSIER — SendSmsTool.
 * Mengirim SMS ke nomor kontak.
 * Memerlukan SEND_SMS permission (dangerous, runtime).
 * Destructive = true → wajib biometric.
 */
package com.zonaosier.agent.tools

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import com.zonaosier.security.AuditLogger

class SendSmsTool(private val context: Context) : Tool {

    override val name: String = "send_sms"
    override val description: String =
        "Kirim pesan SMS ke nomor telepon. " +
        "Argumen: 'phone' (string, nomor tujuan), 'message' (string, isi pesan)."
    override val parameters: String = """
        {
            "type": "object",
            "properties": {
                "phone": {"type": "string", "description": "Nomor telepon tujuan"},
                "message": {"type": "string", "description": "Isi pesan yang akan dikirim"}
            },
            "required": ["phone", "message"]
        }
    """.trimIndent()
    override val isDestructive: Boolean = true
    override val requiresBiometric: Boolean = true

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        // Cek permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AuditLogger.logShellRejected("send_sms", "Permission SEND_SMS tidak diberikan")
            return ToolResult.Error("Izin SEND_SMS belum diberikan. Buka Settings untuk mengaktifkan.")
        }

        val phone = args["phone"]?.toString()?.trim()
        val message = args["message"]?.toString()?.trim()

        if (phone.isNullOrEmpty()) {
            return ToolResult.Error("Argumen 'phone' wajib diisi.")
        }
        if (message.isNullOrEmpty()) {
            return ToolResult.Error("Argumen 'message' wajib diisi.")
        }

        // Validasi nomor telepon (basic)
        val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
        if (cleanPhone.length < 8 || cleanPhone.length > 15) {
            return ToolResult.Error("Nomor telepon tidak valid: $phone")
        }

        return try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Split jika pesan panjang
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                val sentIntents = parts.map {
                    PendingIntent.getBroadcast(context, 0, android.content.Intent("SMS_SENT"),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                }.toTypedArray()
                smsManager.sendMultipartTextMessage(cleanPhone, null, parts, sentIntents, null)
            } else {
                val sentIntent = PendingIntent.getBroadcast(
                    context, 0, android.content.Intent("SMS_SENT"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
                smsManager.sendTextMessage(cleanPhone, null, message, sentIntent, null)
            }

            AuditLogger.logShellApproved("send_sms", "SMS ke $cleanPhone")
            ToolResult.Success("SMS terkirim ke $cleanPhone")
        } catch (e: Exception) {
            AuditLogger.logShellRejected("send_sms", "Exception: ${e.message}")
            ToolResult.Error("Gagal mengirim SMS: ${e.message}")
        }
    }
}
