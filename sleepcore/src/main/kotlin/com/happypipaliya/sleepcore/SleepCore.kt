package com.happypipaliya.sleepcore

import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** One 30-second slice of the night, as measured on the watch. */
data class Epoch(
    val start: Long,     // epoch start, millis
    val move: Double,    // mean |acceleration - gravity| in m/s²
    val hr: Double?,     // mean heart rate (bpm), null when the sensor gave nothing
    val hrSd: Double?,   // spread of heart rate within the epoch (bpm)
)

/** Sleep stages as Samsung reports them (through Health Connect). */
enum class Stage { AWAKE, LIGHT, DEEP, REM, UNKNOWN }

/** The wake window set on the phone. [days] is a bitmask, Monday = bit 0 … Sunday = bit 6. */
data class AlarmSettings(
    val enabled: Boolean = false,
    val startMinute: Int = 6 * 60 + 30,
    val windowMinutes: Int = 30,
    val days: Int = 0b1111111,
) {
    fun isOn(dayOfWeek: java.time.DayOfWeek) = days and (1 shl (dayOfWeek.value - 1)) != 0
}

/** Everything the watch recorded for one wake-up. */
data class NightRecord(
    val monitorStart: Long,
    val windowStart: Long,
    val windowEnd: Long,
    val alarmAt: Long,
    val trigger: String,      // "smart", "window_end" or "test"
    val grogginess: Int?,     // 0-100, from the bezel
    val modelVersion: Int,    // 0 = starter rules
    val epochs: List<Epoch>,
    val scores: List<Double>, // what the watch thought, per epoch (0-1)
)

const val EPOCH_MS = 30_000L

/** Minutes of sensing before the window opens, to learn tonight's resting heart rate. */
const val BASELINE_MINUTES = 20L

object Features {
    val NAMES = listOf(
        "movement", "movement (last 1.5 min)", "heart rate above resting",
        "heart rate above resting (last 1.5 min)", "heart-rate spread", "heart-rate spread (last 1.5 min)",
    )

    /**
     * One feature vector per epoch. Each uses only that epoch and earlier ones, so the watch can
     * compute exactly the same numbers live as the phone does later for training.
     */
    fun compute(epochs: List<Epoch>, baselineUntil: Long): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(epochs.size)
        val baselineHr = ArrayList<Double>()
        val seenHr = ArrayList<Double>()
        var lastHr: Double? = null
        for (i in epochs.indices) {
            val e = epochs[i]
            e.hr?.let {
                lastHr = it
                seenHr += it
                if (e.start < baselineUntil) baselineHr += it
            }
            val base = median(baselineHr.ifEmpty { seenHr })
            val hr = e.hr ?: lastHr
            val move = ln(1 + 100 * e.move)
            val dHr = if (hr != null && base != null) hr - base else 0.0
            val sd = e.hrSd ?: 0.0
            out += doubleArrayOf(move, 0.0, dHr, 0.0, sd, 0.0)
            // Rolling means over the last 3 epochs (1.5 minutes).
            val from = max(0, i - 2)
            for (k in intArrayOf(0, 2, 4)) out[i][k + 1] = (from..i).map { out[it][k] }.average()
        }
        return out
    }
}

/** Starter rules used until there are enough labelled nights to train on. */
object Heuristic {
    fun score(features: List<DoubleArray>, i: Int): Double {
        val typicalMove = median(features.subList(0, i + 1).map { it[0] }) ?: 0.0
        val f = features[i]
        val stirring = f[1] >= max(typicalMove + 0.7, 1.0)  // moving roughly twice as much as usual
        val heartUp = f[3] >= 5.0 && f[5] >= 1.5            // heart rate up and less steady
        return when {
            stirring && heartUp -> 0.9
            stirring || heartUp -> 0.7
            else -> 0.1
        }
    }
}

/** Logistic regression on standardised features: chance that "now is a good moment to wake". */
data class Model(
    val version: Int,
    val means: List<Double>,
    val sds: List<Double>,
    val weights: List<Double>,
    val bias: Double,
    val threshold: Double,
    val minConsecutive: Int,
    val wakeStages: Set<Stage>,
    val trainedNights: Int,
    val accuracy: Double?, // agreement with Samsung's stages on the newest night, before it was trained on
) {
    fun prob(x: DoubleArray): Double {
        var z = bias
        for (k in x.indices) z += weights[k] * (x[k] - means[k]) / sds[k]
        return sigmoid(z)
    }
}

