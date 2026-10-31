package com.crsmthw.hingegate.domain

/**
 * The crack's one animation (docs/DESIGN.md §10): a short display-glitch flicker as it appears, then steady.
 * Finite by design — surfaces animate only while `now − appearedAt < DURATION_MS`, so a crack restored after a
 * process restart does not flicker again.
 */
object CrackAppearance {

    const val DURATION_MS = 520L

    private const val FADE_START_MS = 240L
    private const val DIM = 0.15f
    private const val HALF = 0.35f

    fun alpha(msSinceAppear: Long): Float = when {
        msSinceAppear < 0L -> 1f
        msSinceAppear < 60L -> 1f
        msSinceAppear < 120L -> DIM
        msSinceAppear < 200L -> 1f
        msSinceAppear < FADE_START_MS -> HALF
        msSinceAppear < DURATION_MS -> {
            val t = (msSinceAppear - FADE_START_MS).toFloat() / (DURATION_MS - FADE_START_MS)
            HALF + (1f - HALF) * t
        }
        else -> 1f
    }

    /** Whether a crack that appeared at [appearedAtEpochMs] is still inside its flicker at [nowEpochMs]. */
    fun isAnimating(appearedAtEpochMs: Long, nowEpochMs: Long): Boolean {
        val elapsed = nowEpochMs - appearedAtEpochMs
        return elapsed in 0 until DURATION_MS
    }
}
