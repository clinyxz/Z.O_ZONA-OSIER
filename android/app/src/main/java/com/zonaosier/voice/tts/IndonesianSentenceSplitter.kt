/**
 * ZONA-OSIER — IndonesianSentenceSplitter.
 * Memecah teks menjadi kalimat untuk TTS sentence-level streaming.
 *
 * ⚠️ Java BreakIterator gagal untuk singkatan Indonesia (a.n., dll., dsb., dr., yth.).
 * Implementasi kustom menggunakan regex negative lookbehind
 * untuk singkatan KBBI.
 *
 * Regex: (?<!a\.n|dll|dsb|dr|yth|bapak|ibu|drg|ir|sdr| Brigj|Kol|Let|Mei|Okt|Nov|Des|Nop|Agt|Sep|Feb|Mar|Apr|Jun|Jul|Agu)\.\s+
 */
package com.zonaosier.voice.tts

object IndonesianSentenceSplitter {

    /**
     * Daftar singkatan yang TIDAK mengindikasikan akhir kalimat.
     * Tambahkan sesuai kebutuhan.
     */
    private val ABBREVIATIONS = listOf(
        "a.n", "dll", "dsb", "dr", "drg", "ir", "sdr",
        "yth", "bapak", "ibu", "saudara",
        "Jan", "Feb", "Mar", "Apr", "Mei", "Jun",
        "Jul", "Agu", "Sep", "Okt", "Nov", "Des", "Nop",
        "Brigj", "Kol", "Let", "Prof", "Dr", "Ir",
        "H.", "R.", "A.R", "S.H", "S.T", "M.D",
        "dsk", "dsd", "spt", "masing2", "dll"
    )

    /**
     * Pattern regex untuk split kalimat.
     * Titik diikuti spasi/newline, TIDAK didahului singkatan.
     */
    private val SENTENCE_SPLIT_PATTERN = buildSplitPattern()

    private fun buildSplitPattern(): Regex {
        // Bangun negative lookbehind dari daftar singkatan
        val abbrEscaped = ABBREVIATIONS
            .map { it.replace(".", "\\.") }
            .joinToString("|")

        return Regex("(?<!$abbrEscaped)\\.\\s+")
    }

    /**
     * Split teks menjadi kalimat.
     * @param text Teks input.
     * @return Daftar kalimat.
     */
    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val sentences = mutableListOf<String>()
        var remaining = text.trim()

        while (remaining.isNotBlank()) {
            // Cari titik yang BUKAN singkatan
            val match = SENTENCE_SPLIT_PATTERN.find(remaining)

            if (match != null) {
                val splitIndex = match.range.first + match.value.length
                val sentence = remaining.substring(0, splitIndex).trim()
                if (sentence.isNotBlank()) {
                    sentences.add(sentence)
                }
                remaining = remaining.substring(splitIndex).trim()
            } else {
                // Tidak ada titik lagi — sisa teks sebagai kalimat terakhir
                if (remaining.isNotBlank()) {
                    sentences.add(remaining.trim())
                }
                break
            }
        }

        return sentences
    }

    /**
     * Split dengan batas panjang kalimat.
     * Jika satu kalimat terlalu panjang (>maxChars),
     * pecah pada koma atau spasi terdekat.
     */
    fun split(text: String, maxChars: Int = 200): List<String> {
        val sentences = split(text)
        val result = mutableListOf<String>()

        for (sentence in sentences) {
            if (sentence.length <= maxChars) {
                result.add(sentence)
            } else {
                // Pecah kalimat panjang pada koma
                val chunks = sentence.split(Regex(",\\s+"))
                var buffer = StringBuilder()

                for (chunk in chunks) {
                    if (buffer.length + chunk.length + 2 > maxChars && buffer.isNotBlank()) {
                        result.add(buffer.toString().trim())
                        buffer = StringBuilder()
                    }
                    if (buffer.isNotBlank()) buffer.append(", ")
                    buffer.append(chunk)
                }

                if (buffer.isNotBlank()) {
                    result.add(buffer.toString().trim())
                }
            }
        }

        return result
    }
}