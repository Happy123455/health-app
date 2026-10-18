package com.happypipaliya.sleepcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestionTest {
    private val h = 3_600_000L
    private fun meal(
        name: String, t: Long = 0, carbs: Double = 60.0, protein: Double = 15.0, fat: Double = 12.0, fiber: Double = 8.0,
        kcal: Double = 450.0, iron: Double = 1.0, vitC: Double = 0.0, calcium: Double = 100.0, vitA: Double = 0.0, words: List<String> = emptyList(),
    ) = MealDigest(t, name, listOf(name) + words, carbs, protein, fat, fiber, kcal,
        mapOf("iron_mg" to iron, "vitamin_c_mg" to vitC, "calcium_mg" to calcium, "vitamin_a_mcg" to vitA))

    @Test fun stomachTimesArePlausible() {
        val chai = meal("Masala chai", carbs = 15.0, protein = 3.0, fat = 4.0, fiber = 0.0, kcal = 110.0)
        val thali = meal("Gujarati thali", carbs = 110.0, protein = 25.0, fat = 30.0, fiber = 12.0, kcal = 850.0)
        assertTrue(Digestion.stomachHours(chai) < 1.2)
        assertTrue(Digestion.stomachHours(thali) in 3.0..5.0)
        assertTrue(Digestion.absorbHours(thali) > Digestion.stomachHours(thali))
    }

    @Test fun phasesAdvance() {
        val m = meal("Lunch")
        assertEquals(DigestPhase.STOMACH, Digestion.state(m, h).phase)
        assertEquals(DigestPhase.ABSORBING, Digestion.state(m, (Digestion.stomachHours(m) * h).toLong() + 60_000).phase)
        assertEquals(DigestPhase.DONE, Digestion.state(m, 10 * h).phase)
    }

    @Test fun chaiWithIronMealWarns() {
        val lunch = meal("Palak dal with rice", iron = 5.0)
        val chai = meal("Chai", t = 20 * 60_000L, carbs = 15.0, kcal = 110.0)
        val p = Digestion.pairings(lunch, listOf(chai))
        assertTrue(p.any { it.kind == PairKind.WARN && "iron" in it.text })
        // "Steamed rice" is not tea.
        assertTrue(Digestion.pairings(meal("Dal", iron = 5.0, words = listOf("Steamed white rice")), emptyList()).none { it.kind == PairKind.WARN })
        assertTrue(Digestion.pairings(meal("Poha", iron = 5.0, words = listOf("Masala chai")), emptyList()).any { it.kind == PairKind.WARN })
        // Two hours later is fine.
        assertTrue(Digestion.pairings(lunch, listOf(chai.copy(time = 2 * h))).none { it.kind == PairKind.WARN })
    }

    @Test fun lemonAndProteinComboAreRecognised() {
        val p = Digestion.pairings(meal("Dal rice", iron = 4.0, words = listOf("lemon wedge")), emptyList())
        assertTrue(p.any { it.title == "Iron + vitamin C" })
        assertTrue(p.any { it.title == "Complete protein" })
        val q = Digestion.pairings(meal("Rajma", iron = 4.0), emptyList())
        assertTrue(q.any { it.title == "Add lemon for iron" })
    }

    @Test fun fatSolubleNeedsFat() {
        assertTrue(Digestion.pairings(meal("Carrot salad", fat = 1.0, vitA = 600.0), emptyList()).any { it.title == "Add a little fat" })
    }

    @Test fun walkAndBedtimeTips() {
        val dinnerAt = 21 * 60 // 21:00
        val dinner = meal("Dinner", t = 0, carbs = 90.0, fat = 30.0)
        val r = Digestion.report(20 * 60_000L, listOf(dinner), usualBedMinutes = 23 * 60, minutesOfDay = dinnerAt + 20)
        assertTrue(r.now.any { it.title.startsWith("Walk") })
        assertTrue(r.now.first().kind == PairKind.WARN)
        val early = Digestion.report(0, emptyList(), usualBedMinutes = 23 * 60 + 30, minutesOfDay = 18 * 60)
        assertEquals("Dinner by 20:30", early.now.single().title)
        // Already had dinner: no "dinner by" nudge, and no workout advice late in the evening.
        val fed = Digestion.report(30 * 60_000L, listOf(dinner), usualBedMinutes = 2 * 60, minutesOfDay = 21 * 60 + 30)
        assertTrue(fed.now.none { it.title.startsWith("Dinner by") || it.title.startsWith("Hard workout") })
    }

    @Test fun usualBedtimeHandlesMidnight() {
        val mins = listOf(23 * 60 + 40, 10, 23 * 60 + 50)
        val sleeps = mins.mapIndexed { i, _ -> SleepSession(i * 1000L, i * 1000L + 7 * h) }
        assertEquals(23 * 60 + 50, Digestion.usualBedtime(sleeps) { t -> mins[(t / 1000).toInt()] })
        assertNull(Digestion.usualBedtime(sleeps.take(1)) { 0 })
    }

    @Test fun fullnessRisesThenEmpties() {
        val m = meal("Thali", kcal = 700.0, fat = 25.0)
        assertEquals(0.0, Digestion.fullness(listOf(m), -1), 0.0)
        assertTrue(Digestion.fullness(listOf(m), 5 * 60_000L) > 0.9)
        val mid = Digestion.fullness(listOf(m), (Digestion.stomachHours(m) * h / 2).toLong())
        assertTrue("mid $mid", mid in 0.3..0.7)
        assertEquals(0.0, Digestion.fullness(listOf(m), Digestion.stomachEmptyAt(m) + 1), 0.0)
    }
}
