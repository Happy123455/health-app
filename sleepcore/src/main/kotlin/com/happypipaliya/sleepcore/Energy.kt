package com.happypipaliya.sleepcore

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Energy model.
 *
 * Fuel stores are simulated over the last 48 hours in 10-minute steps:
 *  - liver glycogen ("quick fuel", ~100 g): keeps blood sugar up; drains ~4.5 g/h when fasting,
 *    refills from absorbed carbs;
 *  - muscle glycogen ("muscle fuel", ~6 g per kg body weight): drained by workouts (more carbs at
 *    higher heart rate), refilled from carbs, faster in the hours after training.
 * Protein, water and vitamins/minerals are scored against targets on the right timescale
 * (protein and water: so far today; fast-turnover vitamins: 3 days; stored nutrients: 14 days).
 * The headline energy blends fuel with sleep and recovery, using default weights until the
 * user's own check-ins are enough to learn personal ones.
 */

data class FoodIntake(
    val time: Long,
    val carbs: Double,
    val protein: Double,
    val fiber: Double,
    val micros: Map<String, Double>,
)

data class Exercise(val start: Long, val end: Long, val kcal: Double?, val avgHr: Long?)

data class WaterIntake(val time: Long, val ml: Double)

/** A sleep session from the watch. Under 3 hours counts as a nap. */
data class SleepSession(val start: Long, val end: Long) {
    val minutes: Long get() = (end - start) / 60_000
    val isNap: Boolean get() = minutes < 180
}

/** Something logged by hand: "study", "massage" or "yoga", with its length. */
data class LoggedActivity(val type: String, val start: Long, val minutes: Int) {
    val end: Long get() = start + minutes * 60_000L
}

/** A nutrient that "reserves" tracks. Fast ones (water-soluble) use a 3-day window, others 14 days. */
data class MicroSpec(val key: String, val label: String, val target: Double, val fast: Boolean)

/** "How energetic do you feel?" (0-100) with the model's inputs at that moment. */
data class CheckIn(val time: Long, val value: Int, val features: List<Double>)

data class EnergyInputs(
    val now: Long,
    val zone: ZoneId,
    val weightKg: Double?,
    val age: Int?,
    val proteinTarget: Double,
    val micros: List<MicroSpec>,
    val foods: List<FoodIntake>,
    val exercises: List<Exercise>,
    val water: List<WaterIntake>?,          // null = not tracked
    val sleeps: List<SleepSession>,         // last ~5 days, nights and naps
    val restingHr: Long?,
    val restingHrBaseline: Double?,
    val activities: List<LoggedActivity> = emptyList(),
    /** Expected meals and workouts in the next 24 h (see [MealPlan]); only used for the forecast. */
    val plannedFoods: List<FoodIntake> = emptyList(),
    val plannedExercises: List<Exercise> = emptyList(),
)

data class Gauge(val key: String, val label: String, val level: Double, val detail: String)

data class FuelPoint(val time: Long, val liver: Double, val muscle: Double)

data class EnergyState(
    val energy: Double,                     // 0-1
    val personalised: Boolean,
    val checkIns: Int,
    val gauges: List<Gauge>,
    val need: String,
    val timeline: List<FuelPoint>,          // last 24 h, liver and muscle as fractions of full
    val features: List<Double>,             // see EnergyModel.FEATURE_NAMES
    val lowNutrients: List<Pair<MicroSpec, Double>>,
    /** What's pushing energy up or down right now: label to percentage points (from the active weights). */
    val factors: List<Pair<String, Double>>,
    val mealsLoggedToday: Int,
    val mealsExpectedByNow: Int,
    /** Next 24 h if you eat and train as usual (dotted on the chart). */
    val forecast: List<FuelPoint> = emptyList(),
    val plannedFoods: List<FoodIntake> = emptyList(),
    val plannedExercises: List<Exercise> = emptyList(),
) {
    /** True when fewer meals are logged than a normal day would have by now, so the fuel is probably understated. */
    val missingMeals: Boolean get() = mealsLoggedToday < mealsExpectedByNow
}

