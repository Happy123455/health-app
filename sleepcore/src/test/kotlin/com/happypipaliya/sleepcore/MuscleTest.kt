package com.happypipaliya.sleepcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MuscleTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val chestDay = TrainingSession("Mon - Chest & Triceps", at(18, 17), at(18, 17, 35), 185.0, 140)

    private fun report(now: Long, foods: List<FoodIntake> = emptyList(), sessions: List<TrainingSession> = listOf(chestDay), massage: List<LoggedActivity> = emptyList(), sleep: Double? = 0.9) =
        MuscleModel.report(now, sessions, foods, 66.0, 55.0, 23, sleep, massage, emptyMap(), at(18, 0))

    private fun food(t: Long, protein: Double) = FoodIntake(t, 60.0, protein, 5.0, emptyMap())

    @Test fun routineNamesMapToMuscles() {
        val m = MuscleModel.musclesFor("Mon - Chest & Triceps")
        assertEquals(1.0, m[Muscle.CHEST]!!, 0.0)
        assertEquals(1.0, m[Muscle.TRICEPS]!!, 0.0)
        assertTrue(MuscleModel.musclesFor("Wed - Legs").keys.containsAll(listOf(Muscle.QUADS, Muscle.GLUTES)))
        assertTrue(MuscleModel.musclesFor("Walking (auto)")[Muscle.CALVES]!! < 0.5)
        assertTrue(MuscleModel.musclesFor("Restart Full-Body").size == Muscle.entries.size)
    }

    @Test fun recoversOverTimeAndOtherMusclesStayReady() {
        val right = report(at(18, 18))
        val chest = right.states.first { it.muscle == Muscle.CHEST }
        val legs = right.states.first { it.muscle == Muscle.QUADS }
        assertTrue("chest right after ${chest.recovery}", chest.recovery < 0.2)
        assertEquals(1.0, legs.recovery, 0.0)
        val later = report(at(20, 19), (18..20).flatMap { d -> listOf(food(at(d, 9), 25.0), food(at(d, 14), 25.0), food(at(d, 20), 25.0)) })
        assertTrue(later.states.first { it.muscle == Muscle.CHEST }.recovery > 0.95)
    }

    @Test fun lowProteinSlowsRecovery() {
        val fed = report(at(19, 17), listOf(food(at(18, 19), 30.0), food(at(19, 9), 30.0), food(at(19, 13), 30.0)))
        val unfed = report(at(19, 17), listOf(food(at(19, 9), 5.0)))
        val a = fed.states.first { it.muscle == Muscle.CHEST }.recovery
        val b = unfed.states.first { it.muscle == Muscle.CHEST }.recovery
        assertTrue("fed $a vs unfed $b", a > b)
        assertTrue(unfed.tip, unfed.tip.contains("protein"))
    }

    @Test fun deskDayGapsSuggestGlutesAndBack() {
        val r = report(at(18, 18))
        assertTrue(r.focus.any { it.first == Muscle.GLUTES })
        assertTrue(r.focus.none { it.first == Muscle.CHEST })
    }

    @Test fun readyMusclesHaveNoReadyTime() {
        val r = report(at(25, 10))
        assertTrue(r.recovering.isEmpty())
        assertNull(r.states.first { it.muscle == Muscle.CHEST }.readyAt)
    }
}
