package com.happypipaliya.sleepcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EnergyTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val micros = listOf(MicroSpec("iron_mg", "Iron", 8.0, fast = false), MicroSpec("vitamin_c_mg", "Vitamin C", 90.0, fast = true))

    /** Nights ending at 06:30 on each of the 4 days up to the given day, all [minutes] long unless [last] is given. */
    private fun nights(day: Int, minutes: Long = 450, last: Long = minutes) = (day - 3..day).map { d ->
        val end = at(d, 6, 30)
        SleepSession(end - (if (d == day) last else minutes) * 60_000, end)
    }

    private fun inputs(
        now: Long, foods: List<FoodIntake> = emptyList(), ex: List<Exercise> = emptyList(),
        sleeps: List<SleepSession> = nights(18), activities: List<LoggedActivity> = emptyList(),
    ) = EnergyInputs(
        now = now, zone = zone, weightKg = 55.0, age = 23, proteinTarget = 66.0, micros = micros,
        foods = foods, exercises = ex, water = null, sleeps = sleeps, restingHr = 56, restingHrBaseline = 55.0,
        activities = activities,
    )

    private fun meal(t: Long, carbs: Double, protein: Double = 20.0) =
        FoodIntake(t, carbs, protein, 6.0, mapOf("iron_mg" to 4.0, "vitamin_c_mg" to 30.0))

    private val usualDays = listOf(16, 17).flatMap { d -> listOf(meal(at(d, 8), 70.0), meal(at(d, 13), 90.0), meal(at(d, 20), 80.0)) }

    @Test fun fastingOvernightDrainsQuickFuelAndBreakfastRefillsIt() {
        val beforeBreakfast = EnergyModel.compute(inputs(at(18, 7, 30), usualDays))
        val afterBreakfast = EnergyModel.compute(inputs(at(18, 10, 30), usualDays + meal(at(18, 8), 70.0)))
        val liverBefore = beforeBreakfast.features[0]
        val liverAfter = afterBreakfast.features[0]
        assertTrue("liver after night fast $liverBefore", liverBefore < 0.7)
        assertTrue("breakfast should refill: $liverBefore -> $liverAfter", liverAfter > liverBefore + 0.1)
        assertTrue(afterBreakfast.energy > beforeBreakfast.energy)
    }

    @Test fun skippingMealsEmptiesQuickFuelAndSaysEat() {
        // Breakfast logged, lunch skipped: by 4 pm the store is nearly empty.
        val s = EnergyModel.compute(inputs(at(18, 16), usualDays + meal(at(18, 8), 70.0)))
        assertTrue("liver ${s.features[0]}", s.features[0] < 0.4)
        assertTrue(s.need, s.need.startsWith("Only 1 meal logged") || s.need.startsWith("Quick fuel is low"))
    }

    @Test fun unloggedMealsAreFlaggedInsteadOfBlamed() {
        val s = EnergyModel.compute(inputs(at(18, 16), usualDays + FoodIntake(at(18, 12), 15.0, 1.0, 2.0, emptyMap())))
        assertTrue(s.missingMeals)
        assertEquals(0, s.mealsLoggedToday) // a small snack isn't counted as a meal
        assertTrue(s.need, s.need.startsWith("Only 0 meals logged today"))
    }

    @Test fun hardWorkoutDrainsMuscleFuel() {
        val workout = Exercise(at(18, 17), at(18, 18), kcal = 500.0, avgHr = 165)
        val foods = usualDays + meal(at(18, 8), 70.0) + meal(at(18, 13), 90.0)
        val rested = EnergyModel.compute(inputs(at(18, 18, 10), foods))
        val trained = EnergyModel.compute(inputs(at(18, 18, 10), foods, listOf(workout)))
        assertTrue("muscle ${rested.features[1]} -> ${trained.features[1]}", trained.features[1] < rested.features[1] - 0.2)
    }

    @Test fun proteinTracksTodayAgainstTarget() {
        val s = EnergyModel.compute(inputs(at(18, 21), usualDays + meal(at(18, 8), 70.0, 5.0)))
        assertTrue("protein ${s.features[2]}", s.features[2] < 0.3)
        assertTrue(s.gauges.first { it.key == "protein" }.detail.startsWith("5 of 66 g"))
    }

    @Test fun lowNutrientsAreListed() {
        // Iron: 3 meals x 4 mg = 12 mg/day vs 8 target -> fine. Vitamin C on a low-C day: 1 meal x 10 mg -> low.
        val lowC = usualDays.map { it.copy(micros = mapOf("iron_mg" to 4.0, "vitamin_c_mg" to 10.0)) }
        val s = EnergyModel.compute(inputs(at(18, 7), lowC))
        assertEquals("Vitamin C", s.lowNutrients.firstOrNull()?.first?.label)
        assertTrue(s.lowNutrients.none { it.first.key == "iron_mg" })
    }

    @Test fun checkInsPersonaliseTheScore() {
        val base = listOf(0.5, 0.6, 0.7, 0.9, 0.9, 0.75)
        assertNull(Calibration.fit(List(4) { CheckIn(it.toLong(), 50, base) }))
        // This person feels great even when quick fuel is low: the fitted model should rate that higher than the default.
        val checkIns = (0 until 30).map { i ->
            val liver = (i % 10) / 10.0
            CheckIn(i.toLong(), 80, listOf(liver, 0.6, 0.7, 0.9, 0.9, 0.75))
        }
        val c = Calibration.fit(checkIns)
        assertNotNull(c)
        val f = listOf(0.1, 0.6, 0.7, 0.9, 0.9, 0.75)
        assertTrue(c!!.predict(f) > EnergyModel.defaultEnergy(f))
    }

    private val fedDay = usualDays + meal(at(18, 8), 70.0) + meal(at(18, 13), 90.0)

    @Test fun sleepDebtLowersSleepScore() {
        val rested = EnergyModel.compute(inputs(at(18, 15), fedDay, sleeps = nights(18, 480)))
        val shortWeek = EnergyModel.compute(inputs(at(18, 15), fedDay, sleeps = nights(18, 330)))
        assertTrue(shortWeek.features[3] < rested.features[3] - 0.25)
        assertTrue(shortWeek.gauges.first { it.key == "sleep" }.detail.contains("short over 4 nights"))
        assertTrue(shortWeek.energy < rested.energy)
    }

    @Test fun napHelps() {
        val tired = nights(18, 330)
        val noNap = EnergyModel.compute(inputs(at(18, 16), fedDay, sleeps = tired))
        val nap = EnergyModel.compute(inputs(at(18, 16), fedDay, sleeps = tired + SleepSession(at(18, 14), at(18, 14, 25))))
        assertTrue("${noNap.features[3]} -> ${nap.features[3]}", nap.features[3] > noNap.features[3])
        assertTrue(nap.gauges.first { it.key == "sleep" }.detail.contains("nap 25m"))
    }

    @Test fun longStudyTiresAndMassageHelps() {
        val base = EnergyModel.compute(inputs(at(18, 15), fedDay))
        val study = EnergyModel.compute(inputs(at(18, 15), fedDay, activities = listOf(LoggedActivity("study", at(18, 11), 240))))
        val massage = EnergyModel.compute(inputs(at(18, 15), fedDay, activities = listOf(LoggedActivity("massage", at(18, 14), 20))))
        assertTrue(study.energy < base.energy - 0.03)
        assertTrue(massage.energy > base.energy)
        assertTrue(study.factors.any { it.first.startsWith("Study") && it.second < 0 })
        assertTrue(study.need, study.need.contains("break"))
    }

    @Test fun beingAwakeLongCountsAsTiredness() {
        val afternoon = EnergyModel.compute(inputs(at(18, 13), fedDay))
        val lateNight = EnergyModel.compute(inputs(at(18, 23, 30), fedDay + meal(at(18, 20), 80.0)))
        assertTrue(lateNight.features[7] > 0.5)
        assertEquals(0.0, afternoon.features[7], 1e-9)
    }

    @Test fun olderCheckInsWithFewerInputsStillTrain() {
        val old = List(6) { CheckIn(it.toLong(), 60, listOf(0.5, 0.6, 0.7, 0.9, 0.9, 0.75)) }
        assertNotNull(Calibration.fit(old))
    }
}
