/**
 * ZONA-OSIER — PercikEffect.
 * Partikel cahaya yang mengikuti jari saat long-press + drag di Bindu.
 * Partikel fade out dan hilang.
 */
package com.zonaosier.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zonaosier.ui.theme.LocalZonaColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Satu partikel percik.
 */
data class PercikParticle(
    val id: Int,
    val offsetX: Float,
    val offsetY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val life: Float = 1.0f,  // 1.0 → 0.0
    val size: Float
)

@Composable
fun PercikEffect(
    particles: List<PercikParticle>,
    modifier: Modifier = Modifier
) {
    val colors = LocalZonaColors.current

    Box(modifier = modifier.fillMaxSize()) {
        particles.forEach { particle ->
            if (particle.life <= 0f) return@forEach
            Box(
                modifier = Modifier
                    .offset(particle.offsetX.dp, particle.offsetY.dp)
                    .size(particle.size.dp)
                    .alpha(particle.life)
                    .drawBehind {
                        drawCircle(colors.percikColor.copy(alpha = particle.life * 0.8f))
                    }
            )
        }
    }
}

/**
 * Mengelola lifecycle partikel percik.
 */
@Stable
class PercikState {
    private val _particles = mutableStateListOf<PercikParticle>()
    val particles: List<PercikParticle> get() = _particles.toList()

    private var nextId = 0

    /**
     * Tambah partikel saat drag terdeteksi.
     */
    fun onDrag(dx: Float, dy: Float) {
        // Spawn 2-4 partikel per drag event
        val count = (2..4).random()
        repeat(count) {
            val angle = atan2(dy, dx) + ((-30..30).random() * Math.PI / 180.0)
            val speed = (1f..3f).random()
            _particles.add(
                PercikParticle(
                    id = nextId++,
                    offsetX = 0f,
                    offsetY = 0f,
                    velocityX = cos(angle).toFloat() * speed,
                    velocityY = sin(angle).toFloat() * speed,
                    size = (2f..5f).random()
                )
            )
        }

        // Limit partikel
        while (_particles.size > 50) _particles.removeAt(0)
    }

    /**
     * Update partikel setiap frame.
     */
    fun update() {
        val iterator = _particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            val updated = p.copy(
                offsetX = p.offsetX + p.velocityX,
                offsetY = p.offsetY + p.velocityY,
                velocityX = p.velocityX * 0.95f,  // Friction
                velocityY = p.velocityY * 0.95f,
                life = p.life - 0.03f
            )
            if (updated.life <= 0f) {
                iterator.remove()
            } else {
                _particles[_particles.indexOf(p)] = updated
            }
        }
    }

    fun clear() = _particles.clear()
}
