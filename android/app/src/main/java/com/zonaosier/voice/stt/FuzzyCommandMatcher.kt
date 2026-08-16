/**
 * ZONA-OSIER — FuzzyCommandMatcher.
 * Pencocokan perintah suara dengan fuzzy matching.
 * Menggunakan normalized Levenshtein distance.
 *
 * Threshold dinamis berdasarkan panjang string:
 * - <=5 char: exact match (0.0)
 * - <=10 char: max 15% perbedaan
 * - >10 char: max 25% perbedaan
 */
package com.zonaosier.voice.stt

data class MatchResult(
    val command: String,
    val params: Map<String, String>
)

data class CommandTemplate(
    val name: String,
    val pattern: String,
    val slots: List<String>
)

class FuzzyCommandMatcher(private val patterns: List<CommandTemplate>) {

    /**
     * Cari command terbaik yang cocok dengan input.
     * @return MatchResult atau null jika tidak ada yang cocok.
     */
    fun match(input: String): MatchResult? {
        val normalized = input.lowercase().trim()
        var bestScore = Double.MAX_VALUE
        var bestMatch: MatchResult? = null

        for (template in patterns) {
            // Normalized Levenshtein distance
            val patternText = template.pattern.lowercase()
            val maxLen = maxOf(normalized.length, patternText.length)
            if (maxLen == 0) continue

            val rawScore = levenshtein(normalized, patternText)
            val score = rawScore.toDouble() / maxLen

            // Threshold dinamis berdasarkan panjang string
            val threshold = when {
                maxLen <= 5 -> 0.0    // Exact match untuk string pendek
                maxLen <= 10 -> 0.15  // Max 15% untuk medium
                else -> 0.25          // Max 25% untuk panjang
            }

            if (score < bestScore && score <= threshold) {
                bestScore = score
                bestMatch = MatchResult(template.name, extractParams(normalized, template))
            }
        }

        return bestMatch
    }

    /**
     * Hitung Levenshtein distance.
     */
    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
                }
            }
        }
        return dp[s1.length][s2.length]
    }

    /**
     * Extract parameter dari input berdasarkan template slots.
     */
    private fun extractParams(input: String, template: CommandTemplate): Map<String, String> {
        val params = mutableMapOf<String, String>()
        if (template.slots.isEmpty()) return params

        // Simple extraction: hapus pattern dari input, sisanya adalah parameter
        var remaining = input
        for (keyword in template.pattern.split(" ")) {
            remaining = remaining.replace(keyword, "", ignoreCase = true)
        }
        remaining = remaining.trim()

        if (remaining.isNotBlank() && template.slots.isNotEmpty()) {
            params[template.slots[0]] = remaining
        }

        return params
    }

    companion object {
        /**
         * Daftar perintah bawaan Z.O.
         */
        fun defaultCommands(): List<CommandTemplate> = listOf(
            CommandTemplate("wake", "hey zona", emptyList()),
            CommandTemplate("wake", "hai zona", emptyList()),
            CommandTemplate("wake", "zona", emptyList()),
            CommandTemplate("stop", "berhenti", emptyList()),
            CommandTemplate("stop", "diam", emptyList()),
            CommandTemplate("stop", "stop", emptyList()),
            CommandTemplate("send_sms", "kirim pesan", listOf("message")),
            CommandTemplate("send_sms", "kirim sms", listOf("message")),
            CommandTemplate("place_call", "telepon", listOf("contact")),
            CommandTemplate("place_call", "panggil", listOf("contact")),
            CommandTemplate("place_call", "hubungi", listOf("contact")),
            CommandTemplate("set_alarm", "pasang alarm", listOf("time")),
            CommandTemplate("set_alarm", "atur alarm", listOf("time")),
            CommandTemplate("screen_read", "baca layar", emptyList()),
            CommandTemplate("screen_read", "apa di layar", emptyList()),
            CommandTemplate("memory_search", "cari ingatan", listOf("query")),
            CommandTemplate("memory_search", "ingat tentang", listOf("query")),
            CommandTemplate("web_fetch", "cari di internet", listOf("query")),
            CommandTemplate("web_fetch", "buka", listOf("url")),
            CommandTemplate("weather", "cuaca", emptyList()),
            CommandTemplate("time", "jam berapa", emptyList()),
            CommandTemplate("freeze", "bekukan", emptyList()),
            CommandTemplate("freeze", "freeze", emptyList())
        )
    }
}
