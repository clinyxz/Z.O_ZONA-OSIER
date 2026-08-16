/**
 * ZONA-OSIER — NadiBar.
 * Bar horizontal di top edge layar (tinggi 3dp).
 * Gradient transparan → warna → transparan.
 * Warna dan animasi menunjukkan status sistem:
 * - Normal (biru): Semua OK, pulse lambat (3s cycle)
 * - Warning (kuning): Baterai <20% atau thermal moderate, pulse cepat (1.5s)
 * - Critical (merah): Thermal severe atau freeze aktif, blink (1s, opacity 0.3-1.0)
 * - Frozen (abu): Freeze agent aktif
 */
package com.zonaosier.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zonaosier.governor.GovernorState
import com.zonaosier.security.FreezeAgent
import com.zonaosier.ui.theme.LocalZonaColors
import com.zonaosier.ui.theme.NadiColor

/**
 * Status Nadi yang ditentukan dari GovernorState + FreezeAgent.
 */
enum class NadiStatus {
    NORMAL, WARNING, CRITICAL, FROZEN;

    companion object {
        fun from(governor: GovernorState, isFrozen: Boolean): NadiStatus = when {
            isFrozen -> FROZEN
            governor.shouldStop -> CRITICAL
            governor.shouldThrottle -> CRITICAL
            governor.shouldDownscale -> WARNING
            else -> NORMAL
        }
    }

    val color: Color
        get() = when (this) {
            NORMAL -> Color(0xFF64b5f6)
            WARNING -> Color(0xFFfbbf24)
            CRITICAL -> Color(0xFFf87171)
            FROZEN -> Color(0xFF9E9E9E)
        }

    val cycleMs: Long
        get() = when (this) {
            NORMAL -> 3000L
            WARNING -> 1500L
            CRITICAL -> 1000L
            FROZEN -> 2000L
        }
}

@Composable
fun NadiBar(
    status: NadiStatus = NadiStatus.NORMAL,
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "nadi")

    // Animate opacity berdasarkan status
    val alpha by when (status) {
        NadiStatus.CRITICAL -> {
            // Blink: opacity 0.3 → 1.0 → 0.3
            infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(status.cycleMs, easing = EaseInOutSineWave),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "nadi_blink"
            )
        }
        else -> {
            // Gentle pulse: 0.6 → 1.0 → 0.6
            infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(status.cycleMs, easing = EaseInOutSineWave),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "nadi_pulse"
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .drawBehind {
                val nadiColor = status.color.copy(alpha = alpha)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            nadiColor,
                            Color.Transparent
                        ),
                        startX = 0f,
                        endX = size.width
                    ),
                    size = size
                )
            }
    )
}
