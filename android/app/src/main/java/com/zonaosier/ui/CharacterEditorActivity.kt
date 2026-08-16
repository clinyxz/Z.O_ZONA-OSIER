/**
 * ZONA-OSIER — Character Editor Activity.
 *
 * Form untuk membuat dan mengedit karakter.
 * Menerima intent extra "character_id"; jika null, mode buat baru.
 *
 * Alur:
 * 1. Terima character_id dari intent (null = create new).
 * 2. Load data karakter dari Room DB jika character_id tidak null.
 * 3. Tampilkan CharacterEditorScreen dengan data yang sudah di-load.
 * 4. Saat save, tampilkan Toast dan finish activity.
 */
package com.zonaosier.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.zonaosier.R
import com.zonaosier.ui.screens.CharacterEditorScreen
import com.zonaosier.ui.theme.ZonaOsierTheme
import kotlinx.coroutines.launch

class CharacterEditorActivity : ComponentActivity() {

    companion object {
        /** Key untuk intent extra: ID karakter yang akan diedit. */
        const val EXTRA_CHARACTER_ID = "character_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ambil character_id dari intent. Null = mode buat baru.
        val characterId: String? = intent.getStringExtra(EXTRA_CHARACTER_ID)

        // Default values untuk mode buat baru
        var existingName = ""
        var existingPersona = ""
        var existingFirstMessage = ""
        var existingVoiceTag = "default"
        val existingModelMode = com.zonaosier.memory.entity.ModelMode.AUTO_TIER
        val existingMemoryScope = com.zonaosier.memory.entity.MemoryScope.SHARED

        // Jika edit mode, load data karakter dari Room DB
        if (characterId != null) {
            lifecycleScope.launch {
                try {
                    val dao = com.zonaosier.ZonaOsierApp.instance.database.characterDao()
                    val character = dao.getCharacterById(characterId)
                    if (character != null) {
                        // Data akan di-set ulang via recreate setContent.
                        // Karena ini adalah Activity, kita gunakan pendekatan
                        // dengan men-set content setelah data tersedia.
                        setContentWithCharacter(
                            name = character.name,
                            persona = character.personaPrompt,
                            firstMessage = character.firstMessage ?: "",
                            voiceTag = character.voiceTag,
                            modelMode = existingModelMode,
                            memoryScope = existingMemoryScope
                        )
                    } else {
                        // Karakter tidak ditemukan, fallback ke buat baru
                        setContentWithCharacter(
                            name = existingName,
                            persona = existingPersona,
                            firstMessage = existingFirstMessage,
                            voiceTag = existingVoiceTag,
                            modelMode = existingModelMode,
                            memoryScope = existingMemoryScope
                        )
                        Toast.makeText(
                            this@CharacterEditorActivity,
                            "Karakter tidak ditemukan",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    // Fallback ke mode buat baru jika gagal load
                    setContentWithCharacter(
                        name = existingName,
                        persona = existingPersona,
                        firstMessage = existingFirstMessage,
                        voiceTag = existingVoiceTag,
                        modelMode = existingModelMode,
                        memoryScope = existingMemoryScope
                    )
                }
            }
        } else {
            // Mode buat baru — set content langsung dengan default values
            setContentWithCharacter(
                name = existingName,
                persona = existingPersona,
                firstMessage = existingFirstMessage,
                voiceTag = existingVoiceTag,
                modelMode = existingModelMode,
                memoryScope = existingMemoryScope
            )
        }
    }

    /**
     * Set Compose content dengan data karakter yang sudah di-resolve.
     *
     * @param name Nama karakter.
     * @param persona Persona prompt.
     * @param firstMessage Pesan pertama.
     * @param voiceTag Tag suara.
     * @param modelMode Mode model.
     * @param memoryScope Scope memori.
     */
    private fun setContentWithCharacter(
        name: String,
        persona: String,
        firstMessage: String,
        voiceTag: String,
        modelMode: com.zonaosier.memory.entity.ModelMode,
        memoryScope: com.zonaosier.memory.entity.MemoryScope
    ) {
        setContent {
            ZonaOsierTheme {
                CharacterEditorScreen(
                    existingName = name,
                    existingPersona = persona,
                    existingFirstMessage = firstMessage,
                    existingVoiceTag = voiceTag,
                    existingModelMode = modelMode,
                    existingMemoryScope = memoryScope,
                    onSave = { savedName, _, _, _, _, _ ->
                        Toast.makeText(
                            this@CharacterEditorActivity,
                            "Karakter disimpan",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    },
                    onBack = {
                        finish()
                    }
                )
            }
        }
    }
}