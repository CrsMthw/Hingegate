package com.crsmthw.hingegate.render

import android.content.Context
import androidx.annotation.ColorInt
import com.crsmthw.hingegate.R
import com.crsmthw.hingegate.domain.CrackColor

/**
 * The crack's colours, read once from `res/values/crack_colors.xml`. The explicit `when` is deliberate: it is what
 * makes every colour resource referenced (lint's UnusedResources) and avoids `getIdentifier` (DiscouragedApi).
 */
class CrackPalette private constructor(private val colors: IntArray) {

    @ColorInt
    operator fun get(color: CrackColor): Int = colors[color.ordinal]

    companion object {
        fun from(context: Context): CrackPalette =
            CrackPalette(IntArray(CrackColor.entries.size) { context.getColor(resourceOf(CrackColor.entries[it])) })

        private fun resourceOf(color: CrackColor): Int = when (color) {
            CrackColor.BAND_WHITE -> R.color.crack_band_white
            CrackColor.BAND_ICE -> R.color.crack_band_ice
            CrackColor.BAND_PINK -> R.color.crack_band_pink
            CrackColor.BAND_GREEN -> R.color.crack_band_green
            CrackColor.BAND_CYAN -> R.color.crack_band_cyan
            CrackColor.BAND_LAVENDER -> R.color.crack_band_lavender
            CrackColor.BAND_VIOLET_DARK -> R.color.crack_band_violet_dark
            CrackColor.TRAIL_YELLOW -> R.color.crack_trail_yellow
            CrackColor.TRAIL_WHITE -> R.color.crack_trail_white
            CrackColor.TRAIL_DARK -> R.color.crack_trail_dark
            CrackColor.BLOB_BLACK -> R.color.crack_blob_black
            CrackColor.BLOB_RIM -> R.color.crack_blob_rim
            CrackColor.SPECK_LIGHT -> R.color.crack_speck_light
            CrackColor.SPECK_BLUE -> R.color.crack_speck_blue
        }
    }
}
