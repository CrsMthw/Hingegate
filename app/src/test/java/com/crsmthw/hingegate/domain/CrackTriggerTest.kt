package com.crsmthw.hingegate.domain

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrackTriggerTest {

    private fun input(chance: Int = 35, guarantee: Int = 4, streak: Int = 0, force: Boolean = false) =
        TriggerInput(chance, guarantee, streak, force)

    @Test
    fun `forced wins over everything and resets the streak`() {
        val r = CrackTrigger.decide(input(chance = 5, streak = 9, force = true), Random(1))
        assertTrue(r.hit)
        assertEquals(HitReason.FORCED, r.reason)
        assertEquals(CrackTrigger.NOT_ROLLED, r.roll)
        assertEquals(0, r.newMissStreak)
    }

    @Test
    fun `guaranteed once the streak reaches the limit`() {
        val r = CrackTrigger.decide(input(chance = 5, guarantee = 4, streak = 4), Random(1))
        assertTrue(r.hit)
        assertEquals(HitReason.GUARANTEED, r.reason)
        assertEquals(CrackTrigger.NOT_ROLLED, r.roll)
        assertEquals(0, r.newMissStreak)
    }

    @Test
    fun `no guarantee when it is off`() {
        repeat(50) { seed ->
            val r = CrackTrigger.decide(input(chance = 5, guarantee = 0, streak = 100), Random(seed))
            assertTrue(r.reason == HitReason.ROLLED || r.reason == HitReason.MISSED)
        }
    }

    @Test
    fun `below the limit it rolls`() {
        val r = CrackTrigger.decide(input(guarantee = 4, streak = 3), Random(1))
        assertTrue(r.roll in 0..99)
    }

    @Test
    fun `rolled hit and miss follow the roll`() {
        repeat(200) { seed ->
            val r = CrackTrigger.decide(input(chance = 35, guarantee = 0, streak = 2), Random(seed))
            if (r.roll < 35) {
                assertTrue(r.hit)
                assertEquals(HitReason.ROLLED, r.reason)
                assertEquals(0, r.newMissStreak)
            } else {
                assertFalse(r.hit)
                assertEquals(HitReason.MISSED, r.reason)
                assertEquals(3, r.newMissStreak)
            }
        }
    }

    @Test
    fun `chance 100 always hits`() {
        repeat(1_000) { seed ->
            assertTrue(CrackTrigger.decide(input(chance = 100, guarantee = 0), Random(seed)).hit)
        }
    }

    @Test
    fun `five percent over ten thousand seeded rolls is about five percent`() {
        val random = Random(2026)
        val hits = (0 until 10_000).count { CrackTrigger.decide(input(chance = 5, guarantee = 0), random).hit }
        assertTrue(hits in 420..580, "hits = $hits")
    }

    @Test
    fun `streak arithmetic over a run`() {
        val random = Random(5)
        var streak = 0
        repeat(100) {
            val r = CrackTrigger.decide(input(chance = 35, guarantee = 4, streak = streak), random)
            assertTrue(r.newMissStreak <= 4)
            if (r.hit) assertEquals(0, r.newMissStreak) else assertEquals(streak + 1, r.newMissStreak)
            streak = r.newMissStreak
        }
    }
}
