/**
 * ZONA-OSIER — MemoryTimelineScreen.
 * Timeline evolusi memori dari GitHub-as-Cloud.
 *
 * Spec §9.4 Screen 5:
 * - Header: "Memory Timeline" + subtitle
 * - Timeline Items: Vertikal, dot berwarna, tanggal, pesan
 * - Lazy loading: pagination 20 item (JGit git log tidak dipanggil setiap buka)
 */
package com.zonaosier.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zonaosier.ui.components.NadiBar
import com.zonaosier.ui.theme.LocalZonaColors

/**
 * Satu entri timeline dari commit Git.
 */
data class TimelineEntry(
    val id: String,
    val date: String,
    val message: String,
    val commitHash: String,
    val filesChanged: Int,
    val entryType: TimelineType
)

enum class TimelineType { PERSONALITY, CHARACTER, SETTINGS, SYNC }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryTimelineScreen(
    entries: List<TimelineEntry>,
    isLoading: Boolean,
    onLoadMore: () -> Unit,
    onSync: () -> Unit,
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Kembali", tint = colors.onSurface)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Memory Timeline", color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "GitHub-as-Cloud \u00B7 repo: private \u00B7 encrypted",
                    color = colors.onSurface.copy(alpha = 0.4f), fontSize = 11.sp
                )
            }
            IconButton(onClick = onSync) {
                Icon(Icons.Default.CloudDownload, "Sync", tint = colors.accent)
            }
        }

        HorizontalDivider(color = colors.glassBorder, thickness = 1.dp)

        if (entries.isEmpty() && !isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Belum ada riwayat memori.", color = colors.onSurface.copy(alpha = 0.5f), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Sinkronisasi pertama untuk memulai timeline.", color = colors.onSurface.copy(alpha = 0.3f), fontSize = 12.sp)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                entries.forEachIndexed { index, entry ->
                    TimelineItem(entry = entry, colors = colors, isLast = index == entries.lastIndex)
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
                        color = colors.accent,
                        strokeWidth = 2.dp
                    )
                } else {
                    LaunchedEffect(entries.size) {
                        // Trigger load more saat scroll ke bawah
                        onLoadMore()
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun TimelineItem(
    entry: TimelineEntry,
    colors: com.zonaosier.ui.theme.ZonaSkinColors,
    isLast: Boolean
) {
    val dotColor = when (entry.entryType) {
        TimelineType.PERSONALITY -> colors.accent
        TimelineType.CHARACTER -> colors.secondary
        TimelineType.SETTINGS -> colors.onSurface.copy(alpha = 0.5f)
        TimelineType.SYNC -> colors.success
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        // Timeline line + dot
        Column(
            modifier = Modifier.width(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(12.dp).clip(CircleShape).background(dotColor)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier.width(2.dp).weight(1f).background(colors.glassBorder)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(modifier = Modifier.weight(1f).padding(bottom = 16.dp)) {
            Text(entry.date, color = colors.onSurface.copy(alpha = 0.5f), fontSize = 11.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(entry.message, color = colors.onSurface, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    entry.commitHash.take(7),
                    color = colors.onSurface.copy(alpha = 0.3f), fontSize = 10.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "${entry.filesChanged} file",
                    color = colors.onSurface.copy(alpha = 0.3f), fontSize = 10.sp
                )
            }
        }
    }
}
