package com.crsmthw.hingegate.domain

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * Everything the resolver has learned about this phone, persisted as JSON so a process restart neither forgets
 * the two panels nor mistakes the first observation after it for a fold or an unfold.
 *
 * Areas are PHYSICAL (`Display.Mode` width × height), so they do not change with rotation. The small panel's
 * width/height give the cover's aspect for hinge estimation (§5); the large panel's give the inner screen's shape
 * for the UI preview and for picking a wallpaper image while the posture is still UNKNOWN.
 */
@Serializable
data class PostureMemory(
    val smallArea: Long? = null,
    val largeArea: Long? = null,
    val smallWidth: Int? = null,
    val smallHeight: Int? = null,
    val largeWidth: Int? = null,
    val largeHeight: Int? = null,
    val lastArea: Long? = null,
    val lastWmPresent: Boolean? = null,
    /** A FoldingFeature has been seen at least once: Window Manager can tell the postures apart on this phone. */
    val wmCapable: Boolean = false,
    val lastPosture: Posture = Posture.UNKNOWN,
) {
    /** long/short of the small (cover) panel, or null before it is known. */
    val coverAspect: Float?
        get() = aspectOf(smallWidth, smallHeight)

    /** True once the two learned areas differ enough for display size alone to decide the posture. */
    val displayAuthoritative: Boolean
        get() {
            val small = smallArea ?: return false
            val large = largeArea ?: return false
            return large >= small * PostureResolver.AUTHORITY_RATIO
        }

    private fun aspectOf(w: Int?, h: Int?): Float? {
        if (w == null || h == null || w <= 0 || h <= 0) return null
        return maxOf(w, h).toFloat() / minOf(w, h)
    }
}

/** The outcome of one observation: the memory to keep, the posture now, who decided it, and the transition (if any). */
data class Resolution(
    val memory: PostureMemory,
    val posture: Posture,
    val authority: PostureAuthority,
    val transition: Transition?,
)

/**
 * Turns raw observations into a posture (docs/DESIGN.md §4.2). Pure: the Android glue is `PostureMonitor`.
 *
 * Display size is the primary signal because it works with no window at all: on Samsung (and most book-style
 * foldables) display 0 SWAPS panels, so its physical area jumps between the cover's and the inner screen's on
 * every fold. The resolver learns both areas from what it sees, so no device table is needed. Window Manager's
 * FoldingFeature is the fallback for phones whose display 0 does not swap.
 */
object PostureResolver {

    /** A new area this much larger than the known large one (or smaller than the small one) is a new panel. */
    const val LEARN_RATIO = 1.15

    /** The two panels must differ at least this much in area before display size is trusted. */
    const val AUTHORITY_RATIO = 1.25

    fun onDisplay(memory: PostureMemory, area: Long, width: Int, height: Int): Resolution {
        val learned = learn(memory, area, width, height).copy(lastArea = area)
        return if (learned.displayAuthoritative) {
            val posture = classify(learned, area)
            // The very first fold or unfold after install is also when the second panel is learned: re-reading
            // the previous area with the NEW knowledge still makes it a transition.
            val previous = memory.lastPosture.takeIf { it != Posture.UNKNOWN }
                ?: memory.lastArea?.let { classify(learned, it) }
                ?: Posture.UNKNOWN
            finish(learned, posture, PostureAuthority.DISPLAY_SIZE, previous)
        } else if (learned.wmCapable) {
            val posture = if (learned.lastWmPresent == true) Posture.UNFOLDED else Posture.FOLDED
            finish(learned, posture, PostureAuthority.WINDOW_MANAGER, memory.lastPosture)
        } else {
            Resolution(learned, Posture.UNKNOWN, PostureAuthority.NONE, null)
        }
    }

    fun onWindowManager(memory: PostureMemory, featurePresent: Boolean): Resolution {
        val updated = memory.copy(
            lastWmPresent = featurePresent,
            wmCapable = memory.wmCapable || featurePresent,
        )
        return when {
            // Display size already decides: Window Manager only refreshes the memory, never transitions.
            updated.displayAuthoritative -> {
                val posture = updated.lastArea?.let { classify(updated, it) } ?: updated.lastPosture
                Resolution(updated, posture, PostureAuthority.DISPLAY_SIZE, null)
            }
            updated.wmCapable -> {
                val posture = if (featurePresent) Posture.UNFOLDED else Posture.FOLDED
                // The first feature ever seen comes from UNKNOWN, so finish() yields no transition for it.
                finish(updated, posture, PostureAuthority.WINDOW_MANAGER, memory.lastPosture)
            }
            else -> Resolution(updated, Posture.UNKNOWN, PostureAuthority.NONE, null)
        }
    }

    private fun learn(memory: PostureMemory, area: Long, width: Int, height: Int): PostureMemory {
        val small = memory.smallArea
        val large = memory.largeArea
        if (small == null || large == null) {
            // The first panel seen is both until another one shows up; its size is kept as both, and whichever
            // turns out to be wrong is overwritten when the other panel is seen.
            return memory.copy(
                smallArea = area, largeArea = area,
                smallWidth = width, smallHeight = height,
                largeWidth = width, largeHeight = height,
            )
        }
        var m = memory
        if (area > large * LEARN_RATIO) m = m.copy(largeArea = area, largeWidth = width, largeHeight = height)
        if (area < small / LEARN_RATIO) m = m.copy(smallArea = area, smallWidth = width, smallHeight = height)
        return m
    }

    /** At or above the geometric mean of the two panels is the inner screen. */
    private fun classify(memory: PostureMemory, area: Long): Posture {
        val small = memory.smallArea ?: return Posture.UNKNOWN
        val large = memory.largeArea ?: return Posture.UNKNOWN
        val threshold = sqrt(small.toDouble() * large.toDouble())
        return if (area >= threshold) Posture.UNFOLDED else Posture.FOLDED
    }

    private fun finish(
        memory: PostureMemory,
        posture: Posture,
        authority: PostureAuthority,
        previous: Posture,
    ): Resolution {
        val transition = when {
            previous == Posture.FOLDED && posture == Posture.UNFOLDED -> Transition.UNFOLDED
            previous == Posture.UNFOLDED && posture == Posture.FOLDED -> Transition.FOLDED
            else -> null
        }
        val kept = if (posture != Posture.UNKNOWN) memory.copy(lastPosture = posture) else memory
        return Resolution(kept, posture, authority, transition)
    }
}
