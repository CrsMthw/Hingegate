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

    /**
     * Build 2 (Cris on the Fold 8, 2026-10-31: "the thickest one shown here should be the thinnest"): build 1's band
     * was too thin, the thickest he saw being ≈ 13 dp. Every band size below — fringe and separator widths, the
     * separator's gaps, the overlap limit, the cores' minimum — and the glow are build 1's × this one scale, so build
     * 1's thinnest band (6 dp) is now that 13 dp. The cores have no maximum: they take whatever the fringes, the
     * separator and the (bounded) gaps leave, so a thick band is solid light like photos 1–2 rather than build 1's
     * proportions with a hole in them. The blot follows the band's thickness (see Blob).
     */
    const val BAND_SCALE = 13f / 6f

    /** No crack is thinner than the thickest band Cris saw on build 1 (6 dp × [BAND_SCALE]). */
    const val BAND_THICKNESS_MIN_DP = 13f

    /** Nor thicker than photos 1–2's bands (≈ 26 and 25–30 dp on the Fold 8's 466 dp hinge-to-edge). */
    const val BAND_THICKNESS_MAX_DP = 30f

    /**
     * Photo 1's large splash comes with a thick band (≈ 26 dp; the blot is ≈ 1.6× the band across and 3.5× along
     * it); the small dots of photos 2–6 come with any band. Build 1's 12 dp × [BAND_SCALE].
     */
    const val BAND_WITH_LARGE_BLOB_MIN_DP = 26f

    /** Build 1's 14 dp × [BAND_SCALE] would be 30.3: capped at [BAND_THICKNESS_MAX_DP]. */
    const val BAND_WITH_SMALL_BLOB_MAX_DP = BAND_THICKNESS_MAX_DP
    const val CORE_WIDTH_MIN_DP = 2f * BAND_SCALE // 4.33
    const val CORE_ALPHA_MIN = 0.95f
    const val FRINGE_COUNT_MIN = 2
    const val FRINGE_COUNT_MAX = 5
    const val FRINGE_WIDTH_MIN_DP = 0.6f * BAND_SCALE // 1.3
    const val FRINGE_WIDTH_MAX_DP = 1.6f * BAND_SCALE // 3.47
    const val FRINGE_ALPHA_MIN = 0.7f
    const val PINK_TOP_CHANCE = 0.70f
    const val TWO_CORES_CHANCE = 0.5f
    const val SEPARATOR_CHANCE = 0.40f
    const val SEPARATOR_WIDTH_MIN_DP = 0.8f * BAND_SCALE // 1.73
    const val SEPARATOR_WIDTH_MAX_DP = 1.5f * BAND_SCALE // 3.25
    const val ICE_CORE_CHANCE = 0.35f

    /** Neighbouring sub-lines may overlap by at most this much (anti-aliasing blends them; more would merge them). */
    const val MAX_OVERLAP_DP = 0.3f * BAND_SCALE // 0.65

    /** Gaps around a separator stay tight, so the two cores read as one split line (photo 1). */
    const val SEPARATOR_GAP_MAX_DP = 0.4f * BAND_SCALE // 0.87

    /**
     * Between any other two neighbouring sub-lines at most a hairline of wallpaper shows: up to this, drawn squared
     * so most are far thinner. (Build 2 first spread all the thickness the cores did not take as gaps, which left
     * up to 61 % of a thick band as one hole.)
     */
    const val LOOSE_GAP_MAX_DP = 0.5f * BAND_SCALE // 1.08

    /** Now and then the bottom fringe stands apart from the rest of the band across a visible gap (photo 6). */
    const val LONE_FRINGE_CHANCE = 0.20f

    /** That gap is at least this share of the band's thickness T… */
    const val LONE_GAP_MIN_OF_BAND = 0.15f

    /**
     * …and, with the lone fringe, at most this share (photo 6: ≈ 0.35), so at least 0.65 T always reads as one
     * band and no gap is ever wider than a third of it.
     */
    const val LONE_FRINGE_SHARE_MAX = 0.35f

    /**
     * The gaps never take more than this share of what the band has beyond the cores' minimum, so the cores keep
     * dominating a band crowded with fringes (a lone fringe's gap would otherwise leave them at their minimum).
     */
    const val GAPS_OF_ROOM_MAX = 0.5f

    // ── Glow ─────────────────────────────────────────────────────────────────
    const val GLOW_MAX_DP = 6f * BAND_SCALE // 13
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
    // Its size follows the band's thickness T (build 2: a thicker band gets a bigger blot), so the two keep the
    // photos' proportions at any thickness; its spikes and speckles follow its across-radius, its rim follows T.
    const val SMALL_BLOB_CHANCE = 0.75f

    /** A small dot's width across the hinge (2 × its across-radius) ÷ T: Cris's build 1 screenshot shows ≈ 0.8. */
    const val SMALL_ACROSS_OF_BAND_MIN = 0.7f
    const val SMALL_ACROSS_OF_BAND_MAX = 1.0f
    const val SMALL_ELONGATION_MIN = 1.0f
    const val SMALL_ELONGATION_MAX = 1.6f
    const val SMALL_SPIKES_MAX = 2

    /** The large splash's width across ÷ T: photo 1's is ≈ 1.6 (and ≈ 3.5 along: elongation ≈ 2.2). */
    const val LARGE_ACROSS_OF_BAND_MIN = 1.4f
    const val LARGE_ACROSS_OF_BAND_MAX = 1.8f
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

    /** A speckle's radius as a fraction of the blob's across-radius (build 1: 0.4–1.2 dp in a 10–18 dp one). */
    const val SPECKLE_RADIUS_MIN_FRACTION = 0.03f
    const val SPECKLE_RADIUS_MAX_FRACTION = 0.085f
    const val SPECKLE_PLACEMENT = 0.7f
    const val RADIAL_COUNT = 48
    const val RADIAL_MIN = 0.7f
    const val RADIAL_MAX = 1.3f

    /** Raw radial noise is wider than the clamp: two 3-tap smoothings shrink it, the clamp trims what is left. */
    const val RADIAL_RAW_SPREAD = 0.55f

    /** The rim's width ÷ T: build 1's 0.8–2 dp beside the 13 dp band of Cris's screenshot. */
    const val RIM_WIDTH_MIN_OF_BAND = 0.06f
    const val RIM_WIDTH_MAX_OF_BAND = 0.15f

    private val LOWER_FRINGE_COLORS = listOf(CrackColor.BAND_GREEN, CrackColor.BAND_CYAN, CrackColor.BAND_LAVENDER)

    fun generate(random: Random): CrackSpec {
        val seed = random.nextLong()
        val originAlong = random.between(ORIGIN_MIN, ORIGIN_MAX)
        val smallBlob = random.chance(SMALL_BLOB_CHANCE)
        // The band's thickness is drawn first: the band's sub-lines and the blot are both sized from it.
        val thickness = if (smallBlob) {
            random.between(BAND_THICKNESS_MIN_DP, BAND_WITH_SMALL_BLOB_MAX_DP)
        } else {
            random.between(BAND_WITH_LARGE_BLOB_MIN_DP, BAND_THICKNESS_MAX_DP)
        }
        val band = generateBand(random, thickness)
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
        val blob = generateBlob(random, smallBlob, thickness)
        return CrackSpec(seed, originAlong, band, glowDp, glowAlpha, lowerTrail, upperTrail, blob)
    }

    // ── Band ─────────────────────────────────────────────────────────────────

    private class Slot(
        val color: CrackColor,
        val alpha: Float,
        val isSeparatorSide: Boolean,
        var widthDp: Float,
        val isCore: Boolean = false,
    )

    /** [thickness] is the band's total, edge to edge; the lines and gaps fill exactly that. */
    private fun generateBand(random: Random, thickness: Float): List<BandLine> {
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
                else -> break // unreachable with the constants above: 13 dp ≥ (2 × 1.6 + 2) × BAND_SCALE ≈ 11.3
            }
        }

        // Cores take everything the fringes, the separator and the gaps leave, so they dominate (every photo); two
        // cores share it by these draws. Their widths are set once the gaps are known.
        val coreDraws = List(coreCount) { random.nextFloat() }
        val coreColor = if (random.chance(ICE_CORE_CHANCE)) CrackColor.BAND_ICE else CrackColor.BAND_WHITE

        // Top to bottom: one fringe above the cores (pink most of the time), the cores (split by the separator),
        // then the remaining fringes below in green / cyan / lavender.
        val slots = mutableListOf<Slot>()
        val topColor = if (random.chance(PINK_TOP_CHANCE)) CrackColor.BAND_PINK else LOWER_FRINGE_COLORS.random(random)
        slots += Slot(topColor, random.between(FRINGE_ALPHA_MIN, 1f), false, fringeWidths[0])
        slots += Slot(coreColor, random.between(CORE_ALPHA_MIN, 1f), separator, 0f, isCore = true)
        if (coreCount == 2) {
            if (separator) {
                slots += Slot(CrackColor.BAND_VIOLET_DARK, random.between(FRINGE_ALPHA_MIN, 1f), true, separatorWidth)
            }
            val secondColor = if (random.chance(ICE_CORE_CHANCE)) CrackColor.BAND_ICE else coreColor
            slots += Slot(secondColor, random.between(CORE_ALPHA_MIN, 1f), separator, 0f, isCore = true)
        }
        for (i in 1 until fringeWidths.size) {
            slots += Slot(LOWER_FRINGE_COLORS.random(random), random.between(FRINGE_ALPHA_MIN, 1f), false, fringeWidths[i])
        }

        // Gaps: tight beside the separator; a hairline at most anywhere else, except — now and then — before the
        // bottom fringe (photo 6's lone line; the bottom slot is always a fringe, never beside the separator).
        val gapCount = slots.size - 1
        val tight = BooleanArray(gapCount) { slots[it].isSeparatorSide && slots[it + 1].isSeparatorSide }
        val gaps = FloatArray(gapCount)
        for (i in 0 until gapCount) {
            if (tight[i]) gaps[i] = random.between(-MAX_OVERLAP_DP / 2f, SEPARATOR_GAP_MAX_DP)
        }
        for (i in 0 until gapCount) {
            if (tight[i]) continue
            val draw = random.nextFloat()
            gaps[i] = if (i == gapCount - 1) {
                gapBeforeBottomFringe(draw, thickness, slots.last().widthDp)
            } else {
                LOOSE_GAP_MAX_DP * draw * draw
            }
        }

        // The gaps give way first: the cores never go below their minimum and get at least half of what is beyond it.
        val budget = coreBudget()
        val room = (budget - CORE_WIDTH_MIN_DP * coreCount) * GAPS_OF_ROOM_MAX
        val open = gaps.filter { it > 0f }.sum()
        val overlaps = gaps.filter { it < 0f }.sum()
        if (open > 0f && open + overlaps > room) {
            val shrink = ((room - overlaps) / open).coerceIn(0f, 1f)
            for (i in gaps.indices) if (gaps[i] > 0f) gaps[i] *= shrink
        }
        val coreTotal = budget - gaps.sum()
        val cores = slots.filter { it.isCore }
        if (coreCount == 1) {
            cores[0].widthDp = coreTotal
        } else {
            // Shares 0.25–0.75 of what is above the two minimums, near even most of the time (photo 1).
            val share = (0.5f + coreDraws[0]) / (1f + coreDraws[0] + coreDraws[1])
            cores[0].widthDp = CORE_WIDTH_MIN_DP + (coreTotal - 2f * CORE_WIDTH_MIN_DP) * share
            cores[1].widthDp = coreTotal - cores[0].widthDp
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

    /**
     * The gap above the bottom fringe for a uniform [draw]: a hairline like any other most of the time; with
     * [LONE_FRINGE_CHANCE] photo 6's gap, [LONE_GAP_MIN_OF_BAND]–[LONE_FRINGE_SHARE_MAX] of [thickness] — but the gap
     * and the lone fringe together never more than [LONE_FRINGE_SHARE_MAX] of it.
     */
    private fun gapBeforeBottomFringe(draw: Float, thickness: Float, fringeWidth: Float): Float {
        val hairlines = 1f - LONE_FRINGE_CHANCE
        if (draw < hairlines) return LOOSE_GAP_MAX_DP * (draw / hairlines) * (draw / hairlines)
        val f = (draw - hairlines) / LONE_FRINGE_CHANCE
        val gap = thickness * (LONE_GAP_MIN_OF_BAND + f * (LONE_FRINGE_SHARE_MAX - LONE_GAP_MIN_OF_BAND))
        return minOf(gap, thickness * LONE_FRINGE_SHARE_MAX - fringeWidth).coerceAtLeast(0f)
    }

    // ── Blob ─────────────────────────────────────────────────────────────────

    /** Sized from the band's [thickness]: `across` is the across-radius, half the blot's width across the hinge. */
    private fun generateBlob(random: Random, small: Boolean, thickness: Float): BlobSpec {
        val across: Float
        val along: Float
        val spikeCount: Int
        if (small) {
            across = thickness * random.between(SMALL_ACROSS_OF_BAND_MIN, SMALL_ACROSS_OF_BAND_MAX) / 2f
            along = across * random.between(SMALL_ELONGATION_MIN, SMALL_ELONGATION_MAX)
            spikeCount = random.nextInt(0, SMALL_SPIKES_MAX + 1)
        } else {
            across = thickness * random.between(LARGE_ACROSS_OF_BAND_MIN, LARGE_ACROSS_OF_BAND_MAX) / 2f
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
                    radiusDp = across * random.between(SPECKLE_RADIUS_MIN_FRACTION, SPECKLE_RADIUS_MAX_FRACTION),
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
            rimWidthDp = thickness * random.between(RIM_WIDTH_MIN_OF_BAND, RIM_WIDTH_MAX_OF_BAND),
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
