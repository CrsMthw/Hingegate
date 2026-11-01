package com.crsmthw.hingegate.domain

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwapPreparedTest {

    private val source = PreparedSource(stamp = 1_761_900_000_000, captureId = 79, originalLength = 5_123_456, originalModifiedMs = 1_761_912_345_678)
    private val hinge = HingeLine(HingeOrientation.VERTICAL, 1224f, 0f, HingeSource.WINDOW_MANAGER)
    private val frame = SwapFrame(2448, 1848, hinge, 2.625f)
    private val next = CrackSpecGenerator.generate(Random(1))
    private val pendingSpec = CrackSpecGenerator.generate(Random(2))
    private val showing = CrackSpecGenerator.generate(Random(3))

    private fun plan(
        source: PreparedSource? = this.source,
        frame: SwapFrame? = this.frame,
        swapOn: Boolean = true,
        restorePending: Boolean = false,
        prepareAllowed: Boolean = true,
        cleanIsOriginal: Boolean = false,
        upcoming: List<CrackSpec> = listOf(next),
        showing: List<Long> = emptyList(),
        anchor: CropAnchor = CropAnchor.CENTER,
    ) = SwapPrepared.plan(source, frame, swapOn, restorePending, prepareAllowed, cleanIsOriginal, upcoming, showing, anchor)

    @Test
    fun `names are stable for the same inputs`() {
        assertEquals(SwapPrepared.crackedName(source, frame, 42), SwapPrepared.crackedName(source.copy(), frame.copy(), 42))
        assertEquals(SwapPrepared.cleanName(source, frame), SwapPrepared.cleanName(source, frame))
    }

    @Test
    fun `a stale image is never found under the current name`() {
        val name = SwapPrepared.crackedName(source, frame, 42)
        listOf(
            SwapPrepared.crackedName(source.copy(captureId = 80), frame, 42),
            SwapPrepared.crackedName(source.copy(originalLength = 1), frame, 42),
            SwapPrepared.crackedName(source.copy(originalModifiedMs = 1), frame, 42),
            SwapPrepared.crackedName(source.copy(stamp = 2), frame, 42),
            SwapPrepared.crackedName(source, frame.copy(width = 2447), 42),
            SwapPrepared.crackedName(source, frame.copy(hinge = hinge.copy(positionPx = 1224.5f)), 42),
            SwapPrepared.crackedName(source, frame.copy(hinge = hinge.copy(thicknessPx = 1f)), 42),
            SwapPrepared.crackedName(source, frame.copy(hinge = hinge.copy(orientation = HingeOrientation.HORIZONTAL)), 42),
            SwapPrepared.crackedName(source, frame.copy(density = 3f), 42),
            SwapPrepared.crackedName(source, frame, 43),
            SwapPrepared.crackedName(source, frame, -42),
        ).forEach { assertTrue(it != name, it) }
    }

    @Test
    fun `the clean copy does not depend on the hinge, only on the panel size`() {
        val clean = SwapPrepared.cleanName(source, frame)
        assertEquals(clean, SwapPrepared.cleanName(source, frame.copy(hinge = hinge.copy(source = HingeSource.ESTIMATED, positionPx = 1200f))))
        assertTrue(clean != SwapPrepared.cleanName(source, frame.copy(height = 1840)))
        assertTrue(clean != SwapPrepared.cleanName(source.copy(captureId = 80), frame))
    }

    @Test
    fun `the hinge source alone does not make a new crack image`() {
        assertEquals(
            SwapPrepared.crackedName(source, frame, 42),
            SwapPrepared.crackedName(source, frame.copy(hinge = hinge.copy(source = HingeSource.ESTIMATED)), 42),
        )
    }

    @Test
    fun `names are prepared image names and safe file names`() {
        val names = listOf(SwapPrepared.cleanName(source, frame), SwapPrepared.crackedName(source, frame, Long.MIN_VALUE))
        names.forEach { name ->
            assertTrue(SwapPrepared.isPreparedName(name))
            assertTrue(name.all { it.isLetterOrDigit() || it == '_' || it == '.' }, name)
            assertTrue(name.length < 255)
        }
        assertFalse(SwapPrepared.isPreparedName(SwapPrepared.tempName(names[0], 7)))
        assertFalse(SwapPrepared.isPreparedName("original.img"))
    }

    @Test
    fun `the mode on prepares the clean copy, then the coming cracks, and keeps the cracks showing`() {
        val p = plan(upcoming = listOf(pendingSpec, next, next), showing = listOf(showing.seed))
        assertEquals(
            listOf(
                PreparedImage(SwapPrepared.cleanName(source, frame), null),
                PreparedImage(SwapPrepared.crackedName(source, frame, pendingSpec.seed), pendingSpec),
                PreparedImage(SwapPrepared.crackedName(source, frame, next.seed), next),
            ),
            p.toPrepare,
        )
        assertEquals(p.toPrepare.map { it.name }.toSet() + SwapPrepared.crackedName(source, frame, showing.seed), p.keep)
    }

    @Test
    fun `the original's own bytes stand in for the clean copy`() {
        val p = plan(cleanIsOriginal = true)
        assertEquals(listOf(PreparedImage(SwapPrepared.crackedName(source, frame, next.seed), next)), p.toPrepare)
    }

    @Test
    fun `at a fold the images to make stay the same`() {
        // Folded with a pre-rolled hit: the pending crack IS the next crack, so a running prepare is not replaced.
        assertEquals(plan(upcoming = listOf(next)).toPrepare, plan(upcoming = listOf(next, next)).toPrepare)
    }

    @Test
    fun `nothing new is made from a suspected or stale capture, but its images are kept`() {
        val p = plan(prepareAllowed = false)
        assertTrue(p.toPrepare.isEmpty())
        assertEquals(plan().keep, p.keep)
    }

    @Test
    fun `out of the mode only the clean copy for a pending put-back is kept`() {
        assertEquals(PreparePlan(emptyList(), emptySet()), plan(swapOn = false))
        assertEquals(PreparePlan(emptyList(), setOf(SwapPrepared.cleanName(source, frame))), plan(swapOn = false, restorePending = true))
        assertEquals(PreparePlan(emptyList(), emptySet()), plan(swapOn = false, restorePending = true, cleanIsOriginal = true))
    }

    @Test
    fun `without a capture or a frame everything goes`() {
        assertEquals(PreparePlan(emptyList(), emptySet()), plan(source = null))
        assertEquals(PreparePlan(emptyList(), emptySet()), plan(frame = null))
    }

    @Test
    fun `cleanup deletes what is not kept and spares a temporary file being written`() {
        val kept = SwapPrepared.crackedName(source, frame, 42)
        val gone = SwapPrepared.crackedName(source, frame, 43)
        val keep = setOf(kept)
        assertFalse(SwapPrepared.shouldDelete(kept, keep, ageMs = 0))
        assertTrue(SwapPrepared.shouldDelete(gone, keep, ageMs = 0))
        assertFalse(SwapPrepared.shouldDelete(SwapPrepared.tempName(kept, 1), keep, ageMs = 1_000))
        assertTrue(SwapPrepared.shouldDelete(SwapPrepared.tempName(kept, 1), keep, ageMs = SwapPrepared.TEMP_MAX_AGE_MS + 1))
        assertTrue(SwapPrepared.shouldDelete(SwapPrepared.tempName(gone, 1), keep, ageMs = 0))
        assertTrue(SwapPrepared.shouldDelete("stray.bin", keep, ageMs = 0))
        // A kept file is kept however old it is.
        assertFalse(SwapPrepared.shouldDelete(kept, keep, ageMs = Long.MAX_VALUE))
    }

    @Test
    fun `two writers of the same image use different temporary files`() {
        val name = SwapPrepared.cleanName(source, frame)
        assertTrue(SwapPrepared.tempName(name, 1) != SwapPrepared.tempName(name, 2))
        assertTrue(SwapPrepared.tempName(name, -1).startsWith(name))
    }

    // ── What the swapper hands the plan ──────────────────────────────────────

    private val captured = SwapState(capture = SwapCapture(79, 0L), frame = frame)

    private fun planFor(
        state: SwapState = captured,
        pending: PendingRoll? = null,
        nextSpec: CrackSpec? = next,
        active: ActiveCrack? = null,
        swapOn: Boolean = true,
        cleanIsOriginal: Boolean = false,
        anchor: CropAnchor = CropAnchor.CENTER,
    ) = SwapPrepared.planFor(source, swapOn, cleanIsOriginal, state, pending, nextSpec, active, anchor)

    private val hit = PendingRoll(true, HitReason.ROLLED, 10, 35, 4, 0, pendingSpec)
    private val miss = PendingRoll(false, HitReason.MISSED, 80, 35, 4, 0, null)

    @Test
    fun `while unfolded the clean copy and the pre-chosen next crack are prepared`() {
        val unfolded = planFor(active = ActiveCrack(showing, 0L, CrackCause.UNFOLD), state = captured.copy(staged = Staged.Cracked(showing.seed)))
        assertEquals(listOf(SwapPrepared.cleanName(source, frame), SwapPrepared.crackedName(source, frame, next.seed)), unfolded.toPrepare.map { it.name })
        // The crack showing (active and staged) is kept for a late set, never made again.
        assertTrue(SwapPrepared.crackedName(source, frame, showing.seed) in unfolded.keep)
        assertEquals(unfolded, plan(upcoming = listOf(next), showing = listOf(showing.seed, showing.seed)))
    }

    @Test
    fun `while folded the pending hit's crack comes first, a miss adds nothing`() {
        val folded = planFor(pending = hit)
        assertEquals(
            listOf(SwapPrepared.cleanName(source, frame), SwapPrepared.crackedName(source, frame, pendingSpec.seed), SwapPrepared.crackedName(source, frame, next.seed)),
            folded.toPrepare.map { it.name },
        )
        // A miss carries no crack: only the clean copy and the next crack.
        assertEquals(planFor().toPrepare, planFor(pending = miss).toPrepare)
        // A hit carries the pre-chosen crack itself: it is made once.
        assertEquals(planFor().toPrepare, planFor(pending = hit.copy(spec = next)).toPrepare)
    }

    @Test
    fun `nothing new is made from a stale or suspected capture, but what is there is kept`() {
        listOf(captured.copy(needsRecapture = true), captured.copy(suspectId = 30), captured.copy(frame = null)).forEach { state ->
            assertTrue(planFor(state = state).toPrepare.isEmpty(), "$state")
        }
        assertEquals(planFor().keep, planFor(state = captured.copy(suspectId = 30)).keep)
    }

    @Test
    fun `a crack still staged keeps the clean copy for the put-back when the mode is off`() {
        val staged = captured.copy(staged = Staged.Cracked(showing.seed))
        assertEquals(setOf(SwapPrepared.cleanName(source, frame)), planFor(state = staged, swapOn = false).keep)
        assertTrue(planFor(state = captured, swapOn = false).keep.isEmpty())
        // The original's own bytes serve as the clean copy: nothing to keep for the put-back, nothing to prepare.
        assertTrue(planFor(state = staged, swapOn = false, cleanIsOriginal = true).keep.isEmpty())
        assertEquals(listOf(SwapPrepared.crackedName(source, frame, next.seed)), planFor(cleanIsOriginal = true).toPrepare.map { it.name })
    }

    @Test
    fun `the original serves as the clean copy only as a png of exactly the panel's size`() {
        assertTrue(SwapPrepared.originalServesAsClean("image/png", 2448, 1848, frame))
        listOf("image/jpeg", "image/webp", "image/heif", null).forEach {
            assertFalse(SwapPrepared.originalServesAsClean(it, 2448, 1848, frame), "$it")
        }
        assertFalse(SwapPrepared.originalServesAsClean("image/png", 2447, 1848, frame))
        assertFalse(SwapPrepared.originalServesAsClean("image/png", 2448, 1849, frame))
        // The panel turned: the server would crop it differently.
        assertFalse(SwapPrepared.originalServesAsClean("image/png", 1848, 2448, frame))
        // A bounds read that failed reports -1 × -1.
        assertFalse(SwapPrepared.originalServesAsClean("image/png", -1, -1, frame))
    }

    @Test
    fun `with one wallpaper for both screens the original's own bytes are always the clean copy`() {
        // Lane B, B14f: a 4000 x 3000 photo's captured file was 2798 x 2208; any size, any format.
        listOf("image/png", "image/jpeg", "image/webp", null).forEach {
            assertTrue(SwapPrepared.originalServesAsClean(it, 2798, 2208, frame, sharedWallpaper = true), "$it")
        }
        assertTrue(SwapPrepared.originalServesAsClean("image/png", 2448, 1848, frame, sharedWallpaper = true))
        assertTrue(SwapPrepared.originalServesAsClean("image/png", 1728, 1920, frame, sharedWallpaper = true))
        // Only a real image: a failed bounds read is not one.
        assertFalse(SwapPrepared.originalServesAsClean("image/png", -1, -1, frame, sharedWallpaper = true))
        assertFalse(SwapPrepared.originalServesAsClean(null, 0, 0, frame, sharedWallpaper = true))
        // Samsung (one wallpaper per panel) keeps the exact-size PNG rule.
        assertFalse(SwapPrepared.originalServesAsClean("image/png", 2798, 2208, frame, sharedWallpaper = false))
    }

    @Test
    fun `the crop anchor is part of a prepared image's name and travels with it`() {
        // The centre adds nothing: Samsung's names are as before.
        assertEquals(SwapPrepared.crackedName(source, frame, 42), SwapPrepared.crackedName(source, frame, 42, CropAnchor.CENTER))
        assertEquals(SwapPrepared.cleanName(source, frame), SwapPrepared.cleanName(source, frame, CropAnchor.CENTER))
        val names = CropAnchor.entries.map { SwapPrepared.crackedName(source, frame, 42, it) }
        assertEquals(names.size, names.toSet().size)
        val cleanNames = CropAnchor.entries.map { SwapPrepared.cleanName(source, frame, it) }
        assertEquals(cleanNames.size, cleanNames.toSet().size)
        (names + cleanNames).forEach { assertTrue(SwapPrepared.isPreparedName(it), it) }
        val p = plan(anchor = CropAnchor.START, showing = listOf(showing.seed))
        assertEquals(
            listOf(
                PreparedImage(SwapPrepared.cleanName(source, frame, CropAnchor.START), null, CropAnchor.START),
                PreparedImage(SwapPrepared.crackedName(source, frame, next.seed, CropAnchor.START), next, CropAnchor.START),
            ),
            p.toPrepare,
        )
        assertTrue(SwapPrepared.crackedName(source, frame, showing.seed, CropAnchor.START) in p.keep)
        assertEquals(
            planFor(anchor = CropAnchor.END).toPrepare.map { it.anchor }.toSet(),
            setOf(CropAnchor.END),
        )
    }
}
