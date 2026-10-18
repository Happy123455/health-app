package com.happypipaliya.sleepcore

import kotlin.math.max
import kotlin.math.min

/*
 * Muscle recovery model.
 *
 * Each workout is mapped to the muscles it trains (from its name: the user's routines are named by
 * muscle group, and walks/runs/rides are recognised), with a stimulus from duration and heart rate.
 * Recovery after a session takes ~18-60 h depending on how hard it was (beginners recover in about
 * 48 h from a solid session), slower with low protein or short sleep, a little faster after massage.
 * Nothing here measures muscle damage directly; it is an estimate from training load, food and sleep.
 */

enum class Muscle(val label: String) {
    CHEST("Chest"), SHOULDERS("Shoulders"), BICEPS("Biceps"), TRICEPS("Triceps"), FOREARMS("Forearms"),
    UPPER_BACK("Upper back"), LOWER_BACK("Lower back"), CORE("Core"), GLUTES("Glutes"),
    QUADS("Quads"), HAMSTRINGS("Hamstrings"), CALVES("Calves"),
}

data class TrainingSession(val title: String, val start: Long, val end: Long, val kcal: Double?, val avgHr: Long?)

data class MuscleState(
    val muscle: Muscle,
    val recovery: Double,      // 0 = just trained hard, 1 = ready
    val readyAt: Long?,        // when it's expected to be ~fully recovered (null if already)
    val lastTrained: Long?,
    val weekLoad: Double,      // training stimulus over the last 7 days
    val dailyUse: Double,      // how much a typical study/desk day uses it (0-1, general guidance)
)

data class RepairNeed(val key: String, val label: String, val have: Double, val need: Double, val unit: String, val why: String) {
    val level: Double get() = if (need <= 0) 1.0 else (have / need).coerceIn(0.0, 1.0)
}

data class MuscleReport(
    val states: List<MuscleState>,
    val repair: List<RepairNeed>,
    val focus: List<Pair<Muscle, String>>,
    val tip: String,
) {
    val recovering: List<MuscleState> get() = states.filter { it.recovery < 0.95 }.sortedBy { it.recovery }
}

object MuscleModel {
    private const val HOUR = 3_600_000.0

    /** Typical use on a study/desk day: forearms (typing, carrying) and calves (walking) work; glutes, upper back and hamstrings barely do. */
    val DESK_DAY_USE = mapOf(
        Muscle.CHEST to 0.2, Muscle.SHOULDERS to 0.35, Muscle.BICEPS to 0.35, Muscle.TRICEPS to 0.25,
        Muscle.FOREARMS to 0.7, Muscle.UPPER_BACK to 0.25, Muscle.LOWER_BACK to 0.4, Muscle.CORE to 0.35,
        Muscle.GLUTES to 0.2, Muscle.QUADS to 0.45, Muscle.HAMSTRINGS to 0.3, Muscle.CALVES to 0.6,
    )

