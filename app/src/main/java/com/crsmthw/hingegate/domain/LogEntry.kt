package com.crsmthw.hingegate.domain

import kotlinx.serialization.Serializable

@Serializable
enum class LogKind { UNFOLD_HIT, UNFOLD_MISS, UNFOLD_DISABLED, FOLD_CLEARED, FOLD, MANUAL_SHOW, MANUAL_CLEAR }

/**
 * One line of the detection log (docs/DESIGN.md §9.3). Plain folds are logged too, so detection can be checked
 * on a phone where cracks are rare.
 *
 * [missStreak]: for a miss, the clean unfolds in a row INCLUDING this one; for a guaranteed hit, the clean
 * unfolds that earned it.
 */
@Serializable
data class LogEntry(
    val timeEpochMs: Long,
    val kind: LogKind,
    val reason: HitReason? = null,
    val roll: Int? = null,
    val chancePercent: Int? = null,
    val missStreak: Int? = null,
    val authority: PostureAuthority = PostureAuthority.NONE,
    val hingeSource: HingeSource? = null,
) {
    companion object {
        /** Newest first, at most this many. */
        const val CAPACITY = 40
    }
}

/**
 * What a log line should say, decided without string resources so it can be unit-tested; the UI turns each
 * phrase into a localised sentence.
 */
sealed interface LogPhrase {
    data class CrackedRolled(val roll: Int, val chancePercent: Int) : LogPhrase
    data class CrackedGuaranteed(val afterClean: Int) : LogPhrase
    data object CrackedForced : LogPhrase
    data class Clean(val roll: Int, val inARow: Int) : LogPhrase
    data object UnfoldedWhileOff : LogPhrase
    data object FoldCleared : LogPhrase
    data object Folded : LogPhrase
    data object ShownByHand : LogPhrase
    data object ClearedByHand : LogPhrase

    companion object {
        fun of(entry: LogEntry): LogPhrase = when (entry.kind) {
            LogKind.UNFOLD_HIT -> when (entry.reason) {
                HitReason.GUARANTEED -> CrackedGuaranteed(entry.missStreak ?: 0)
                HitReason.ROLLED -> CrackedRolled(entry.roll ?: 0, entry.chancePercent ?: 0)
                else -> CrackedForced
            }
            LogKind.UNFOLD_MISS -> Clean(entry.roll ?: 0, entry.missStreak ?: 0)
            LogKind.UNFOLD_DISABLED -> UnfoldedWhileOff
            LogKind.FOLD_CLEARED -> FoldCleared
            LogKind.FOLD -> Folded
            LogKind.MANUAL_SHOW -> ShownByHand
            LogKind.MANUAL_CLEAR -> ClearedByHand
        }
    }
}
