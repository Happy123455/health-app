package com.happypipaliya.sleepcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class SleepCoreTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    /** A fake night: deep sleep (still, steady heart) then light sleep (stirring, heart up) for each stage block. */
    private fun night(seed: Int, blocks: List<Pair<Stage, Int>>, grogginess: Int? = null, wake: Stage? = null): LabelledNight {
        val rnd = Random(seed)
        val start = 1_790_000_000_000L + seed * 86_400_000L
        val epochs = ArrayList<Epoch>()
        val labels = ArrayList<Stage>()
        var t = start
        for ((stage, count) in blocks) repeat(count) {
            val light = stage == Stage.LIGHT || stage == Stage.AWAKE
            val move = if (light) 0.15 + rnd.nextDouble() * 0.4 else 0.005 + rnd.nextDouble() * 0.02
            val hr = if (light) 64.0 + rnd.nextDouble() * 6 else 55.0 + rnd.nextDouble() * 2
            val sd = if (light) 2.0 + rnd.nextDouble() * 2 else 0.4 + rnd.nextDouble() * 0.5
            epochs += Epoch(t, move, hr, sd)
            labels += stage
            t += EPOCH_MS
        }
        val windowStart = start + BASELINE_MINUTES * 60_000
        val record = NightRecord(start, windowStart, windowStart + 30 * 60_000, t, "smart", grogginess, 0, epochs, epochs.map { 0.0 })
        return LabelledNight(record, labels, wake)
    }

    private val pattern = listOf(Stage.DEEP to 30, Stage.LIGHT to 12, Stage.REM to 10, Stage.LIGHT to 14)

    @Test fun trainsAndSeparatesLightFromDeep() {
        val nights = (1..4).map { night(it, pattern) }
        val model = Trainer.train(nights, previous = null)
        assertNotNull(model)
        model!!
        val test = night(99, pattern)
        val acc = Trainer.accuracy(Trainer.samples(test, model.wakeStages)) { model.prob(it) >= model.threshold }
        assertTrue("accuracy was $acc", acc > 0.8)
        assertNotNull("prospective accuracy should be measured", model.accuracy)
    }

    @Test fun notEnoughDataGivesNoModel() {
        assertNull(Trainer.train(listOf(night(1, listOf(Stage.DEEP to 20))), previous = null))
    }

    @Test fun deciderRingsDuringLightSleepInsideTheWindow() {
        val model = Trainer.train((1..4).map { night(it, pattern) }, null)!!
        val n = night(50, pattern).record
        val decider = WakeDecider(model, n.windowStart)
        var rangAt: Int? = null
        n.epochs.forEachIndexed { i, e ->
            val (_, ring) = decider.add(e, inWindow = e.start >= n.windowStart)
            if (ring && rangAt == null) rangAt = i
        }
        assertNotNull(rangAt)
        // First light block starts at epoch 30, after the 20-minute (40 epoch) baseline the window opens.
        assertTrue("rang at ${rangAt}", rangAt!! >= 40)
    }

    @Test fun starterRulesNoticeStirring() {
        val n = night(7, pattern).record
        val decider = WakeDecider(null, n.windowStart)
        val rings = n.epochs.map { decider.add(it, inWindow = true).second }
        assertFalse("shouldn't ring during the first deep-sleep epochs", rings.take(30).any { it })
        assertTrue("should ring once stirring starts", rings.drop(30).any { it })
    }

    @Test fun remIsAddedWhenItLeavesYouLessGroggy() {
        val rated = (1..4).map { night(it, pattern, grogginess = 60, wake = Stage.LIGHT) } +
            (5..7).map { night(it, pattern, grogginess = 25, wake = Stage.REM) }
        assertEquals(setOf(Stage.AWAKE, Stage.LIGHT, Stage.REM), Trainer.wakeStages(rated))
        assertEquals(setOf(Stage.AWAKE, Stage.LIGHT), Trainer.wakeStages(rated.take(5)))
    }

    @Test fun nextWindowSkipsDaysThatAreOff() {
        val weekdays = AlarmSettings(enabled = true, startMinute = 6 * 60 + 30, windowMinutes = 30, days = 0b0011111)
        val fridayNight = ZonedDateTime.of(2026, 10, 2, 23, 0, 0, 0, zone) // Friday
        assertEquals(ZonedDateTime.of(2026, 10, 5, 6, 30, 0, 0, zone), WakeSchedule.nextWindowStart(weekdays, fridayNight))
        val insideWindow = ZonedDateTime.of(2026, 10, 2, 6, 45, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 10, 2, 6, 30, 0, 0, zone), WakeSchedule.nextWindowStart(weekdays, insideWindow))
        assertNull(WakeSchedule.nextWindowStart(weekdays.copy(enabled = false), fridayNight))
    }

    @Test fun codecRoundTrips() {
        val s = AlarmSettings(true, 400, 45, 0b1010101)
        assertEquals(s, Codec.decodeSettings(Codec.encode(s)))
        val model = Trainer.train((1..4).map { night(it, pattern) }, null)!!
        assertEquals(model, Codec.decodeModel(Codec.encode(model)))
        val r = night(3, pattern, grogginess = 40).record.copy(epochs = listOf(Epoch(1L, 0.1, null, null), Epoch(2L, 0.2, 60.0, 1.5)), scores = listOf(0.1, 0.9))
        assertEquals(r, Codec.decodeNight(Codec.encode(r)))
    }
}
