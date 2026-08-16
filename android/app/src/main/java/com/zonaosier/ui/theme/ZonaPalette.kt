/**
 * ZONA-OSIER — ZonaPalette.
 * Token warna Compose untuk 4 skin.
 *
 * Setiap skin mendefinisikan:
 * - Warna primer, sekunder, aksen
 * - Warna Nadi (status bar edge glow)
 * - Tingkat transparansi (alpha untuk glass effect)
 * - Warna glass background
 *
 * Nama dan palet orisinal — lihat §9.5 Catatan Provenance.
 */
package com.zonaosier.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nadi status warna — digunakan oleh NadiBar.
 */
enum class NadiColor(val color: Color, val label: String) {
    NORMAL(Color(0xFF64b5f6), "Normal"),
    WARNING(Color(0xFFfbbf24), "Warning"),
    CRITICAL(Color(0xFFf87171), "Critical"),
    FROZEN(Color(0xFF9E9E9E), "Bekuk")
}

/**
 * Data class warna satu skin.
 */
data class ZonaSkinColors(
    // === Primer ===
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    // === Background ===
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    // === Aksen ===
    val accent: Color,
    val accentDim: Color,
    // === Glass ===
    val glassBackground: Color,
    val glassBorder: Color,
    val glassBlur: Float,  // 0-30f dp
    // === Nadi ===
    val nadiDefault: NadiColor,
    // === Status ===
    val success: Color,
    val warning: Color,
    val error: Color,
    // === Bindu ===
    val binduGlow: Color,
    val binduCore: Color,
    // === Chat ===
    val userBubble: Color,
    val aiBubble: Color,
    val inputField: Color,
    // === Percik ===
    val percikColor: Color
)

/**
 * Palet untuk 4 skin ZONA-OSIER.
 */
object ZonaPalette {

    /**
     * Sunyata — Flat, clean, minimal. Default.
     * Abu-abu gelap, putih lembut, aksen emas.
     * Tidak ada animasi khusus.
     */
    val SUNYATA = ZonaSkinColors(
        primary = Color(0xFFE8C547),
        onPrimary = Color(0xFF0D0D0F),
        secondary = Color(0xFF47E8A0),
        onSecondary = Color(0xFF0D0D0F),
        background = Color(0xFF0D0D0F),
        surface = Color(0xFF0D0D0F),
        surfaceVariant = Color(0xFF1A1A1F),
        onBackground = Color(0xFFF0F0F0),
        onSurface = Color(0xFFF0F0F0),
        accent = Color(0xFFE8C547),
        accentDim = Color(0x66E8C547),
        glassBackground = Color(0x1AFFFFFF),
        glassBorder = Color(0x33FFFFFF),
        glassBlur = 0f,
        nadiDefault = NadiColor.NORMAL,
        success = Color(0xFF81c784),
        warning = Color(0xFFfbbf24),
        error = Color(0xFFE84747),
        binduGlow = Color(0xFFE8C547),
        binduCore = Color(0xFFE8C547),
        userBubble = Color(0x1AFFFFFF),
        aiBubble = Color(0x0DFFFFFF),
        inputField = Color(0x1A1A1F),
        percikColor = Color(0xFFE8C547)
    )

    /**
     * Embun — Translucent + blur backdrop.
     * Biru kehijauan, transparansi tinggi.
     * Animasi: blur breathing (subtle scale pulse).
     */
    val EMBUN = ZonaSkinColors(
        primary = Color(0xFF4DD0E1),
        onPrimary = Color(0xFF0A1A1F),
        secondary = Color(0xFF80CBC4),
        onSecondary = Color(0xFF0A1A1F),
        background = Color(0xFF0A1A1F),
        surface = Color(0xFF0F2229),
        surfaceVariant = Color(0xFF163339),
        onBackground = Color(0xFFE0F7FA),
        onSurface = Color(0xFFE0F7FA),
        accent = Color(0xFF4DD0E1),
        accentDim = Color(0x664DD0E1),
        glassBackground = Color(0x26FFFFFF),
        glassBorder = Color(0x40FFFFFF),
        glassBlur = 16f,
        nadiDefault = NadiColor.NORMAL,
        success = Color(0xFF80CBC4),
        warning = Color(0xFFffe082),
        error = Color(0xFFef9a9a),
        binduGlow = Color(0xFF4DD0E1),
        binduCore = Color(0xFF80CBC4),
        userBubble = Color(0x20FFFFFF),
        aiBubble = Color(0x10FFFFFF),
        inputField = Color(0xFF163339),
        percikColor = Color(0xFF4DD0E1)
    )

    /**
     * Denyut — Animasi geometrik mengikuti kategori BPM.
     * Merah muda, oranye lembut.
     * Pulse mengikuti kategori BPM terakhir dari Health Connect.
     */
    val DENYUT = ZonaSkinColors(
        primary = Color(0xFFf48fb1),
        onPrimary = Color(0xFF1A0A10),
        secondary = Color(0xFFffb74d),
        onSecondary = Color(0xFF1A0A10),
        background = Color(0xFF1A0A10),
        surface = Color(0xFF241018),
        surfaceVariant = Color(0xFF2E1520),
        onBackground = Color(0xFFFCE4EC),
        onSurface = Color(0xFFFCE4EC),
        accent = Color(0xFFf48fb1),
        accentDim = Color(0x66f48fb1),
        glassBackground = Color(0x20FFFFFF),
        glassBorder = Color(0x33FFFFFF),
        glassBlur = 8f,
        nadiDefault = NadiColor.NORMAL,
        success = Color(0xFF81c784),
        warning = Color(0xFFffb74d),
        error = Color(0xFFef5350),
        binduGlow = Color(0xFFf48fb1),
        binduCore = Color(0xFFffb74d),
        userBubble = Color(0x20FFFFFF),
        aiBubble = Color(0x10FFFFFF),
        inputField = Color(0xFF2E1520),
        percikColor = Color(0xFFf48fb1)
    )

    /**
     * Cakra — Line-art + concentric rings.
     * Ungu, emas.
     * Animasi: rotasi ring, glow radial.
     */
    val CAKRA = ZonaSkinColors(
        primary = Color(0xFFCE93D8),
        onPrimary = Color(0xFF0F0A14),
        secondary = Color(0xFFFFD54F),
        onSecondary = Color(0xFF0F0A14),
        background = Color(0xFF0F0A14),
        surface = Color(0xFF18101E),
        surfaceVariant = Color(0xFF221630),
        onBackground = Color(0xFFF3E5F5),
        onSurface = Color(0xFFF3E5F5),
        accent = Color(0xFFCE93D8),
        accentDim = Color(0x66CE93D8),
        glassBackground = Color(0x1AFFFFFF),
        glassBorder = Color(0x2FFFFFFF),
        glassBlur = 12f,
        nadiDefault = NadiColor.NORMAL,
        success = Color(0xFFAED581),
        warning = Color(0xFFFFD54F),
        error = Color(0xFFEF5350),
        binduGlow = Color(0xFFCE93D8),
        binduCore = Color(0xFFFFD54F),
        userBubble = Color(0x18FFFFFF),
        aiBubble = Color(0x0CFFFFFF),
        inputField = Color(0xFF221630),
        percikColor = Color(0xFFFFD54F)
    )

    /** Semua skin yang tersedia. */
    val ALL_SKINS = mapOf(
        "sunyata" to SUNYATA,
        "embun" to EMBUN,
        "denyut" to DENYUT,
        "cakra" to CAKRA
    )
}
