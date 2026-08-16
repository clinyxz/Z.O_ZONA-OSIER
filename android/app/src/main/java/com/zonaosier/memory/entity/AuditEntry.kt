/**
 * ZONA-OSIER — Audit Log Entry.
 * Append-only, terenkripsi, lokal.
 * Mencatat setiap eksekusi tool (lolos maupun ditolak).
 */
package com.zonaosier.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Status validasi aksi — dilabeli di setiap entry audit. */
@Serializable
enum class AuditStatus(val label: String, val emoji: String) {
    @SerialName("approved")
    APPROVED("Disetujui", "\u2705"),

    @SerialName("modified")
    MODIFIED("Dimodifikasi", "\uD83D\uDD27"),

    @SerialName("rejected")
    REJECTED("Ditolak", "\u26A0\uFE0F"),

    @SerialName("error")
    ERROR("Error Sistem", "\u274C")
}

@Serializable
@Entity(
    tableName = "audit_log",
    indices = [
        Index(value = ["character_id"]),
        Index(value = ["tool_name"]),
        Index(value = ["timestamp"])
    ]
)
data class AuditEntry(
    @PrimaryKey
    @SerialName("id")
    val id: String,

    /** Tool yang dipanggil. */
    @SerialName("tool_name")
    val toolName: String,

    /** Aksi spesifik (misal: "execute", "validate", "freeze"). */
    @SerialName("action")
    val action: String,

    /** Status validasi. */
    @SerialName("status")
    val status: AuditStatus,

    /** Detail pesan (alasan reject, output, dll). */
    @SerialName("detail")
    val detail: String,

    /** ID karakter konteks (bisa null untuk aksi global). */
    @SerialName("character_id")
    val characterId: String? = null,

    /** Timestamp epoch millis. */
    @SerialName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)