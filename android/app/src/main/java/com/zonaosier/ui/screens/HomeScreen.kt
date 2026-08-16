/**
 * ZONA-OSIER — HomeScreen.
 * Screen utama: greeting, dual-brain indicator, weather card, quick actions, Bindu.
 *
 * Spec §9.4 Screen 1:
 * - Status bar (jam, tier badge, thermal dot, sinyal)
 * - Nadi edge glow
 * - Greeting
 * - Dual-Brain Indicator
 * - Weather/Status Card
 * - Quick Actions 3x2
 * - Bindu di bawah
 */
package com.zonaosier.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zonaosier.governor.GovernorState
import com.zonaosier.model.ModelTierSelector
import com.zonaosier.model.RouterStatus
import com.zonaosier.ui.components.*
import com.zonaosier.ui.theme.LocalZonaColors

enum class QuickAction(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Default.Chat),
    VOICE("Suara", Icons.Default.Mic),
    SETTINGS("Setelan", Icons.Default.Settings),
    SCREEN("Layar", Icons.Default.PhoneAndroid),
    SHELL("Shell", Icons.Default.Terminal),
    MEMORY("Memori", Icons.Default.History)
}

@Composable
fun HomeScreen(
    governorState: GovernorState,
    routerStatus: RouterStatus,
    activeTier: String,
    onQuickAction: (QuickAction) -> Unit,
    onCharacterDrawerOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 20.dp)
    ) {
        // Nadi
        NadiBar(
            status = NadiStatus.from(governorState, isFrozen = false),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Greeting row with avatar
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Selamat ${getGreetingTime()},",
                color = colors.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            // Avatar tap → character drawer
            IconButton(onClick = onCharacterDrawerOpen) {
                Icon(Icons.Default.Person, contentDescription = "Karakter", tint = colors.onSurface)
            }
        }

        Text(
            "ZONA-OSIER siap melayani.",
            color = colors.onSurface.copy(alpha = 0.6f),
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Dual-Brain Indicator
        DualBrainIndicator(colors = colors, routerStatus = routerStatus)

        Spacer(modifier = Modifier.height(16.dp))

        // Status Card (Weather-like)
        StatusCard(governorState = governorState, activeTier = activeTier, colors = colors)

        Spacer(modifier = Modifier.height(20.dp))

        // Quick Actions Grid 3x2
        Text(
            "Aksi Cepat",
            color = colors.onSurface.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(QuickAction.entries) { action ->
                QuickActionButton(action = action, colors = colors, onClick = { onQuickAction(action) })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bindu
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            BinduButton(
                modifier = Modifier.padding(bottom = 24.dp),
                onTap = { onQuickAction(QuickAction.CHAT) },
                onHold = { onQuickAction(QuickAction.VOICE) }
            )
            Text(
                "Tap untuk bicara",
                color = colors.onSurface.copy(alpha = 0.4f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun DualBrainIndicator(colors: com.zonaosier.ui.theme.ZonaSkinColors, routerStatus: RouterStatus) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.glassBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BrainPanel("System Thinker", routerStatus.activeProvider, colors.accent, colors)
            BrainPanel("Voice Assistant", routerStatus.activeModel ?: "Standby", colors.secondary, colors)
        }
    }
}

@Composable
private fun BrainPanel(title: String, subtitle: String, accent: androidx.compose.ui.graphics.Color, colors: com.zonaosier.ui.theme.ZonaSkinColors) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            subtitle,
            color = accent,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun StatusCard(governorState: GovernorState, activeTier: String, colors: com.zonaosier.ui.theme.ZonaSkinColors) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.glassBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = colors.onSurface.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Tier: $activeTier", color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Baterai: ${governorState.batteryLevel}% | ${if (governorState.isOnline) "Online" else "Offline"}",
                    color = colors.onSurface.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
            // Thermal dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        when {
                            governorState.shouldStop -> colors.error
                            governorState.shouldThrottle -> colors.warning
                            governorState.shouldDownscale -> colors.warning
                            else -> colors.success
                        }
                    )
            )
        }
    }
}

@Composable
private fun QuickActionButton(action: QuickAction, colors: com.zonaosier.ui.theme.ZonaSkinColors, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(action.icon, contentDescription = action.label, tint = colors.accent, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(action.label, color = colors.onSurface, fontSize = 11.sp)
        }
    }
}

private fun getGreetingTime(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 0..11 -> "pagi"
        in 12..14 -> "siang"
        in 15..17 -> "sore"
        else -> "malam"
    }
}
