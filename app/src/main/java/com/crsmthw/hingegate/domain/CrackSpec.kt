package com.crsmthw.hingegate.domain

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The crack's colours by role. The actual ARGB values live in `res/values/crack_colors.xml` (read by
 * `CrackPalette`), so the spec — persisted as JSON — stays valid if the palette is retuned.
 */
@Serializable
enum class CrackColor {
    BAND_WHITE, BAND_ICE, BAND_PINK, BAND_GREEN, BAND_CYAN, BAND_LAVENDER, BAND_VIOLET_DARK,
    TRAIL_YELLOW, TRAIL_WHITE, TRAIL_DARK, BLOB_BLACK, BLOB_RIM, SPECK_LIGHT, SPECK_BLUE,
}

/** One stacked line of the band. [offsetDp] is its centre, from the band's centre line (negative = toward the hinge's start). */
@Serializable
data class BandLine(val offsetDp: Float, val widthDp: Float, val color: CrackColor, val alpha: Float)

/** A line along the hinge; [offsetDp] moves it toward the band side. [coreColor] draws a thinner core on top. */
@Serializable
data class TrailLine(
    val widthDp: Float,
    val color: CrackColor,
    val alpha: Float,
    val coreColor: CrackColor? = null,
    val offsetDp: Float = 0f,
)

/** A thin dark triangle out of the blob; [angleDeg] is measured from "down the hinge" toward the band side. */
@Serializable
data class Spike(val angleDeg: Float, val lengthDp: Float, val widthDp: Float)

/** A tiny dot inside the blob: [dxDp] across the hinge (band side positive), [dyDp] along it (down positive). */
@Serializable
data class Speckle(val dxDp: Float, val dyDp: Float, val radiusDp: Float, val color: CrackColor)

/** The ink blot at the origin, elongated along the hinge; [radial] perturbs its outline (48 multipliers). */
@Serializable
data class BlobSpec(
    val radiusAcrossDp: Float,
    val radiusAlongDp: Float,
    val radial: List<Float>,
    val spikes: List<Spike>,
    val speckles: List<Speckle>,
    val rimWidthDp: Float,
)

/**
 * One crack, resolution-independent: sizes in dp, the origin as a fraction of the hinge length, colours by role
 * (docs/DESIGN.md §7). Generated once per crack and persisted, so the same crack survives a process restart.
 */
@Serializable
data class CrackSpec(
    val seed: Long,
    val originAlong: Float,
    val band: List<BandLine>,
    val glowDp: Float,
    val glowAlpha: Float,
    val lowerTrail: TrailLine,
    val upperTrail: TrailLine?,
    val blob: BlobSpec,
)

/**
 * Random but photo-shaped cracks (docs/DESIGN.md §7, tuned against `docs/reference/`). Deterministic for a seeded
 * [Random], which is what makes a persisted seed reproducible and the distributions testable.
 *
 * What the photos show, and so what the numbers aim for: a band of one or two dominant white / icy cores with
 * thin coloured fringes — magenta-pink on top most of the time, green / cyan / lavender below — sometimes split
 * by a thin dark violet line (photos 1 and 6), sometimes with a visible gap to a lone fringe (photo 6); a
 * yellow-green trail down the fold, often with a whiter core, and a dark hairline up it; and a black blot that is
 * usually a small dot but now and then a large spiky splash with light specks inside (photo 1).
 */
object CrackSpecGenerator {

    // ── Origin ───────────────────────────────────────────────────────────────
    const val ORIGIN_MIN = 0.50f
    const val ORIGIN_MAX = 0.70f

    // ── Band ─────────────────────────────────────────────────────────────────
    const val BAND_THICKNESS_MIN_DP = 6f
    const val BAND_THICKNESS_MAX_DP = 18f

