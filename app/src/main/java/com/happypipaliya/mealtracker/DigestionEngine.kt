package com.happypipaliya.mealtracker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.happypipaliya.sleepcore.Digestion
import com.happypipaliya.sleepcore.DigestionReport
import com.happypipaliya.sleepcore.MealDigest
import com.happypipaliya.sleepcore.PairKind
import com.happypipaliya.sleepcore.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Digestion timing, pairing notes and the post-meal reminder (logic in sleepcore/Digestion.kt). */
object DigestionEngine {
    const val CHANNEL = "digestion"
    private val zone get() = ZoneId.systemDefault()

    fun digest(m: Meal): MealDigest? {
        val a = m.analysis?.takeIf { it.isFood } ?: return null
        val n = a.nutrients
        return MealDigest(
            m.takenAt, a.mealName, listOf(a.mealName) + a.items.map { it.name },
            n["carbs_g"] ?: 0.0, n["protein_g"] ?: 0.0, n["fat_g"] ?: 0.0, n["fiber_g"] ?: 0.0, n["calories_kcal"] ?: 0.0, n,
        )
    }

    private fun minuteOfDay(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    suspend fun report(ctx: Context): DigestionReport = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val meals = db.doneMealsBetween(today.minusDays(1).toString(), today.toString()).mapNotNull(::digest)
        val bed = Digestion.usualBedtime(db.sleepsSince(now - 10 * 86_400_000L), ::minuteOfDay)
        Digestion.report(now, meals, bed, LocalTime.now().let { it.hour * 60 + it.minute })
    }

    /** Call off the main thread (reads the database). */
    fun pairings(ctx: Context, meal: Meal): List<Pairing> {
        val d = digest(meal) ?: return emptyList()
        val day = LocalDate.parse(meal.date)
        val nearby = Db.get(ctx).doneMealsBetween(day.minusDays(1).toString(), day.plusDays(1).toString())
            .filter { it.id != meal.id }.mapNotNull(::digest)
        return Digestion.pairings(d, nearby)
    }

    /** After a meal is analysed: a nudge ~15 min after eating (walk now; chai later if it's iron-rich). */
    fun scheduleReminder(ctx: Context, meal: Meal) {
        if (!Prefs(ctx).digestionReminders) return
        val d = digest(meal) ?: return
        if (Digestion.isTea(d) && d.kcal < 200) return
        val now = System.currentTimeMillis()
        if (now - meal.takenAt > 50 * 60_000L) return // too late to be useful
        val delay = (meal.takenAt + 15 * 60_000L - now).coerceAtLeast(0)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "digest-${meal.id}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DigestReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("meal" to meal.id))
                .build(),
        )
    }

    fun remind(ctx: Context, meal: Meal) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val d = digest(meal) ?: return
        val r = Digestion.report(System.currentTimeMillis(), listOf(d), null, LocalTime.now().let { it.hour * 60 + it.minute })
        val lines = r.now.map { it.title }
        if (lines.isEmpty()) return
        val warn = Digestion.pairings(d, emptyList()).firstOrNull { it.kind != PairKind.GOOD }
        val open = PendingIntent.getActivity(
            ctx, 50_000 + meal.id.toInt(),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_MEAL, meal.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = (r.now.map { "${it.title}: ${it.text}" } + listOfNotNull(warn?.let { "${it.title}: ${it.text}" })).joinToString("\n")
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_meal)
            .setContentTitle(lines.joinToString(" · "))
            .setContentText("After ${d.name}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(60 * 60_000L)
            .build()
        NotificationManagerCompat.from(ctx).notify(50_000 + meal.id.toInt(), n)
    }
}

class DigestReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("meal", -1)
        Db.get(applicationContext).meal(id)?.let { DigestionEngine.remind(applicationContext, it) }
        return Result.success()
    }
}
