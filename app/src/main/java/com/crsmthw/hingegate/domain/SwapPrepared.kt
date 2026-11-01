package com.crsmthw.hingegate.domain

/**
 * What a prepared image's pixels come from besides the frame and the crack: the installed build ([stamp], its
 * install time — the renderer may change between builds while the version code stays the same), the captured
 * original's id, and the original FILE (its length and modification time: a recapture writes a new file).
 */
data class PreparedSource(val stamp: Long, val captureId: Int, val originalLength: Long, val originalModifiedMs: Long)

/**
 * One image to prepare under [name]: the clean copy when [spec] is null, else the original with that crack; the
 * original's panel-sized part taken at [anchor] (docs/SWAP-MODE.md §10.35).
 */
data class PreparedImage(val name: String, val spec: CrackSpec?, val anchor: CropAnchor = CropAnchor.CENTER)

/** The images to prepare now, in order, and every file name that may stay on disk (the rest are deleted). */
data class PreparePlan(val toPrepare: List<PreparedImage>, val keep: Set<String>)

/**
 * The Real wallpaper mode's prepared images (docs/SWAP-MODE.md §10.28): the clean copy and the NEXT crack's copy,
 * rendered and encoded while the inner screen is in use, so the fold only hands a finished file to the system.
 *
 * A file's name is its whole key — the install, the original (id, length, time), the frame and the crack's seed
 * — so a stale image is never found: a recapture, a frame change or a new crack all look up a name that does not
 * exist yet, and the set falls back to rendering on the spot.
 */
object SwapPrepared {

    const val CLEAN_PREFIX = "clean_"
    const val CRACKED_PREFIX = "cracked_"
    const val SUFFIX = ".png"
    const val TEMP_MARK = ".tmp-"

    /** What the wallpaper server writes its crop as, and the only format streamed as it is. */
    const val PNG_MIME = "image/png"

    /** A prepare writes a full panel in a few seconds at most; a temporary file this old has no writer left. */
    const val TEMP_MAX_AGE_MS = 10 * 60 * 1000L

    /**
     * The clean copy: the original cropped to the panel at [anchor] (the centre: Samsung, as named since build 2).
     * The hinge does not change its pixels.
     */
    fun cleanName(source: PreparedSource, frame: SwapFrame, anchor: CropAnchor = CropAnchor.CENTER): String =
        "$CLEAN_PREFIX${sourceKey(source)}_${frame.width}x${frame.height}${anchorKey(anchor)}$SUFFIX"

    /**
     * The original with the crack of [seed], for [frame] (size, hinge position and thickness, density), cropped at
     * [anchor] (the centre adds nothing to the name, so Samsung's names are as before).
     */
    fun crackedName(source: PreparedSource, frame: SwapFrame, seed: Long, anchor: CropAnchor = CropAnchor.CENTER): String =
        "$CRACKED_PREFIX${sourceKey(source)}_${frameKey(frame)}${anchorKey(anchor)}_${seed.toULong().toString(HEX)}$SUFFIX"

    /** Whether [name] is a finished prepared image (not a temporary file). */
    fun isPreparedName(name: String): Boolean =
        name.endsWith(SUFFIX) && (name.startsWith(CLEAN_PREFIX) || name.startsWith(CRACKED_PREFIX))

    /** The temporary file an image is written to before it is renamed to [name]; [nonce] keeps two writers apart. */
    fun tempName(name: String, nonce: Long): String = "$name$TEMP_MARK${nonce.toULong().toString(HEX)}"

    /**
     * Whether a file in the prepared folder goes: its name (a temporary file's: the name it is for) is not in
     * [keep], or it is a temporary file older than [TEMP_MAX_AGE_MS] (its writer is gone). A temporary file for a
     * kept name is left alone otherwise: a prepare may be writing it right now.
     */
    fun shouldDelete(fileName: String, keep: Set<String>, ageMs: Long): Boolean {
        val base = fileName.substringBefore(TEMP_MARK)
        val temporary = base != fileName
        return base !in keep || (temporary && ageMs > TEMP_MAX_AGE_MS)
    }