    /**
     * Photo 1's large splash comes with a thick band (the blot is about 1.5× the band across and 3–4× along it);
     * the small dots of photos 2–6 come with thinner bands. So the band's thickness range depends on the blot.
     */
    const val BAND_WITH_LARGE_BLOB_MIN_DP = 12f
    const val BAND_WITH_SMALL_BLOB_MAX_DP = 14f
    const val CORE_WIDTH_MIN_DP = 2f
    const val CORE_WIDTH_MAX_DP = 6f
    const val CORE_ALPHA_MIN = 0.95f
    const val FRINGE_COUNT_MIN = 2
    const val FRINGE_COUNT_MAX = 5
    const val FRINGE_WIDTH_MIN_DP = 0.6f
    const val FRINGE_WIDTH_MAX_DP = 1.6f
    const val FRINGE_ALPHA_MIN = 0.7f
    const val PINK_TOP_CHANCE = 0.70f
    const val TWO_CORES_CHANCE = 0.5f
    const val SEPARATOR_CHANCE = 0.40f
    const val SEPARATOR_WIDTH_MIN_DP = 0.8f
    const val SEPARATOR_WIDTH_MAX_DP = 1.5f
    const val ICE_CORE_CHANCE = 0.35f

    /** Neighbouring sub-lines may overlap by at most this much (anti-aliasing blends them; more would merge them). */
    const val MAX_OVERLAP_DP = 0.3f

    /** Gaps around a separator stay tight, so the two cores read as one split line (photo 1). */
    const val SEPARATOR_GAP_MAX_DP = 0.4f

    // ── Glow ─────────────────────────────────────────────────────────────────
    const val GLOW_MAX_DP = 6f
    const val GLOW_ALPHA_MIN = 0.25f
    const val GLOW_ALPHA_MAX = 0.45f

    // ── Trails ───────────────────────────────────────────────────────────────
    const val LOWER_WIDTH_MIN_DP = 1.2f
    const val LOWER_WIDTH_MAX_DP = 3f
    const val LOWER_ALPHA_MIN = 0.85f
    const val LOWER_YELLOW_CHANCE = 0.75f
    const val LOWER_CORE_CHANCE = 0.60f
    const val LOWER_OFFSET_MAX_DP = 1f
    const val UPPER_CHANCE = 0.80f
    const val UPPER_WIDTH_MIN_DP = 0.6f
    const val UPPER_WIDTH_MAX_DP = 1.2f
    const val UPPER_ALPHA_MIN = 0.6f
    const val UPPER_ALPHA_MAX = 0.9f

    // ── Blob ─────────────────────────────────────────────────────────────────
    const val SMALL_BLOB_CHANCE = 0.75f
    const val SMALL_ACROSS_MIN_DP = 2.5f
    const val SMALL_ACROSS_MAX_DP = 6f
    const val SMALL_ELONGATION_MIN = 1.0f
    const val SMALL_ELONGATION_MAX = 1.6f
    const val SMALL_SPIKES_MAX = 2
    const val LARGE_ACROSS_MIN_DP = 10f
    const val LARGE_ACROSS_MAX_DP = 18f
    const val LARGE_ELONGATION_MIN = 1.4f
    const val LARGE_ELONGATION_MAX = 2.4f
    const val LARGE_SPIKES_MIN = 3
    const val LARGE_SPIKES_MAX = 8
    const val SPIKE_LENGTH_MIN = 0.5f
    const val SPIKE_LENGTH_MAX = 1.8f
    const val SPIKE_ALONG_HINGE_CHANCE = 0.75f
    const val SPIKE_ALONG_HINGE_SPREAD_DEG = 25f

    /** The other spikes leave on the far side of the hinge: none in the photos crosses into the band. */
    const val SPIKE_FAR_SIDE_MIN_DEG = 200f
    const val SPIKE_FAR_SIDE_MAX_DEG = 340f

