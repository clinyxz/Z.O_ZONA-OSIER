/**
 * ZONA-OSIER — CharacterEditorScreen.
 * Form untuk membuat dan mengedit karakter.
 *
 * Field: Nama, Persona Prompt, First Message, Example Dialogue,
 * Model Binding (provider, model, temperature), Voice Tag, Tool Policy.
 */
package com.zonaosier.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zonaosier.memory.entity.ModelBinding
import com.zonaosier.memory.entity.ModelMode
import com.zonaosier.memory.entity.MemoryScope
import com.zonaosier.ui.components.NadiBar
import com.zonaosier.ui.theme.LocalZonaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditorScreen(
    existingName: String = "",
    existingPersona: String = "",
    existingFirstMessage: String = "",
    existingVoiceTag: String = "default",
    existingModelMode: ModelMode = ModelMode.AUTO_TIER,
    existingMemoryScope: MemoryScope = MemoryScope.SHARED,
    onSave: (name: String, persona: String, firstMessage: String, voiceTag: String, modelMode: ModelMode, memoryScope: MemoryScope) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current
    val scrollState = rememberScrollState()

    var name by remember { mutableStateOf(existingName) }
    var persona by remember { mutableStateOf(existingPersona) }
    var firstMessage by remember { mutableStateOf(existingFirstMessage) }
    var voiceTag by remember { mutableStateOf(existingVoiceTag) }
    var selectedModelMode by remember { mutableStateOf(existingModelMode) }
    var selectedMemoryScope by remember { mutableStateOf(existingMemoryScope) }
    var temperature by remember { mutableFloatStateOf(0.7f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        NadiBar(modifier = Modifier.fillMaxWidth())

        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Kembali", tint = colors.onSurface)
            }
            Text(
                if (existingName.isNotBlank()) "Edit Karakter" else "Karakter Baru",
                color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name, persona, firstMessage, voiceTag, selectedModelMode, selectedMemoryScope)
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Icon(Icons.Default.Save, "Simpan", tint = if (name.isNotBlank()) colors.accent else colors.onSurface.copy(alpha = 0.3f))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Nama
            Text("Nama Karakter", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Contoh: Kode-Reviewer") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = outlinedTextFieldColors(colors)
            )

            // Persona Prompt
            Text("Persona Prompt", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it },
                placeholder = { Text("[KARAKTER]\\nKamu adalah...\\n[KEPRIBADIAN]\\n...") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                shape = RoundedCornerShape(12.dp),
                colors = outlinedTextFieldColors(colors)
            )

            // First Message
            Text("Pesan Pertama", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = firstMessage,
                onValueChange = { firstMessage = it },
                placeholder = { Text("Pesan pembuka karakter") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                shape = RoundedCornerShape(12.dp),
                colors = outlinedTextFieldColors(colors)
            )

            // Voice Tag
            Text("Voice Tag", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("default", "tenang", "tegas", "ekspresif", "naratif", "melodius").forEach { tag ->
                    FilterChip(
                        selected = voiceTag == tag,
                        onClick = { voiceTag = tag },
                        label = { Text(tag.replaceFirstChar { it.uppercase() }, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.3f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }

            // Model Mode
            Text("Model", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    ModelMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedModelMode == mode,
                                onClick = { selectedModelMode = mode },
                                colors = RadioButtonDefaults.colors(selectedColor = colors.accent)
                            )
                            Text(mode.displayName, color = colors.onSurface, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Memory Scope
            Text("Memory Scope", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(MemoryScope.SHARED, MemoryScope.ISOLATED).forEach { scope ->
                    FilterChip(
                        selected = selectedMemoryScope == scope,
                        onClick = { selectedMemoryScope = scope },
                        label = { Text(scope.name, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.3f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }

            // Temperature slider
            Text("Temperature: ${"%.1f".format(temperature)}", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0f..2f,
                colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun outlinedTextFieldColors(colors: com.zonaosier.ui.theme.ZonaSkinColors) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = colors.accent,
    unfocusedBorderColor = colors.glassBorder,
    cursorColor = colors.accent,
    textColor = colors.onSurface,
    placeholderColor = colors.onSurface.copy(alpha = 0.3f)
)