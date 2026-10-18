package com.happypipaliya.mealtracker.wear

import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** "How energetic do you feel?" on the bezel; the answer goes to the phone to personalise the energy score. */
class EnergyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var value by remember { mutableIntStateOf(60) }
                var sent by remember { mutableStateOf(false) }
                val focus = remember { FocusRequester() }
                val view = LocalView.current
                fun change(d: Int) {
                    val next = (value + d).coerceIn(0, 100)
                    if (next != value) {
                        value = next
                        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                val back = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f) // back-out
                val sweep by animateFloatAsState(value / 100f, tween(420, easing = back), label = "sweep")
                val color by animateColorAsState(
                    if (value >= 50) lerp(Color(0xFFF5B83D), Color(0xFF2FBF71), (value - 50) / 50f) else lerp(Color(0xFFE5484D), Color(0xFFF5B83D), value / 50f),
                    tween(300), label = "color",
                )
                LaunchedEffect(sent) { if (sent) { delay(900); finish() } }
                Box(
                    Modifier.fillMaxSize()
                        .onRotaryScrollEvent { e -> if (e.verticalScrollPixels != 0f) change(if (e.verticalScrollPixels > 0) 5 else -5); true }
                        .focusRequester(focus).focusable(),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                        val s = 10.dp.toPx()
                        val arc = Size(size.width - s, size.height - s)
                        drawArc(color.copy(alpha = 0.18f), 135f, 270f, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
                        drawArc(color, 135f, 270f * sweep.coerceIn(0f, 1f), false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (sent) "Sent to phone" else "Energy right now?", style = MaterialTheme.typography.caption1)
                        Text("$value%", style = MaterialTheme.typography.display1, color = color)
                        Button(onClick = {
                            sent = true
                            val v = value
                            watchScope.launch {
                                runCatching {
                                    val now = System.currentTimeMillis()
                                    val req = PutDataMapRequest.create("/energy/$now").apply {
                                        dataMap.putInt("value", v)
                                        dataMap.putLong("time", now)
                                    }.asPutDataRequest().setUrgent()
                                    Wearable.getDataClient(applicationContext).putDataItem(req).await()
                                }
                            }
                        }, enabled = !sent) { Text(if (sent) "✓" else "Save") }
                        Text("100% = full of energy", style = MaterialTheme.typography.caption3)
                    }
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
    }
}
