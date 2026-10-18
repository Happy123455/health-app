package com.happypipaliya.mealtracker

import android.content.Context
import com.happypipaliya.sleepcore.FoodIntake
import com.happypipaliya.sleepcore.MuscleModel
import com.happypipaliya.sleepcore.MuscleReport
import com.happypipaliya.sleepcore.TrainingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Gathers workouts (from the watch), food, sleep and massage logs for the muscle model (sleepcore/Muscles.kt). */
object MuscleEngine {
    suspend fun compute(ctx: Context): MuscleReport = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        val p = Prefs(ctx)
        val targets = Targets.forProfile(p)
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val sessions = db.activityBetween(today.minusDays(8).toString(), today.toString())
            .flatMap { it.workouts }
            .distinctBy { it.start }
            .map { TrainingSession(it.title, it.start, it.end, it.kcal, it.avgHr) }
        val foods = db.doneMealsBetween(today.minusDays(8).toString(), today.toString()).mapNotNull { m ->
            val n = m.analysis?.nutrients ?: return@mapNotNull null
            FoodIntake(m.takenAt, n["carbs_g"] ?: 0.0, n["protein_g"] ?: 0.0, n["fiber_g"] ?: 0.0, n)
        }
        val sleep = runCatching { EnergyEngine.compute(ctx).gauges.firstOrNull { it.key == "sleep" }?.level }.getOrNull()
        MuscleModel.report(
            now = now, sessions = sessions, foods = foods,
            proteinTarget = targets["protein_g"] ?: 50.0, weightKg = p.weightKg, age = p.age,
            sleepScore = sleep,
            massages = db.activitiesSince(now - 8 * 86_400_000L).filter { it.type == "massage" },
            microTargets = targets, dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }
}
