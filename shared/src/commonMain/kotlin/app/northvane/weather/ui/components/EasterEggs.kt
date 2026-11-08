package app.northvane.weather.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Easter egg: tap Radar six times quickly and a cow tumbles across the
 * screen, Twister-style. Purely decorative; it doesn't block anything.
 */
@Composable
fun StormChaser(onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        listOf(
            async { progress.animateTo(1f, tween(2600, easing = LinearEasing)) },
            async { spin.animateTo(1080f, tween(2600, easing = LinearEasing)) },
        ).awaitAll()
        delay(1200)
        onDone()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val x = maxWidth * (progress.value * 1.3f - 0.15f)
        val y = maxHeight * (0.35f - 0.12f * sin(progress.value * 6.28f))
        Text("🐄", fontSize = 56.sp, modifier = Modifier.offset(x, y).rotate(spin.value))
        Text("🌪️", fontSize = 40.sp, modifier = Modifier.offset(x - 40.dp, y + 70.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
        ) {
            Text(
                "Holy cow! You're a real storm chaser.",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** Sunglasses drawn over the sun on the Sun card (long-press the sun). */
@Composable
fun Sunglasses(modifier: Modifier = Modifier) {
    Box(modifier) { Text("😎", fontSize = 22.sp) }
}