    private val KEYWORDS: List<Pair<List<String>, Map<Muscle, Double>>> = listOf(
        listOf("chest", "push") to mapOf(Muscle.CHEST to 1.0, Muscle.TRICEPS to 0.6, Muscle.SHOULDERS to 0.5, Muscle.CORE to 0.3),
        listOf("tricep") to mapOf(Muscle.TRICEPS to 1.0),
        listOf("back", "pull", "deadlift") to mapOf(
            Muscle.UPPER_BACK to 1.0, Muscle.LOWER_BACK to 0.7, Muscle.BICEPS to 0.5, Muscle.FOREARMS to 0.4,
            Muscle.HAMSTRINGS to 0.4, Muscle.GLUTES to 0.4,
        ),
        listOf("bicep", "curl") to mapOf(Muscle.BICEPS to 1.0, Muscle.FOREARMS to 0.5),
        listOf("leg", "squat", "lunge") to mapOf(Muscle.QUADS to 1.0, Muscle.GLUTES to 0.9, Muscle.HAMSTRINGS to 0.6, Muscle.CALVES to 0.5),
        listOf("shoulder") to mapOf(Muscle.SHOULDERS to 1.0, Muscle.TRICEPS to 0.5, Muscle.UPPER_BACK to 0.4),
        listOf("core", "abs", "plank") to mapOf(Muscle.CORE to 1.0, Muscle.SHOULDERS to 0.3),
        listOf("cardio", "hiit", "burpee") to mapOf(
            Muscle.QUADS to 0.5, Muscle.CALVES to 0.7, Muscle.GLUTES to 0.4, Muscle.CORE to 0.4, Muscle.CHEST to 0.3, Muscle.SHOULDERS to 0.3,
        ),
        listOf("full") to Muscle.entries.associateWith { 0.6 },
        listOf("run", "jog") to mapOf(Muscle.QUADS to 0.7, Muscle.CALVES to 0.8, Muscle.HAMSTRINGS to 0.6, Muscle.GLUTES to 0.6, Muscle.CORE to 0.3),
        listOf("walk", "hik") to mapOf(Muscle.CALVES to 0.4, Muscle.QUADS to 0.3, Muscle.GLUTES to 0.3, Muscle.HAMSTRINGS to 0.2),
        listOf("cycl", "bik") to mapOf(Muscle.QUADS to 0.9, Muscle.GLUTES to 0.5, Muscle.CALVES to 0.4, Muscle.HAMSTRINGS to 0.3),
        listOf("yoga", "stretch", "pilates") to mapOf(Muscle.CORE to 0.4, Muscle.SHOULDERS to 0.3, Muscle.HAMSTRINGS to 0.3, Muscle.LOWER_BACK to 0.3),
    )

    /** Muscles a workout trains (0-1 each), from its name. */
    fun musclesFor(title: String): Map<Muscle, Double> {
        val t = title.lowercase()
        val out = HashMap<Muscle, Double>()
        for ((words, map) in KEYWORDS) if (words.any { it in t }) map.forEach { (m, w) -> out[m] = max(out[m] ?: 0.0, w) }
        return out
    }

    /** How hard the session was: duration (35 min = 1) times heart-rate intensity. */
    fun stimulus(s: TrainingSession, age: Int?): Double {
        val minutes = (s.end - s.start) / 60_000.0
        val hrMax = 208 - 0.7 * (age ?: 25)
        val hr = s.avgHr?.let { it / hrMax }
        val intensity = when {
            hr == null -> 1.0
            hr >= 0.75 -> 1.2
            hr < 0.6 -> 0.8
            else -> 1.0
        }
        return (minutes / 35.0).coerceIn(0.4, 1.6) * intensity
    }

    /** Hours to recover from a session of this stimulus on one muscle: ~18 h light up to ~60 h very hard. */
    fun baseHours(stim: Double) = 18 + 42 * min(stim, 1.5) / 1.5

