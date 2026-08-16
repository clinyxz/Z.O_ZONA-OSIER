/**
 * ZONA-OSIER — SecurityScreen.
 * Status keamanan, audit log terbaru, tombol freeze.
 *
 * Spec §9.4 Screen 6:
 * - Status Group: Shell Security, Audit Log, Voice-Print
 * - Recent Audit Log: 4 item terakhir
 * - Freeze Button: tombol merah besar
 */
package com.zonaosier.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zonaosier.security.AuditStatus
import com.zonaosier.ui.components.NadiBar
import com.zonaosier.ui.theme.LocalZonaColors

data class AuditEntryUi(
    val toolName: String,
    val command: String,
    val status: AuditStatus,
    val timestamp: Long,
    val reason: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    auditEntries: List<AuditEntryUi>,
    isFrozen: Boolean,
    onFreeze: () -> Unit,
    onUnfreeze: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current
    val scrollState = rememberScrollState()

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            NadiBar(modifier = Modifier.fillMaxWidth())

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Kembali", tint = colors.onSurface)
                }
                Text("Keamanan & Audit", color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status Group
                Text("Status", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusRow("Shell Security Policy", "Aktif", colors.success)
                        StatusRow("Voice-Print", "Tidak aktif", colors.onSurface.copy(alpha = 0.5f))
                        StatusRow("Biometric Gate", "Aktif", colors.success)
                        StatusRow("Audit Log", "${auditEntries.size} entri", colors.onSurface)
                        StatusRow("Freeze Agent", if (isFrozen) "AKTIF" else "Nonaktif", if (isFrozen) colors.error else colors.success)
                    }
                }

                // Recent Audit Log
                Text("Log Audit Terbaru", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        auditEntries.take(4).forEach { entry ->
                            AuditEntryRow(entry = entry, colors = colors)
                        }
                        if (auditEntries.isEmpty()) {
                            Text("Belum ada log.", color = colors.onSurface.copy(alpha = 0.4f), fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                        }
                    }
                }

                // Freeze Button
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = if (isFrozen) onUnfreeze else onFreeze,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFrozen) colors.success else colors.error
                    )
                ) {
                    Icon(
                        if (isFrozen) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isFrozen) "Unfreeze Agent" else "FREEZE AGENT — Cabut Semua Akses",
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        // Freeze overlay
        if (isFrozen) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⏸", fontSize = 48.sp, color = colors.onSurface)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Agent Dibekukan", color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Semua akses elevated telah dicabut.", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color) {
    val colors = LocalZonaColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = colors.onSurface.copy(alpha = 0.7f), fontSize = 13.sp)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AuditEntryRow(entry: AuditEntryUi, colors: com.zonaosier.ui.theme.ZonaSkinColors) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(8.dp).background(
                if (entry.status.name.contains("APPROVED")) colors.success else colors.error,
                androidx.compose.foundation.shape.CircleShape
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.toolName, color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(entry.command, color = colors.onSurface.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1)
        }
        Text(
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp)),
            color = colors.onSurface.copy(alpha = 0.4f), fontSize = 10.sp
        )
    }
}