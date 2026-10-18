package com.happypipaliya.mealtracker.ui

import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.happypipaliya.mealtracker.Dates
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.SleepLink
import com.happypipaliya.mealtracker.UiRefresh
import com.happypipaliya.sleepcore.AlarmSettings
import com.happypipaliya.sleepcore.Grogginess
import com.happypipaliya.sleepcore.LabelledNight
import com.happypipaliya.sleepcore.Stage
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Settings card: turn the watch smart alarm on and choose the wake window. */
@Composable
fun SmartAlarmSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(SleepLink.settings(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { connected = SleepLink.watchConnected(ctx) }

    fun update(next: AlarmSettings) {
        s = next
        scope.launch {
            status = if (SleepLink.publishSettings(ctx, next)) "Saved and sent to your watch."
            else "Saved. It will reach your watch when they're connected."
        }
    }

    CollapsibleCard(
        "Smart alarm (watch)",
        subtitle = if (s.enabled) "On · ${hm(s.startMinute)}–${hm(s.startMinute + s.windowMinutes)}" else "Off",
    ) {
            Small(
                "Your watch rings inside a window when it senses light sleep, or at the end of the window. " +
                    "After you stop it, rate your grogginess with the bezel. It learns from Samsung's sleep stages and your scores.",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wake me in a window", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = s.enabled, onCheckedChange = { update(s.copy(enabled = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Window opens at", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    TimePickerDialog(ctx, { _, h, m -> update(s.copy(startMinute = h * 60 + m)) }, s.startMinute / 60, s.startMinute % 60, true).show()
                }) { Text(hm(s.startMinute)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Length", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                listOf(15, 20, 30, 45).forEach { len ->
                    FilterChip(selected = s.windowMinutes == len, onClick = { update(s.copy(windowMinutes = len)) }, label = { Text("$len") })
                }
            }
            Small("Minutes. Window: ${hm(s.startMinute)}–${hm(s.startMinute + s.windowMinutes)}.")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val bit = 1 shl (day.value - 1)
                    DayDot(day.getDisplayName(TextStyle.NARROW, Locale.UK), s.days and bit != 0) { update(s.copy(days = s.days xor bit)) }
                }
            }
            // Status changes slide up into place (expo-out).
            AnimatedContent(
                targetState = status ?: when (connected) {
                    true -> "Watch connected."
                    false -> "Watch not connected right now; changes are sent when it is."
                    null -> "Checking the watch…"
                },
                transitionSpec = {
                    (slideInVertically(tween(Motion.BASE, easing = Motion.ExpoOut)) { it } + fadeIn(tween(Motion.BASE)))
                        .togetherWith(slideOutVertically(tween(Motion.QUICK)) { -it } + fadeOut(tween(Motion.QUICK)))
                },
                label = "status",
            ) { Small(it) }
            OutlinedButton(onClick = {
                scope.launch {
                    status = if (SleepLink.testAlarm(ctx)) "Your watch will ring in a few seconds." else "Couldn't reach the watch."
                }
            }) { Text("Test alarm on watch") }
            Small("For the first few nights, keep a normal backup alarm on your phone at the end of the window.")
    }
}

