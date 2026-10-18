package com.happypipaliya.sleepcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MealPlanTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun food(t: Long, carbs: Double = 70.0) = FoodIntake(t, carbs, 15.0, 6.0, emptyMap())

    private val history = (8..17).flatMap { d -> listOf(food(at(d, 8, 30 + d % 3 * 10)), food(at(d, 14, d % 4 * 10)), food(at(d, 21, 0), 90.0)) } +
        listOf(food(at(12, 17, 0), 40.0)) // one-off snack

    @Test fun learnsUsualTimes() {
        val s = MealPlan.slots(history, zone)
        assertEquals(3, s.size)
        assertTrue(s[0].minute in 8 * 60 + 30..8 * 60 + 55)
        assertTrue(s[1].minute in 14 * 60..14 * 60 + 30)
        assertEquals(90.0, s[2].carbs, 0.0)
    }

    @Test fun upcomingSkipsMealsAlreadyEaten() {
        val s = MealPlan.slots(history, zone)
        // 21:30 on the 18th, dinner already eaten at 20:50: next are breakfast, lunch, dinner tomorrow.
        val next = MealPlan.upcoming(s, listOf(food(at(18, 20, 50))), at(18, 21, 30), zone)
        assertEquals(3, next.size)
        assertTrue(next.all { it.time > at(18, 21, 30) && it.time <= at(19, 21, 30) })
        // 20:15, dinner eaten early at 19:30: tonight's 21:00 dinner is skipped.
        val early = MealPlan.upcoming(s, listOf(food(at(18, 19, 30))), at(18, 20, 15), zone)
        assertTrue(early.none { it.time == at(18, 21, 0) })
    }

    @Test fun forecastDipsOvernightAndRisesAfterBreakfast() {
        val now = at(18, 21, 30)
        val foods = history.filter { it.time < now } + food(at(18, 20, 50))
        val plan = MealPlan.upcoming(MealPlan.slots(foods, zone), foods, now, zone)
        val inp = EnergyInputs(now, zone, 55.0, 23, 66.0, emptyList(), foods, emptyList(), null, emptyList(), null, null, plannedFoods = plan)
        val st = EnergyModel.compute(inp)
        assertTrue(st.forecast.size > 140)
        val dawn = st.forecast.first { it.time >= at(19, 8, 0) }.liver
        val midMorning = st.forecast.first { it.time >= at(19, 11, 0) }.liver
        assertTrue("dawn $dawn vs after breakfast $midMorning", midMorning > dawn)
        assertEquals(st.timeline.last().liver, st.forecast.first().liver, 1e-9)
    }

    @Test fun workoutsRepeatWeekly() {
        val w = Exercise(at(11, 18), at(11, 18, 40), 200.0, 140)
        val r = MealPlan.repeatWorkouts(listOf(w), at(18, 9))
        assertEquals(at(18, 18), r.single().start)
    }
}
