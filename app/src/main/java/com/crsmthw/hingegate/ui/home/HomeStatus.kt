package com.crsmthw.hingegate.ui.home

import com.crsmthw.hingegate.data.Settings
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.Posture
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The one-line status under the app name. Decided here, worded by the screen from string resources. */
enum class HomeStatus { OFF, LEARNING, FOLDED, UNFOLDED_CRACKED, UNFOLDED_CLEAN;

    companion object {
        fun of(settings: Settings, active: ActiveCrack?, posture: Posture): HomeStatus = when {
            !settings.enabled -> OFF
            posture == Posture.UNKNOWN -> LEARNING
            posture == Posture.FOLDED -> FOLDED
            active != null -> UNFOLDED_CRACKED
            else -> UNFOLDED_CLEAN
        }
    }
}

/** Log times: just the time for today's entries, the date as well for older ones. */
object LogTime {
    fun format(epochMs: Long, nowEpochMs: Long, zone: ZoneId, locale: Locale): String {
        val time = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        val formatter = if (time.toLocalDate() == today) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        }
        return formatter.withLocale(locale).format(time)
    }
}