/** Today: how this morning's wake-up went. */
@Composable
fun WakeCard() {
    val ctx = LocalContext.current
    val resume by UiRefresh.ticks.collectAsState()
    val nights by rememberLoad("wake-card") { Db.get(ctx).nights(limit = 3).filter { it.record.trigger != "test" } }
    val settings = remember(resume) { SleepLink.settings(ctx) }
    val last = nights?.firstOrNull()
    if (last == null && !settings.enabled) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Wake-up", style = MaterialTheme.typography.titleMedium)
            if (last == null) {
                Small("Smart alarm is on (${hm(settings.startMinute)}–${hm(settings.startMinute + settings.windowMinutes)}). Your first wake-up will show here.")
            } else {
                val r = last.record
                Text(
                    "${Dates.label(Dates.dateOf(r.alarmAt))}, ${Dates.time(r.alarmAt)} · ${triggerText(r.trigger)}" +
                        (r.grogginess?.let { " · $it% groggy" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Small(
                    when {
                        !last.labelled -> "Waiting for Samsung's sleep stages for that night…"
                        last.wakeStage != null -> "Samsung says you were in ${stageText(last.wakeStage)} when it rang."
                        else -> "Samsung had no sleep stages for that moment."
                    },
                )
            }
        }
    }
}

/** Insights: how the alarm's model is learning, and what leaves you least groggy. */
@Composable
fun WakeLearningCard() {
    val ctx = LocalContext.current
    val rows by rememberLoad("wake-learning") { Db.get(ctx).nights(limit = 365).filter { it.record.trigger != "test" } }
    val all = rows.orEmpty()
    val model = remember(rows) { SleepLink.model(ctx) }
    if (all.isEmpty() && !SleepLink.settings(ctx).enabled) return
    val labelled = all.filter { it.labelled && it.labels.isNotEmpty() }
    val asNights = all.map { LabelledNight(it.record, it.labels, it.wakeStage) }
    val byTrigger = Grogginess.byTrigger(asNights)
    val byStage = Grogginess.byStage(asNights)
    val rated = all.filter { it.record.grogginess != null }.take(7).reversed()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Smart alarm learning", style = MaterialTheme.typography.titleMedium)
            Text(
                "${all.size} wake-ups recorded, ${labelled.size} matched with Samsung's sleep stages.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Small(
                model?.let {
                    "Model v${it.version}, trained on ${it.trainedNights} nights" +
                        (it.accuracy?.let { a -> ". On the latest night it agreed with Samsung ${(a * 100).roundToInt()}% of the time." } ?: ".")
                } ?: "Using starter rules (movement and heart rate) until it has nights matched with Samsung's stages.",
            )
            if (byTrigger.isNotEmpty()) Small(
                "Average grogginess: " + byTrigger.entries.joinToString(" · ") { "${triggerShort(it.key)} ${it.value.roundToInt()}%" },
            )
            if (byStage.isNotEmpty()) Small(
                "By stage when it rang: " + byStage.entries.sortedBy { it.value }.joinToString(" · ") { "${stageText(it.key)} ${it.value.roundToInt()}%" },
            )
            Small("Aiming for: " + (model?.wakeStages ?: setOf(Stage.LIGHT)).filter { it != Stage.AWAKE }.joinToString(" or ") { stageText(it) } + ".")
            if (rated.size >= 2) {
                Text("Grogginess, recent wake-ups", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                BarChart(
                    labels = rated.map { Dates.weekdayShort(Dates.dateOf(it.record.alarmAt)).take(2) },
                    values = rated.map { it.record.grogginess!!.toDouble() },
                    target = null,
                    unit = "%",
                )
            }
        }
    }
}

/** Day toggle: springs (back-out) and changes colour (ease-in-out) when tapped. */
@Composable
private fun DayDot(letter: String, selected: Boolean, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (!seen) { seen = true; return@LaunchedEffect }
        scale.snapTo(0.72f)
        scale.animateTo(1f, tween(Motion.BASE + 160, easing = Motion.BackOut))
    }
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) colors.primary else colors.surfaceVariant, tween(Motion.BASE, easing = Motion.EaseInOut), label = "dot")
    val fg by animateColorAsState(if (selected) colors.onPrimary else colors.onSurfaceVariant, tween(Motion.BASE, easing = Motion.EaseInOut), label = "dotText")
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        contentColor = fg,
        modifier = Modifier.size(36.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(letter, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun hm(minuteOfDay: Int) = "%02d:%02d".format((minuteOfDay / 60) % 24, minuteOfDay % 60)

private fun triggerText(trigger: String) = when (trigger) {
    "smart" -> "rang in light sleep"
    "window_end" -> "rang at the end of the window"
    else -> "test"
}

private fun triggerShort(trigger: String) = when (trigger) {
    "smart" -> "smart wake"
    "window_end" -> "end of window"
    else -> trigger
}

private fun stageText(stage: Stage) = when (stage) {
    Stage.AWAKE -> "already awake"
    Stage.LIGHT -> "light sleep"
    Stage.DEEP -> "deep sleep"
    Stage.REM -> "REM sleep"
    Stage.UNKNOWN -> "an unknown stage"
}