    /**
     * What to prepare and what to keep.
     * - Nothing captured, no frame, or the mode off with nothing of ours to put back: nothing is kept (they are
     *   copies of the user's wallpaper).
     * - The mode off with a crack still to put back ([restorePending]): only the clean copy is kept.
     * - Otherwise the clean copy (unless the original's own bytes serve as it, [cleanIsOriginal]) and the crack
     *   of every [upcoming] spec — the pending roll's while folded, the pre-chosen next crack — are prepared in
     *   that order, but only while [prepareAllowed] (no stale or suspected capture: nothing new is made from an
     *   image the user may have replaced). The cracks [showing] (active or staged) are kept, never re-made: a set
     *   made late after an unfold can still use them.
     * Every image is cropped at [anchor] (docs/SWAP-MODE.md §10.35), which is part of its name.
     */
    fun plan(
        source: PreparedSource?,
        frame: SwapFrame?,
        swapOn: Boolean,
        restorePending: Boolean,
        prepareAllowed: Boolean,
        cleanIsOriginal: Boolean,
        upcoming: List<CrackSpec>,
        showing: List<Long>,
        anchor: CropAnchor = CropAnchor.CENTER,
    ): PreparePlan {
        if (source == null || frame == null) return PreparePlan(emptyList(), emptySet())
        val clean = PreparedImage(cleanName(source, frame, anchor), null, anchor).takeUnless { cleanIsOriginal }
        if (!swapOn) {
            return PreparePlan(emptyList(), if (restorePending && clean != null) setOf(clean.name) else emptySet())
        }
        val cracks = upcoming.distinctBy { it.seed }.map { PreparedImage(crackedName(source, frame, it.seed, anchor), it, anchor) }
        val wanted = listOfNotNull(clean) + cracks
        val keep = wanted.map { it.name }.toSet() + showing.map { crackedName(source, frame, it, anchor) }
        return PreparePlan(if (prepareAllowed) wanted else emptyList(), keep)
    }

    /**
     * [plan] from the swapper's own state (what the next fold may need): the upcoming cracks are the pending roll's
     * (present only for a hit) and the pre-chosen [nextSpec], in that order; the cracks showing are the [active]
     * one and the staged one; a crack still staged is a put-back pending; nothing new is made unless the capture is
     * trusted ([SwapState.hasOriginal]: no stale or suspected capture); the frame is the state's.
     */
    fun planFor(
        source: PreparedSource?,
        swapOn: Boolean,
        cleanIsOriginal: Boolean,
        state: SwapState,
        pending: PendingRoll?,
        nextSpec: CrackSpec?,
        active: ActiveCrack?,
        anchor: CropAnchor = CropAnchor.CENTER,
    ): PreparePlan = plan(
        source = source,
        frame = state.frame,
        swapOn = swapOn,
        restorePending = state.staged is Staged.Cracked,
        prepareAllowed = state.hasOriginal,
        cleanIsOriginal = cleanIsOriginal,
        upcoming = listOfNotNull(pending?.spec, nextSpec),
        showing = listOfNotNull(active?.spec?.seed, (state.staged as? Staged.Cracked)?.seed),
        anchor = anchor,
    )

    /**
     * Whether the original's own bytes are already the clean copy for [frame]: a PNG (what the wallpaper server
     * writes its crop as; no EXIF rotation to disagree about) of exactly the panel's natural size, [width] ×
     * [height] as stored. Then the clean copy is the original streamed as it is — pixel-identical — and not
     * prepared. Anything else (another format, another size: captured before a resolution change or a frame
     * correction) would be cropped or scaled by the server differently from the rendered clean copy.
     *
     * With one home wallpaper for both screens ([sharedWallpaper]: every phone but Samsung) the original's own
     * bytes are ALWAYS the clean copy, whatever their size or format (docs/SWAP-MODE.md §10.35): the captured file is
     * what the server shows both screens from, and handed back unchanged the server frames it for each screen as it
     * did before. A panel-sized centre crop re-framed a wallpaper not sized to the panel on both screens, for good.
     * [width] and [height] only have to be a real image's (a failed bounds read reports -1 × -1).
     */
    fun originalServesAsClean(mime: String?, width: Int, height: Int, frame: SwapFrame, sharedWallpaper: Boolean = false): Boolean =
        if (sharedWallpaper) {
            width > 0 && height > 0
        } else {
            mime == PNG_MIME && width == frame.width && height == frame.height
        }

    private fun sourceKey(source: PreparedSource): String = listOf(
        source.stamp.toULong().toString(HEX),
        source.captureId.toString(),
        source.originalLength.toString(),
        source.originalModifiedMs.toString(),
    ).joinToString("_")

    /** Exact, not rounded: two frames that differ at all never share a file. */
    private fun frameKey(frame: SwapFrame): String = listOf(
        "${frame.width}x${frame.height}",
        frame.hinge.orientation.name.first().toString(),
        bits(frame.hinge.positionPx),
        bits(frame.hinge.thicknessPx),
        bits(frame.density),
    ).joinToString("_")

    /** Nothing for the centre (Samsung, names as before), else the anchor's initial. */
    private fun anchorKey(anchor: CropAnchor): String =
        if (anchor == CropAnchor.CENTER) "" else "_${anchor.name.first()}"

    private fun bits(value: Float): String = value.toRawBits().toUInt().toString(HEX)

    private const val HEX = 16
}
