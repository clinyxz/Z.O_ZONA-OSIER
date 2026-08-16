/**
 * ZONA-OSIER — BinduButton.
 * Tombol bulat 72dp di bawah tengah layar.
 *
 * Gesture:
 * - Tap → buka chat / kirim pesan
 * - Hold ≥3 detik → push-to-talk (VoiceForegroundService)
 * - Long-press + drag → Percik (partikel cahaya)
 *
 * ⚠️ consume() wajib pada drag untuk mencegah bubble ke parent (§9.3).
 */
package com.zonaosier.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.zonaosier.ui.theme.LocalZonaColors
import com.zonaosier.ui.theme.SkinAnimationConfig
import com.zonaosier.ui.theme.LocalAnimationConfig

enum class BinduState { IDLE, LISTENING, PROCESSING, SPEAKING }

@Composable
fun BinduButton(
    state: BinduState = BinduState.IDLE,
    onTap: () -> Unit = {},
    onHold: () -> Unit = {},
    onPercik: (Float, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current
    val animConfig = LocalAnimationConfig.current
    var isPressed by remember { mutableStateOf(false) }

    // Scale animation on press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.08f else 1.0f,
        animationSpec = tween(150, easing = EaseOutCubic),
        label = "bindu_scale"
    )

    // Glow animation for SPEAKING state
    val glowPulse by animateFloatAsState(
        targetValue = if (state == BinduState.SPEAKING) 1.5f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSineWave),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bindu_glow"
    )

    // LISTENING: outer ring pulse
    val listeningPulse by animateFloatAsState(
        targetValue = if (state == BinduState.LISTENING) 1.3f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSineWave),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bindu_listening"
    )

    Box(
        modifier = modifier
            .size(72.dp)
            .scale(scale)
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { onTap() },
                    onPress = {
                        isPressed = true
                        val holdStart = System.currentTimeMillis()
                        tryAwaitRelease()
                        val holdDuration = System.currentTimeMillis() - holdStart
                        isPressed = false
                        if (holdDuration >= 3000) onHold()
                    }
                )
            }
            .pointerInput(state) {
                detectDragGestures { change, dragAmount ->
                    // v5.1.2: Wajib consume event
                    change.consume()
                    onPercik(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(72.dp * glowPulse * listeningPulse)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                colors.binduGlow.copy(alpha = 0.3f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2, size.height / 2),
                            radius = size.width / 2
                        )
                    )
                }
        )

        // Core circle
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(colors.binduCore)
                }
        )
    }
}
