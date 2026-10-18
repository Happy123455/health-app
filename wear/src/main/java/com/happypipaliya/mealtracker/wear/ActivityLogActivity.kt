package com.happypipaliya.mealtracker.wear

import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private val TYPES = listOf(Triple("study", "Study", 60), Triple("massage", "Massage chair", 20), Triple("yoga", "Yoga", 20))

/** Log study / massage chair / yoga ending now: pick the type, turn the bezel for minutes, Log. */
class ActivityLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { LogFlow { finish() } } }
    }
}

@Composable
private fun LogFlow(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var type by remember { mutableStateOf<Triple<String, String, Int>?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sent) { if (sent != null) { delay(1100); onDone() } }
    AnimatedContent(
        targetState = Pair(type, sent),
        transitionSpec = { (fadeIn(tween(300)) + scaleIn(tween(450), initialScale = 0.9f)).togetherWith(fadeOut(tween(150))) },
        label = "log",
    ) { (t, s) ->
        when {
            s != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("✓ Logged\n$s", textAlign = TextAlign.Center, style = MaterialTheme.typography.title3)
            }
            t == null -> {
                val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
                Scaffold(timeText = { TimeText() }) {
                    ScalingLazyColumn(Modifier.fillMaxWidth(), state = state, autoCentering = AutoCenteringParams(itemIndex = 0)) {
                        item { ListHeader { Text("What did you do?") } }
                        TYPES.forEach { tp ->
                            item {
                                Chip(onClick = { type = tp }, label = { Text(tp.second) }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
            else -> MinutesPicker(t.second, t.third) { minutes ->
                val now = System.currentTimeMillis()
                watchScope.launch {
                    runCatching {
                        val req = PutDataMapRequest.create("/activity/$now").apply {
                            dataMap.putString("type", t.first)
                            dataMap.putInt("minutes", minutes)
                            dataMap.putLong("time", now)
                        }.asPutDataRequest().setUrgent()
                        Wearable.getDataClient(ctx).putDataItem(req).await()
                    }
                }
                sent = "${t.second}, $minutes min"
            }
        }
    }
}

/** Bezel in 5-minute steps; the number rolls in the direction you turn. */
@Composable
private fun MinutesPicker(label: String, start: Int, onLog: (Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(start) }
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    fun step(d: Int) {
        val next = (minutes + d).coerceIn(5, 300)
        if (next != minutes) {
            minutes = next
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    Box(
        Modifier.fillMaxSize()
            .onRotaryScrollEvent { e -> if (e.verticalScrollPixels != 0f) step(if (e.verticalScrollPixels > 0) 5 else -5); true }
            .focusRequester(focus).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(label, style = MaterialTheme.typography.caption1)
            AnimatedContent(
                targetState = minutes,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(tween(240)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(200)))
                        .togetherWith(slideOutVertically(tween(200)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(150)))
                },
                label = "minutes",
            ) { m -> Text(if (m >= 60) "${m / 60}h ${m % 60}m" else "$m min", style = MaterialTheme.typography.display2) }
            Text("ending now", style = MaterialTheme.typography.caption2)
            Button(onClick = { onLog(minutes) }) { Text("Log") }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
