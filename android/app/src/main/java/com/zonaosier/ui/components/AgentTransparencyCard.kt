/**
 * ZONA-OSIER — AgentTransparencyCard.
 * Card transparan yang muncul saat System Thinker aktif.
 *
 * Menampilkan:
 * 1. Header: Dot hijau berkedip + label
 * 2. Plan Steps: ✓ Done, ● Active, ○ Pending
 * 3. Confidence Bar: kategori (bukan persentase)
 * 4. Tool Call Detail: expandable
 *
 * ⚠️ Tidak menampilkan persentase absolut — self-consistency category only.
 */
package com.zonaosier.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zonaosier.ui.theme.LocalZonaColors

enum class StepStatus { DONE, ACTIVE, PENDING }
enum class ConfidenceCategory(val label: String, val colorHex: Long) {
    HIGH("Konsisten Tinggi", 0xFF81c784),
    MEDIUM("Konsisten Sedang", 0xFFfbbf24),
    LOW("Perlu Verifikasi", 0xFFffb74d)
}

data class PlanStep(val description: String, val status: StepStatus)

@Composable
fun AgentTransparencyCard(
    steps: List<PlanStep>,
    confidenceCategory: ConfidenceCategory,
    currentTool: String?,
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current
    var showToolDetail by remember { mutableStateOf(false) }

    // Blinking green dot
    val infiniteTransition = rememberInfiniteTransition(label = "atc_dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSineWave),
            repeatMode = RepeatMode.Reverse
        ),
        label = "atc_dot_alpha"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.glassBackground
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(androidx.compose.ui.graphics.Color(0xFF81c784).copy(alpha = dotAlpha))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "System Thinker — Berpikir",
                    color = colors.onSurface,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Steps
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (step.status) {
                            StepStatus.DONE -> "\u2713"
                            StepStatus.ACTIVE -> "\u25CF"
                            StepStatus.PENDING -> "\u25CB"
                        },
                        color = when (step.status) {
                            StepStatus.DONE -> colors.success
                            StepStatus.ACTIVE -> colors.accent
                            StepStatus.PENDING -> colors.onSurface.copy(alpha = 0.4f)
                        },
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        step.description,
                        color = colors.onSurface.copy(
                            alpha = if (step.status == StepStatus.PENDING) 0.5f else 1.0f
                        ),
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Confidence bar
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    confidenceCategory.label,
                    color = androidx.compose.ui.graphics.Color(confidenceCategory.colorHex),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(colors.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(
                                when (confidenceCategory) {
                                    ConfidenceCategory.HIGH -> 0.8f
                                    ConfidenceCategory.MEDIUM -> 0.5f
                                    ConfidenceCategory.LOW -> 0.3f
                                }
                            )
                            .background(androidx.compose.ui.graphics.Color(confidenceCategory.colorHex))
                    )
                }
            }

            // Tool call detail (expandable)
            if (currentTool != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { showToolDetail = !showToolDetail }) {
                    Text(
                        if (showToolDetail) "Sembunyikan detail tool" else "Lihat detail tool",
                        color = colors.accent,
                        fontSize = 11.sp
                    )
                }
                if (showToolDetail) {
                    Surface(
                        color = colors.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            currentTool,
                            color = colors.onSurface.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