/** Runs live on the watch: feed it each epoch, it says when to ring. */
class WakeDecider(private val model: Model?, private val baselineUntil: Long) {
    private val epochs = ArrayList<Epoch>()
    private var streak = 0

    /** Returns this epoch's score and whether to ring now. */
    fun add(e: Epoch, inWindow: Boolean): Pair<Double, Boolean> {
        epochs += e
        val features = Features.compute(epochs, baselineUntil)
        val p = model?.prob(features.last()) ?: Heuristic.score(features, features.lastIndex)
        streak = if (p >= (model?.threshold ?: 0.6)) streak + 1 else 0
        return p to (inWindow && streak >= (model?.minConsecutive ?: 2))
    }
}

/** A recorded night plus Samsung's stage for each epoch. */
data class LabelledNight(val record: NightRecord, val labels: List<Stage>, val wakeStage: Stage?)

object Trainer {
    private val BASE_TARGET = setOf(Stage.AWAKE, Stage.LIGHT)

    /**
     * Which stages count as "good to wake". Starts as light sleep (or briefly awake); after enough
     * grogginess scores, REM is added if waking from REM leaves this person clearly less groggy.
     */
    fun wakeStages(nights: List<LabelledNight>): Set<Stage> {
        val rated = nights.filter { it.record.grogginess != null && it.wakeStage != null && it.record.trigger != "test" }
        if (rated.size < 6) return BASE_TARGET
        val avg = rated.groupBy { it.wakeStage!! }.mapValues { (_, n) -> n.map { it.record.grogginess!! }.average() }
        val rem = avg[Stage.REM] ?: return BASE_TARGET
        val light = avg[Stage.LIGHT] ?: avg[Stage.AWAKE] ?: return BASE_TARGET
        val remNights = rated.count { it.wakeStage == Stage.REM }
        return if (remNights >= 2 && rem <= light - 10) BASE_TARGET + Stage.REM else BASE_TARGET
    }

    fun samples(night: LabelledNight, target: Set<Stage>): List<Pair<DoubleArray, Int>> {
        val features = Features.compute(night.record.epochs, night.record.windowStart)
        return features.indices.mapNotNull { i ->
            val stage = night.labels.getOrNull(i) ?: Stage.UNKNOWN
            if (stage == Stage.UNKNOWN) null else features[i] to (if (stage in target) 1 else 0)
        }
    }

    /** Trains on every labelled night. Returns null while there isn't enough data of both kinds. */
    fun train(nights: List<LabelledNight>, previous: Model?): Model? {
        val usable = nights.filter { it.record.trigger != "test" }.sortedBy { it.record.monitorStart }
        if (usable.size < 2) return null // one night is too little to trust over the starter rules
        val target = wakeStages(usable)
        val data = usable.flatMap { samples(it, target) }
        if (data.size < 40 || data.none { it.second == 1 } || data.none { it.second == 0 }) return null

        // How well did we do on the newest night, before learning from it?
        val newest = usable.last()
        val newestSamples = samples(newest, target)
        val accuracy = when {
            newestSamples.isEmpty() -> previous?.accuracy
            previous != null -> accuracy(newestSamples) { previous.prob(it) >= previous.threshold }
            usable.size >= 2 -> fit(usable.dropLast(1).flatMap { samples(it, target) }, target, 0, 0)
                ?.let { m -> accuracy(newestSamples) { m.prob(it) >= m.threshold } }
            else -> null
        }
        return fit(data, target, (previous?.version ?: 0) + 1, usable.size)?.copy(accuracy = accuracy)
    }

    fun accuracy(samples: List<Pair<DoubleArray, Int>>, predict: (DoubleArray) -> Boolean): Double =
        samples.count { (x, y) -> predict(x) == (y == 1) }.toDouble() / samples.size

