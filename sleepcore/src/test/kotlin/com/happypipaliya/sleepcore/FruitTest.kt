package com.happypipaliya.sleepcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FruitTest {
    private val targets = mapOf("vitamin_c_mg" to 90.0, "potassium_mg" to 3400.0, "fiber_g" to 33.0, "folate_mcg" to 400.0,
        "vitamin_a_mcg" to 900.0, "magnesium_mg" to 400.0, "vitamin_k_mcg" to 120.0, "iron_mg" to 11.0)

    @Test fun agmarknetNamesMap() {
        assertEquals("chikoo", FruitGuide.forCommodity("Chikoos(Sapota)")?.key)
        assertEquals("mosambi", FruitGuide.forCommodity("Mousambi(Sweet Lime)")?.key)
        assertEquals("apple", FruitGuide.forCommodity("Apple")?.key)
        assertEquals("sitafal", FruitGuide.forCommodity("Custard Apple (Sharifa)")?.key)
        assertEquals("muskmelon", FruitGuide.forCommodity("Karbuja(Musk Melon)")?.key)
        assertNull(FruitGuide.forCommodity("Pineapple"))
        assertNull(FruitGuide.forCommodity("Tomato"))
    }

    @Test fun seasonsMakeSense() {
        val oct = FruitGuide.inSeason(10).map { it.key }
        assertTrue(oct.containsAll(listOf("guava", "sitafal", "pomegranate", "banana")))
        assertTrue("mango" !in oct && "watermelon" !in oct)
        assertTrue("mango" in FruitGuide.inSeason(5).map { it.key })
        assertTrue(FruitGuide.arriving(10).any { it.key == "amla" })
    }

    @Test fun lowVitaminCFavoursGuavaOrAmla() {
        val picks = FruitGuide.suggest(11, targets, mapOf("vitamin_c_mg" to 0.8), emptyMap(), ironMealWithoutC = true)
        assertTrue(picks.first().fruit.key in setOf("guava", "amla"))
        assertTrue(picks.first().reason.contains("iron"))
        assertEquals(1, picks.count { it.reason.contains("iron from your last meal") })
    }

    @Test fun trainingFavoursBananaOverOrange() {
        val picks = FruitGuide.suggest(10, targets, emptyMap(), emptyMap(), trainedRecently = true).map { it.fruit.key }
        assertTrue(picks.indexOf("banana") < picks.indexOf("orange"))
    }

    @Test fun summerFavoursMelons() {
        assertTrue(FruitGuide.suggest(5, targets, emptyMap(), emptyMap()).take(3).any { it.fruit.key in setOf("watermelon", "muskmelon") })
    }
}