object EnergyModel {
    const val LIVER_CAP = 100.0
    private const val STEP_MS = 10 * 60_000L
    private const val HOUR = 3_600_000.0
    val FEATURE_NAMES = listOf(
        "quick fuel", "muscle fuel", "protein", "sleep", "recovery", "water",
        "study load", "time awake", "massage", "yoga",
    )
    /**
     * Starting weights. Fuel, sleep and recovery add up to 1; study and long waking hours subtract
     * (mental fatigue), massage and yoga add a little (recovery). Check-ins re-learn all of them.
     */
    val DEFAULT_WEIGHTS = listOf(0.32, 0.13, 0.12, 0.25, 0.13, 0.05, -0.08, -0.10, 0.03, 0.03)
    const val SLEEP_NEED_MIN = 480.0

    fun muscleCap(weightKg: Double?) = (weightKg ?: 65.0) * 6.0

    fun compute(inp: EnergyInputs, checkIns: List<CheckIn> = emptyList()): EnergyState {
        val full = simulate(inp, until = inp.now + 24 * HOUR.toLong())
        val sim = full.filter { it.time <= inp.now }
        val forecast = full.filter { it.time >= sim.last().time }
        val last = sim.last()
        val liver = last.liver
        val muscle = last.muscle

        val wake = inp.now.let { val d = Instant.ofEpochMilli(it).atZone(inp.zone).toLocalDate(); d.atTime(7, 0).atZone(inp.zone).toInstant().toEpochMilli() }
        val dayStart = Instant.ofEpochMilli(inp.now).atZone(inp.zone).toLocalDate().atStartOfDay(inp.zone).toInstant().toEpochMilli()
        val dayProgress = ((inp.now - wake) / (16 * HOUR)).coerceIn(0.15, 1.0)

        // Protein: today's intake against the share of the target expected by now.
        val today = inp.foods.filter { it.time in dayStart..inp.now }
        val proteinToday = today.sumOf { it.protein }
        val lastGoodProtein = inp.foods.filter { it.protein >= 15 && it.time <= inp.now }.maxOfOrNull { it.time }
        val hoursSinceProtein = lastGoodProtein?.let { (inp.now - it) / HOUR }
        var protein = (proteinToday / (inp.proteinTarget * dayProgress)).coerceIn(0.0, 1.0)
        if (hoursSinceProtein == null || hoursSinceProtein > 5) protein *= 0.8

        // Water: drinks today against ~35 ml/kg, pro-rated through the day.
        val waterLevel = inp.water?.let { w ->
            val drunk = w.filter { it.time in dayStart..inp.now }.sumOf { it.ml }
            val target = (inp.weightKg ?: 65.0) * 35
            (drunk / (target * dayProgress)).coerceIn(0.0, 1.0) to drunk
        }

        // Sleep: last night counts most, the 3 nights before add sleep debt, naps help a little.
        val sleepInfo = sleepInfo(inp)
        val sleep = sleepInfo?.score
        val recovery = if (inp.restingHr != null && inp.restingHrBaseline != null) {
            (1.0 - max(0.0, inp.restingHr - inp.restingHrBaseline) / 10.0).coerceIn(0.3, 1.0)
        } else null

        // Vitamins and minerals: average daily intake on logged days in each nutrient's window.
        val ratios = inp.micros.map { spec ->
            val days = if (spec.fast) 3 else 14
            val from = dayStart - (days - 1) * 24 * HOUR.toLong()
            val window = inp.foods.filter { it.time in from..inp.now }
            val loggedDays = window.map { Instant.ofEpochMilli(it.time).atZone(inp.zone).toLocalDate() }.distinct().size
            val perDay = if (loggedDays == 0) 0.0 else window.sumOf { it.micros[spec.key] ?: 0.0 } / loggedDays
            spec to if (spec.target > 0) (perDay / spec.target).coerceIn(0.0, 1.0) else 1.0
        }
        val reserves = if (ratios.isEmpty()) 1.0 else ratios.sumOf { it.second } / ratios.size
        val low = ratios.filter { it.second < 0.7 }.sortedBy { it.second }.take(3)

        // Logged activities, fading with time: study tires (half gone after ~2 h), massage and yoga help for a day.
        fun weighted(type: String, decayH: Double) = inp.activities.filter { it.type == type && it.start <= inp.now }
            .sumOf { a -> val ago = max(0.0, (inp.now - a.end) / HOUR); min(a.minutes.toDouble(), (inp.now - a.start) / 60_000.0) * kotlin.math.exp(-ago / decayH) }
        val studyLoad = (weighted("study", 3.0) / 240).coerceIn(0.0, 1.0)
        val massage = (weighted("massage", 12.0) / 20).coerceIn(0.0, 1.0)
        val yoga = (weighted("yoga", 12.0) / 30).coerceIn(0.0, 1.0)
        val awake = sleepInfo?.awakeFatigue ?: 0.0

        val features = listOf(liver, muscle, protein, sleep ?: 0.7, recovery ?: 0.8, waterLevel?.first ?: 0.75, studyLoad, awake, massage, yoga)
        val calibration = Calibration.fit(checkIns)
        val energy = (calibration?.predict(features) ?: defaultEnergy(features)).coerceIn(0.0, 1.0)
        val weights = calibration?.weights ?: DEFAULT_WEIGHTS
        val studyMin = inp.activities.filter { it.type == "study" && inp.now - it.end < 8 * HOUR }.sumOf { it.minutes }
        val massageMin = inp.activities.filter { it.type == "massage" && inp.now - it.end < 24 * HOUR }.sumOf { it.minutes }
        val yogaMin = inp.activities.filter { it.type == "yoga" && inp.now - it.end < 24 * HOUR }.sumOf { it.minutes }
        val factors = buildList {
            sleepInfo?.let { add("Sleep (${fmtMin(it.lastNight)} last night)" to weights[3] * (it.score - 1.0) * 100) }
            if (studyLoad > 0.02) add("Study (${fmtMin(studyMin.toLong())} recently)" to weights[6] * studyLoad * 100)
            if (awake > 0.02) add("Awake ${fmtH(sleepInfo!!.hoursAwake)}" to weights[7] * awake * 100)
            if (massage > 0.02) add("Massage chair ($massageMin min)" to weights[8] * massage * 100)
            if (yoga > 0.02) add("Yoga ($yogaMin min)" to weights[9] * yoga * 100)
        }.filter { abs(it.second) >= 0.5 }

        val hoursSinceMeal = inp.foods.filter { it.time <= inp.now }.maxOfOrNull { it.time }?.let { (inp.now - it) / HOUR }
        val gauges = buildList {
            add(Gauge("liver", "Quick fuel", liver, "Blood-sugar store in your liver" + (hoursSinceMeal?.let { ", last ate ${fmtH(it)} ago" } ?: "")))
            add(Gauge("muscle", "Muscle fuel", muscle, "Glycogen your muscles use in workouts"))
            add(Gauge("protein", "Protein", protein, "${proteinToday.toInt()} of ${inp.proteinTarget.toInt()} g today" + (hoursSinceProtein?.let { ", last good dose ${fmtH(it)} ago" } ?: "")))
            waterLevel?.let { add(Gauge("water", "Water", it.first, "${it.second.toInt()} ml today")) }
            sleepInfo?.let { add(Gauge("sleep", "Sleep", it.score, it.detail)) }
            add(Gauge("reserves", "Vitamins & minerals", reserves, if (low.isEmpty()) "On track this week" else "Low: " + low.joinToString { it.first.label }))
        }
        // Typical meal times (08:30, 13:30, 20:00): how many should be logged by now?
        val hourNow = Instant.ofEpochMilli(inp.now).atZone(inp.zone).let { it.hour + it.minute / 60.0 }
        val expected = listOf(9.5, 14.5, 21.0).count { hourNow >= it }
        val logged = today.count { it.carbs + it.protein >= 30 } // a few orange segments aren't a meal
        val need = if (logged < expected) {
            "Only $logged meal${if (logged == 1) "" else "s"} logged today, so your fuel may look lower than it is. " +
                "If you've eaten more, add it so the battery is right."
        } else need(liver, muscle, protein, waterLevel?.first, sleep, inp, hoursSinceMeal, low, studyLoad, awake, sleepInfo)
        val dayAgo = inp.now - 24 * HOUR.toLong()
        return EnergyState(energy, calibration != null, checkIns.size, gauges, need, sim.filter { it.time >= dayAgo }, features, low, factors, logged, expected,
            forecast, inp.plannedFoods, inp.plannedExercises)
    }

