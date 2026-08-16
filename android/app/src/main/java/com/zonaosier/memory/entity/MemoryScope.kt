/**
 * ZONA-OSIER — Scope memori per karakter.
 * Menentukan apakah memori karakter terisolasi dari memori global.
 */
package com.zonaosier.memory.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryScope(val description: String) {
    /** Memori terisolasi — percakapan karakter ini tidak tercampur dengan memori global */
    @SerialName("isolated")
    ISOLATED("Terisolasi dari memori global Z.O"),

    /** Memori terintegrasi — percakapan masuk ke memori global */
    @SerialName("shared")
    SHARED("Tergabung dengan memori global Z.O")
}