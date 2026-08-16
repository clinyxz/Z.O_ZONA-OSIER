/**
 * ZONA-OSIER — SkinEngine.
 * Mengatur tema dinamis berdasarkan pilihan skin.
 *
 * Skin disimpan di SharedPreferences dan diterapkan
 * via CompositionLocalProvider ke seluruh Compose tree.
 *
 * Bindu animasi spesifik per skin:
 * - Sunyata: Tidak ada animasi khusus
 * - Embun: Blur breathing (subtle scale pulse 4s cycle)
 * - Denyut: Pulse mengikuti kategori BPM (1.5-3s cycle)
 * - Cakra: Rotasi ring (8s cycle) + glow radial
 */
package com.zonaosier.ui.theme

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Kategori BPM dari Health Connect (bukan real-time).
 */
enum class BpmCategory(val cycleMs: Long, val color: Color) {
    LOW(3000L, Color(0xFF64b5f6)),       // <60 BPM
    NORMAL(2000L, Color(0xFF81c784)),     // 60-100 BPM
    HIGH(1500L, Color(0xFFffb74d)),      // >100 BPM
    UNKNOWN(2500L, Color(0xFF9E9E9E))    // Tidak ada data
}

/**
 * Animasi config per skin.
 */
data class SkinAnimationConfig(
    val hasBlurBreathing: Boolean = false,
    val blurCycleMs: Long = 4000L,
    val hasPulse: Boolean = false,
    val pulseCycleMs: Long = 2500L,
    val hasRingRotation: Boolean = false,
    val ringRotationCycleMs: Long = 8000L,
    val hasRadialGlow: Boolean = false
)
/**
 * CompositionLocal untuk akses skin colors dari mana saja.
 */
val LocalZonaColors = staticCompositionLocalOf { ZonaPalette.SUNYATA }
val LocalSkinId = staticCompositionLocalOf { "sunyata" }
val LocalAnimationConfig = staticCompositionLocalOf { SkinAnimationConfig() }

class SkinEngine(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "zona_skin"
        private const val KEY_SKIN = "skin_id"
        private const val DEFAULT_SKIN = "sunyata"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentSkin = MutableStateFlow(prefs.getString(KEY_SKIN, DEFAULT_SKIN) ?: DEFAULT_SKIN)
    val currentSkin: StateFlow<String> = _currentSkin.asStateFlow()

    private val _bpmCategory = MutableStateFlow(BpmCategory.UNKNOWN)
    val bpmCategory: StateFlow<BpmCategory> = _bpmCategory.asStateFlow()

    /**
     * Dapatkan warna skin saat ini.
     */
    fun getColors(): ZonaSkinColors {
        return ZonaPalette.ALL_SKINS[_currentSkin.value] ?: ZonaPalette.SUNYATA
    }

    /**
     * Ganti skin. Simpan ke preferences.
     */
    fun setSkin(skinId: String) {
        val normalized = skinId.lowercase()
        if (ZonaPalette.ALL_SKINS.containsKey(normalized)) {
            prefs.edit().putString(KEY_SKIN, normalized).apply()
            _currentSkin.value = normalized
        }
    }

    /**
     * Update kategori BPM dari Health Connect.
     * Dipanggil oleh observer yang membaca data HR berkala.
     */
    fun updateBpmCategory(category: BpmCategory) {
        _bpmCategory.value = category
    }

    /**
     * Dapatkan animasi config untuk skin saat ini.
     */
    fun getAnimationConfig(): SkinAnimationConfig {
        return when (_currentSkin.value) {
            "embun" -> SkinAnimationConfig(
                hasBlurBreathing = true,
                blurCycleMs = 4000L
            )
            "denyut" -> SkinAnimationConfig(
                hasPulse = true,
                pulseCycleMs = _bpmCategory.value.cycleMs
            )
            "cakra" -> SkinAnimationConfig(
                hasRingRotation = true,
                ringRotationCycleMs = 8000L,
                hasRadialGlow = true
            )
            else -> SkinAnimationConfig() // Sunyata: no animation
        }
    }

    /**
     * Konversi ZonaSkinColors ke Material3 ColorScheme.
     */
    fun toColorScheme(colors: ZonaSkinColors): ColorScheme {
        return darkColorScheme(
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
    }
}
