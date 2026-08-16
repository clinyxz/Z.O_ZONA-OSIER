/**
 * ZONA-OSIER — Strategi konteks percakapan.
 * Menentukan bagaimana sistem memori dan context window dikelola.
 */
package com.zonaosier.memory.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ContextStrategy(val description: String) {
    /** Percakapan standar — sliding window 8-16K token */
    @SerialName("standard")
    STANDARD("Percakapan standar dengan sliding window"),

    /** Dokumen panjang — chunked delivery, iterasi agent dinaikkan */
    @SerialName("long_document")
    LONG_DOCUMENT("Mode dokumen panjang, chunked delivery"),

    /** Task-oriented — fokus instruksi, minim memori percakapan */
    @SerialName("task")
    TASK("Orientasi tugas, memori percakapan minimal")
}
