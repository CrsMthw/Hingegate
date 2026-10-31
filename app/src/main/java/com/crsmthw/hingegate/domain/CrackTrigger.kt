package com.crsmthw.hingegate.domain

import kotlin.random.Random

data class TriggerInput(
    val chancePercent: Int,
    /** 0 = off; otherwise a crack is guaranteed once this many unfolds in a row stayed clean. */
    val guaranteeAfter: Int,
    val missStreak: Int,
    val forceNext: Boolean,
)

enum class HitReason { FORCED, GUARANTEED, ROLLED, MISSED }

data class TriggerResult(
    val hit: Boolean,
    val reason: HitReason,
    /** 0..99, or -1 when no roll was needed. */
    val roll: Int,
    val newMissStreak: Int,
)

/** Decides whether an unfold cracks (docs/DESIGN.md §9.1). Pure, so the odds are testable over seeded rolls. */
object CrackTrigger {

    const val NOT_ROLLED = -1

    fun decide(input: TriggerInput, random: Random): TriggerResult = when {
        input.forceNext -> TriggerResult(true, HitReason.FORCED, NOT_ROLLED, 0)
        input.guaranteeAfter > 0 && input.missStreak >= input.guaranteeAfter ->
            TriggerResult(true, HitReason.GUARANTEED, NOT_ROLLED, 0)
        else -> {
            val roll = random.nextInt(100)
            if (roll < input.chancePercent) {
                TriggerResult(true, HitReason.ROLLED, roll, 0)
            } else {
                TriggerResult(false, HitReason.MISSED, roll, input.missStreak + 1)
            }
        }
    }
}
