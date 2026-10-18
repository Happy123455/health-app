package com.happypipaliya.sleepcore

import kotlin.math.max

/*
 * Digestion timing and food pairing.
 *
 * Timing: a mixed meal leaves the stomach in roughly 2-4 h. Fat slows it most, then fibre and protein;
 * small or liquid meals empty in under an hour. Absorption in the small intestine takes a few hours
 * more. These are population averages, so the app labels them as estimates.
 *
 * Pairing rules follow well-replicated findings:
 * - Tea and coffee polyphenols reduce non-haem (plant) iron absorption, by about half or more when
 *   drunk with the meal; waiting about an hour after eating avoids most of the effect.
 * - Vitamin C eaten with plant iron increases its absorption severalfold.
 * - A large calcium dose with a meal modestly reduces iron uptake from that meal.
 * - Vitamins A and K are fat-soluble and absorb poorly from a meal with almost no fat.
 * - Pulses plus grains supply all essential amino acids between them.
 * - Eating vegetables/protein before the carbs, and a light walk after eating, both lower the
 *   blood-sugar rise.
 * - Eating within ~3 h of lying down raises reflux risk.
 */

data class MealDigest(
    val time: Long,
    val name: String,
    val words: List<String>, // meal name + item names, for spotting chai, dal, lemon...
    val carbs: Double,
    val protein: Double,
    val fat: Double,
    val fiber: Double,
    val kcal: Double,
    val micros: Map<String, Double>,
)

enum class DigestPhase(val label: String) { STOMACH("In your stomach"), ABSORBING("Being absorbed"), DONE("Digested") }

data class DigestState(
    val meal: MealDigest,
    val stomachEmptyAt: Long,
    val absorbedAt: Long,
    val phase: DigestPhase,
    val progress: Double, // 0 at eating, 1 when absorbed
)

enum class PairKind { GOOD, TIP, WARN }

data class Pairing(val kind: PairKind, val title: String, val text: String)

data class DigestionReport(
    val states: List<DigestState>, // meals still digesting (and those that finished in the last hour), oldest first
    val now: List<Pairing>,        // what to do right now, most useful first
)

object Digestion {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    private val TEA = listOf("chai", "tea", "coffee", "kadak", "cappuccino", "latte", "espresso")
    private val PULSES = listOf("dal", "daal", "rajma", "chana", "chole", "moong", "masoor", "toor", "urad", "lentil", "bean", "sprout", "khichdi", "sambar", "besan", "dhokla", "kadhi", "soya", "tofu")
    private val GRAINS = listOf("rice", "roti", "chapati", "rotli", "bajra", "jowar", "bhakri", "naan", "paratha", "thepla", "puri", "khichdi", "bread", "poha", "upma")
    private val VIT_C = listOf("lemon", "lime", "amla", "tomato", "guava", "orange", "kiwi", "capsicum", "bell pepper", "mosambi", "papaya")

    // Whole-word starts, so "steamed" isn't tea and "price" isn't rice; plurals ("lentils") still match.
    private val patterns = HashMap<List<String>, Regex>()
    private fun MealDigest.has(list: List<String>): Boolean {
        val re = patterns.getOrPut(list) { Regex("\\b(" + list.joinToString("|") { Regex.escape(it) } + ")(s|es)?\\b", RegexOption.IGNORE_CASE) }
        return words.any { re.containsMatchIn(it) }
    }
    private fun MealDigest.m(k: String) = micros[k] ?: 0.0
    fun isTea(m: MealDigest) = m.has(TEA)
    /** A drink or snack that is mostly tea/coffee. */
    private fun isJustTea(m: MealDigest) = isTea(m) && m.kcal < 200
    private fun ironRich(m: MealDigest) = m.m("iron_mg") >= 3.0
    private fun vitCRich(m: MealDigest) = m.m("vitamin_c_mg") >= 25.0 || m.has(VIT_C)

