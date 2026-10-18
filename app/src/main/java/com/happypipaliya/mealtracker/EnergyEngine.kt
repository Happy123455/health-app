package com.happypipaliya.mealtracker

import android.content.Context
import com.happypipaliya.sleepcore.CheckIn
import com.happypipaliya.sleepcore.EnergyInputs
import com.happypipaliya.sleepcore.EnergyModel
import com.happypipaliya.sleepcore.EnergyState
import com.happypipaliya.sleepcore.Exercise
import com.happypipaliya.sleepcore.FoodIntake
import com.happypipaliya.sleepcore.MealPlan
import com.happypipaliya.sleepcore.MicroSpec
import com.happypipaliya.sleepcore.WaterIntake
import com.happypipaliya.sleepcore.median
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Gathers meals, watch data and water, and runs the energy model (sleepcore/Energy.kt). */
object EnergyEngine {
    /** Water-soluble nutrients the body barely stores: judged over 3 days instead of 14. */
    private val FAST = setOf("vitamin_c_mg", "thiamin_mg", "riboflavin_mg", "niacin_mg", "vitamin_b6_mg", "folate_mcg", "potassium_mg")

    suspend fun compute(ctx: Context, at: Long = System.currentTimeMillis()): EnergyState = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        val p = Prefs(ctx)
        val targets = Targets.forProfile(p)
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

        val foods = db.doneMealsBetween(day.minusDays(14).toString(), day.toString())
            .filter { it.takenAt <= at }
            .mapNotNull { m ->
                val n = m.analysis?.nutrients ?: return@mapNotNull null
                FoodIntake(m.takenAt, n["carbs_g"] ?: 0.0, n["protein_g"] ?: 0.0, n["fiber_g"] ?: 0.0, n)
            }
        val activity = db.activityBetween(day.minusDays(14).toString(), day.toString())
        val todayActivity = activity.firstOrNull { it.date == day.toString() }
        val exercises = activity.filter { LocalDate.parse(it.date) >= day.minusDays(2) }
            .flatMap { it.workouts }
            .map { Exercise(it.start, it.end, it.kcal, it.avgHr) }
        val lastWeekWorkouts = activity.filter { LocalDate.parse(it.date) >= day.minusDays(8) }
            .flatMap { it.workouts }.distinctBy { it.start }
            .map { Exercise(it.start, it.end, it.kcal, it.avgHr) }
        val baselineHr = median(activity.filter { it.date != day.toString() }.mapNotNull { it.restingHr?.toDouble() })
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        // Water logged here, plus any other app's water in Health Connect (Samsung Health doesn't share its own).
        val water = (db.waterBetween(dayStart, at) + HealthSync.water(ctx, dayStart, at).orEmpty().filter { it.second > 0 })
            .map { WaterIntake(it.first, it.second) }

        val micros = Nutrients.all.filter { it.kind == Kind.MICRO }
            .map { MicroSpec(it.key, it.label, targets[it.key] ?: 0.0, it.key in FAST) }

        EnergyModel.compute(
            EnergyInputs(
                now = at, zone = zone, weightKg = p.weightKg, age = p.age,
                proteinTarget = targets["protein_g"] ?: 50.0, micros = micros,
                foods = foods, exercises = exercises, water = water,
                sleeps = db.sleepsSince(at - 5 * 86_400_000L).filter { it.start <= at },
                restingHr = todayActivity?.restingHr, restingHrBaseline = baselineHr,
                activities = db.activitiesSince(at - 2 * 86_400_000L),
                plannedFoods = MealPlan.upcoming(MealPlan.slots(foods, zone), foods, at, zone),
                plannedExercises = MealPlan.repeatWorkouts(lastWeekWorkouts, at),
            ),
            db.checkIns().filter { it.time < at },
        )
    }

    /** One glass (or bottle) of water, from the phone or the watch. */
    suspend fun logWater(ctx: Context, ml: Double, at: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        Db.get(ctx).logWater(at, ml)
        SleepLink.publishEnergy(ctx)
    }

    suspend fun undoWater(ctx: Context, time: Long) = withContext(Dispatchers.IO) {
        Db.get(ctx).deleteWater(time)
        SleepLink.publishEnergy(ctx)
    }

    enum class QuickSize(val label: String, val scale: Double) { SNACK("Snack", 0.4), MEAL("Meal", 1.0), BIG("Big meal", 1.5) }

    /**
     * Adds a meal eaten without a photo, estimated from the user's own average logged meal (last 30 days),
     * so the fuel model isn't fooled by gaps. Shows in the meal list and goes to Samsung Health like any meal.
     */
    suspend fun quickAdd(ctx: Context, size: QuickSize, at: Long = System.currentTimeMillis()): Long = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        val today = LocalDate.now()
        val meals = db.doneMealsBetween(today.minusDays(30).toString(), today.toString())
            .mapNotNull { it.analysis }.filter { it.isFood && (it.nutrients["calories_kcal"] ?: 0.0) >= 250 }
        val avg: Map<String, Double> = if (meals.isNotEmpty()) {
            Nutrients.all.associate { d -> d.key to meals.sumOf { it.nutrients[d.key] ?: 0.0 } / meals.size }
        } else mapOf("calories_kcal" to 550.0, "carbs_g" to 70.0, "protein_g" to 18.0, "fat_g" to 20.0, "fiber_g" to 7.0, "sugar_g" to 10.0)
        val n = org.json.JSONObject()
        Nutrients.all.forEach { d -> n.put(d.key, ((avg[d.key] ?: 0.0) * size.scale * 10).toLong() / 10.0) }
        val hour = java.time.LocalTime.now().hour
        val type = if (size == QuickSize.SNACK) "snack" else when (hour) { in 4..10 -> "breakfast"; in 11..16 -> "lunch"; else -> "dinner" }
        val json = org.json.JSONObject()
            .put("is_food", true).put("meal_name", "${size.label} (no photo)").put("meal_type", type)
            .put("items", org.json.JSONArray()).put("nutrients", n).put("confidence", "low")
            .put("assumptions", "Added without a photo; estimated from your average logged meal" +
                if (meals.isEmpty()) " (none yet, so a typical meal was used)." else " over the last 30 days (${meals.size} meals).")
            .put("health_score", meals.map { it.healthScore }.average().takeIf { !it.isNaN() }?.toInt() ?: 6)
            .put("highlights", org.json.JSONArray()).put("watch_outs", org.json.JSONArray())
            .put("upgrades", org.json.JSONArray()).put("next_meal", "")
        val id = db.insertQuickMeal(at, Analysis.parse(json), json.toString())
        if (id > 0) { HealthSync.writeMealIfEnabled(ctx, id); SleepLink.publishEnergy(ctx) }
        id
    }

    /** Logs study, massage chair or yoga, ending now (or at [end]). */
    suspend fun logActivity(ctx: Context, type: String, minutes: Int, end: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        Db.get(ctx).logActivity(com.happypipaliya.sleepcore.LoggedActivity(type, end - minutes * 60_000L, minutes))
        SleepLink.publishEnergy(ctx)
    }

    /** Records how energetic the user feels (0-100), with the model's view at that moment. */
    suspend fun checkIn(ctx: Context, value: Int, at: Long = System.currentTimeMillis()) {
        val state = compute(ctx, at)
        Db.get(ctx).saveCheckIn(CheckIn(at, value.coerceIn(0, 100), state.features), state.energy)
        SleepLink.publishEnergy(ctx)
    }
}