    fun defaultEnergy(f: List<Double>): Double {
        var e = f.indices.sumOf { DEFAULT_WEIGHTS[it] * f[it] }
        if (f[0] < 0.25) e -= (0.25 - f[0]) * 0.6 // running on empty feels worse than linear
        return e
    }

    /** Fuel stores from 48 h ago to [until] (now by default; later runs on the planned meals and workouts). */
    fun simulate(inp: EnergyInputs, until: Long = inp.now): List<FuelPoint> {
        val foods = inp.foods + inp.plannedFoods.filter { it.time > inp.now }
        val exercises = inp.exercises + inp.plannedExercises.filter { it.start > inp.now }
        val muscleCap = muscleCap(inp.weightKg)
        val hrMax = 208 - 0.7 * (inp.age ?: 25)
        var liver = 0.6 * LIVER_CAP
        var muscle = 0.65 * muscleCap
        val out = ArrayList<FuelPoint>()
        val start = inp.now - 48 * HOUR.toLong()
        var t = start
        val dtH = STEP_MS / HOUR
        while (t <= until) {
            val tEnd = t + STEP_MS
            // Carbs absorb over 1.5-4 h starting 15 min after eating; fibre slows it down.
            var absorbed = 0.0
            for (f in foods) {
                if (f.carbs <= 0) continue
                val dur = (2.0 + 2.0 * (f.fiber / f.carbs.coerceAtLeast(1.0)) * 5).coerceIn(1.5, 4.0) * HOUR
                val a = f.time + 15 * 60_000L
                val b = a + dur.toLong()
                val overlap = max(0L, min(tEnd, b) - max(t, a))
                absorbed += f.carbs * overlap / dur
            }
            // Workouts burn carbs, mostly from muscle; more at higher heart rate.
            var burned = 0.0
            var recentWorkout = false
            for (e in exercises) {
                val overlap = max(0L, min(tEnd, e.end) - max(t, e.start))
                if (t - e.end in 0..(3 * HOUR).toLong()) recentWorkout = true
                if (overlap == 0L) continue
                val minutes = max(1.0, (e.end - e.start) / 60_000.0)
                val kcal = e.kcal ?: (minutes * 6)
                val pct = e.avgHr?.let { it / hrMax }
                val carbShare = when {
                    pct == null -> 0.5
                    pct < 0.6 -> 0.35
                    pct < 0.75 -> 0.55
                    else -> 0.75
                }
                burned += kcal * (overlap / 60_000.0) / minutes * carbShare / 4.0
            }
            muscle -= burned * 0.8 + 0.3 * dtH
            liver -= burned * 0.2
            liver -= (if (absorbed > 0.05) 1.0 else 4.5) * dtH
            val toLiver = min(absorbed * 0.35, LIVER_CAP - liver).coerceAtLeast(0.0)
            liver += toLiver
            val toMuscle = min((absorbed - toLiver) * if (recentWorkout) 0.75 else 0.5, muscleCap - muscle).coerceAtLeast(0.0)
            muscle += toMuscle
            liver = liver.coerceIn(0.0, LIVER_CAP)
            muscle = muscle.coerceIn(0.0, muscleCap)
            out += FuelPoint(t, liver / LIVER_CAP, muscle / muscleCap)
            t = tEnd
        }
        return out
    }

