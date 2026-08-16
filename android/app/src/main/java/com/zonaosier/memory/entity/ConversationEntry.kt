/**
 * ZONA-OSIER — Entry percakapan.
 * Disimpan di Room DB per karakter (jika scope ISOLATED) atau global.
 */
package com.zonaosier.memory.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MessageRole {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool") TOOL
}

@Serializable
@Entity(
    tableName = "conversation_entries",
    foreignKeys = [
        ForeignKey(
            entity = CharacterCard::class,
            parentColumns = ["id"],
            childColumns = ["character_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["character_id"]),
        Index(value = ["character_id", "timestamp"])
    ]
)
data class ConversationEntry(
    @PrimaryKey
    @SerialName("id")
    val id: String,

    /** ID karakter. Null jika scope SHARED. */
    @SerialName("character_id")
    val characterId: String? = null,

    /** Peran pengirim. */
    @SerialName("role")
    val role: MessageRole,

    /** Isi pesan. */
    @SerialName("content")
    val content: String,

    /** Token count (opsional, dihitung pasca-facto). */
    @SerialName("token_count")
    val tokenCount: Int? = null,

    /** Timestamp epoch millis. */
    @SerialName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)