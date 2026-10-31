package com.crsmthw.hingegate.domain

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostureResolverTest {

    // Fold 8 (SM-F971B): cover 1248×1972, inner 2448×1848 — physical sizes, rotation-invariant.
    private val coverW = 1248
    private val coverH = 1972
    private val innerW = 2448
    private val innerH = 1848
    private val cover = coverW.toLong() * coverH
    private val inner = innerW.toLong() * innerH

    private fun MutableList<Resolution>.feedDisplay(memory: PostureMemory, area: Long): PostureMemory {
        val (w, h) = if (area == cover) coverW to coverH else innerW to innerH
        val r = PostureResolver.onDisplay(memory, area, w, h)
        add(r)
        return r.memory
    }

    @Test
    fun `first cycle after install, installed while folded`() {
        val results = mutableListOf<Resolution>()
        var m = PostureMemory()
        m = results.feedDisplay(m, cover)
        assertEquals(Posture.UNKNOWN, results.last().posture)
        assertEquals(PostureAuthority.NONE, results.last().authority)
        assertNull(results.last().transition)

        // The first unfold is also when the large panel is learned: still a transition.
        m = results.feedDisplay(m, inner)
        assertEquals(Posture.UNFOLDED, results.last().posture)
        assertEquals(PostureAuthority.DISPLAY_SIZE, results.last().authority)
        assertEquals(Transition.UNFOLDED, results.last().transition)
        assertEquals(inner, m.largeArea)
        assertEquals(cover, m.smallArea)
        assertEquals(coverW, m.smallWidth)
        assertEquals(coverH, m.smallHeight)
        assertEquals(innerW, m.largeWidth)
        assertEquals(innerH, m.largeHeight)

        m = results.feedDisplay(m, cover)
        assertEquals(Transition.FOLDED, results.last().transition)
        assertEquals(Posture.FOLDED, results.last().posture)

        results.feedDisplay(m, inner)
        assertEquals(Transition.UNFOLDED, results.last().transition)
    }

    @Test
    fun `first cycle after install, installed while unfolded`() {
        val results = mutableListOf<Resolution>()
        var m = results.feedDisplay(PostureMemory(), inner)
        assertEquals(Posture.UNKNOWN, results.last().posture)

        m = results.feedDisplay(m, cover)
        assertEquals(Posture.FOLDED, results.last().posture)
        assertEquals(Transition.FOLDED, results.last().transition)
        assertEquals(cover, m.smallArea)
        assertEquals(coverW, m.smallWidth)

        results.feedDisplay(m, inner)
        assertEquals(Transition.UNFOLDED, results.last().transition)
    }

    @Test
    fun `repeated callbacks for the same panel produce no transition`() {
        val results = mutableListOf<Resolution>()
        var m = results.feedDisplay(PostureMemory(), cover)
        m = results.feedDisplay(m, inner)
        assertEquals(Transition.UNFOLDED, results.last().transition)
        repeat(5) {
            m = results.feedDisplay(m, inner)
            assertNull(results.last().transition)
            assertEquals(Posture.UNFOLDED, results.last().posture)
        }
        m = results.feedDisplay(m, cover)
        assertEquals(Transition.FOLDED, results.last().transition)
        repeat(3) {
            m = results.feedDisplay(m, cover)
            assertNull(results.last().transition)
        }
    }

    @Test
    fun `restart while unfolded from persisted memory is not a transition`() {
        val results = mutableListOf<Resolution>()
        var m = results.feedDisplay(PostureMemory(), cover)
        m = results.feedDisplay(m, inner)
        val restored = Json.decodeFromString(PostureMemory.serializer(), Json.encodeToString(PostureMemory.serializer(), m))
        assertEquals(m, restored)

        results.feedDisplay(restored, inner)
        assertEquals(Posture.UNFOLDED, results.last().posture)
        assertNull(results.last().transition)
    }

    @Test
    fun `restart while folded, then unfold, is a transition`() {
        val results = mutableListOf<Resolution>()
        var m = results.feedDisplay(PostureMemory(), cover)
        m = results.feedDisplay(m, inner)
        m = results.feedDisplay(m, cover)
        val restored = Json.decodeFromString(PostureMemory.serializer(), Json.encodeToString(PostureMemory.serializer(), m))

        m = results.feedDisplay(restored, cover)
        assertNull(results.last().transition)
        assertEquals(Posture.FOLDED, results.last().posture)
        results.feedDisplay(m, inner)
        assertEquals(Transition.UNFOLDED, results.last().transition)
    }

    @Test
    fun `small area changes below the learning ratio do not learn a new panel`() {
        var m = PostureResolver.onDisplay(PostureMemory(), cover, coverW, coverH).memory
        m = PostureResolver.onDisplay(m, (cover * 1.1).toLong(), coverW, coverH).memory
        assertEquals(cover, m.largeArea)
        assertFalse(m.displayAuthoritative)
    }

    @Test
    fun `window manager only device`() {
        // Display 0 never changes size: the area is always the same.
        val same = inner
        var r = PostureResolver.onDisplay(PostureMemory(), same, innerW, innerH)
        assertEquals(Posture.UNKNOWN, r.posture)

        // A "no feature" before any feature was ever seen tells nothing.
        r = PostureResolver.onWindowManager(r.memory, featurePresent = false)
        assertEquals(Posture.UNKNOWN, r.posture)
        assertFalse(r.memory.wmCapable)

        // The first feature ever seen makes WM capable but is not a transition from UNKNOWN.
        r = PostureResolver.onWindowManager(r.memory, featurePresent = true)
        assertTrue(r.memory.wmCapable)
        assertEquals(Posture.UNFOLDED, r.posture)
        assertEquals(PostureAuthority.WINDOW_MANAGER, r.authority)
        assertNull(r.transition)

        r = PostureResolver.onWindowManager(r.memory, featurePresent = false)
        assertEquals(Posture.FOLDED, r.posture)
        assertEquals(Transition.FOLDED, r.transition)

        r = PostureResolver.onWindowManager(r.memory, featurePresent = true)
        assertEquals(Transition.UNFOLDED, r.transition)

        // A display callback in between keeps the WM posture and transitions nothing.
        r = PostureResolver.onDisplay(r.memory, same, innerW, innerH)
        assertEquals(Posture.UNFOLDED, r.posture)
        assertEquals(PostureAuthority.WINDOW_MANAGER, r.authority)
        assertNull(r.transition)
    }

    @Test
    fun `window manager updates are ignored for transitions under display authority`() {
        var m = PostureResolver.onDisplay(PostureMemory(), cover, coverW, coverH).memory
        m = PostureResolver.onDisplay(m, inner, innerW, innerH).memory
        var r = PostureResolver.onWindowManager(m, featurePresent = false)
        assertNull(r.transition)
        assertEquals(PostureAuthority.DISPLAY_SIZE, r.authority)
        assertEquals(Posture.UNFOLDED, r.posture)
        assertEquals(false, r.memory.lastWmPresent)
        r = PostureResolver.onWindowManager(r.memory, featurePresent = true)
        assertNull(r.transition)
        assertTrue(r.memory.wmCapable)
    }

    @Test
    fun `last posture is kept while unknown`() {
        val r = PostureResolver.onDisplay(PostureMemory(), cover, coverW, coverH)
        assertEquals(Posture.UNKNOWN, r.memory.lastPosture)
        assertEquals(cover, r.memory.lastArea)
    }

    @Test
    fun `cover aspect comes from the small panel`() {
        var m = PostureResolver.onDisplay(PostureMemory(), inner, innerW, innerH).memory
        m = PostureResolver.onDisplay(m, cover, coverW, coverH).memory
        assertEquals(1972f / 1248f, m.coverAspect!!, 1e-4f)
    }
}
