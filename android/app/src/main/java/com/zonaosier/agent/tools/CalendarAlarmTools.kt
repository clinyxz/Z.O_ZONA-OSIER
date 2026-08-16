/**
 * ZONA-OSIER — Calendar & Alarm Tools.
 * Non-destruktif, selalu tersedia.
 */
package com.zonaosier.agent.tools

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.zonaosier.agent.Tool
import com.zonaosier.agent.ToolResult
import java.util.Calendar

/**
 * Buat event di kalender.
 */
class CreateCalendarEventTool(private val context: Context) : Tool {

    override val name: String = "create_calendar_event"
    override val description: String =
        "Buat event di kalender. " +
        "Argumen: 'title' (string), 'description' (string opsional), " +
        "'start_time' (string, format yyyy-MM-dd HH:mm), " +
        "'end_time' (string opsional, default +1 jam)."
    override val parameters: String = """
        {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Judul event"},
                "description": {"type": "string", "description": "Deskripsi (opsional)"},
                "start_time": {"type": "string", "description": "Waktu mulai (yyyy-MM-dd HH:mm)"},
                "end_time": {"type": "string", "description": "Waktu selesai (opsional)"}
            },
            "required": ["title", "start_time"]
        }
    """.trimIndent()
    override val isDestructive: Boolean = false
    override val requiresBiometric: Boolean = false

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val title = args["title"]?.toString()?.trim()
            ?: return ToolResult.Error("Argumen 'title' wajib diisi.")
        val startTimeStr = args["start_time"]?.toString()?.trim()
            ?: return ToolResult.Error("Argumen 'start_time' wajib diisi.")
        val description = args["description"]?.toString() ?: ""
        val endTimeStr = args["end_time"]?.toString()

        val startTime = parseDateTime(startTimeStr)
            ?: return ToolResult.Error("Format start_time tidak valid. Gunakan: yyyy-MM-dd HH:mm")
        val endTime = endTimeStr?.let { parseDateTime(it) } ?: Calendar.getInstance().apply {
            timeInMillis = startTime.timeInMillis + 3600_000 // +1 jam
        }

        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime.timeInMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime.timeInMillis)
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, description)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            ToolResult.Success("Event '$title' dibuat: $startTimeStr")
        } catch (e: Exception) {
            ToolResult.Error("Gagal membuat event: ${e.message}")
        }
    }

    private fun parseDateTime(str: String): Calendar? {
        return try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            val date = format.parse(str) ?: return null
            Calendar.getInstance().apply { time = date }
        } catch (e: Exception) { null }
    }
}

/**
 * Set alarm.
 */
class SetAlarmTool(private val context: Context) : Tool {

    override val name: String = "set_alarm"
    override val description: String =
        "Atur alarm. Argumen: 'time' (string, HH:mm), 'label' (string opsional)."
    override val parameters: String = """
        {
            "type": "object",
            "properties": {
                "time": {"type": "string", "description": "Waktu alarm (HH:mm)"},
                "label": {"type": "string", "description": "Label alarm (opsional)"}
            },
            "required": ["time"]
        }
    """.trimIndent()
    override val isDestructive: Boolean = false
    override val requiresBiometric: Boolean = false

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val time = args["time"]?.toString()?.trim()
            ?: return ToolResult.Error("Argumen 'time' wajib diisi.")
        val label = args["label"]?.toString() ?: "Z.O Alarm"

        val parts = time.split(":")
        if (parts.size != 2) {
            return ToolResult.Error("Format waktu tidak valid. Gunakan HH:mm")
        }

        val hour = parts[0].toIntOrNull()
        val minute = parts[1].toIntOrNull()
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            return ToolResult.Error("Waktu tidak valid: $time")
        }

        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            ToolResult.Success("Alarm diatur: $time - $label")
        } catch (e: Exception) {
            ToolResult.Error("Gagal mengatur alarm: ${e.message}")
        }
    }
}