    /** Needle-thin at the base: a fraction of the blob's across-radius. */
    const val SPIKE_WIDTH_MIN_FRACTION = 0.08f
    const val SPIKE_WIDTH_MAX_FRACTION = 0.22f
    const val SPECKLES_MIN = 4
    const val SPECKLES_MAX = 12
    const val SPECKLE_RADIUS_MIN_DP = 0.4f
    const val SPECKLE_RADIUS_MAX_DP = 1.2f
    const val SPECKLE_PLACEMENT = 0.7f
    const val RADIAL_COUNT = 48
    const val RADIAL_MIN = 0.7f
    const val RADIAL_MAX = 1.3f

    /** Raw radial noise is wider than the clamp: two 3-tap smoothings shrink it, the clamp trims what is left. */
    const val RADIAL_RAW_SPREAD = 0.55f
    const val RIM_WIDTH_MIN_DP = 0.8f
    const val RIM_WIDTH_MAX_DP = 2f

    private val LOWER_FRINGE_COLORS = listOf(CrackColor.BAND_GREEN, CrackColor.BAND_CYAN, CrackColor.BAND_LAVENDER)

    fun generate(random: Random): CrackSpec {
        val seed = random.nextLong()
        val originAlong = random.between(ORIGIN_MIN, ORIGIN_MAX)
        val smallBlob = random.chance(SMALL_BLOB_CHANCE)
        val band = generateBand(random, smallBlob)
        val glowDp = random.between(0f, GLOW_MAX_DP)
        val glowAlpha = random.between(GLOW_ALPHA_MIN, GLOW_ALPHA_MAX)
        val lowerTrail = TrailLine(
            widthDp = random.between(LOWER_WIDTH_MIN_DP, LOWER_WIDTH_MAX_DP),
            color = if (random.chance(LOWER_YELLOW_CHANCE)) CrackColor.TRAIL_YELLOW else CrackColor.TRAIL_WHITE,
            alpha = random.between(LOWER_ALPHA_MIN, 1f),
            coreColor = if (random.chance(LOWER_CORE_CHANCE)) CrackColor.TRAIL_WHITE else null,
            offsetDp = random.between(0f, LOWER_OFFSET_MAX_DP),
        )
        val upperTrail = if (random.chance(UPPER_CHANCE)) {
            TrailLine(
                widthDp = random.between(UPPER_WIDTH_MIN_DP, UPPER_WIDTH_MAX_DP),
                color = CrackColor.TRAIL_DARK,
                alpha = random.between(UPPER_ALPHA_MIN, UPPER_ALPHA_MAX),
            )
        } else {
            null
        }
        val blob = generateBlob(random, smallBlob)
        return CrackSpec(seed, originAlong, band, glowDp, glowAlpha, lowerTrail, upperTrail, blob)
    }

    // ── Band ─────────────────────────────────────────────────────────────────

    private class Slot(val widthDp: Float, val color: CrackColor, val alpha: Float, val isSeparatorSide: Boolean)