    fun report(
        now: Long,
        sessions: List<TrainingSession>,
        foods: List<FoodIntake>,
        proteinTarget: Double,
        weightKg: Double?,
        age: Int?,
        sleepScore: Double?,
        massages: List<LoggedActivity>,
        microTargets: Map<String, Double>,
        dayStart: Long,
    ): MuscleReport {
        val weekAgo = now - 7 * 24 * HOUR.toLong()
        val recent = sessions.filter { it.end in weekAgo..now }
        val states = Muscle.entries.map { m ->
            var fatigue = 0.0
            var readyAt: Long? = null
            var last: Long? = null
            var weekLoad = 0.0
            for (s in recent) {
                val w = musclesFor(s.title)[m] ?: continue
                val stim = w * stimulus(s, age)
                weekLoad += stim
                last = max(last ?: 0L, s.end)
                // Protein eaten since this session, against what's needed over the same time.
                val days = max(0.5, (now - s.end) / (24 * HOUR))
                val protein = foods.filter { it.time >= s.end && it.time <= now }.sumOf { it.protein }
                val pRatio = protein / (proteinTarget * days)
                var hours = baseHours(stim)
                hours *= when {
                    pRatio < 0.6 -> 1.3
                    pRatio < 0.9 -> 1.12
                    else -> 0.92
                }
                if (sleepScore != null && sleepScore < 0.7) hours *= 1.15
                if (massages.any { it.start in s.end..(s.end + 24 * HOUR.toLong()) }) hours *= 0.95
                val x = ((now - s.end) / HOUR / hours).coerceIn(0.0, 1.0)
                val recovered = 1 - (1 - x) * (1 - x) // fastest at first, then tapering off
                fatigue += min(1.0, stim) * (1 - recovered)
                if (recovered < 0.95) readyAt = max(readyAt ?: 0L, s.end + (hours * HOUR).toLong())
            }
            MuscleState(m, (1 - min(1.0, fatigue)).coerceIn(0.0, 1.0), readyAt, last, weekLoad, DESK_DAY_USE.getValue(m))
        }

        // Repair kit: today's intake of what rebuilding muscle needs.
        val today = foods.filter { it.time in dayStart..now }
        fun micro(k: String) = today.sumOf { it.micros[k] ?: 0.0 }
        val repair = listOf(
            RepairNeed("protein", "Protein", today.sumOf { it.protein }, proteinTarget, "g", "Builds new muscle fibre to repair the micro-tears training causes"),
            RepairNeed("carbs", "Carbs", today.sumOf { it.carbs }, (weightKg ?: 60.0) * 4, "g", "Refills the muscle fuel your workout used"),
            RepairNeed("vitamin_c_mg", "Vitamin C", micro("vitamin_c_mg"), microTargets["vitamin_c_mg"] ?: 90.0, "mg", "Makes collagen for tendons and connective tissue"),
            RepairNeed("zinc_mg", "Zinc", micro("zinc_mg"), microTargets["zinc_mg"] ?: 11.0, "mg", "Helps build protein and heal tissue"),
            RepairNeed("magnesium_mg", "Magnesium", micro("magnesium_mg"), microTargets["magnesium_mg"] ?: 400.0, "mg", "Lets muscles contract and relax; low levels mean cramps"),
            RepairNeed("vitamin_d_mcg", "Vitamin D", micro("vitamin_d_mcg"), microTargets["vitamin_d_mcg"] ?: 15.0, "mcg", "Supports muscle strength and function"),
        )

        // Muscles that both desk life and this week's training leave out.
        val focus = states.filter { it.weekLoad < 0.5 && it.dailyUse < 0.4 }
            .sortedBy { it.weekLoad + it.dailyUse }
            .take(3)
            .map { it.muscle to suggestionFor(it.muscle) }

        val recovering = states.filter { it.recovery < 0.95 }.sortedBy { it.recovery }
        val protein = repair.first()
        val tip = when {
            recovering.isNotEmpty() && protein.level < 0.6 ->
                "${recovering.take(2).joinToString(" & ") { it.muscle.label }} ${if (recovering.size == 1 && !recovering.first().muscle.label.endsWith("s")) "is" else "are"} still recovering. " +
                    "About 25 g of protein in your next meal (dal, paneer, curd, eggs, soya) helps repair."
            recovering.isNotEmpty() ->
                "${recovering.first().muscle.label} ${if (recovering.first().muscle.label.endsWith("s")) "are" else "is"} ${(recovering.first().recovery * 100).toInt()}% recovered. Protein is on track; sleep does the rest."
            focus.isNotEmpty() -> "Everything's recovered. ${focus.first().first.label} ${if (focus.first().first.label.endsWith("s")) "get" else "gets"} little use on desk days, so it's a good one to train next."
            else -> "All muscles are recovered and ready."
        }
        return MuscleReport(states, repair, focus, tip)
    }

    private fun suggestionFor(m: Muscle) = when (m) {
        Muscle.GLUTES -> "Sitting switches glutes off. Add squats, lunges or glute bridges."
        Muscle.UPPER_BACK -> "Desk posture rounds the upper back. Back extensions or bag rows help."
        Muscle.HAMSTRINGS -> "Hamstrings stay short when you sit. Bag deadlifts and stretches help."
        Muscle.CHEST -> "Push-ups train chest with no equipment."
        Muscle.CORE -> "Planks and leg raises build the core that supports long sitting."
        Muscle.TRICEPS -> "Bag overhead extensions or close push-ups."
        Muscle.SHOULDERS -> "Bag shoulder presses and lateral raises."
        Muscle.BICEPS -> "Bag curls work biceps well."
        Muscle.LOWER_BACK -> "Back extensions strengthen it gently."
        Muscle.QUADS -> "Squats and wall sits."
        Muscle.CALVES -> "Calf raises on a step."
        Muscle.FOREARMS -> "Carrying your bag or hanging from a bar."
    }
}
