package com.happypipaliya.mealtracker.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The six motion curves from the design reference. Each has one job, so motion stays consistent:
 * 01 linear (endless loops), 02 ease-in-out (state changes), 03 expo-out (arrivals),
 * 04 back-out (touch feedback), 05 elastic (results), 06 bounce (things landing).
 */
object Motion {
    val Linear: Easing = LinearEasing
    val EaseInOut: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    val ExpoOut: Easing = Easing { t -> if (t >= 1f) 1f else 1f - 2f.pow(-10f * t) }
    val BackOut: Easing = Easing { t ->
        val c1 = 1.70158f
        val x = t - 1f
        1f + (c1 + 1f) * x * x * x + c1 * x * x
    }
    val Elastic: Easing = Easing { t ->
        when {
            t <= 0f -> 0f
            t >= 1f -> 1f
            else -> 2f.pow(-10f * t) * sin((t * 10f - 0.75f) * (2f * PI.toFloat() / 3f)) + 1f
        }
    }
    val Bounce: Easing = Easing { t -> bounceOut(t) }

    const val QUICK = 180
    const val BASE = 380
    const val SLOW = 700
    const val HERO = 1100
    const val STAGGER = 55L

    private fun bounceOut(x: Float): Float {
        val n = 7.5625f
        val d = 2.75f
        return when {
            x < 1f / d -> n * x * x
            x < 2f / d -> (x - 1.5f / d).let { n * it * it + 0.75f }
            x < 2.5f / d -> (x - 2.25f / d).let { n * it * it + 0.9375f }
            else -> (x - 2.625f / d).let { n * it * it + 0.984375f }
        }
    }
}

/** Fades and slides in when first shown (expo-out). [index] staggers siblings so lists cascade. */
fun Modifier.enter(index: Int = 0, dx: Dp = 0.dp, dy: Dp = 28.dp, easing: Easing = Motion.ExpoOut): Modifier = composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(10) * Motion.STAGGER)
        p.animateTo(1f, tween(Motion.SLOW, easing = easing))
    }
    val density = LocalDensity.current
    val x = with(density) { dx.toPx() }
    val y = with(density) { dy.toPx() }
    graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationX = (1f - p.value) * x
        translationY = (1f - p.value) * y
    }
}

/** Presses down a little, then springs back with back-out when released. */
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = if (isPressed) tween(Motion.QUICK, easing = Motion.EaseInOut) else tween(Motion.BASE + 120, easing = Motion.BackOut),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A light band sweeping across while something loads (linear, endless). */
fun Modifier.shimmer(): Modifier = composed {
    val x by rememberInfiniteTransition(label = "shimmer")
        .animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(1300, easing = Motion.Linear)), label = "x")
    val glow = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    drawWithContent {
        drawContent()
        val w = size.width
        drawRect(Brush.linearGradient(listOf(Color.Transparent, glow, Color.Transparent), Offset(w * x - w * 0.4f, 0f), Offset(w * x + w * 0.4f, size.height)))
    }
}

/**
 * Last value each keyed counter, ring or bar showed. Coming back to a screen continues from there,
 * so numbers only animate when they actually change instead of replaying from 0 every time.
 */
object MotionMemory {
    val values = HashMap<String, Float>()
}

/** Counts up to [target] (expo-out): from 0 the first time, from the remembered value after that. */
@Composable
fun animateCount(target: Double, delayMs: Int = 0, durationMs: Int = Motion.HERO, memoryKey: String? = null): Double {
    val a = remember { Animatable(memoryKey?.let { MotionMemory.values[it] } ?: 0f) }
    LaunchedEffect(target) {
        memoryKey?.let { MotionMemory.values[it] = target.toFloat() }
        if (a.value != target.toFloat()) {
            delay(delayMs.toLong())
            a.animateTo(target.toFloat(), tween(durationMs, easing = Motion.ExpoOut))
        }
    }
    return a.value.toDouble()
}

/** 0 → 1 with [easing] when [key] changes; for pops and reveals. */
@Composable
fun rememberReveal(key: Any?, delayMs: Int = 0, durationMs: Int = 900, easing: Easing = Motion.Elastic): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(key) {
        a.snapTo(0f)
        delay(delayMs.toLong())
        a.animateTo(1f, tween(durationMs, easing = easing))
    }
    return a.value
}

/** Ring that sweeps to [progress] (expo-out). Draws a gradient when [colors] has more than one colour. */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier,
    stroke: Dp,
    colors: List<Color>,
    track: Color,
    delayMs: Int = 0,
    durationMs: Int = Motion.HERO,
    memoryKey: String? = null,
) {
    val goal = progress.coerceIn(0f, 1f)
    val a = remember { Animatable(memoryKey?.let { MotionMemory.values[it] } ?: 0f) }
    LaunchedEffect(goal) {
        memoryKey?.let { MotionMemory.values[it] = goal }
        if (a.value != goal) {
            delay(delayMs.toLong())
            a.animateTo(goal, tween(durationMs, easing = Motion.ExpoOut))
        }
    }
    Canvas(modifier) {
        val s = stroke.toPx()
        val arcSize = Size(size.width - s, size.height - s)
        val topLeft = Offset(s / 2, s / 2)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(s, cap = StrokeCap.Round))
        if (a.value > 0.001f) rotate(-90f) {
            val brush = if (colors.size > 1) Brush.sweepGradient(colors + colors.first()) else Brush.linearGradient(listOf(colors[0], colors[0]))
            drawArc(brush, 0f, 360f * a.value, false, topLeft, arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
    }
}

/** Small dot that breathes while work is in progress (ease-in-out, endless). */
@Composable
fun PulsingDot(color: Color = MaterialTheme.colorScheme.primary) {
    val s by rememberInfiniteTransition(label = "pulse")
        .animateFloat(0.55f, 1f, infiniteRepeatable(tween(700, easing = Motion.EaseInOut), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(9.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = 0.35f + 0.65f * s }.background(color, CircleShape))
}

// ---- shared photo between a meal row and its detail screen ----

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPhoto(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    return with(shared) {
        this@sharedPhoto.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = nav,
            boundsTransform = { _, _ -> tween(Motion.SLOW - 100, easing = Motion.ExpoOut) },
        )
    }
}
