package com.happypipaliya.mealtracker.wear

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.happypipaliya.sleepcore.Codec

/** Receives the wake window, new models and "test alarm" from the phone. */
class PhoneListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) apply(this, it.dataItem) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/alarm/test") {
            ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_TEST))
        }
    }

    companion object {
        fun apply(ctx: Context, item: DataItem) {
            val map = DataMapItem.fromDataItem(item).dataMap
            val prefs = WatchPrefs(ctx)
            when (item.uri.path) {
                "/alarm/settings" -> {
                    prefs.settings = Codec.decodeSettings(map.getString("settings"))
                    Scheduler.reschedule(ctx)
                }
                "/alarm/favourites" -> {
                    prefs.favourites = map.getString("favourites").orEmpty()
                    // Save each meal photo so the list can show it.
                    val dir = java.io.File(ctx.filesDir, "thumbs").apply { mkdirs() }
                    map.keySet().filter { it.startsWith("thumb_") }.forEach { key ->
                        runCatching {
                            val asset = map.getAsset(key) ?: return@runCatching
                            val fd = com.google.android.gms.tasks.Tasks.await(
                                com.google.android.gms.wearable.Wearable.getDataClient(ctx).getFdForAsset(asset),
                            )
                            fd.inputStream.use { input -> java.io.File(dir, key.removePrefix("thumb_") + ".jpg").outputStream().use { input.copyTo(it) } }
                        }
                    }
                }
                "/alarm/energy" -> prefs.energyFactors = map.getString("factors").orEmpty()
            }
            when (item.uri.path) {
                "/alarm/energy" -> prefs.energy = listOf(
                    map.getDouble("energy").toString(), map.getBoolean("charging").toString(),
                    map.getBoolean("personalised").toString(), map.getLong("time").toString(),
                    map.getString("need").orEmpty().replace('\n', ' '),
                ).joinToString("\n") + "\n" + map.getString("gauges").orEmpty()
                "/alarm/model" -> Codec.decodeModel(map.getString("model"))?.let { prefs.model = it }
            }
        }
    }
}
