package com.crsmthw.hingegate.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WallpaperChoiceTest {

    private val both = setOf(ImageSlot.MAIN, ImageSlot.COVER)
    private val coverAspect = 1972f / 1248f
    private val innerAspect = 2448f / 1848f

    private fun slot(posture: Posture, available: Set<ImageSlot>, w: Int = 0, h: Int = 0, learned: Boolean = true) =
        WallpaperChoice.slotFor(
            posture, available, w, h,
            if (learned) coverAspect else null,
            if (learned) innerAspect else null,
        )

    @Test
    fun `unfolded prefers main, falls back to cover`() {
        assertEquals(ImageSlot.MAIN, slot(Posture.UNFOLDED, both))
        assertEquals(ImageSlot.COVER, slot(Posture.UNFOLDED, setOf(ImageSlot.COVER)))
    }

    @Test
    fun `folded prefers cover, falls back to main`() {
        assertEquals(ImageSlot.COVER, slot(Posture.FOLDED, both))
        assertEquals(ImageSlot.MAIN, slot(Posture.FOLDED, setOf(ImageSlot.MAIN)))
    }

    @Test
    fun `no image at all`() = assertNull(slot(Posture.UNFOLDED, emptySet()))

    @Test
    fun `unknown posture goes by the surface shape`() {
        assertEquals(ImageSlot.COVER, slot(Posture.UNKNOWN, both, 1248, 1972))
        assertEquals(ImageSlot.MAIN, slot(Posture.UNKNOWN, both, 2448, 1848))
        assertEquals(ImageSlot.MAIN, slot(Posture.UNKNOWN, both, 1248, 1972, learned = false))
    }

    @Test
    fun `center crop of a wide image into a tall surface`() {
        val crop = WallpaperChoice.centerCrop(4000, 2000, 1000, 2000)
        assertEquals(CropRect(1500f, 0f, 2500f, 2000f), crop)
    }

    @Test
    fun `center crop of a tall image into a wide surface`() {
        val crop = WallpaperChoice.centerCrop(1000, 3000, 2000, 1000)
        assertEquals(CropRect(0f, 1250f, 1000f, 1750f), crop)
    }

    @Test
    fun `sample size keeps the result at least the target`() {
        assertEquals(1, WallpaperChoice.sampleSize(2448, 1848, 2448, 1848))
        assertEquals(2, WallpaperChoice.sampleSize(4896, 3696, 2448, 1848))
        assertEquals(1, WallpaperChoice.sampleSize(800, 600, 2448, 1848))
        // The tighter axis decides: 4000×2000 into 1000×2000 can only be sampled by 1.
        assertEquals(1, WallpaperChoice.sampleSize(4000, 2000, 1000, 2000))
        assertEquals(3, WallpaperChoice.sampleSize(7344, 5544, 2448, 1848))
        assertEquals(1, WallpaperChoice.sampleSize(10, 10, 0, 0))
    }
}
