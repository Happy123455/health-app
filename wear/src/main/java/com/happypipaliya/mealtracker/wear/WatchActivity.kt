package com.happypipaliya.mealtracker.wear

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.happypipaliya.sleepcore.WakeSchedule
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Home() } }
    }
}

@Composable
private fun Home() {
    val ctx = LocalContext.current
    val prefs = remember { WatchPrefs(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        runCatching { PhoneLink.pull(ctx) }
        Scheduler.reschedule(ctx)
        PhoneLink.sendPending(ctx)
        tick++
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    val settings = remember(tick) { prefs.settings }
    val model = remember(tick) { prefs.model }
    val next = remember(tick) { WakeSchedule.nextWindowStart(settings, ZonedDateTime.now()) }
    val missing = remember(tick) {
        listOf(heartRatePermission(), Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
    }
    val fullScreenOk = remember(tick) {
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            Modifier.fillMaxWidth(),
            state = listState,
            autoCentering = AutoCenteringParams(itemIndex = 0),
        ) {
            item { ListHeader { Text("Smart alarm") } }
            item {
                val e = remember(tick) { WatchEnergy.load(ctx) }
                Chip(
                    onClick = { ctx.startActivity(Intent(ctx, WatchEnergyActivity::class.java)) },
                    icon = { WatchBattery(e?.energy ?: 0.0, Modifier.size(width = 18.dp, height = 28.dp), e?.charging == true) },
                    label = { Text(e?.let { "Energy ${(it.energy * 100).toInt()}%" } ?: "Energy") },
                    secondaryLabel = { Text(e?.let { energyLabel(it.energy) } ?: "Open the phone app to sync") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                val start = settings.startMinute
                val end = start + settings.windowMinutes
                Text(
                    if (!settings.enabled) "Off. Turn it on in Meal Tracker on your phone (Settings)."
                    else "Wake between %02d:%02d and %02d:%02d\n%s".format(start / 60, start % 60, (end / 60) % 24, end % 60, days(settings.days)),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.body2,
                )
            }
            next?.let { item { Text("Next: " + it.format(DateTimeFormatter.ofPattern("EEE HH:mm")), style = MaterialTheme.typography.caption1) } }
            item {
                Text(
                    model?.let {
                        "Model v${it.version}, learned from ${it.trainedNights} nights" +
                            (it.accuracy?.let { a -> ", ${(a * 100).roundToInt()}% agreement" } ?: "")
                    } ?: "Starter rules until it has learned from your nights",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption2,
                )
            }
            prefs.lastWake?.let { item { Text("Last: $it", textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2) } }
            if (missing.isNotEmpty()) item {
                Chip(
                    onClick = { permissions.launch(missing.toTypedArray()) },
                    label = { Text("Allow heart rate & alerts") },
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
            if (!fullScreenOk) item {
                Chip(
                    onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                        }
                    },
                    label = { Text("Allow full-screen alarm") },
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
            item { WaterChip() }
            item {
                Chip(
                    onClick = { ctx.startActivity(Intent(ctx, ActivityLogActivity::class.java)) },
                    label = { Text("Log activity") },
                    secondaryLabel = { Text("Study, massage chair, yoga") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    onClick = { ctx.startActivity(Intent(ctx, RepeatActivity::class.java)) },
                    label = { Text("Eat again") },
                    secondaryLabel = { Text("Log a meal you've had before") },
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
            item {
                Chip(
                    onClick = { ctx.startActivity(Intent(ctx, EnergyActivity::class.java)) },
                    label = { Text("Rate energy") },
                    secondaryLabel = { Text("Turn the bezel, 3 seconds") },
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
            item {
                Chip(
                    onClick = {
                        ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_TEST))
                    },
                    label = { Text("Test alarm") },
                    secondaryLabel = { Text("Rings in 3 seconds") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

/** Wear OS 6 (Android 16) replaced BODY_SENSORS with a dedicated heart-rate permission. */
fun heartRatePermission(): String =
    if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

private fun days(mask: Int): String = when (mask) {
    0b1111111 -> "Every day"
    0b0011111 -> "Weekdays"
    0b0111111 -> "Mon–Sat"
    else -> DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }
        .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.UK) }
}

/** One tap = one glass (250 ml) sent to the phone; the label confirms and counts this session's glasses. */
@Composable
private fun WaterChip() {
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    var added by remember { mutableStateOf(0) }
    Chip(
        onClick = {
            val now = System.currentTimeMillis()
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            added++
            watchScope.launch {
                runCatching {
                    val req = com.google.android.gms.wearable.PutDataMapRequest.create("/water/$now").apply {
                        dataMap.putDouble("ml", 250.0)
                        dataMap.putLong("time", now)
                    }.asPutDataRequest().setUrgent()
                    com.google.android.gms.wearable.Wearable.getDataClient(ctx).putDataItem(req).await()
                }
            }
        },
        label = { Text("Drink water") },
        secondaryLabel = { Text(if (added == 0) "Tap for 1 glass (250 ml)" else "✓ $added glass${if (added == 1) "" else "es"} added") },
        colors = ChipDefaults.secondaryChipColors(),
    )
}