    private fun fit(data: List<Pair<DoubleArray, Int>>, target: Set<Stage>, version: Int, nights: Int): Model? {
        if (data.isEmpty() || data.none { it.second == 1 } || data.none { it.second == 0 }) return null
        val d = data[0].first.size
        val n = data.size
        val means = (0 until d).map { k -> data.sumOf { it.first[k] } / n }
        val sds = (0 until d).map { k -> sqrt(data.sumOf { (it.first[k] - means[k]).let { v -> v * v } } / n).coerceAtLeast(1e-6) }
        val x = data.map { (f, _) -> DoubleArray(d) { k -> (f[k] - means[k]) / sds[k] } }
        val y = data.map { it.second }
        val positives = y.count { it == 1 }.toDouble()
        val weightPos = n / (2 * positives)          // balance the classes
        val weightNeg = n / (2 * (n - positives))
        val w = DoubleArray(d)
        var b = 0.0
        repeat(1500) {
            val gw = DoubleArray(d)
            var gb = 0.0
            for (i in 0 until n) {
                var z = b
                for (k in 0 until d) z += w[k] * x[i][k]
                val err = (sigmoid(z) - y[i]) * (if (y[i] == 1) weightPos else weightNeg)
                for (k in 0 until d) gw[k] += err * x[i][k]
                gb += err
            }
            for (k in 0 until d) w[k] -= 0.3 * (gw[k] / n + 0.01 * w[k])
            b -= 0.3 * gb / n
        }
        val model = Model(version, means, sds, w.toList(), b, 0.6, 2, target, nights, null)
        return model.copy(threshold = pickThreshold(data, model))
    }

    /** Threshold that favours being right when we ring (F0.5: precision counts double). */
    private fun pickThreshold(data: List<Pair<DoubleArray, Int>>, m: Model): Double {
        val probs = data.map { m.prob(it.first) to it.second }
        var best = 0.6
        var bestScore = -1.0
        var t = 0.5
        while (t <= 0.901) {
            val tp = probs.count { it.first >= t && it.second == 1 }.toDouble()
            val fp = probs.count { it.first >= t && it.second == 0 }.toDouble()
            val fn = probs.count { it.first < t && it.second == 1 }.toDouble()
            val precision = if (tp + fp > 0) tp / (tp + fp) else 0.0
            val recall = if (tp + fn > 0) tp / (tp + fn) else 0.0
            val f05 = if (precision + recall > 0) 1.25 * precision * recall / (0.25 * precision + recall) else 0.0
            if (f05 > bestScore + 1e-9) { bestScore = f05; best = t }
            t += 0.05
        }
        return (best * 100).toLong() / 100.0
    }
}

object WakeSchedule {
    /** Start of the next wake window that hasn't ended yet, or null if the alarm is off. */
    fun nextWindowStart(s: AlarmSettings, now: ZonedDateTime): ZonedDateTime? {
        if (!s.enabled || s.days == 0) return null
        for (d in 0L..7L) {
            val date = now.toLocalDate().plusDays(d)
            if (!s.isOn(date.dayOfWeek)) continue
            val start = date.atTime(s.startMinute / 60, s.startMinute % 60).atZone(now.zone)
            if (start.plusMinutes(s.windowMinutes.toLong()).isAfter(now)) return start
        }
        return null
    }
}

/** Averages of the grogginess scores, for the phone's charts. */
object Grogginess {
    fun byTrigger(nights: List<LabelledNight>): Map<String, Double> = nights
        .filter { it.record.grogginess != null && it.record.trigger != "test" }
        .groupBy { it.record.trigger }
        .mapValues { (_, n) -> n.map { it.record.grogginess!! }.average() }

    fun byStage(nights: List<LabelledNight>): Map<Stage, Double> = nights
        .filter { it.record.grogginess != null && it.wakeStage != null && it.record.trigger != "test" }
        .groupBy { it.wakeStage!! }
        .mapValues { (_, n) -> n.map { it.record.grogginess!! }.average() }
}

/** Stage at a moment, from Samsung's stage intervals (start, end, stage). */
fun stageAt(intervals: List<Triple<Long, Long, Stage>>, t: Long): Stage =
    intervals.firstOrNull { t >= it.first && t < it.second }?.third ?: Stage.UNKNOWN

fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))

fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/** Movement for one epoch from raw accelerometer magnitudes (m/s²). */
fun movementOf(magnitudes: DoubleArray, count: Int): Double =
    if (count == 0) 0.0 else (0 until count).sumOf { abs(magnitudes[it] - 9.80665) } / count
