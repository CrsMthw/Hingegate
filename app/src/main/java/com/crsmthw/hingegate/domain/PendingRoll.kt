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
    /** True when the crack shown is the pre-chosen next crack: a new one must be chosen for the next hit. */
    val consumedNext: Boolean = false,
)

/**
 * Pre-rolling (docs/SWAP-MODE.md §2), pure. The fold rolls the next unfold with [preRoll]; the unfold consumes it
 * with [atUnfold]. The miss streak and the log are still committed at the unfold, so for the overlay and live
 * wallpaper modes nothing changes but WHEN the dice are thrown.
 *
 * The crack a hit shows is chosen even earlier (docs/SWAP-MODE.md §10.28): the controller keeps a persisted
 * "next crack", and every hit decided for a fold or an unfold shows it; the unfold that shows it chooses the next
 * one ([nextSpecAfter]). The odds are untouched — whether an unfold cracks is still [CrackTrigger.decide] on the
 * same random source — only the crack's shape is known before the fold, so the Real wallpaper mode can render it
 * while the inner screen is in use. "Show crack now" keeps making a fresh crack of its own.
 */
object RollPlanner {

    /** Rolls the next unfold now. A hit carries [nextSpec], the pre-chosen crack. */
    fun preRoll(input: TriggerInput, random: Random, nextSpec: CrackSpec): PendingRoll {
        val result = CrackTrigger.decide(input, random)
        return PendingRoll(
            hit = result.hit,
            reason = result.reason,
            roll = result.roll,
            chancePercent = input.chancePercent,
            guaranteeAfter = input.guaranteeAfter,
            missStreak = input.missStreak,
            spec = if (result.hit) nextSpec else null,
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
     * hit (FORCED) over a pending miss; with no usable pending roll the unfold rolls right here, as before
     * pre-rolling existed. A pending FORCED roll whose switch was turned off is not usable. Every hit decided
     * here shows [nextSpec] (a usable pending hit shows its own crack, which is [nextSpec] unless it was rolled by
     * an older build).
     */
    fun atUnfold(pending: PendingRoll?, input: TriggerInput, random: Random, nextSpec: CrackSpec): UnfoldOutcome {
        val usable = pending?.takeIf {
            it.matchesOdds(input) && it.isWellFormed() && (it.reason != HitReason.FORCED || input.forceNext)
        }
        val outcome = when {
            usable == null -> {
                val result = CrackTrigger.decide(input, random)
                UnfoldOutcome(result, if (result.hit) nextSpec else null, preRolled = false)
            }
            usable.hit -> UnfoldOutcome(TriggerResult(true, usable.reason, usable.roll, 0), usable.spec, preRolled = true)
            input.forceNext -> {
                val forced = TriggerResult(true, HitReason.FORCED, CrackTrigger.NOT_ROLLED, 0)
                UnfoldOutcome(forced, nextSpec, preRolled = false)
            }
            else -> UnfoldOutcome(TriggerResult(false, HitReason.MISSED, usable.roll, input.missStreak + 1), null, preRolled = true)
        }
        return outcome.copy(consumedNext = outcome.spec != null && outcome.spec.seed == nextSpec.seed)
    }

    /** The pre-chosen crack after an unfold: a new one once [outcome] showed it, else the same one. */
    fun nextSpecAfter(outcome: UnfoldOutcome, nextSpec: CrackSpec, random: Random): CrackSpec =
        if (outcome.consumedNext) newSpec(random) else nextSpec

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
