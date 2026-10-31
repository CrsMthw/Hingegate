package com.crsmthw.hingegate.domain

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * The decision for the NEXT unfold, made while the phone is folded (docs/SWAP-MODE.md §2), so the Real wallpaper
 * mode can stage the inner screen's wallpaper before it is seen. Persisted: a process restart while folded keeps
 * the same decision.
 *
 * [chancePercent], [guaranteeAfter] and [missStreak] are the odds it was rolled with; a roll made for other odds
 * is stale and is rolled again. [spec] is the crack to show, present exactly when [hit].
 */
@Serializable
data class PendingRoll(
    val hit: Boolean,
    val reason: HitReason,
    /** 0..99, or [CrackTrigger.NOT_ROLLED]. */
    val roll: Int,
    val chancePercent: Int,
    val guaranteeAfter: Int,
    val missStreak: Int,
    val spec: CrackSpec?,
)

/** What an unfold decided: the trigger result (for the streak and the log) and the crack to show, if any. */
data class UnfoldOutcome(
    val result: TriggerResult,
    val spec: CrackSpec?,
    /** True when the pending roll made at the fold was used as it was. */
    val preRolled: Boolean,
)

/**
 * Pre-rolling (docs/SWAP-MODE.md §2), pure. The fold rolls the next unfold with [preRoll]; the unfold consumes it
 * with [atUnfold]. The miss streak and the log are still committed at the unfold, so for the overlay and live
 * wallpaper modes nothing changes but WHEN the dice are thrown.
 */
object RollPlanner {

    /** Rolls the next unfold now. A hit carries its crack, generated from the same random source. */
    fun preRoll(input: TriggerInput, random: Random): PendingRoll {
        val result = CrackTrigger.decide(input, random)
        return PendingRoll(
            hit = result.hit,
            reason = result.reason,
            roll = result.roll,
            chancePercent = input.chancePercent,
            guaranteeAfter = input.guaranteeAfter,
            missStreak = input.missStreak,
            spec = if (result.hit) newSpec(random) else null,
        )
    }

    /**
     * Whether [pending] is still the right decision for [input] while folded: rolled for the same odds and streak,
     * FORCED exactly when "crack on the next unfold" is on. Anything else is rolled again (§2: recomputed when the
     * chance, the guarantee or the switch changes while folded).
     */
    fun isCurrent(pending: PendingRoll?, input: TriggerInput): Boolean =
        pending != null &&
            pending.matchesOdds(input) &&
            (pending.reason == HitReason.FORCED) == input.forceNext &&
            pending.isWellFormed()

    /**
     * The unfold's outcome. A usable pending roll is used as it was; "crack on the next unfold" still forces a
     * hit (FORCED, a fresh crack) over a pending miss; with no usable pending roll the unfold rolls right here,
     * as before pre-rolling existed. A pending FORCED roll whose switch was turned off is not usable.
     */
    fun atUnfold(pending: PendingRoll?, input: TriggerInput, random: Random): UnfoldOutcome {
        val usable = pending?.takeIf {
            it.matchesOdds(input) && it.isWellFormed() && (it.reason != HitReason.FORCED || input.forceNext)
        }
        if (usable == null) {
            val result = CrackTrigger.decide(input, random)
            return UnfoldOutcome(result, if (result.hit) newSpec(random) else null, preRolled = false)
        }
        if (usable.hit) {
            return UnfoldOutcome(TriggerResult(true, usable.reason, usable.roll, 0), usable.spec, preRolled = true)
        }
        if (input.forceNext) {
            val forced = TriggerResult(true, HitReason.FORCED, CrackTrigger.NOT_ROLLED, 0)
            return UnfoldOutcome(forced, newSpec(random), preRolled = false)
        }
        val missed = TriggerResult(false, HitReason.MISSED, usable.roll, input.missStreak + 1)
        return UnfoldOutcome(missed, null, preRolled = true)
    }

    /**
     * The streak an unfold's log line shows (docs/DESIGN.md §18.5): for a miss, the clean unfolds in a row
     * INCLUDING this one ([TriggerResult.newMissStreak]); for any hit, the streak before it ([streakBefore]), which
     * is what earned a guaranteed hit.
     */
    fun logStreak(result: TriggerResult, streakBefore: Int): Int =
        if (result.reason == HitReason.MISSED) result.newMissStreak else streakBefore

    /** A fresh crack: its own seeded Random, so the spec is reproducible from its seed. */
    fun newSpec(random: Random): CrackSpec = CrackSpecGenerator.generate(Random(random.nextLong()))

    private fun PendingRoll.matchesOdds(input: TriggerInput): Boolean =
        chancePercent == input.chancePercent && guaranteeAfter == input.guaranteeAfter && missStreak == input.missStreak

    private fun PendingRoll.isWellFormed(): Boolean = hit == (spec != null)
}
