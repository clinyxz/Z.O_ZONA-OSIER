/**
 * ZONA-OSIER — Kebijakan akses tool per karakter.
 * Menentukan tool mana yang boleh diakses oleh karakter.
 * Enforced oleh FilteredToolRegistry.
 */
package com.zonaosier.memory.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Konfigurasi kebijakan tool untuk satu karakter.
 * Default: semua izin dimatikan (default DENY).
 */
@Serializable
data class ToolPolicy(
    /** Izinkan akses shell (termux_exec, shizuku_shell, shizuku_tap, shizuku_screenshot) */
    @SerialName("allow_shell")
    val allowShell: Boolean = false,

    /** Izinkan kirim SMS */
    @SerialName("allow_sms")
    val allowSms: Boolean = false,

    /** Izinkan panggilan telepon */
    @SerialName("allow_call")
    val allowCall: Boolean = false,

    /** Izinkan akses kamera (face_recognize, camera_capture) */
    @SerialName("allow_camera")
    val allowCamera: Boolean = false,

    /** Izinkan akses kalender (create_calendar_event) */
    @SerialName("allow_calendar")
    val allowCalendar: Boolean = false,

    /** Izinkan akses lokasi */
    @SerialName("allow_location")
    val allowLocation: Boolean = false,

    /** Izinkan web fetch */
    @SerialName("allow_web")
    val allowWeb: Boolean = true,

    /** Izinkan operasi memori (memory_search, memory_store) */
    @SerialName("allow_memory")
    val allowMemory: Boolean = true,

    /** Izinkan screen reading */
    @SerialName("allow_screen_read")
    val allowScreenRead: Boolean = true,

    /** Izinkan personality extraction */
    @SerialName("allow_personality")
    val allowPersonality: Boolean = true
) {
    companion object {
        /** Kebijakan default — hanya tool non-destruktif */
        val DEFAULT = ToolPolicy()

        /** Kebijakan full access — semua tool tersedia */
        val FULL_ACCESS = ToolPolicy(
            allowShell = true,
            allowSms = true,
            allowCall = true,
            allowCamera = true,
            allowCalendar = true,
            allowLocation = true,
            allowWeb = true,
            allowMemory = true,
            allowScreenRead = true,
            allowPersonality = true
        )
    }
}