    private fun need(
        liver: Double, muscle: Double, protein: Double, water: Double?, sleep: Double?,
        inp: EnergyInputs, hoursSinceMeal: Double?, low: List<Pair<MicroSpec, Double>>,
        studyLoad: Double, awake: Double, sleepInfo: SleepInfo?,
    ): String {
        val recentWorkout = inp.exercises.any { inp.now - it.end in 0..(6 * HOUR).toLong() }
        return when {
            liver < 0.2 || (liver < 0.35 && (hoursSinceMeal == null || hoursSinceMeal > 3)) ->
                "Quick fuel is low. Have a carb-based meal or snack now: poha, a banana with peanut butter, roti with sabzi or rice with dal."
            muscle < 0.45 && recentWorkout ->
                "Refuel after your workout: carbs plus protein, like rice with dal and curd, or a paneer roll."
            muscle < 0.4 ->
                "Muscle fuel is running low. Include a good portion of carbs (rice, roti, potatoes, fruit) in your next meal."
            protein < 0.6 ->
                "You're behind on protein. Aim for 20-30 g in your next meal: dal, chana, paneer, eggs, curd or soya chunks."
            water != null && water < 0.6 -> "Drink a glass or two of water; you're behind for this time of day."
            studyLoad > 0.6 ->
                "You've studied a lot recently. A 10-20 minute break (walk, stretch or a short nap under 30 min) will help more than food right now."
            awake > 0.6 && sleepInfo != null ->
                "You've been awake ${fmtH(sleepInfo.hoursAwake)}. Fuel is fine; the dip is mostly tiredness, so wind down or take a short nap if it's early enough."
            sleep != null && sleep < 0.65 ->
                "Your fuel looks fine. If you feel low, it's more likely sleep: " + (sleepInfo?.detail ?: "a short night") + "."
            low.isNotEmpty() -> "Well fuelled. This week ${low.first().first.label} has been low: " + foodFor(low.first().first.key)
            else -> "You're well fuelled. Keep doing what you're doing."
        }
    }

