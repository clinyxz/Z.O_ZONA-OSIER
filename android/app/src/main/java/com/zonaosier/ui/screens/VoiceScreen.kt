/**
 * ZONA-OSIER — VoiceScreen.
 * Layar percakapan suara real-time.
 *
 * Spec §9.4 Screen 3:
 * - State Label: MENDENGARKAN / MEMPROSES / BERBICARA
 * - Voice Orb: 120dp, inner core 60dp
 * - Waveform: 7 bar vertikal
 * - Engine Badges: STT + TTS labels
 */
package com.zonaosier.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zonaosier.ui.components.NadiBar
import com.zonaosier.ui.theme.LocalZonaColors

enum class VoiceState { LISTENING, PROCESSING, SPEAKING, IDLE }

@Composable
fun VoiceScreen(
    voiceState: VoiceState = VoiceState.IDLE,
    sttEngine: String = "Vosk",
    ttsEngine: String = "Lokal",
    transcript: String = "",
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current

    // Voice Orb animations
    val orbScale by animateFloatAsState(
        targetValue = when (voiceState) {
            VoiceState.LISTENING -> 1.2f
            VoiceState.PROCESSING -> 1.0f
            VoiceState.SPEAKING -> 1.0f
            VoiceState.IDLE -> 1.0f
        },
        animationSpec = tween(300),
        label = "orb_scale"
    )

    val orbPulse by animateFloatAsState(
        targetValue = when (voiceState) {
            VoiceState.LISTENING -> 1.1f
            VoiceState.IDLE -> 1.0f
            else -> 1.0f
        },
        animationSpec = if (voiceState == VoiceState.LISTENING) {
            infiniteRepeatable(tween(800, easing = EaseInOutSineWave), RepeatMode.Reverse)
        } else {
            tween(0)
        },
        label = "orb_pulse"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NadiBar(modifier = Modifier.fillMaxWidth())

        // Back button
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = colors.onSurface)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // State Label
        Text(
            when (voiceState) {
                VoiceState.LISTENING -> "MENDENGARKAN"
                VoiceState.PROCESSING -> "MEMPROSES"
                VoiceState.SPEAKING -> "BERBICARA"
                VoiceState.IDLE -> "SIAP"
            },
            color = colors.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Voice Orb (120dp outer, 60dp inner)
        Box(
            modifier = Modifier.size(120.dp * orbScale * orbPulse),
            contentAlignment = Alignment.Center
        ) {
            // Outer ring
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(colors.binduGlow.copy(alpha = 0.15f))
            )
            // Inner core
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(colors.binduCore)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Waveform (7 bars)
        if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.SPEAKING) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                repeat(7) { index ->
                    val barScale by animateFloatAsState(
                        targetValue = if (voiceState == VoiceState.LISTENING) {
                            (0.3f..1.0f).random()
                        } else 0.5f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = 600,
                                delayMillis = index * 75,
                                easing = EaseInOutSineWave
                            ),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "bar_$index"
                    )
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height((40 * barScale).dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                            .background(colors.accent.copy(alpha = 0.6f))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Transcript
        if (transcript.isNotBlank()) {
            Text(
                transcript,
                color = colors.onSurface.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Engine badges
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 48.dp)
        ) {
            EngineBadge("STT: $sttEngine", colors.accent)
            EngineBadge("TTS: $ttsEngine", colors.secondary)
        }
    }
}

@Composable
private fun EngineBadge(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
