package com.crsmthw.hingegate.domain

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RollPlannerTest {

    private fun input(chance: Int = 35, guarantee: Int = 4, streak: Int = 0, force: Boolean = false) =
        TriggerInput(chance, guarantee, streak, force)

    /** A pending roll with the given outcome, made for [input]'s odds. */
    private fun pending(hit: Boolean, reason: HitReason, roll: Int = 50, from: TriggerInput = input()) = PendingRoll(
        hit = hit,
        reason = reason,
        roll = roll,
        chancePercent = from.chancePercent,
        guaranteeAfter = from.guaranteeAfter,
        missStreak = from.missStreak,
        spec = if (hit) CrackSpecGenerator.generate(Random(roll.toLong())) else null,
    )

    @Test
    fun `a pre-roll is the trigger's decision, with a crack exactly when it hits`() {
        repeat(300) { seed ->
            val i = input(chance = 35, guarantee = 0, streak = 2)
            val p = RollPlanner.preRoll(i, Random(seed))
            val decided = CrackTrigger.decide(i, Random(seed))
            assertEquals(decided.hit, p.hit)
            assertEquals(decided.reason, p.reason)
            assertEquals(decided.roll, p.roll)
            assertEquals(p.hit, p.spec != null)
            assertEquals(35, p.chancePercent)
            assertEquals(0, p.guaranteeAfter)
            assertEquals(2, p.missStreak)
        }
    }

    @Test
    fun `pre-rolls keep the forced and guaranteed rules`() {
        val forced = RollPlanner.preRoll(input(chance = 5, force = true), Random(1))
        assertEquals(HitReason.FORCED, forced.reason)
        assertNotNull(forced.spec)
        val guaranteed = RollPlanner.preRoll(input(chance = 5, guarantee = 3, streak = 3), Random(1))
        assertEquals(HitReason.GUARANTEED, guaranteed.reason)
        assertNotNull(guaranteed.spec)
    }

    @Test
    fun `a pre-roll is deterministic for a seed`() {
        assertEquals(RollPlanner.preRoll(input(chance = 100), Random(9)), RollPlanner.preRoll(input(chance = 100), Random(9)))
    }

    @Test
    fun `current while the odds, the streak and the switch are the same`() {
        val i = input()
        val p = RollPlanner.preRoll(i, Random(3))
        assertTrue(RollPlanner.isCurrent(p, i))
        assertFalse(RollPlanner.isCurrent(null, i))
        assertFalse(RollPlanner.isCurrent(p, i.copy(chancePercent = 40)))
        assertFalse(RollPlanner.isCurrent(p, i.copy(guaranteeAfter = 2)))
        assertFalse(RollPlanner.isCurrent(p, i.copy(missStreak = 1)))
        assertFalse(RollPlanner.isCurrent(p, i.copy(forceNext = true)))
    }

    @Test
    fun `a forced pre-roll stops being current when the switch is turned off`() {
        val p = RollPlanner.preRoll(input(force = true), Random(3))
        assertTrue(RollPlanner.isCurrent(p, input(force = true)))
        assertFalse(RollPlanner.isCurrent(p, input(force = false)))
    }

    @Test
    fun `a malformed pending roll is never current`() {
        val broken = pending(hit = true, reason = HitReason.ROLLED).copy(spec = null)
        assertFalse(RollPlanner.isCurrent(broken, input()))
    }

    @Test
    fun `a pending hit is shown at the unfold with its own crack`() {
        val p = pending(hit = true, reason = HitReason.ROLLED, roll = 12)
        val outcome = RollPlanner.atUnfold(p, input(), Random(1))
        assertTrue(outcome.result.hit)
        assertEquals(HitReason.ROLLED, outcome.result.reason)
        assertEquals(12, outcome.result.roll)
        assertEquals(0, outcome.result.newMissStreak)
        assertEquals(p.spec, outcome.spec)
        assertTrue(outcome.preRolled)
    }

    @Test
    fun `a pending guaranteed hit stays guaranteed`() {
        val from = input(guarantee = 3, streak = 3)
        val p = pending(hit = true, reason = HitReason.GUARANTEED, roll = CrackTrigger.NOT_ROLLED, from = from)
        val outcome = RollPlanner.atUnfold(p, from, Random(1))
        assertEquals(HitReason.GUARANTEED, outcome.result.reason)
        assertEquals(0, outcome.result.newMissStreak)
    }

    @Test
    fun `a pending miss is a clean unfold that grows the streak`() {
        val from = input(streak = 2)
        val outcome = RollPlanner.atUnfold(pending(hit = false, reason = HitReason.MISSED, roll = 71, from = from), from, Random(1))
        assertFalse(outcome.result.hit)
        assertEquals(HitReason.MISSED, outcome.result.reason)
        assertEquals(71, outcome.result.roll)
        assertEquals(3, outcome.result.newMissStreak)
        assertNull(outcome.spec)
        assertTrue(outcome.preRolled)
    }

    @Test
    fun `crack on the next unfold still forces a hit over a pending miss`() {
        val p = pending(hit = false, reason = HitReason.MISSED, roll = 90)
        val outcome = RollPlanner.atUnfold(p, input(force = true), Random(1))
        assertTrue(outcome.result.hit)
        assertEquals(HitReason.FORCED, outcome.result.reason)
        assertEquals(CrackTrigger.NOT_ROLLED, outcome.result.roll)
        assertEquals(0, outcome.result.newMissStreak)
        assertNotNull(outcome.spec)
        assertFalse(outcome.preRolled)
    }

    @Test
    fun `without a pending roll the unfold rolls right there`() {
        repeat(100) { seed ->
            val i = input(chance = 35, guarantee = 0, streak = 1)
            val outcome = RollPlanner.atUnfold(null, i, Random(seed))
            val decided = CrackTrigger.decide(i, Random(seed))
            assertEquals(decided, outcome.result)
            assertEquals(decided.hit, outcome.spec != null)
            assertFalse(outcome.preRolled)
        }
    }

    @Test
    fun `a pending roll made for other odds is rolled again`() {
        val stale = pending(hit = true, reason = HitReason.ROLLED, from = input(chance = 100))
        val outcome = RollPlanner.atUnfold(stale, input(chance = 5, guarantee = 0), Random(4))
        assertFalse(outcome.preRolled)
        assertEquals(CrackTrigger.decide(input(chance = 5, guarantee = 0), Random(4)), outcome.result)
    }

    @Test
    fun `a forced pending roll whose switch was turned off is rolled again`() {
        val forced = RollPlanner.preRoll(input(force = true), Random(2))
        val outcome = RollPlanner.atUnfold(forced, input(force = false, guarantee = 0), Random(2))
        assertFalse(outcome.preRolled)
        assertTrue(outcome.result.reason == HitReason.ROLLED || outcome.result.reason == HitReason.MISSED)
    }

    @Test
    fun `fresh cracks differ`() {
        val random = Random(8)
        assertNotSame(RollPlanner.newSpec(random), RollPlanner.newSpec(random))
        assertTrue(RollPlanner.newSpec(Random(1)).seed != RollPlanner.newSpec(Random(2)).seed)
    }

    @Test
    fun `pending rolls round trip as json`() {
        val hit = RollPlanner.preRoll(input(chance = 100), Random(6))
        val miss = pending(hit = false, reason = HitReason.MISSED)
        assertEquals(hit, Json.decodeFromString(PendingRoll.serializer(), Json.encodeToString(PendingRoll.serializer(), hit)))
        assertEquals(miss, Json.decodeFromString(PendingRoll.serializer(), Json.encodeToString(PendingRoll.serializer(), miss)))
    }

    @Test
    fun `the log shows the streak including a miss, and the streak that earned a hit`() {
        assertEquals(3, RollPlanner.logStreak(TriggerResult(false, HitReason.MISSED, 80, 3), streakBefore = 2))
        assertEquals(4, RollPlanner.logStreak(TriggerResult(true, HitReason.GUARANTEED, CrackTrigger.NOT_ROLLED, 0), streakBefore = 4))
        assertEquals(2, RollPlanner.logStreak(TriggerResult(true, HitReason.ROLLED, 10, 0), streakBefore = 2))
        assertEquals(1, RollPlanner.logStreak(TriggerResult(true, HitReason.FORCED, CrackTrigger.NOT_ROLLED, 0), streakBefore = 1))
    }
}