    private fun foodFor(key: String) = when (key) {
        "iron_mg" -> "try spinach dal, rajma or chana with a squeeze of lemon."
        "vitamin_b12_mcg" -> "curd, milk, paneer or eggs help."
        "vitamin_d_mcg" -> "15 minutes of midday sun, eggs or fortified milk."
        "calcium_mg" -> "curd, paneer, ragi or sesame."
        "vitamin_c_mg" -> "amla, guava, orange or lemon on your food."
        "folate_mcg" -> "dal, chana or leafy greens."
        "magnesium_mg" -> "nuts, seeds, bajra or dark leafy greens."
        "zinc_mg" -> "chickpeas, pumpkin seeds or cashews."
        "potassium_mg" -> "banana, coconut water or potatoes."
        "vitamin_a_mcg" -> "carrots, papaya or mango."
        "vitamin_e_mg" -> "almonds or sunflower seeds."
        "vitamin_k_mcg" -> "leafy greens like spinach or methi."
        else -> "add more vegetables, pulses and whole grains."
    }

    data class SleepInfo(
        val score: Double, val lastNight: Long, val debtMin: Long, val napMin: Long,
        val hoursAwake: Double, val awakeFatigue: Double, val detail: String,
    )

    /** Sleep score, 4-night debt, naps today and time awake. Null when there's no sleep data. */
    fun sleepInfo(inp: EnergyInputs): SleepInfo? {
        val past = inp.sleeps.filter { it.end <= inp.now }.sortedBy { it.end }
        val nights = past.filter { !it.isNap }
        val lastNight = nights.lastOrNull() ?: return null
        if (inp.now - lastNight.end > 36 * HOUR) return null
        // Total sleep per night (sessions within 4 h of each other are one night).
        val totals = ArrayList<Long>()
        var lastEnd = 0L
        for (n in nights) {
            if (totals.isNotEmpty() && n.start - lastEnd < 4 * HOUR) totals[totals.lastIndex] = totals.last() + n.minutes
            else totals += n.minutes
            lastEnd = n.end
        }
        val recent = totals.takeLast(4)
        val last = recent.last()
        val before = recent.dropLast(1)
        val naps = past.filter { it.isNap && it.start >= lastNight.end }
        val napMin = naps.sumOf { it.minutes }
        val debt = max(0L, recent.sumOf { max(0L, SLEEP_NEED_MIN.toLong() - it) } - napMin)
        var score = if (before.isEmpty()) last / SLEEP_NEED_MIN
        else 0.6 * last / SLEEP_NEED_MIN + 0.4 * before.average() / SLEEP_NEED_MIN
        score += min(napMin, 30L) / 30.0 * 0.10 + max(0L, min(napMin, 90L) - 30) / 60.0 * 0.05
        // Time awake since the night ended; a nap of 20+ min takes ~1.5 h off.
        val hoursAwake = max(0.0, (inp.now - lastNight.end) / HOUR - if (napMin >= 20) min(napMin, 90L) / 60.0 * 1.5 else 0.0)
        val awakeFatigue = ((hoursAwake - 8) / 10).coerceIn(0.0, 1.0)
        val detail = buildList {
            add("${fmtMin(last)} last night")
            if (before.isNotEmpty() && debt >= 30) add("${fmtMin(debt)} short over ${recent.size} nights")
            if (napMin > 0) add("nap ${fmtMin(napMin)}")
        }.joinToString(" · ")
        return SleepInfo(score.coerceIn(0.0, 1.0), last, debt, napMin, hoursAwake, awakeFatigue, detail)
    }

