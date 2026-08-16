/**
 * ZONA-OSIER — WebFetchTool.
 * Mengambil konten dari URL.
 */
package com.zonaosier.agent.tools

import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class WebFetchTool : Tool {

    companion object {
        private const val MAX_CONTENT_LENGTH = 10_000
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 15_000
        private val ALLOWED_SCHEMES = listOf("https", "http")
    }

    override val name: String = "web_fetch"
    override val description: String =
        "Ambil konten halaman web dari URL. " +
        "Argumen: 'url' (string). Mengembalikan teks halaman (max 10K chars)."
    override val parameters: String = """
        {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "URL halaman web"}
            },
            "required": ["url"]
        }
    """.trimIndent()
    override val isDestructive: Boolean = false
    override val requiresBiometric: Boolean = false

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val urlStr = args["url"]?.toString()?.trim()
            ?: return ToolResult.Error("Argumen 'url' wajib diisi.")

        // Validasi URL
        val url = try {
            val parsed = URL(urlStr)
            if (parsed.protocol !in ALLOWED_SCHEMES) {
                return ToolResult.Error("Hanya HTTP/HTTPS yang diizinkan.")
            }
            parsed
        } catch (e: Exception) {
            return ToolResult.Error("URL tidak valid: $urlStr")
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "ZONA-OSIER/1.0")

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    return@withContext ToolResult.Error(
                        "HTTP $responseCode dari ${url.host}"
                    )
                }

                val content = connection.inputStream.bufferedReader().use { it.readText() }
                val truncated = if (content.length > MAX_CONTENT_LENGTH) {
                    content.take(MAX_CONTENT_LENGTH) + "\n...(truncated)"
                } else {
                    content
                }

                ToolResult.Success(truncated)
            } catch (e: Exception) {
                ToolResult.Error("Gagal mengambil halaman: ${e.message}")
            }
        }
    }
}