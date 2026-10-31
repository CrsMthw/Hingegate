package com.crsmthw.hingegate.ui

import com.crsmthw.hingegate.data.Settings
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.CrackCause
import com.crsmthw.hingegate.domain.CrackSpecGenerator
import com.crsmthw.hingegate.domain.Posture
import com.crsmthw.hingegate.ui.home.HomeStatus
import com.crsmthw.hingegate.ui.home.LogTime
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HomeStatusTest {

    private val crack = ActiveCrack(CrackSpecGenerator.generate(Random(1)), 0L, CrackCause.MANUAL)

    @Test
    fun `status for every state`() {
        assertEquals(HomeStatus.OFF, HomeStatus.of(Settings(enabled = false), crack, Posture.UNFOLDED))
        assertEquals(HomeStatus.LEARNING, HomeStatus.of(Settings(), null, Posture.UNKNOWN))
        assertEquals(HomeStatus.FOLDED, HomeStatus.of(Settings(), null, Posture.FOLDED))
        assertEquals(HomeStatus.UNFOLDED_CRACKED, HomeStatus.of(Settings(), crack, Posture.UNFOLDED))
        assertEquals(HomeStatus.UNFOLDED_CLEAN, HomeStatus.of(Settings(), null, Posture.UNFOLDED))
    }

    @Test
    fun `log time shows the date only for older entries`() {
        val zone = ZoneId.of("UTC")
        val now = LocalDateTime.of(2026, 10, 31, 18, 0).atZone(zone).toInstant().toEpochMilli()
        val earlierToday = LocalDateTime.of(2026, 10, 31, 9, 5).atZone(zone).toInstant().toEpochMilli()
        val yesterday = LocalDateTime.of(2026, 10, 30, 9, 5).atZone(zone).toInstant().toEpochMilli()
        val today = LogTime.format(earlierToday, now, zone, Locale.UK)
        val older = LogTime.format(yesterday, now, zone, Locale.UK)
        assertEquals("09:05", today)
        assertNotEquals(today, older)
        assertEquals(true, older.contains("30"))
    }
}