    private fun generateBand(random: Random, smallBlob: Boolean): List<BandLine> {
        val thickness = if (smallBlob) {
            random.between(BAND_THICKNESS_MIN_DP, BAND_WITH_SMALL_BLOB_MAX_DP)
        } else {
            random.between(BAND_WITH_LARGE_BLOB_MIN_DP, BAND_THICKNESS_MAX_DP)
        }
        var coreCount = if (random.chance(TWO_CORES_CHANCE)) 2 else 1
        var separator = coreCount == 2 && random.chance(SEPARATOR_CHANCE)
        val fringeWidths = MutableList(random.nextInt(FRINGE_COUNT_MIN, FRINGE_COUNT_MAX + 1)) {
            random.between(FRINGE_WIDTH_MIN_DP, FRINGE_WIDTH_MAX_DP)
        }
        var separatorWidth = if (separator) random.between(SEPARATOR_WIDTH_MIN_DP, SEPARATOR_WIDTH_MAX_DP) else 0f

        // Make room for the cores' minimum width: fewer fringes first, then no separator, then a single core.
        fun coreBudget() = thickness - fringeWidths.sum() - separatorWidth
        while (coreBudget() < CORE_WIDTH_MIN_DP * coreCount) {
            when {
                fringeWidths.size > FRINGE_COUNT_MIN -> fringeWidths.removeAt(fringeWidths.lastIndex)
                separator -> { separator = false; separatorWidth = 0f }
                coreCount == 2 -> coreCount = 1
                else -> break // unreachable with the constants above: 6 dp ≥ 2 × 1.6 + 2
            }
        }

        // Cores take most of the budget so they dominate (every photo); what is left becomes gaps.
        val perCoreMax = minOf(CORE_WIDTH_MAX_DP, coreBudget() / coreCount)
        val perCoreMin = maxOf(CORE_WIDTH_MIN_DP, perCoreMax * 0.55f).coerceAtMost(perCoreMax)
        val coreWidths = List(coreCount) { random.between(perCoreMin, perCoreMax) }
        val coreColor = if (random.chance(ICE_CORE_CHANCE)) CrackColor.BAND_ICE else CrackColor.BAND_WHITE

        // Top to bottom: one fringe above the cores (pink most of the time), the cores (split by the separator),
        // then the remaining fringes below in green / cyan / lavender.
        val slots = mutableListOf<Slot>()
        val topColor = if (random.chance(PINK_TOP_CHANCE)) CrackColor.BAND_PINK else LOWER_FRINGE_COLORS.random(random)
        slots += Slot(fringeWidths[0], topColor, random.between(FRINGE_ALPHA_MIN, 1f), false)
        slots += Slot(coreWidths[0], coreColor, random.between(CORE_ALPHA_MIN, 1f), separator)
        if (coreCount == 2) {
            if (separator) {
                slots += Slot(separatorWidth, CrackColor.BAND_VIOLET_DARK, random.between(FRINGE_ALPHA_MIN, 1f), true)
            }
            val secondColor = if (random.chance(ICE_CORE_CHANCE)) CrackColor.BAND_ICE else coreColor
            slots += Slot(coreWidths[1], secondColor, random.between(CORE_ALPHA_MIN, 1f), separator)
        }
        for (i in 1 until fringeWidths.size) {
            slots += Slot(fringeWidths[i], LOWER_FRINGE_COLORS.random(random), random.between(FRINGE_ALPHA_MIN, 1f), false)
        }

        // Gaps: the leftover thickness, spread unevenly (squared weights bunch it into one or two gaps, which
        // is what photo 6's lone violet line looks like); the separator's neighbours stay tight.
        val leftover = (thickness - slots.sumOf { it.widthDp.toDouble() }.toFloat()).coerceAtLeast(0f)
        val gapCount = slots.size - 1
        val tight = BooleanArray(gapCount) { slots[it].isSeparatorSide && slots[it + 1].isSeparatorSide }
        val tightCount = tight.count { it }
        val tightMax = if (tightCount == 0) 0f else minOf(SEPARATOR_GAP_MAX_DP, leftover / tightCount)
        val gaps = FloatArray(gapCount)
        for (i in 0 until gapCount) {
            if (tight[i]) gaps[i] = random.between(-MAX_OVERLAP_DP / 2f, tightMax)
        }
        val tightTotal = gaps.sum()
        val weights = FloatArray(gapCount) { if (tight[it]) 0f else random.nextFloat().let { w -> w * w } + 0.02f }
        val weightTotal = weights.sum()
        val loose = leftover - tightTotal
        for (i in 0 until gapCount) {
            if (!tight[i] && weightTotal > 0f) gaps[i] = loose * weights[i] / weightTotal
        }

        // Offsets from the band's centre line.
        val total = slots.sumOf { it.widthDp.toDouble() }.toFloat() + gaps.sum()
        var cursor = -total / 2f
        return slots.mapIndexed { index, slot ->
            val line = BandLine(cursor + slot.widthDp / 2f, slot.widthDp, slot.color, slot.alpha)
            cursor += slot.widthDp + if (index < gapCount) gaps[index] else 0f
            line
        }
    }

