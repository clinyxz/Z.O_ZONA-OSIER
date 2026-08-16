/**
 * ZONA-OSIER — Compose Theme.
 * Dikelola oleh SkinEngine untuk 4 tema.
 * Menggunakan CompositionLocalProvider untuk menyebarkan warna skin.
 */
package com.zonaosier.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun ZonaOsierTheme(
    colors: ZonaSkinColors = ZonaPalette.SUNYATA,
    animationConfig: SkinAnimationConfig = SkinAnimationConfig(),
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        secondary = colors.secondary,
        onSecondary = colors.onSecondary,
        background = colors.background,
        surface = colors.surface,
        surfaceVariant = colors.surfaceVariant,
        onBackground = colors.onBackground,
        onSurface = colors.onSurface,
        error = colors.error,
        onError = colors.onBackground
    )

    CompositionLocalProvider(
        LocalZonaColors provides colors,
        LocalSkinId provides (colors.glassBlur.toString()),
        LocalAnimationConfig provides animationConfig
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}