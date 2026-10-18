package com.happypipaliya.mealtracker.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.happypipaliya.sleepcore.AlarmSettings
import com.happypipaliya.sleepcore.BASELINE_MINUTES
import com.happypipaliya.sleepcore.Codec
import com.happypipaliya.sleepcore.Model
import com.happypipaliya.sleepcore.NightRecord
import com.happypipaliya.sleepcore.WakeSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.ZonedDateTime

private const val TAG = "SmartAlarm"

val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** What the watch remembers: settings and model from the phone, and nights not yet sent back. */
class WatchPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("smart_alarm", Context.MODE_PRIVATE)

    var settings: AlarmSettings
        get() = Codec.decodeSettings(sp.getString("settings", null))
        set(v) = sp.edit { putString("settings", Codec.encode(v)) }

    var model: Model?
        get() = Codec.decodeModel(sp.getString("model", null))
        set(v) = sp.edit { putString("model", v?.let { Codec.encode(it) }) }

    /** Short line for the watch screen, e.g. "06:42 · light sleep spotted · 30% groggy". */
    var lastWake: String?
        get() = sp.getString("last_wake", null)
        set(v) = sp.edit { putString("last_wake", v) }

    /** Meals the phone says you eat most: one per line, id|name|kcal|protein|count. */
    var favourites: String
        get() = sp.getString("favourites", "")!!
        set(v) = sp.edit { putString("favourites", v) }

    /** Latest energy state from the phone: energy, charging, personalised, time, need, then gauge lines. */
    var energy: String
        get() = sp.getString("energy", "")!!
        set(v) = sp.edit { putString("energy", v) }

    /** "label|points" per line: what's pushing energy up or down. */
    var energyFactors: String
        get() = sp.getString("energy_factors", "")!!
        set(v) = sp.edit { putString("energy_factors", v) }

    fun saveNight(r: NightRecord) = sp.edit { putString("night_${r.monitorStart}", Codec.encode(r)) }

    fun night(monitorStart: Long): NightRecord? = Codec.decodeNight(sp.getString("night_$monitorStart", null))

    fun pendingNights(): List<NightRecord> =
        sp.all.keys.filter { it.startsWith("night_") }.mapNotNull { Codec.decodeNight(sp.getString(it, null)) }

    fun removeNight(monitorStart: Long) = sp.edit { remove("night_$monitorStart") }
}

/** Sets the two daily alarms: start sensing 20 minutes before the window, and a backstop ring at its end. */
object Scheduler {
    const val ACTION_MONITOR = "com.happypipaliya.mealtracker.wear.MONITOR"
    const val ACTION_BACKSTOP = "com.happypipaliya.mealtracker.wear.BACKSTOP"
    const val EXTRA_WINDOW_START = "window_start"
    const val EXTRA_WINDOW_END = "window_end"

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, ACTION_MONITOR, 0, 0))
        am.cancel(pending(ctx, ACTION_BACKSTOP, 0, 0))
        val start = WakeSchedule.nextWindowStart(WatchPrefs(ctx).settings, ZonedDateTime.now()) ?: return
        val windowStart = start.toInstant().toEpochMilli()
        val windowEnd = start.plusMinutes(WatchPrefs(ctx).settings.windowMinutes.toLong()).toInstant().toEpochMilli()
        val monitorAt = maxOf(windowStart - BASELINE_MINUTES * 60_000, System.currentTimeMillis() + 2_000)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, monitorAt, pending(ctx, ACTION_MONITOR, windowStart, windowEnd))
        // Rings at the end of the window even if sensing failed. Also shows as "next alarm" on the watch.
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(windowEnd, show), pending(ctx, ACTION_BACKSTOP, windowStart, windowEnd))
        Log.i(TAG, "Next window $start, sensing from ${java.util.Date(monitorAt)}")
    }

    private fun pending(ctx: Context, action: String, windowStart: Long, windowEnd: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, action.hashCode(),
            Intent(ctx, AlarmReceiver::class.java).setAction(action)
                .putExtra(EXTRA_WINDOW_START, windowStart).putExtra(EXTRA_WINDOW_END, windowEnd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, MonitorService::class.java)
            .putExtra(Scheduler.EXTRA_WINDOW_START, intent.getLongExtra(Scheduler.EXTRA_WINDOW_START, 0))
            .putExtra(Scheduler.EXTRA_WINDOW_END, intent.getLongExtra(Scheduler.EXTRA_WINDOW_END, 0))
        when (intent.action) {
            Scheduler.ACTION_MONITOR -> ContextCompat.startForegroundService(context, service.setAction(MonitorService.ACTION_MONITOR))
            Scheduler.ACTION_BACKSTOP -> ContextCompat.startForegroundService(context, service.setAction(MonitorService.ACTION_BACKSTOP))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.reschedule(context)
}

/** Talks to the Meal Tracker app on the phone. */
object PhoneLink {
    /** Sends finished nights (with grogginess) to the phone; they stay queued until delivered. */
    fun sendPending(ctx: Context) = watchScope.launch {
        val prefs = WatchPrefs(ctx)
        for (night in prefs.pendingNights().filter { it.alarmAt > 0 }) {
            runCatching {
                val req = PutDataMapRequest.create("/night/${night.monitorStart}").apply {
                    dataMap.putString("record", Codec.encode(night))
                    dataMap.putLong("sent", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(ctx).putDataItem(req).await()
                prefs.removeNight(night.monitorStart)
            }.onFailure { Log.w(TAG, "Couldn't queue night for the phone", it) }
        }
    }

    /** Fetches the latest settings and model the phone published (in case a change was missed). */
    suspend fun pull(ctx: Context) {
        val client = Wearable.getDataClient(ctx)
        val items = client.getDataItems(Uri.parse("wear://*/alarm"), com.google.android.gms.wearable.DataClient.FILTER_PREFIX).await()
        try {
            for (item in items) PhoneListenerService.apply(ctx, item)
        } finally {
            items.release()
        }
    }
}
