/**
 * ZONA-OSIER — ChatScreen.
 * Percakapan teks dengan karakter aktif.
 *
 * Spec §9.4 Screen 2:
 * - Header: Avatar, nama, status
 * - Bubble List: User / AI
 * - Agent Transparency Card (saat System Thinker aktif)
 * - Input Bar
 */
package com.zonaosier.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automorphic.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zonaosier.ui.components.AgentTransparencyCard
import com.zonaosier.ui.components.NadiBar
import com.zonaosier.ui.theme.LocalZonaColors

data class ChatMessage(
    val id: String,
    val role: String, // "user", "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    characterName: String,
    personaRegister: String,
    messages: List<ChatMessage>,
    isThinking: Boolean,
    thinkingSteps: List<com.zonaosier.ui.components.PlanStep>,
    confidenceCategory: com.zonaosier.ui.components.ConfidenceCategory,
    currentTool: String?,
    onSendMessage: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll ke bawah saat pesan baru
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // Nadi
        NadiBar(modifier = Modifier.fillMaxWidth())

        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = colors.onSurface)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                characterName,
                color = colors.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Online \u00B7 Persona: $personaRegister",
                color = colors.success,
                fontSize = 11.sp
            )
        }

        HorizontalDivider(color = colors.glassBorder, thickness = 1.dp)

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(message = msg, colors = colors)
            }

            // Agent Transparency Card
            if (isThinking) {
                item {
                    AgentTransparencyCard(
                        steps = thinkingSteps,
                        confidenceCategory = confidenceCategory,
                        currentTool = currentTool
                    )
                }
            }
        }

        // Input Bar
        Surface(
            color = colors.surface,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ketik pesan dalam bahasa baku...", color = colors.onSurface.copy(alpha = 0.3f)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.glassBorder,
                        cursorColor = colors.accent,
                        textColor = colors.onSurface
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendMessage(inputText)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Kirim",
                        tint = if (inputText.isNotBlank()) colors.accent else colors.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, colors: com.zonaosier.ui.theme.ZonaSkinColors) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isUser) colors.userBubble else colors.aiBubble)
                .padding(12.dp)
        ) {
            if (!isUser) {
                Text(
                    "Z.O",
                    color = colors.accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                message.content,
                color = colors.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}