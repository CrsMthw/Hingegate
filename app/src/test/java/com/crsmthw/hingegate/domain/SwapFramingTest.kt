package com.crsmthw.hingegate.domain

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwapFramingTest {

    @Test
    fun `samsung keeps the centre, every other phone the start of the line`() {
        assertEquals(CropAnchor.CENTER, SwapFraming.anchor(isSamsung = true, rtl = false))
        assertEquals(CropAnchor.CENTER, SwapFraming.anchor(isSamsung = true, rtl = true))
        assertEquals(CropAnchor.START, SwapFraming.anchor(isSamsung = false, rtl = false))
        assertEquals(CropAnchor.END, SwapFraming.anchor(isSamsung = false, rtl = true))
    }

    @Test
    fun `the centre is exactly build 3's centre crop`() {
        listOf(2798 to 2208, 1728 to 1920, 2208 to 1840, 4000 to 3000, 2448 to 1848).forEach { (w, h) ->
            assertEquals(WallpaperChoice.centerCrop(w, h, 2208, 1840), SwapFraming.crop(w, h, 2208, 1840, CropAnchor.CENTER))
            assertEquals(WallpaperChoice.centerCrop(w, h, 2448, 1848), SwapFraming.crop(w, h, 2448, 1848, CropAnchor.CENTER))
        }
    }

    @Test
    fun `a wider image is cut at the start or the end, full height`() {
        // Lane B, B14f: STILL-3's captured file on the Pixel Fold emulator, for the 2208 x 1840 inner panel.
        val start = SwapFraming.crop(2798, 2208, 2208, 1840, CropAnchor.START)
        assertEquals(0f, start.left)
        assertEquals(0f, start.top)
        assertEquals(2208f, start.bottom)
        assertEquals(2208f * 2208f / 1840f, start.right, 0.01f)
        val end = SwapFraming.crop(2798, 2208, 2208, 1840, CropAnchor.END)
        assertEquals(2798f, end.right)
        assertEquals(start.right - start.left, end.right - end.left, 0.01f)
    }

    @Test
    fun `the start crop keeps the picture where the pixel showed it`() {
        // The image's centre line sat at x 1325.6 of the 2798-wide file and at screen x 1105 on the inner baseline;
        // the centre crop moved it to 1043 (1037 after the launcher's 1.1 zoom about the middle): 68 px off.
        val crop = SwapFraming.crop(2798, 2208, 2208, 1840, CropAnchor.START)
        val onScreen = (1325.6f - crop.left) * 2208f / (crop.right - crop.left)
        assertTrue(abs(onScreen - 1105f) < 1.5f, "$onScreen")
        val centre = SwapFraming.crop(2798, 2208, 2208, 1840, CropAnchor.CENTER)
        val centred = (1325.6f - centre.left) * 2208f / (centre.right - centre.left)
        assertTrue(abs(centred - 1043f) < 1.5f, "$centred")
    }

    @Test
    fun `a narrower image is cut top and bottom evenly whatever the anchor`() {
        // The Pixel's own crop of its 1728 x 1920 on-device default for the inner panel: (0, 239, 1728, 1679).
        CropAnchor.entries.forEach { anchor ->
            val crop = SwapFraming.crop(1728, 1920, 2208, 1840, anchor)
            assertEquals(0f, crop.left)
            assertEquals(1728f, crop.right)
            assertEquals(240f, crop.top, 0.01f)
            assertEquals(1680f, crop.bottom, 0.01f)
        }
    }

    @Test
    fun `an image of the panel's shape is used whole`() {
        CropAnchor.entries.forEach { anchor ->
            listOf(2208 to 1840, 4416 to 3680).forEach { (w, h) ->
                val c = SwapFraming.crop(w, h, 2208, 1840, anchor)
                listOf(0f to c.left, 0f to c.top, w.toFloat() to c.right, h.toFloat() to c.bottom).forEach { (want, got) ->
                    assertEquals(want, got, 0.01f, "$w x $h $anchor $c")
                }
            }
        }
    }

    @Test
    fun `a crop always has the panel's shape and stays inside the image`() {
        val sizes = listOf(2798 to 2208, 4000 to 3000, 1080 to 2092, 3000 to 1000, 1000 to 3000, 2208 to 1840)
        sizes.forEach { (w, h) ->
            CropAnchor.entries.forEach { anchor ->
                val c = SwapFraming.crop(w, h, 2208, 1840, anchor)
                assertTrue(c.left >= 0f && c.top >= 0f && c.right <= w + 0.01f && c.bottom <= h + 0.01f, "$w x $h $anchor $c")
                assertEquals(2208f / 1840f, (c.right - c.left) / (c.bottom - c.top), 0.001f)
            }
        }
    }
}