    private fun fmtMin(m: Long) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    private fun fmtH(h: Double) = if (h < 1) "${(h * 60).toInt()} min" else "${"%.1f".format(h).removeSuffix(".0")} h"
}

/**
 * Learns personal weights from check-ins: ridge regression pulled towards the default weights, so a
 * few check-ins nudge the model and many check-ins let it fit you closely.
 */
class Calibration(val bias: Double, val weights: List<Double>) {
    fun predict(f: List<Double>) = bias + f.indices.sumOf { weights[it] * f[it] }

    companion object {
        const val MIN_CHECK_INS = 5

        fun fit(checkIns: List<CheckIn>): Calibration? {
            // Older check-ins had fewer inputs; the newer ones are padded with "none" (0).
            val n = EnergyModel.DEFAULT_WEIGHTS.size
            val data = checkIns.filter { it.features.size <= n }
                .map { it.copy(features = it.features + List(n - it.features.size) { 0.0 }) }
            if (data.size < MIN_CHECK_INS) return null
            val d = EnergyModel.DEFAULT_WEIGHTS.size + 1 // + bias
            val lambda = 3.0
            val prior = listOf(0.0) + EnergyModel.DEFAULT_WEIGHTS
            val a = Array(d) { DoubleArray(d) }
            val b = DoubleArray(d)
            for (c in data) {
                val x = listOf(1.0) + c.features
                val y = c.value / 100.0
                for (i in 0 until d) {
                    b[i] += x[i] * y
                    for (j in 0 until d) a[i][j] += x[i] * x[j]
                }
            }
            for (i in 0 until d) { a[i][i] += lambda; b[i] += lambda * prior[i] }
            val w = solve(a, b) ?: return null
            return Calibration(w[0], w.drop(1))
        }

        /** Average error of the fitted model on the check-ins (0-1). */
        fun error(checkIns: List<CheckIn>): Double? {
            val c = fit(checkIns) ?: return null
            val n = EnergyModel.DEFAULT_WEIGHTS.size
            return checkIns.map { it.copy(features = it.features + List(n - it.features.size) { 0.0 }) }.map { abs(c.predict(it.features).coerceIn(0.0, 1.0) - it.value / 100.0) }.average()
        }

        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { i -> a[i].copyOf() + b[i] }
            for (col in 0 until n) {
                val pivot = (col until n).maxByOrNull { abs(m[it][col]) } ?: return null
                if (abs(m[pivot][col]) < 1e-12) return null
                val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
                for (r in 0 until n) if (r != col) {
                    val f = m[r][col] / m[col][col]
                    for (k in col..n) m[r][k] -= f * m[col][k]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}
