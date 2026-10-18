package com.happypipaliya.mealtracker.wear

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** The same motion curves as the phone app (see ui/Motion.kt there). */
private object WearMotion {
    val EaseInOut: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    val ExpoOut: Easing = Easing { t -> if (t >= 1f) 1f else 1f - 2f.pow(-10f * t) }
    val BackOut: Easing = Easing { t -> val x = t - 1f; 1f + 2.70158f * x * x * x + 1.70158f * x * x }
    val Elastic: Easing = Easing { t ->
        if (t <= 0f || t >= 1f) t.coerceIn(0f, 1f) else 2f.pow(-10f * t) * sin((t * 10f - 0.75f) * (2f * PI.toFloat() / 3f)) + 1f
    }
}

/** Full-screen alarm: Stop, then rate grogginess with the bezel. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val night = intent.getLongExtra(EXTRA_NIGHT, 0L)
        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "test"
        val rateOnly = intent.getBooleanExtra(EXTRA_RATE_ONLY, false)
        setContent {
            MaterialTheme {
                AlarmScreen(
                    reason = MonitorService.reason(trigger),
                    rateOnly = rateOnly,
                    onStop = { startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)) },
                    onSave = { save(night, it) },
                    onDone = { finish() },
                )
            }
        }
    }

    private fun save(night: Long, grogginess: Int) {
        val prefs = WatchPrefs(this)
        prefs.night(night)?.let { r ->
            prefs.saveNight(r.copy(grogginess = grogginess))
            prefs.lastWake = "${hm(r.alarmAt)} · ${MonitorService.reason(r.trigger).lowercase()} · $grogginess% groggy"
        }
        PhoneLink.sendPending(this)
    }

    companion object {
        const val EXTRA_NIGHT = "night"
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_RATE_ONLY = "rate_only"

        fun hm(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}

private enum class Phase { RINGING, RATING, SAVED }

@Composable
private fun AlarmScreen(reason: String, rateOnly: Boolean, onStop: () -> Unit, onSave: (Int) -> Unit, onDone: () -> Unit) {
    var phase by remember { mutableStateOf(if (rateOnly) Phase.RATING else Phase.RINGING) }
    var saved by remember { mutableIntStateOf(0) }
    LaunchedEffect(phase) { if (phase == Phase.SAVED) { delay(1100); onDone() } }
    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            (fadeIn(tween(350, easing = WearMotion.EaseInOut)) + scaleIn(tween(550, easing = WearMotion.ExpoOut), initialScale = 0.85f))
                .togetherWith(fadeOut(tween(200)))
        },
        label = "phase",
    ) { p ->
        when (p) {
            Phase.RINGING -> Ringing(reason) { onStop(); phase = Phase.RATING }
            Phase.RATING -> GrogginessPicker { value -> saved = value; onSave(value); phase = Phase.SAVED }
            Phase.SAVED -> Saved(saved)
        }
    }
}

/** Two rings breathe outwards behind the time (ease-in-out, endless); Stop springs back when pressed. */
@Composable
private fun Ringing(reason: String, onStop: () -> Unit) {
    val breathe = rememberInfiniteTransition(label = "breathe")
    val a by breathe.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = WearMotion.EaseInOut), RepeatMode.Restart), label = "a")
    val b by breathe.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, delayMillis = 900, easing = WearMotion.EaseInOut), RepeatMode.Restart), label = "b")
    val ring = MaterialTheme.colors.primary
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(if (pressed) 150 else 450, easing = if (pressed) WearMotion.EaseInOut else WearMotion.BackOut), label = "stop")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (t in listOf(a, b)) {
                drawCircle(ring.copy(alpha = (1f - t) * 0.35f), radius = size.minDimension * (0.25f + 0.25f * t), style = Stroke(6f))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(AlarmActivity.hm(System.currentTimeMillis()), style = MaterialTheme.typography.display1)
            Text(reason, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Button(
                onClick = onStop,
                interactionSource = press,
                modifier = Modifier.padding(top = 12.dp).size(ButtonDefaults.LargeButtonSize).graphicsLayer { scaleX = scale; scaleY = scale },
            ) { Text("Stop") }
        }
    }
}

/**
 * Turn the bezel (or tap − / +). The arc glides to the value (back-out) and shifts green → amber → red,
 * the number rolls in the direction you turned, and each click gives a haptic tick.
 */
@Composable
private fun GrogginessPicker(onSave: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(50) }
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    fun change(delta: Int) {
        val next = (value + delta).coerceIn(0, 100)
        if (next != value) {
            value = next
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    val sweep by animateFloatAsState(value / 100f, tween(420, easing = WearMotion.BackOut), label = "sweep")
    val green = Color(0xFF22C55E)
    val amber = Color(0xFFF59E0B)
    val red = Color(0xFFEF4444)
    val color by animateColorAsState(
        if (value <= 50) lerp(green, amber, value / 50f) else lerp(amber, red, (value - 50) / 50f),
        tween(300, easing = WearMotion.EaseInOut), label = "color",
    )
    Box(
        Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { e ->
                if (e.verticalScrollPixels != 0f) change(if (e.verticalScrollPixels > 0) 5 else -5)
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val s = 10.dp.toPx()
            val arc = Size(size.width - s, size.height - s)
            val topLeft = Offset(s / 2, s / 2)
            drawArc(color.copy(alpha = 0.18f), 135f, 270f, false, topLeft, arc, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * sweep.coerceIn(0f, 1f), false, topLeft, arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("How groggy?", style = MaterialTheme.typography.caption1)
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(tween(260, easing = WearMotion.ExpoOut)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(200)))
                        .togetherWith(slideOutVertically(tween(200)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(150)))
                },
                label = "value",
            ) { v -> Text("$v%", style = MaterialTheme.typography.display1, color = color) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { change(-5) }, modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) { Text("−") }
                Button(onClick = { onSave(value) }) { Text("Save") }
                Button(onClick = { change(5) }, modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) { Text("+") }
            }
            Text("0% = wide awake", style = MaterialTheme.typography.caption3)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A tick that pops in (elastic) and draws itself, then the screen closes. */
@Composable
private fun Saved(value: Int) {
    val pop = remember { Animatable(0f) }
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, tween(800, easing = WearMotion.Elastic))
    }
    LaunchedEffect(Unit) {
        delay(150)
        draw.animateTo(1f, tween(450, easing = WearMotion.ExpoOut))
    }
    val green = Color(0xFF22C55E)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(84.dp).graphicsLayer { val s = 0.4f + 0.6f * pop.value; scaleX = s; scaleY = s }) {
            drawCircle(green)
            val w = size.width
            val p1 = Offset(w * 0.28f, w * 0.52f)
            val p2 = Offset(w * 0.44f, w * 0.68f)
            val p3 = Offset(w * 0.74f, w * 0.36f)
            val t = draw.value
            val stroke = w * 0.09f
            val first = (t / 0.4f).coerceIn(0f, 1f)
            drawLine(Color.White, p1, p1 + (p2 - p1) * first, stroke, StrokeCap.Round)
            if (t > 0.4f) {
                val second = ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)
                drawLine(Color.White, p2, p2 + (p3 - p2) * second, stroke, StrokeCap.Round)
            }
        }
        Text("Saved · $value%", style = MaterialTheme.typography.title3, modifier = Modifier.padding(top = 10.dp))
        Text("Good morning", style = MaterialTheme.typography.caption2)
    }
}
