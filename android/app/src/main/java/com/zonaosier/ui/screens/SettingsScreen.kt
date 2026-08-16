/**
 * ZONA-OSIER — SettingsScreen.
 * Konfigurasi model, provider, keamanan, suara, dan sistem.
 *
 * Spec §9.4 Screen 4:
 * - Group 1: Model & Voice
 * - Group 2: Provider Quota (TPM display)
 * - Group 3: Security & Persona
 * - Group 4: System (Thermal, GitHub, Skin)
 * - Group 5: TTS Premium (kredit)
 */
package com.zonaosier.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zonaosier.governor.GovernorState
import com.zonaosier.memory.entity.ModelMode
import com.zonaosier.model.ProviderStatus
import com.zonaosier.ui.components.NadiBar
import com.zonaosier.ui.theme.LocalZonaColors
import com.zonaosier.ui.theme.SkinEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    governorState: GovernorState,
    providers: List<ProviderStatus>,
    selectedSkin: String,
    onSkinChange: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
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
            Text("Setelan", color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Group 1: Model & Voice
            SettingsGroup("Model & Suara") {
                SettingsItem("Model Lokal", "Auto (deteksi RAM)") { }
                SettingsItem("Mode STT", "Hybrid (Vosk + Cloud)") { }
                SettingsItem("Router TTS", "Live (MiniMax/ElevenLabs)") { }
            }

            // Group 2: Provider Quota
            SettingsGroup("Provider Quota") {
                providers.filter { !it.isLocal }.forEach { provider ->
                    val quota = provider.quotaState
                    val tpmText = if (quota != null && quota.remainingTokensPerMinute > 0) {
                        "TPM: ${formatNumber(quota.remainingTokensPerMinute)}/${formatNumber(quota.remainingTokensPerMinute + (quota.remainingTokens - quota.remainingTokens))}"
                    } else {
                        "Quota: ${if (quota?.hasQuota() == true) "Tersedia" else "Tidak diketahui"}"
                    }
                    SettingsItem(
                        provider.providerName,
                        tpmText,
                        tint = if (provider.isAvailable) colors.onSurface else colors.onSurface.copy(alpha = 0.4f)
                    ) { }
                }
            }

            // Group 3: Security & Persona
            SettingsGroup("Keamanan & Persona") {
                SettingsItem("Bahasa Persona", "Indonesia Baku (KBBI)") { }
                SettingsItem("Voice-Print", "Tidak aktif") { }
                SettingsItem("Biometric Gate", "Aktif (sidik jari)") { }
                SettingsItem("Audit Log", "Lihat log aksi tool") { }
            }

            // Group 4: System
            SettingsGroup("Sistem") {
                SettingsItem(
                    "Thermal Governor",
                    "Baterai: ${governorState.batteryLevel}% | ${governorState.getRecommendation()}"
                ) { }
                SettingsItem("GitHub Sync", "Private repo, encrypted") { }

                // Skin Selection
                Text("Skin", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("sunyata", "embun", "denyut", "cakra").forEach { skinId ->
                        val skinLabel = skinId.replaceFirstChar { it.uppercase() }
                        FilterChip(
                            selected = selectedSkin == skinId,
                            onClick = { onSkinChange(skinId) },
                            label = { Text(skinLabel, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accent.copy(alpha = 0.3f),
                                selectedLabelColor = colors.accent
                            )
                        )
                    }
                }
            }

            // Group 5: TTS Premium
            SettingsGroup("TTS Premium") {
                SettingsItem("MiniMax Kredit", "Cek kredit di pengaturan TTS") { }
                SettingsItem("ElevenLabs Kredit", "~10.000 kredit/bulan") { }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalZonaColors.current
    Text(title, color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(4.dp))
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(4.dp)) { content() }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    tint: Color? = null,
    onClick: () -> Unit = {}
) {
    val colors = LocalZonaColors.current
    ListItem(
        headlineContent = { Text(title, color = tint ?: colors.onSurface, fontSize = 14.sp) },
        supportingContent = { Text(subtitle, color = colors.onSurface.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 2) },
        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun formatNumber(n: Int): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
    n >= 1_000 -> "${"%.1f".format(n / 1_000.0)}K"
    else -> n.toString()
}