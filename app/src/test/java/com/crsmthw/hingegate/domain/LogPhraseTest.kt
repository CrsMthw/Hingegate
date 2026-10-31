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
    fun `log entries round trip as json`() {
        val list = listOf(entry(LogKind.UNFOLD_HIT, HitReason.ROLLED, 3, 35, 0), entry(LogKind.FOLD))
        val serializer = ListSerializer(LogEntry.serializer())
        assertEquals(list, Json.decodeFromString(serializer, Json.encodeToString(serializer, list)))
    }
}
