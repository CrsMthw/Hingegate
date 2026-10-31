package com.crsmthw.hingegate.domain

import kotlinx.serialization.Serializable

/** VERTICAL: the hinge is a vertical line at x = position. HORIZONTAL: a horizontal line at y = position. */
enum class HingeOrientation { VERTICAL, HORIZONTAL }

/** Where the hinge position came from: the phone's own FoldingFeature, or [HingeInference]'s guess. */
enum class HingeSource { WINDOW_MANAGER, ESTIMATED }

/**
 * The hinge as a line in some pixel frame (which one is always stated by whoever holds it). Serializable because
 * the Real wallpaper mode keeps the inner panel's hinge, in its natural frame, for staging while folded.
 */
@Serializable
data class HingeLine(
    val orientation: HingeOrientation,
    val positionPx: Float,
    val thicknessPx: Float,
    val source: HingeSource,
)
