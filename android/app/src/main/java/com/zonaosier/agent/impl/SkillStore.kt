/**
 * ZONA-OSIER — SkillStore.
 * Menyimpan dan mengelola skill (pola tool-call yang sering dipakai)
 * untuk meningkatkan efisiensi agent.
 *
 * Skill = resep multi-step yang bisa dipanggil sebagai satu unit.
 * Contoh: "Kirim SMS ke X: Y" = [parse_contact, send_sms, confirm]
 */
package com.zonaosier.agent.impl

import com.zonaosier.agent.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Satu skill yang tersimpan.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val triggerPatterns: List<String>,  // Pola input yang memicu skill ini
    val steps: List<ToolCall>,           // Langkah-langkah tool call
    val isBuiltIn: Boolean = false,       // true = tidak bisa dihapus
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null
) {
    val successRate: Float
        get() = if (successCount + failureCount == 0) 0f
        else successCount.toFloat() / (successCount + failureCount)

    fun recordSuccess() = copy(
        successCount = successCount + 1,
        lastUsedAt = System.currentTimeMillis()
    )

    fun recordFailure() = copy(
        failureCount = failureCount + 1,
        lastUsedAt = System.currentTimeMillis()
    )
}

/**
 * In-memory store untuk skill.
 * Persistensi ke Room DB bisa ditambahkan nanti.
 */
class SkillStore {

    private val skills = ConcurrentHashMap<String, Skill>()

    init {
        registerBuiltInSkills()
    }

    /**
     * Cari skill yang cocok dengan input user.
     * Menggunakan simple keyword matching.
     */
    fun matchSkill(input: String): Skill? {
        val normalizedInput = input.lowercase().trim()

        return skills.values
            .filter { it.successRate >= 0.5f } // Hanya skill dengan success rate >= 50%
            .maxByOrNull { skill ->
                skill.triggerPatterns.count { pattern ->
                    normalizedInput.contains(pattern.lowercase())
                }
            }
    }

    /**
     * Ambil skill by ID.
     */
    fun getSkill(id: String): Skill? = skills[id]

    /**
     * Simpan skill baru.
     */
    fun saveSkill(skill: Skill) {
        skills[skill.id] = skill
    }

    /**
     * Hapus skill (tidak bisa hapus built-in).
     */
    fun deleteSkill(id: String): Boolean {
        val skill = skills[id] ?: return false
        if (skill.isBuiltIn) return false
        skills.remove(id)
        return true
    }

    /**
     * Daftar semua skill.
     */
    fun getAllSkills(): List<Skill> = skills.values.toList().sortedByDescending { it.lastUsedAt ?: 0 }

    /**
     * Record penggunaan skill.
     */
    fun recordUsage(skillId: String, success: Boolean) {
        val skill = skills[skillId] ?: return
        skills[skillId] = if (success) skill.recordSuccess() else skill.recordFailure()
    }

    /**
     * Daftar skill bawaan Z.O.
     */
    private fun registerBuiltInSkills() {
        // Skill: Kirim SMS
        saveSkill(
            Skill(
                id = "builtin_send_sms",
                name = "Kirim SMS",
                description = "Kirim pesan teks ke kontak",
                triggerPatterns = listOf("kirim sms", "kirim pesan", "sms ke", "pesan ke"),
                steps = listOf(
                    ToolCall(
                        id = "sms_1",
                        name = "send_sms",
                        arguments = mapOf("contact" to "{contact}", "message" to "{message}")
                    )
                ),
                isBuiltIn = true
            )
        )

        // Skill: Telepon
        saveSkill(
            Skill(
                id = "builtin_place_call",
                name = "Telepon",
                description = "Lakukan panggilan telepon",
                triggerPatterns = listOf("telepon", "panggil", "call", "hubungi"),
                steps = listOf(
                    ToolCall(
                        id = "call_1",
                        name = "place_call",
                        arguments = mapOf("contact" to "{contact}")
                    )
                ),
                isBuiltIn = true
            )
        )

        // Skill: Set Alarm
        saveSkill(
            Skill(
                id = "builtin_set_alarm",
                name = "Set Alarm",
                description = "Atur alarm",
                triggerPatterns = listOf("alarm", "bangun", "timer", "pengingat waktu"),
                steps = listOf(
                    ToolCall(
                        id = "alarm_1",
                        name = "set_alarm",
                        arguments = mapOf("time" to "{time}", "label" to "{label}")
                    )
                ),
                isBuiltIn = true
            )
        )

        // Skill: Baca Layar
        saveSkill(
            Skill(
                id = "builtin_screen_read",
                name = "Baca Layar",
                description = "Baca konten layar saat ini",
                triggerPatterns = listOf("baca layar", "apa di layar", "screen read", "lihat layar"),
                steps = listOf(
                    ToolCall(
                        id = "screen_1",
                        name = "screen_read",
                        arguments = emptyMap()
                    )
                ),
                isBuiltIn = true
            )
        )
    }
}
