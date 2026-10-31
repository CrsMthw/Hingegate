package com.crsmthw.hingegate.domain

import com.crsmthw.hingegate.domain.CrackSpecGenerator as G
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CrackSpecGeneratorTest {

    private val eps = 1e-3f

    private fun assertIn(value: Float, min: Float, max: Float, what: String) =
        assertTrue(value >= min - eps && value <= max + eps, "$what = $value not in [$min, $max]")

    private val specs: List<CrackSpec> by lazy { (0L until 1_000L).map { G.generate(Random(it)) } }

    @Test
    fun `same seed gives the same crack`() {
        assertEquals(G.generate(Random(42)), G.generate(Random(42)))
        assertNotEquals(G.generate(Random(42)), G.generate(Random(43)))
    }

    @Test
    fun `serialization round trip`() {
        val active = ActiveCrack(G.generate(Random(7)), 1_700_000_000_000L, CrackCause.UNFOLD)
        val json = Json.encodeToString(ActiveCrack.serializer(), active)
        assertEquals(active, Json.decodeFromString(ActiveCrack.serializer(), json))
    }

    @Test
    fun `origin and glow in range`() = specs.forEach { s ->
        assertIn(s.originAlong, G.ORIGIN_MIN, G.ORIGIN_MAX, "originAlong")
        assertIn(s.glowDp, 0f, G.GLOW_MAX_DP, "glowDp")
        assertIn(s.glowAlpha, G.GLOW_ALPHA_MIN, G.GLOW_ALPHA_MAX, "glowAlpha")
    }

    @Test
    fun `band thickness, line widths and colours in range`() = specs.forEach { s ->
        val top = s.band.minOf { it.offsetDp - it.widthDp / 2f }
        val bottom = s.band.maxOf { it.offsetDp + it.widthDp / 2f }
        assertIn(bottom - top, G.BAND_THICKNESS_MIN_DP, G.BAND_THICKNESS_MAX_DP, "band thickness")
        // Centred on the band's centre line.
        assertEquals(0f, (top + bottom) / 2f, 1e-2f)

        val cores = s.band.filter { it.color == CrackColor.BAND_WHITE || it.color == CrackColor.BAND_ICE }
        assertTrue(cores.size in 1..2, "cores = ${cores.size}")
        cores.forEach {
            assertIn(it.widthDp, G.CORE_WIDTH_MIN_DP, G.CORE_WIDTH_MAX_DP, "core width")
            assertIn(it.alpha, G.CORE_ALPHA_MIN, 1f, "core alpha")
        }
        val separators = s.band.filter { it.color == CrackColor.BAND_VIOLET_DARK }
        assertTrue(separators.size <= 1)
        separators.forEach {
            assertIn(it.widthDp, G.SEPARATOR_WIDTH_MIN_DP, G.SEPARATOR_WIDTH_MAX_DP, "separator width")
            assertEquals(2, cores.size, "a separator needs two cores")
            val i = s.band.indexOf(it)
            assertTrue(s.band[i - 1] in cores && s.band[i + 1] in cores, "separator sits between the cores")
        }
        val fringes = s.band - cores.toSet() - separators.toSet()
        assertTrue(fringes.size in G.FRINGE_COUNT_MIN..G.FRINGE_COUNT_MAX, "fringes = ${fringes.size}")
        fringes.forEach {
            assertIn(it.widthDp, G.FRINGE_WIDTH_MIN_DP, G.FRINGE_WIDTH_MAX_DP, "fringe width")
            assertIn(it.alpha, G.FRINGE_ALPHA_MIN, 1f, "fringe alpha")
            assertTrue(
                it.color in setOf(CrackColor.BAND_PINK, CrackColor.BAND_GREEN, CrackColor.BAND_CYAN, CrackColor.BAND_LAVENDER),
            )
        }
        // Pink only ever on the top edge.
        s.band.drop(1).forEach { assertNotEquals(CrackColor.BAND_PINK, it.color) }
    }

    @Test
    fun `sub-lines are ordered and overlap by at most the limit`() = specs.forEach { s ->
        s.band.zipWithNext { a, b ->
            val aBottom = a.offsetDp + a.widthDp / 2f
            val bTop = b.offsetDp - b.widthDp / 2f
            assertTrue(bTop - aBottom >= -G.MAX_OVERLAP_DP - eps, "overlap ${aBottom - bTop} in seed ${s.seed}")
            assertTrue(b.offsetDp > a.offsetDp)
        }
    }

    @Test
    fun `pink tops the band about 70 percent of the time`() {
        val pinkTop = specs.count { it.band.first().color == CrackColor.BAND_PINK }
        assertTrue(pinkTop in 630..770, "pink on top in $pinkTop of 1000")
    }

    @Test
    fun `trails in range`() = specs.forEach { s ->
        val lower = s.lowerTrail
        assertIn(lower.widthDp, G.LOWER_WIDTH_MIN_DP, G.LOWER_WIDTH_MAX_DP, "lower width")
        assertTrue(lower.color == CrackColor.TRAIL_YELLOW || lower.color == CrackColor.TRAIL_WHITE)
        assertTrue(lower.coreColor == null || lower.coreColor == CrackColor.TRAIL_WHITE)
        assertIn(lower.offsetDp, 0f, G.LOWER_OFFSET_MAX_DP, "lower offset")
        assertIn(lower.alpha, G.LOWER_ALPHA_MIN, 1f, "lower alpha")
        s.upperTrail?.let { upper ->
            assertEquals(CrackColor.TRAIL_DARK, upper.color)
            assertIn(upper.widthDp, G.UPPER_WIDTH_MIN_DP, G.UPPER_WIDTH_MAX_DP, "upper width")
            assertIn(upper.alpha, G.UPPER_ALPHA_MIN, G.UPPER_ALPHA_MAX, "upper alpha")
        }
    }

    @Test
    fun `trail odds match the spec`() {
        val yellow = specs.count { it.lowerTrail.color == CrackColor.TRAIL_YELLOW }
        val upper = specs.count { it.upperTrail != null }
        assertTrue(yellow in 700..800, "yellow $yellow")
        assertTrue(upper in 750..850, "upper $upper")
    }

    @Test
    fun `blob in range`() = specs.forEach { s ->
        val b = s.blob
        assertEquals(G.RADIAL_COUNT, b.radial.size)
        b.radial.forEach { assertIn(it, G.RADIAL_MIN, G.RADIAL_MAX, "radial") }
        assertIn(b.rimWidthDp, G.RIM_WIDTH_MIN_DP, G.RIM_WIDTH_MAX_DP, "rim")
        val small = b.radiusAcrossDp <= G.SMALL_ACROSS_MAX_DP
        if (small) {
            assertIn(b.radiusAcrossDp, G.SMALL_ACROSS_MIN_DP, G.SMALL_ACROSS_MAX_DP, "small across")
            assertIn(b.radiusAlongDp / b.radiusAcrossDp, G.SMALL_ELONGATION_MIN, G.SMALL_ELONGATION_MAX, "small elongation")
            assertTrue(b.spikes.size <= G.SMALL_SPIKES_MAX)
            assertTrue(b.speckles.isEmpty())
        } else {
            assertIn(b.radiusAcrossDp, G.LARGE_ACROSS_MIN_DP, G.LARGE_ACROSS_MAX_DP, "large across")
            assertIn(b.radiusAlongDp / b.radiusAcrossDp, G.LARGE_ELONGATION_MIN, G.LARGE_ELONGATION_MAX, "large elongation")
            assertTrue(b.spikes.size in G.LARGE_SPIKES_MIN..G.LARGE_SPIKES_MAX)
            assertTrue(b.speckles.size in G.SPECKLES_MIN..G.SPECKLES_MAX)
            b.speckles.forEach {
                assertIn(it.radiusDp, G.SPECKLE_RADIUS_MIN_DP, G.SPECKLE_RADIUS_MAX_DP, "speckle radius")
                assertTrue(it.color == CrackColor.SPECK_LIGHT || it.color == CrackColor.SPECK_BLUE)
                // Inside the blob's ellipse.
                val e = (it.dxDp / b.radiusAcrossDp).let { x -> x * x } + (it.dyDp / b.radiusAlongDp).let { y -> y * y }
                assertTrue(e <= 1f)
            }
        }
        b.spikes.forEach {
            assertIn(it.angleDeg, 0f, 360f, "spike angle")
            assertIn(it.lengthDp / b.radiusAcrossDp, G.SPIKE_LENGTH_MIN, G.SPIKE_LENGTH_MAX, "spike length")
        }
    }

    @Test
    fun `blob is elongated along the hinge`() = specs.forEach { s ->
        assertTrue(s.blob.radiusAlongDp >= s.blob.radiusAcrossDp - eps)
    }

    @Test
    fun `small blobs are about three in four`() {
        val small = specs.count { it.blob.radiusAcrossDp <= G.SMALL_ACROSS_MAX_DP }
        assertTrue(small in 700..800, "small $small")
    }

    @Test
    fun `band thickness follows the blob size`() = specs.forEach { s ->
        val top = s.band.minOf { it.offsetDp - it.widthDp / 2f }
        val bottom = s.band.maxOf { it.offsetDp + it.widthDp / 2f }
        if (s.blob.radiusAcrossDp <= G.SMALL_ACROSS_MAX_DP) {
            assertIn(bottom - top, G.BAND_THICKNESS_MIN_DP, G.BAND_WITH_SMALL_BLOB_MAX_DP, "band with a small blob")
        } else {
            assertIn(bottom - top, G.BAND_WITH_LARGE_BLOB_MIN_DP, G.BAND_THICKNESS_MAX_DP, "band with a large blob")
        }
    }

    @Test
    fun `no spike points into the band`() = specs.flatMap { it.blob.spikes }.forEach {
        // Angles between the two along-hinge cones on the band side (sin > 0) never occur.
        val bandSide = (G.SPIKE_ALONG_HINGE_SPREAD_DEG + eps)..(180f - G.SPIKE_ALONG_HINGE_SPREAD_DEG - eps)
        assertTrue(it.angleDeg !in bandSide, "angle ${it.angleDeg}")
    }

    @Test
    fun `large blob spikes mostly run along the hinge`() {
        val spikes = specs.flatMap { it.blob.spikes }
        val alongHinge = spikes.count {
            val a = it.angleDeg
            a <= G.SPIKE_ALONG_HINGE_SPREAD_DEG || a >= 360f - G.SPIKE_ALONG_HINGE_SPREAD_DEG ||
                kotlin.math.abs(a - 180f) <= G.SPIKE_ALONG_HINGE_SPREAD_DEG
        }
        assertTrue(alongHinge.toFloat() / spikes.size > 0.7f)
    }
}