    // ── Blob ─────────────────────────────────────────────────────────────────

    private fun generateBlob(random: Random, small: Boolean): BlobSpec {
        val across: Float
        val along: Float
        val spikeCount: Int
        if (small) {
            across = random.between(SMALL_ACROSS_MIN_DP, SMALL_ACROSS_MAX_DP)
            along = across * random.between(SMALL_ELONGATION_MIN, SMALL_ELONGATION_MAX)
            spikeCount = random.nextInt(0, SMALL_SPIKES_MAX + 1)
        } else {
            across = random.between(LARGE_ACROSS_MIN_DP, LARGE_ACROSS_MAX_DP)
            along = across * random.between(LARGE_ELONGATION_MIN, LARGE_ELONGATION_MAX)
            spikeCount = random.nextInt(LARGE_SPIKES_MIN, LARGE_SPIKES_MAX + 1)
        }
        val radial = radialNoise(random)
        val spikes = List(spikeCount) {
            // Most spikes run up or down the crease (photo 1's splash is stretched along it); the rest leave on
            // the side away from the band.
            val angle = if (random.chance(SPIKE_ALONG_HINGE_CHANCE)) {
                val base = if (random.nextBoolean()) 0f else 180f
                base + random.between(-SPIKE_ALONG_HINGE_SPREAD_DEG, SPIKE_ALONG_HINGE_SPREAD_DEG)
            } else {
                random.between(SPIKE_FAR_SIDE_MIN_DEG, SPIKE_FAR_SIDE_MAX_DEG)
            }
            Spike(
                angleDeg = normalizeDegrees(angle),
                lengthDp = across * random.between(SPIKE_LENGTH_MIN, SPIKE_LENGTH_MAX),
                widthDp = across * random.between(SPIKE_WIDTH_MIN_FRACTION, SPIKE_WIDTH_MAX_FRACTION),
            )
        }
        val speckles = if (small) {
            emptyList()
        } else {
            List(random.nextInt(SPECKLES_MIN, SPECKLES_MAX + 1)) {
                // Uniform inside an ellipse a bit smaller than the blob, so specks never sit on its rim.
                val t = random.between(0f, (2.0 * PI).toFloat())
                val r = sqrt(random.nextFloat()) * SPECKLE_PLACEMENT
                Speckle(
                    dxDp = sin(t) * across * r,
                    dyDp = cos(t) * along * r,
                    radiusDp = random.between(SPECKLE_RADIUS_MIN_DP, SPECKLE_RADIUS_MAX_DP),
                    color = if (random.nextBoolean()) CrackColor.SPECK_LIGHT else CrackColor.SPECK_BLUE,
                )
            }
        }
        return BlobSpec(
            radiusAcrossDp = across,
            radiusAlongDp = along,
            radial = radial,
            spikes = spikes,
            speckles = speckles,
            rimWidthDp = random.between(RIM_WIDTH_MIN_DP, RIM_WIDTH_MAX_DP),
        )
    }

    /** 48 random multipliers, smoothed by a circular 3-tap average twice, clamped to [RADIAL_MIN, RADIAL_MAX]. */
    private fun radialNoise(random: Random): List<Float> {
        var values = FloatArray(RADIAL_COUNT) { 1f + random.between(-RADIAL_RAW_SPREAD, RADIAL_RAW_SPREAD) }
        repeat(2) {
            val previous = values
            values = FloatArray(RADIAL_COUNT) { i ->
                (previous[(i + RADIAL_COUNT - 1) % RADIAL_COUNT] + previous[i] + previous[(i + 1) % RADIAL_COUNT]) / 3f
            }
        }
        return values.map { it.coerceIn(RADIAL_MIN, RADIAL_MAX) }
    }

    private fun normalizeDegrees(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

    private fun Random.between(min: Float, max: Float): Float = if (max <= min) min else min + nextFloat() * (max - min)

    private fun Random.chance(probability: Float): Boolean = nextFloat() < probability
}