    /** Hours until the stomach has emptied this meal: ~0.7 h for chai, ~1.5 h for a snack, ~3 h for a thali. */
    fun stomachHours(m: MealDigest): Double =
        (0.6 + 0.012 * m.carbs + 0.02 * m.protein + 0.045 * m.fat + 0.03 * m.fiber).coerceIn(0.6, 5.0)

    /** Most absorption is done ~2-3 h after the stomach empties. */
    fun absorbHours(m: MealDigest): Double = stomachHours(m) + (1.2 + 0.006 * (m.carbs + m.protein)).coerceAtMost(3.0)

    fun state(m: MealDigest, now: Long): DigestState {
        val empty = m.time + (stomachHours(m) * HOUR).toLong()
        val done = m.time + (absorbHours(m) * HOUR).toLong()
        val phase = when {
            now < empty -> DigestPhase.STOMACH
            now < done -> DigestPhase.ABSORBING
            else -> DigestPhase.DONE
        }
        return DigestState(m, empty, done, phase, ((now - m.time).toDouble() / (done - m.time)).coerceIn(0.0, 1.0))
    }

    /** Pairing notes for one meal, using what else was eaten or drunk within an hour of it. */
    fun pairings(m: MealDigest, nearby: List<MealDigest>): List<Pairing> = buildList {
        val teaWith = isTea(m) || nearby.any { isTea(it) && kotlin.math.abs(it.time - m.time) <= HOUR }
        if (ironRich(m) && teaWith) add(
            Pairing(PairKind.WARN, "Chai is blocking some iron",
                "Tea and coffee with an iron-rich meal can cut the iron you absorb by half or more. Have it about an hour after eating instead."),
        )
        if (ironRich(m) && vitCRich(m)) add(
            Pairing(PairKind.GOOD, "Iron + vitamin C",
                "The vitamin C in this meal helps you absorb its plant iron (${fmt1(m.m("iron_mg"))} mg)."),
        ) else if (ironRich(m)) add(
            Pairing(PairKind.TIP, "Add lemon for iron",
                "This meal has ${fmt1(m.m("iron_mg"))} mg of iron. A squeeze of lemon, amla, tomato or guava with it can raise how much you absorb several times over."),
        )
        if (ironRich(m) && m.m("calcium_mg") >= 350) add(
            Pairing(PairKind.TIP, "Lots of dairy with iron",
                "A large calcium serving (milk, curd, paneer) slightly lowers iron uptake from the same meal. Small effect overall; only worth spacing out if your iron is low."),
        )
        val fatSoluble = m.m("vitamin_a_mcg") >= 250 || m.m("vitamin_k_mcg") >= 60
        if (fatSoluble && m.fat < 5) add(
            Pairing(PairKind.TIP, "Add a little fat",
                "Vitamins A and K need some fat to be absorbed. A spoon of ghee, oil or a few nuts with this meal helps."),
        ) else if (fatSoluble) add(
            Pairing(PairKind.GOOD, "Vitamins A/K with fat", "There's enough fat here to absorb the vitamin A and K from the vegetables."),
        )
        if (m.has(PULSES) && m.has(GRAINS)) add(
            Pairing(PairKind.GOOD, "Complete protein", "Dal (or other pulses) with grains gives all the essential amino acids your muscles need."),
        )
        if (m.carbs >= 60 && m.fiber < 6 && !isJustTea(m)) add(
            Pairing(PairKind.TIP, "Sabzi or dal first",
                "This meal is carb-heavy. Eating the vegetables, salad or dal before the rice/roti can soften the blood-sugar spike."),
        )
    }

