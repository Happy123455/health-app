package com.happypipaliya.mealtracker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.happypipaliya.sleepcore.AlarmSettings
import com.happypipaliya.sleepcore.Codec
import com.happypipaliya.sleepcore.EPOCH_MS
import com.happypipaliya.sleepcore.Model
import com.happypipaliya.sleepcore.Stage
import com.happypipaliya.sleepcore.Trainer
import com.happypipaliya.sleepcore.stageAt
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Duration

private const val TAG = "SleepLink"

/** The phone's side of the watch smart alarm: settings out, nights in, model out. */
object SleepLink {
    fun settings(ctx: Context): AlarmSettings = Codec.decodeSettings(Prefs(ctx).smartAlarm)

    fun model(ctx: Context): Model? = Codec.decodeModel(Prefs(ctx).wakeModel)

    /** Saves the wake window and sends it to the watch. Returns false if it couldn't be queued. */
    suspend fun publishSettings(ctx: Context, s: AlarmSettings): Boolean {
        Prefs(ctx).smartAlarm = Codec.encode(s)
        return put(ctx, "/alarm/settings", "settings", Codec.encode(s))
    }

    private suspend fun publishModel(ctx: Context, m: Model) = put(ctx, "/alarm/model", "model", Codec.encode(m))

    private suspend fun put(ctx: Context, path: String, key: String, value: String): Boolean = runCatching {
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString(key, value)
            dataMap.putLong("ts", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(request).await()
        true
    }.onFailure { Log.w(TAG, "Couldn't send $path", it) }.getOrDefault(false)

    suspend fun watchConnected(ctx: Context): Boolean =
        runCatching { Wearable.getNodeClient(ctx).connectedNodes.await().isNotEmpty() }.getOrDefault(false)

    /** Makes the watch ring in a few seconds, to try the alarm and grogginess screens. */
    suspend fun testAlarm(ctx: Context): Boolean = runCatching {
        val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
        nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, "/alarm/test", ByteArray(0)).await() }
        nodes.isNotEmpty()
    }.getOrDefault(false)

    /** Stores a night sent by the watch and queues labelling and training. */
    fun receive(ctx: Context, item: DataItem) {
        if (item.uri.path?.startsWith("/activity/") == true) {
            // Study / massage / yoga logged on the watch.
            val map = DataMapItem.fromDataItem(item).dataMap
            val type = map.getString("type").orEmpty()
            val minutes = map.getInt("minutes", 0)
            val end = map.getLong("time", System.currentTimeMillis())
            Wearable.getDataClient(ctx).deleteDataItems(item.uri)
            if (type.isNotBlank() && minutes > 0) appScope.launch { EnergyEngine.logActivity(ctx, type, minutes, end) }
            return
        }
        if (item.uri.path?.startsWith("/water/") == true) {
            // A glass of water logged on the watch.
            val map = DataMapItem.fromDataItem(item).dataMap
            val ml = map.getDouble("ml", 250.0)
            val time = map.getLong("time", System.currentTimeMillis())
            Wearable.getDataClient(ctx).deleteDataItems(item.uri)
            appScope.launch { EnergyEngine.logWater(ctx, ml, time) }
            return
        }
        if (item.uri.path?.startsWith("/repeat/") == true) {
            // "Eat again" chosen on the watch: log that meal again with the chosen portion.
            val map = DataMapItem.fromDataItem(item).dataMap
            val mealId = map.getLong("meal", -1L)
            val portion = map.getDouble("portion", 1.0)
            val time = map.getLong("time", System.currentTimeMillis())
            Wearable.getDataClient(ctx).deleteDataItems(item.uri)
            appScope.launch {
                Db.get(ctx).meal(mealId)?.let { MealCapture.repeat(ctx, it, portion, time) }
                publishFavourites(ctx)
            }
            return
        }
        if (item.uri.path?.startsWith("/energy/") == true) {
            // Energy check-in rated on the watch's bezel.
            val map = DataMapItem.fromDataItem(item).dataMap
            val value = map.getInt("value", -1)
            val time = map.getLong("time", System.currentTimeMillis())
            Wearable.getDataClient(ctx).deleteDataItems(item.uri)
            if (value >= 0) appScope.launch { EnergyEngine.checkIn(ctx, value, time) }
            return
        }
        if (item.uri.path?.startsWith("/night/") != true) return
        val record = Codec.decodeNight(DataMapItem.fromDataItem(item).dataMap.getString("record")) ?: return
        Db.get(ctx).saveNight(record)
        Wearable.getDataClient(ctx).deleteDataItems(item.uri)
        scheduleTraining(ctx)
    }

    /** Picks up nights the listener may have missed (e.g. while the app was being updated). */
    /**
     * Sends the watch the meals you eat most (id, name, kcal, protein, times eaten), so "Eat again"
     * works on the wrist. One line per meal: id|name|kcal|protein|count.
     */
    suspend fun publishFavourites(ctx: Context) {
        val favs = Db.get(ctx).favourites(15)
        val text = favs.joinToString("\n") { (m, count) ->
            listOf(m.id, m.name.orEmpty().replace('|', '/').replace('\n', ' '), (m.calories ?: 0.0).toInt(), (m.protein ?: 0.0).toInt(), count).joinToString("|")
        }
        runCatching {
            val request = PutDataMapRequest.create("/alarm/favourites").apply {
                dataMap.putString("favourites", text)
                dataMap.putLong("ts", System.currentTimeMillis())
                // A tiny square photo per meal (~4 KB) for the watch list.
                favs.forEach { (m, _) -> watchThumb(m.thumbPath)?.let { dataMap.putAsset("thumb_${m.id}", Asset.createFromBytes(it)) } }
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(ctx).putDataItem(request).await()
        }.onFailure { Log.w(TAG, "Couldn't send favourites", it) }
    }

    /**
     * Sends the watch the current energy state: headline, charging, the "what you need now" tip and
     * every gauge (one per line: key|label|level|detail). Called after anything that changes it and
     * every 15 minutes, since fuel drains with time.
     */
    suspend fun publishEnergy(ctx: Context) {
        val s = runCatching { EnergyEngine.compute(ctx) }.getOrNull() ?: return
        val charging = s.timeline.size > 3 && s.timeline.last().liver > s.timeline[s.timeline.size - 4].liver + 0.01
        runCatching {
            val request = PutDataMapRequest.create("/alarm/energy").apply {
                dataMap.putDouble("energy", s.energy)
                dataMap.putBoolean("charging", charging)
                dataMap.putBoolean("personalised", s.personalised)
                dataMap.putString("need", s.need)
                dataMap.putString("gauges", s.gauges.joinToString("\n") { g ->
                    listOf(g.key, g.label, g.level, g.detail.replace('|', '/').replace('\n', ' ')).joinToString("|")
                })
                dataMap.putString("factors", s.factors.joinToString("\n") { (label, pts) -> "${label.replace('|', '/')}|$pts" })
                dataMap.putLong("time", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(ctx).putDataItem(request).await()
        }.onFailure { Log.w(TAG, "Couldn't send energy", it) }
    }

    /** Centre-cropped 120 px JPEG of a meal photo. */
    private fun watchThumb(path: String?): ByteArray? = runCatching {
        val src = android.graphics.BitmapFactory.decodeFile(path ?: return null) ?: return null
        val side = minOf(src.width, src.height)
        val square = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val small = android.graphics.Bitmap.createScaledBitmap(square, 120, 120, true)
        java.io.ByteArrayOutputStream().also { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
    }.getOrNull()

    suspend fun pullNights(ctx: Context) {
        for (prefix in listOf("night", "energy", "repeat", "activity", "water")) {
            val items = runCatching {
                Wearable.getDataClient(ctx).getDataItems(Uri.parse("wear://*/$prefix"), DataClient.FILTER_PREFIX).await()
            }.getOrNull() ?: continue
            try {
                items.forEach { receive(ctx, it) }
            } finally {
                items.release()
            }
        }
    }


    fun scheduleTraining(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "wake-train", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WakeTrainWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(30))
                .build(),
        )
    }

    /**
     * Labels each new night with Samsung's sleep stages (they reach the phone after you wake up),
     * retrains the model on all labelled nights and sends it to the watch.
     */
    suspend fun labelAndTrain(ctx: Context): Boolean {
        val db = Db.get(ctx)
        var waiting = false
        var added = false
        for (r in db.nightsToLabel()) {
            if (r.trigger == "test") {
                db.saveLabels(r.monitorStart, emptyList(), null)
                continue
            }
            val stages = HealthSync.sleepStages(ctx, r.monitorStart - 12 * 3_600_000L, r.alarmAt + 2 * 3_600_000L)
            val labels = stages?.let { s -> r.epochs.map { stageAt(s, it.start + EPOCH_MS / 2) } }
            val coverage = labels?.let { l -> l.count { it != Stage.UNKNOWN }.toDouble() / l.size.coerceAtLeast(1) } ?: 0.0
            val hoursSince = (System.currentTimeMillis() - r.alarmAt) / 3_600_000.0
            if (coverage < 0.5 && hoursSince < 36) {
                waiting = true // Samsung hasn't synced this night yet
                continue
            }
            val wakeStage = stages?.let { stageAt(it, r.alarmAt - 60_000) }?.takeIf { it != Stage.UNKNOWN }
            db.saveLabels(r.monitorStart, labels.orEmpty(), wakeStage)
            added = true
        }
        if (added) {
            val nights = db.labelledNights()
            Trainer.train(nights, model(ctx))?.let { m ->
                Prefs(ctx).wakeModel = Codec.encode(m)
                nights.lastOrNull()?.let { db.setNightAccuracy(it.record.monitorStart, m.accuracy) }
                publishModel(ctx, m)
            }
        }
        return waiting
    }
}

class WakeTrainWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        if (SleepLink.labelAndTrain(applicationContext) && runAttemptCount < 10) Result.retry() else Result.success()
}

/** Receives finished nights from the watch. */
class WatchListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) SleepLink.receive(this, it.dataItem) }
    }
}
