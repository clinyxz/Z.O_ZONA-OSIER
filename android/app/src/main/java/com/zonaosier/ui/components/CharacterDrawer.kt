/**
 * ZONA-OSIER — CharacterDrawer.
 * Panel geser dari kiri untuk memilih karakter aktif.
 * Swipe kanan di Chat atau tap avatar untuk membuka.
 *
 * Menampilkan:
 * - Daftar karakter dengan avatar
 * - Indikator memori scope (SHARED / ISOLATED)
 * - Badge model binding
 * - Tombol Impor Karakter
 */
package com.zonaosier.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zonaosier.memory.entity.CharacterCard
import com.zonaosier.memory.entity.MemoryScope
import com.zonaosier.ui.theme.LocalZonaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDrawer(
    characters: List<CharacterCard>,
    activeCharacterId: String?,
    onCharacterSelected: (CharacterCard) -> Unit,
    onImportClicked: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current

    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        containerColor = colors.surface,
        modifier = modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Karakter",
                    color = colors.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = colors.onSurface)
                }
            }

            // Import button
            OutlinedButton(
                onClick = onImportClicked,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                border = BorderStroke(1.dp, colors.accent),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Impor Karakter", fontSize = 14.sp)
            }

            HorizontalDivider(color = colors.glassBorder, thickness = 1.dp)

            // Character list
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(characters, key = { it.id }) { character ->
                    CharacterListItem(
                        character = character,
                        isActive = character.id == activeCharacterId,
                        onClick = {
                            onCharacterSelected(character)
                            onClose()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterListItem(
    character: CharacterCard,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalZonaColors.current

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) colors.glassBackground else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (character.avatarUri != null) {
                    AsyncImage(
                        model = character.avatarUri,
                        contentDescription = character.name,
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                    )
                } else {
                    Text(
                        character.name.take(1).uppercase(),
                        color = colors.accent,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    character.name,
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Memory scope badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (character.memoryScope) {
                            MemoryScope.SHARED -> colors.success.copy(alpha = 0.2f)
                            MemoryScope.ISOLATED -> colors.accent.copy(alpha = 0.2f)
                        }
                    ) {
                        Text(
                            character.memoryScope.name,
                            color = when (character.memoryScope) {
                                MemoryScope.SHARED -> colors.success
                                MemoryScope.ISOLATED -> colors.accent
                            },
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        character.modelBinding.mode.displayName,
                        color = colors.onSurface.copy(alpha = 0.5f),
                        fontSize = 10.sp
                    )
                }
            }

            // Active indicator
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                    .background(colors.success, CircleShape)
                )
            }
        }
    }
}
