package com.crsmthw.hingegate.domain

/**
 * Where the panel-sized part of a wider captured wallpaper is taken from (docs/SWAP-MODE.md §10.35): the middle
 * ([CENTER], Samsung, as built), or the start or end of the line ([START] left, [END] right, for a phone whose
 * wallpaper server shows a wide image from its start).
 */
enum class CropAnchor { CENTER, START, END }

/**
 * How the Real wallpaper mode frames the captured wallpaper for its cracked copy (docs/SWAP-MODE.md §10.35).
 *
 * Samsung: the captured file is the inner panel's own crop (one wallpaper per panel), almost always exactly the
 * panel's size; it is centre-cropped as since build 1 ([CropAnchor.CENTER]).
 *
 * Elsewhere (one home wallpaper for both screens, Android 15+ multi-crop): the captured file is the union of the
 * screens' crops (a 4000 × 3000 photo became 2798 × 2208 on the Pixel Fold emulator, wider than the inner panel's
 * 1.2), and the inner screen shows a wide image from its START: measured left-to-right on the Pixel launcher's
 * first home page of the Pixel Fold emulator, a centre line at x 1325.6 of the 2798-wide file landed at screen
 * x 1105 = 1325.6 × 1840 / 2208. A centre crop moved the picture 68 px left while the crack showed. The
 * right-to-left branch ([CropAnchor.END]) mirrors it by assumption: not tested. An image narrower than the panel
 * is cut top and bottom evenly (the server's own crop for the 1728 × 1920 on-device default was
 * (0, 239, 1728, 1679)). The crop has the panel's exact shape, so the cracked copy has no parallax room and its
 * crack stays on the hinge on every home page.
 */
object SwapFraming {

    /** The anchor for this phone: Samsung keeps the centre; elsewhere the start of the line ([rtl] flips it). */
    fun anchor(isSamsung: Boolean, rtl: Boolean): CropAnchor = when {
        isSamsung -> CropAnchor.CENTER
        rtl -> CropAnchor.END
        else -> CropAnchor.START
    }

    /**
     * The part of a [srcW] × [srcH] image with the shape of [dstW] × [dstH]: the full height of a wider image, taken
     * at [anchor] across; the full width of a narrower one, centred top to bottom. [CropAnchor.CENTER] is exactly
     * [WallpaperChoice.centerCrop].
     */
    fun crop(srcW: Int, srcH: Int, dstW: Int, dstH: Int, anchor: CropAnchor): CropRect {
        val centred = WallpaperChoice.centerCrop(srcW, srcH, dstW, dstH)
        if (anchor == CropAnchor.CENTER || centred.left <= 0f) return centred
        val width = centred.right - centred.left
        return when (anchor) {
            CropAnchor.START -> CropRect(0f, centred.top, width, centred.bottom)
            CropAnchor.END -> CropRect(srcW - width, centred.top, srcW.toFloat(), centred.bottom)
            CropAnchor.CENTER -> centred
        }
    }
}
