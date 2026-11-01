package com.crsmthw.hingegate.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test
import kotlin.test.assertEquals

class LogPhraseTest {

    private fun entry(kind: LogKind, reason: HitReason? = null, roll: Int? = null, chance: Int? = null, streak: Int? = null) =
        LogEntry(1L, kind, reason, roll, chance, streak, PostureAuthority.DISPLAY_SIZE, HingeSource.ESTIMATED)

    @Test
    fun `every kind maps to its phrase`() {
        assertEquals(LogPhrase.CrackedRolled(23, 35), LogPhrase.of(entry(LogKind.UNFOLD_HIT, HitReason.ROLLED, 23, 35, 0)))
        assertEquals(LogPhrase.CrackedGuaranteed(4), LogPhrase.of(entry(LogKind.UNFOLD_HIT, HitReason.GUARANTEED, -1, 35, 4)))
        assertEquals(LogPhrase.CrackedForced, LogPhrase.of(entry(LogKind.UNFOLD_HIT, HitReason.FORCED)))
        assertEquals(LogPhrase.Clean(71, 2), LogPhrase.of(entry(LogKind.UNFOLD_MISS, HitReason.MISSED, 71, 35, 2)))
        assertEquals(LogPhrase.UnfoldedWhileOff, LogPhrase.of(entry(LogKind.UNFOLD_DISABLED)))
        assertEquals(LogPhrase.FoldCleared, LogPhrase.of(entry(LogKind.FOLD_CLEARED)))
        assertEquals(LogPhrase.Folded, LogPhrase.of(entry(LogKind.FOLD)))
        assertEquals(LogPhrase.ShownByHand, LogPhrase.of(entry(LogKind.MANUAL_SHOW)))
        assertEquals(LogPhrase.ClearedByHand, LogPhrase.of(entry(LogKind.MANUAL_CLEAR)))
    }

    @Test
    fun `real wallpaper entries carry the flags and the id`() {
        fun set(kind: LogKind, cracked: Boolean) = LogEntry(1L, kind, swapCracked = cracked, swapWhich = 5, swapId = 42)
        assertEquals(LogPhrase.SwapSet(SwapTiming.AHEAD, true, 5, 42), LogPhrase.of(set(LogKind.SWAP_STAGED, true)))
        assertEquals(LogPhrase.SwapSet(SwapTiming.ON_TIME, false, 5, 42), LogPhrase.of(set(LogKind.SWAP_SET, false)))
        assertEquals(LogPhrase.SwapSet(SwapTiming.LATE, true, 5, 42), LogPhrase.of(set(LogKind.SWAP_SET_LATE, true)))
        assertEquals(LogPhrase.SwapSet(SwapTiming.RESTORE, false, 5, 42), LogPhrase.of(set(LogKind.SWAP_RESTORED, false)))
        assertEquals(LogPhrase.SwapCaptured(7), LogPhrase.of(LogEntry(1L, LogKind.SWAP_CAPTURED, swapId = 7)))
        assertEquals(LogPhrase.SwapRecaptured(8), LogPhrase.of(LogEntry(1L, LogKind.SWAP_RECAPTURED, swapId = 8)))
        assertEquals(LogPhrase.SwapIdAdopted(9), LogPhrase.of(LogEntry(1L, LogKind.SWAP_ID_ADOPTED, swapId = 9)))
        assertEquals(LogPhrase.SwapStageMissed, LogPhrase.of(LogEntry(1L, LogKind.SWAP_STAGE_MISSED)))
        assertEquals(
            LogPhrase.SwapFailed(SwapFailure.LIVE_WALLPAPER),
            LogPhrase.of(LogEntry(1L, LogKind.SWAP_FAILED, swapFailure = SwapFailure.LIVE_WALLPAPER)),
        )
    }

    @Test
    fun `each set timing has its own log kind`() {
        SwapTiming.entries.forEach { timing ->
            val phrase = LogPhrase.of(LogEntry(1L, LogPhrase.kindOf(timing), swapCracked = true, swapWhich = 1, swapId = 2))
            assertEquals(LogPhrase.SwapSet(timing, true, 1, 2), phrase)
        }
    }

    @Test
    fun `each set reads as its own sentence`() {
        fun wording(timing: SwapTiming, cracked: Boolean) = LogPhrase.SwapSet(timing, cracked, 5, 42).wording
        assertEquals(SwapSetWording.STAGED_CRACKED, wording(SwapTiming.AHEAD, true))
        assertEquals(SwapSetWording.STAGED_CLEAN, wording(SwapTiming.AHEAD, false))
        assertEquals(SwapSetWording.SET_CRACKED, wording(SwapTiming.ON_TIME, true))
        assertEquals(SwapSetWording.SET_CLEAN, wording(SwapTiming.ON_TIME, false))
        assertEquals(SwapSetWording.LATE_CRACKED, wording(SwapTiming.LATE, true))
        // Only an unfold's crack is "late": a clean copy set while unfolded is an ordinary set.
        assertEquals(SwapSetWording.SET_CLEAN, wording(SwapTiming.LATE, false))
        assertEquals(SwapSetWording.RESTORED, wording(SwapTiming.RESTORE, true))
        assertEquals(SwapSetWording.RESTORED, wording(SwapTiming.RESTORE, false))
    }

    @Test
    fun `an entry from before the real wallpaper mode still reads`() {
        val old = """[{"timeEpochMs":5,"kind":"FOLD","authority":"DISPLAY_SIZE"}]"""
        val decoded = Json.decodeFromString(ListSerializer(LogEntry.serializer()), old)
        assertEquals(LogEntry(5L, LogKind.FOLD, authority = PostureAuthority.DISPLAY_SIZE), decoded.single())
    }

    @Test
    fun `log entries round trip as json`() {
        val list = listOf(
            entry(LogKind.UNFOLD_HIT, HitReason.ROLLED, 3, 35, 0),
            entry(LogKind.FOLD),
            LogEntry(2L, LogKind.SWAP_STAGED, swapCracked = true, swapWhich = 5, swapId = 42),
            LogEntry(3L, LogKind.SWAP_FAILED, swapFailure = SwapFailure.SET_FAILED),
        )
        val serializer = ListSerializer(LogEntry.serializer())
        assertEquals(list, Json.decodeFromString(serializer, Json.encodeToString(serializer, list)))
    }
}
