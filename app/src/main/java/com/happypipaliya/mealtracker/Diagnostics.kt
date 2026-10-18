package com.happypipaliya.mealtracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/** Settings › Connections: live checks of every link the app depends on. Results also go to logcat (tag "Connections"). */
object Diagnostics {
    enum class State { OK, WARN, FAIL }
    data class Check(val group: String, val name: String, val state: State, val detail: String)

    private const val TAG = "Connections"
    private const val SAMSUNG = "com.sec.android.app.shealth"

    private fun ago(t: Long?): String {
        if (t == null) return "never"
        val m = (System.currentTimeMillis() - t) / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / 1440} days ago"
        }
    }

    private fun source(pkgs: Collection<String>) = pkgs.map {
        when {
            it == SAMSUNG -> "Samsung Health"
            it.startsWith("com.happypipaliya") -> "Meal Tracker"
            it.startsWith("com.android.healthconnect") -> "phone sensors"
            else -> it
        }
    }.distinct().joinToString()

    suspend fun run(ctx: Context): List<Check> = withContext(Dispatchers.IO) {
        val out = ArrayList<Check>()
        fun add(g: String, n: String, s: State, d: String) { out += Check(g, n, s, d); Log.i(TAG, "[$s] $g / $n: $d") }
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val dayStart = LocalDate.now().atStartOfDay(zone).toInstant()

        // Health Connect
        val hc = "Health Connect"
        if (!HealthSync.available(ctx)) {
            add(hc, "Health Connect", State.FAIL, "Not available on this phone")
        } else {
            val granted = HealthSync.granted(ctx)
            val missing = HealthSync.permissionsToRequest(ctx) - granted
            add(hc, "Permissions", if (missing.isEmpty()) State.OK else State.WARN,
                if (missing.isEmpty()) "All ${granted.size} granted" else "Missing: " + missing.joinToString { it.substringAfterLast('.').lowercase().replace('_', ' ') })
            val client = HealthConnectClient.getOrCreate(ctx)
            suspend fun <T : Record> probe(name: String, type: KClass<T>, from: Instant, expectRecent: Duration?, timeOf: (T) -> Instant, summary: (List<T>) -> String) {
                val r = runCatching { client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, now), ascendingOrder = false, pageSize = 500)).records }
                r.onFailure { add(hc, name, State.FAIL, "Can't read: ${it.message?.take(80)}") }
                r.onSuccess { recs ->
                    val latest = recs.maxOfOrNull { timeOf(it).toEpochMilli() }
                    val stale = expectRecent != null && (latest == null || System.currentTimeMillis() - latest > expectRecent.toMillis())
                    val state = when {
                        recs.isEmpty() -> State.WARN
                        stale -> State.WARN
                        else -> State.OK
                    }
                    add(hc, name, state, if (recs.isEmpty()) (if (type == HydrationRecord::class) "None from other apps (Samsung Health doesn't share water). ${Db.get(ctx).waterBetween(dayStart.toEpochMilli(), now.toEpochMilli()).sumOf { it.second }.toInt()} ml logged in Meal Tracker today" else "No records") else "${summary(recs)} · latest ${ago(latest)} · from ${source(recs.map { it.metadata.dataOrigin.packageName })}")
                }
            }
            probe("Steps", StepsRecord::class, dayStart, Duration.ofHours(3), { it.endTime }) { "${it.sumOf { s -> s.count }} today" }
            probe("Heart rate", HeartRateRecord::class, now.minus(Duration.ofHours(6)), Duration.ofHours(2), { it.endTime }) { "${it.sumOf { h -> h.samples.size }} readings in 6 h" }
            probe("Sleep", SleepSessionRecord::class, now.minus(Duration.ofDays(3)), Duration.ofHours(36), { it.endTime }) { recs -> "${recs.size} sessions in 3 days" }
            probe("Workouts", ExerciseSessionRecord::class, now.minus(Duration.ofDays(7)), null, { it.endTime }) { "${it.size} in 7 days" }
            probe("Water", HydrationRecord::class, dayStart, null, { it.endTime }) { "${it.sumOf { h -> h.volume.inMilliliters }.toInt()} ml today (${it.size} entries)" }
            probe("Weight", WeightRecord::class, now.minus(Duration.ofDays(365)), null, { it.time }) { "${"%.1f".format(it.first().weight.inKilograms)} kg" }
            // Meals we write (reading them back would need nutrition read access, so check the last write instead).
            val pr = Prefs(ctx)
            add(hc, "Meals → Samsung Health", when {
                !pr.writeNutritionToHealthConnect || HealthSync.WRITE_NUTRITION !in granted -> State.WARN
                pr.hcWriteError.isNotEmpty() -> State.FAIL
                else -> State.OK
            }, when {
                !pr.writeNutritionToHealthConnect -> "Turned off"
                HealthSync.WRITE_NUTRITION !in granted -> "No permission to write meals"
                pr.hcWriteError.isNotEmpty() -> "Last write failed ${ago(pr.hcWriteAt)}: ${pr.hcWriteError.take(80)}"
                pr.hcWriteAt == 0L -> "Allowed; no meal written since this check was added"
                else -> "Last meal written ${ago(pr.hcWriteAt)}. Samsung Health copies it in when it next syncs"
            })
        }
        val db = Db.get(ctx)
        add(hc, "Last copy into the app", if ((System.currentTimeMillis() - (db.newest("activity_days", "updated_at") ?: 0)) < 3 * 3_600_000) State.OK else State.WARN,
            "Watch data ${ago(db.newest("activity_days", "updated_at"))}")

        // Watch
        val w = "Galaxy Watch"
        val nodes = runCatching { Wearable.getNodeClient(ctx).connectedNodes.await() }.getOrNull()
        when {
            nodes == null -> add(w, "Connection", State.FAIL, "Wear OS services unavailable")
            nodes.isEmpty() -> add(w, "Connection", State.FAIL, "No watch connected (Bluetooth off or out of range?)")
            else -> add(w, "Connection", State.OK, nodes.joinToString { it.displayName + if (it.isNearby) " (nearby)" else " (via cloud)" })
        }
        val sent = runCatching { SleepLink.publishEnergy(ctx); true }.getOrDefault(false)
        add(w, "Phone → watch", if (sent && !nodes.isNullOrEmpty()) State.OK else State.WARN, if (sent) "Energy, favourites and alarm settings sent" else "Couldn't send")
        val fromWatch = listOfNotNull(db.newest("nights", "received_at"), db.newest("check_ins", "time"), db.newest("activities", "start")).maxOrNull()
        add(w, "Watch → phone", if (fromWatch != null) State.OK else State.WARN, "Last thing from the watch: ${ago(maxOf(fromWatch ?: 0, db.newest("water", "time") ?: 0).takeIf { it > 0 })} (alarm nights, check-ins, activities, water)")

        // App internals
        val a = "App"
        val lastMeal = db.newest("meals", "analysed_at", "status='DONE' AND IFNULL(model,'')!='estimate'")
        val waiting = db.count("meals", "status IN ('PENDING','ANALYSING','FAILED')")
        add(a, "Gemini", if (waiting == 0) State.OK else State.WARN, "Last analysis ${ago(lastMeal)}" + if (waiting > 0) " · $waiting photo(s) waiting" else "")
        add(a, "Photo access", if (Importer.hasPhotoAccess(ctx)) State.OK else State.FAIL, if (Importer.hasPhotoAccess(ctx)) "Can read the ${Prefs(ctx).albumName} album" else "No access to photos")
        val notif = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        add(a, "Notifications", if (notif) State.OK else State.WARN, if (notif) "Allowed" else "Blocked: no meal summaries or reminders")
        val pm = ctx.getSystemService(PowerManager::class.java)
        val unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        add(a, "Battery", if (unrestricted) State.OK else State.WARN, if (unrestricted) "Unrestricted" else "Optimised: background syncs may be delayed")
        val work = runCatching { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow("periodic").first() }.getOrNull().orEmpty()
        val running = work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        add(a, "Background sync", if (running) State.OK else State.FAIL, if (running) "Scheduled every 15 min" else "Not scheduled")
        val fetched = Prefs(ctx).pricesFetchedAt.takeIf { it > 0 }
        add(a, "Mandi prices", if (fetched != null && System.currentTimeMillis() - fetched < 30 * 3_600_000L) State.OK else State.WARN, "Fetched ${ago(fetched)}")
        out
    }
}
