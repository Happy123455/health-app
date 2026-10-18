package com.happypipaliya.sleepcore

import java.time.Instant
import java.time.ZoneId

/**
 * What the next 24 hours probably hold, for the energy forecast: your usual meals (learned from the
 * last two weeks of logged meals) and the workouts you did on the same day last week.
 */
object MealPlan {
    private const val MIN = 60_000L
    private const val DAY = 24 * 60 * MIN

    /** A usual meal: time of day (minutes after midnight) and median macros. */
    data class Slot(val minute: Int, val carbs: Double, val protein: Double, val fiber: Double, val days: Int, val fat: Double = 15.0, val kcal: Double = 450.0)

    /** Meal times you keep coming back to. Clusters meals within 90 min; keeps ones on at least ~a third of logged days. */
    fun slots(history: List<FoodIntake>, zone: ZoneId): List<Slot> {
        val meals = history.filter { it.carbs + it.protein >= 30 }
        val days = meals.map { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }.distinct().size
        if (days < 3) return DEFAULT
        fun minute(t: Long) = Instant.ofEpochMilli(t).atZone(zone).let { it.hour * 60 + it.minute }
        val sorted = meals.sortedBy { minute(it.time) }
        val clusters = ArrayList<MutableList<FoodIntake>>()
        for (m in sorted) {
            val c = clusters.lastOrNull()
            if (c != null && minute(m.time) - minute(c.last().time) <= 90) c += m else clusters += mutableListOf(m)
        }
        val keep = clusters.filter { c -> c.map { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }.distinct().size >= maxOf(2, (days * 0.35).toInt()) }
        if (keep.isEmpty()) return DEFAULT
        return keep.map { c ->
            Slot(median(c.map { minute(it.time).toDouble() }).toInt(), median(c.map { it.carbs }), median(c.map { it.protein }), median(c.map { it.fiber }),
                c.map { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }.distinct().size,
                median(c.map { it.micros["fat_g"] ?: it.carbs * 0.3 }),
                median(c.map { it.micros["calories_kcal"] ?: (it.carbs * 4 + it.protein * 4 + (it.micros["fat_g"] ?: it.carbs * 0.3) * 9) }))
        }
    }

    private val DEFAULT = listOf(Slot(9 * 60, 55.0, 15.0, 6.0, 0), Slot(13 * 60 + 30, 75.0, 18.0, 8.0, 0), Slot(20 * 60 + 30, 75.0, 18.0, 8.0, 0))

    /**
     * Meals expected in (now, now + 24 h]. A usual meal coming up soon is skipped for today if you've
     * already eaten within 2 h before it (you had it early).
     */
    fun upcoming(slots: List<Slot>, eaten: List<FoodIntake>, now: Long, zone: ZoneId): List<FoodIntake> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return slots.mapNotNull { s ->
            var at = today.atStartOfDay(zone).toInstant().toEpochMilli() + s.minute * MIN
            val early = eaten.any { it.carbs + it.protein >= 30 && it.time in (at - 120 * MIN)..now }
            if (at <= now || early) at += DAY
            if (at > now + DAY) null else FoodIntake(at, s.carbs, s.protein, s.fiber, mapOf("fat_g" to s.fat, "calories_kcal" to s.kcal))
        }.sortedBy { it.time }
    }

    /** Workouts from the same day last week, moved forward a week. */
    fun repeatWorkouts(exercises: List<Exercise>, now: Long): List<Exercise> =
        exercises.filter { it.start + 7 * DAY in (now + 1)..(now + DAY) }
            .map { it.copy(start = it.start + 7 * DAY, end = it.end + 7 * DAY) }

    private fun median(v: List<Double>): Double = v.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
}