    /**
     * Today's digestion and what to do right now.
     * [usualBedMinutes]: usual bedtime as minutes after midnight (e.g. 23:30 = 1410), or null if unknown.
     * [minutesOfDay]: the current local time of day in minutes.
     */
    fun report(now: Long, meals: List<MealDigest>, usualBedMinutes: Int?, minutesOfDay: Int): DigestionReport {
        val states = meals.filter { it.time <= now && now - it.time < 10 * HOUR }
            .map { state(it, now) }
            .filter { it.phase != DigestPhase.DONE || now - it.absorbedAt < HOUR }
        val tips = ArrayList<Pairing>()
        val last = meals.filter { it.time <= now && !isJustTea(it) }.maxByOrNull { it.time }
        val toBed = usualBedMinutes?.let { ((it - minutesOfDay) + 1440) % 1440 }
        if (last != null) {
            val since = (now - last.time) / MIN
            val st = state(last, now)
            if (since in 0..60 && last.carbs >= 30) tips += Pairing(
                PairKind.TIP, "Walk for 10 minutes",
                "A light 10-15 min walk in the hour after eating lowers the blood-sugar rise from ${last.name}.",
            )
            if (ironRich(last) && since < 60 && meals.none { isTea(it) && it.time > last.time }) tips += Pairing(
                PairKind.TIP, "Chai after ${clock(minutesOfDay + (60 - since).toInt())}",
                "${last.name} is rich in iron. Waiting an hour for tea or coffee lets you absorb more of it.",
            )
            val daytime = minutesOfDay in 5 * 60..21 * 60 && (toBed == null || toBed > 180)
            if (daytime && st.phase == DigestPhase.STOMACH && stomachHours(last) >= 2.5) tips += Pairing(
                PairKind.TIP, "Hard workout after ${clock(minutesOfDay + ((st.stomachEmptyAt - now) / MIN).toInt())}",
                "That was a big meal and it's still in your stomach. A walk is fine now; save intense exercise until it has emptied.",
            )
        }
        val ateRecently = last != null && now - last.time < 3 * HOUR && last.kcal >= 300
        if (usualBedMinutes != null && toBed != null) {
            val inStomachAtBed = states.any { it.stomachEmptyAt > now + toBed * MIN }
            if (toBed in 1..180 && inStomachAtBed) tips += Pairing(
                PairKind.WARN, "Still digesting at bedtime",
                "Your last meal will still be in your stomach at your usual bedtime (${clock(usualBedMinutes)}). Lying down on a full stomach can cause reflux; stay up a little or keep late meals light.",
            ) else if (toBed in 181..360 && !ateRecently) tips += Pairing(
                PairKind.TIP, "Dinner by ${clock(usualBedMinutes - 180)}",
                "Finishing dinner about 3 hours before your usual bedtime (${clock(usualBedMinutes)}) helps you sleep and avoids reflux.",
            )
        }
        return DigestionReport(states, tips.sortedBy { it.kind != PairKind.WARN })
    }

    /**
     * How full the stomach is at [t], 0-1 (1 ≈ a large ~700 kcal meal). Each meal starts emptying after a
     * short lag, then at a steady rate (solid meals empty roughly linearly) until [stomachHours].
     */
    fun fullness(meals: List<MealDigest>, t: Long): Double = meals.sumOf { m ->
        if (t < m.time) return@sumOf 0.0
        val x = (t - m.time) / (stomachHours(m) * HOUR)
        if (x >= 1) return@sumOf 0.0
        val left = if (x < 0.08) 1.0 else 1 - (x - 0.08) / 0.92
        (m.kcal.coerceAtLeast(60.0) / 700.0).coerceAtMost(1.2) * left
    }.coerceAtMost(1.0)

    fun stomachEmptyAt(m: MealDigest): Long = m.time + (stomachHours(m) * HOUR).toLong()

    fun clock(minutes: Int): String {
        val m = ((minutes % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** Usual bedtime (minutes after midnight) from recent main sleeps: the median start, with evening times kept before midnight. */
    fun usualBedtime(sleeps: List<SleepSession>, minuteOfDay: (Long) -> Int): Int? {
        val starts = sleeps.filter { !it.isNap }.map { minuteOfDay(it.start) }.map { if (it < 12 * 60) it + 1440 else it }.sorted()
        if (starts.size < 2) return null
        return starts[(starts.size - 1) / 2] % 1440 // lower middle: the earlier (safer) time
    }

    private fun fmt1(v: Double) = if (v >= 10) "%.0f".format(v) else "%.1f".format(max(v, 0.0))
}
