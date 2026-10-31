package com.crsmthw.hingegate.swap

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.crsmthw.hingegate.crack.CrackController
import com.crsmthw.hingegate.crack.CrackState
import com.crsmthw.hingegate.data.CrackLog
import com.crsmthw.hingegate.data.CrackMode
import com.crsmthw.hingegate.data.SettingsRepository
import com.crsmthw.hingegate.data.SwapRepository
import com.crsmthw.hingegate.domain.IdAction
import com.crsmthw.hingegate.domain.IdCheck
import com.crsmthw.hingegate.domain.LogEntry
import com.crsmthw.hingegate.domain.LogKind
import com.crsmthw.hingegate.domain.LogPhrase
import com.crsmthw.hingegate.domain.Posture
import com.crsmthw.hingegate.domain.PreparePlan
import com.crsmthw.hingegate.domain.PreparedSource
import com.crsmthw.hingegate.domain.RecaptureOutcome
import com.crsmthw.hingegate.domain.SettleKey
import com.crsmthw.hingegate.domain.SettleMark
import com.crsmthw.hingegate.domain.Sighting
import com.crsmthw.hingegate.domain.Staged
import com.crsmthw.hingegate.domain.SwapAction
import com.crsmthw.hingegate.domain.SwapCapture
import com.crsmthw.hingegate.domain.SwapFailure
import com.crsmthw.hingegate.domain.SwapFlags
import com.crsmthw.hingegate.domain.SwapFrame
import com.crsmthw.hingegate.domain.SwapFrames
import com.crsmthw.hingegate.domain.SwapIds
import com.crsmthw.hingegate.domain.SwapInput
import com.crsmthw.hingegate.domain.SwapPlanner
import com.crsmthw.hingegate.domain.SwapPrepared
import com.crsmthw.hingegate.domain.SwapSettle
import com.crsmthw.hingegate.domain.SwapState
import com.crsmthw.hingegate.posture.PostureMonitor
import com.crsmthw.hingegate.posture.PostureSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * The Real wallpaper mode (docs/SWAP-MODE.md §3): puts the user's own wallpaper, with or without the crack, on the
 * inner screen's home wallpaper. A process singleton in the AppContainer, started with the app.
 *
 * It applies a desired STATE, decided by [SwapPlanner] from the controller's consistent [CrackState] and the
 * settings: every change (settings, controller, display, a wallpaper change, the app coming to the front) asks
 * for one reconcile, reconciles are serialized by a lock and conflated (rapid changes collapse into one). The
 * images are prepared while the inner screen is in use (§10.28), so a set at the fold only streams a finished file
 * to the system; with nothing prepared it renders on the spot, as before.
 *
 * Safety rules, because this replaces the user's wallpaper:
 * - On Samsung every read names the inner panel (`FLAG_SYSTEM | 4`, §1), and every decision taken from a read
 *   waits for a SETTLED inner state ([SwapSettle], §10.24): a trigger that comes earlier schedules one check for
 *   the end of the settle period.
 * - An id that is neither ours nor the captured one is only SUSPECTED at first: nothing is staged from the old
 *   image and nothing of ours is touched until a second settled read agrees (§10.25); then it is recaptured. A
 *   recapture that fails for a final-looking reason is tried once more in a settled state before the original is
 *   let go (§10.26). Nothing is ever set over an id no settled check has seen (§10.27).
 * - With nothing of ours staged, nothing is ever put back.
 *
 * Diagnostics: every posture change, check, set and prepare is logged with [TAG] ("HingegateSwap"), for a
 * filtered logcat capture on the phone.
 */
class WallpaperSwapper(
    context: Context,
    private val settings: SettingsRepository,
    private val store: SwapRepository,
    private val source: CurrentWallpaperSource,
    private val renderer: SwapRenderer,
    private val prepared: PreparedImages,
    private val controller: CrackController,
    private val monitor: PostureMonitor,
    private val log: CrackLog,
    private val scope: CoroutineScope,
    /** Samsung keeps one wallpaper per panel, which is what makes staging while folded possible (§1). */
    val isSamsung: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uptime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val appContext = context.applicationContext
    private val wallpaperManager = WallpaperManager.getInstance(appContext)
    private val displayManager: DisplayManager? = appContext.getSystemService(DisplayManager::class.java)

    /**
     * `FLAG_SYSTEM`, plus Samsung's inner-panel bit there (§1): the home screen of the inner panel only. Computed,
     * never a literal, so lint does not read the panel bit as a `@SetWallpaperFlags` value.
     */
    private val which: Int = SwapFlags.which(isSamsung, WallpaperManager.FLAG_SYSTEM)

    /** The source's reads name the inner panel (Samsung): their ids mean the inner screen even while folded. */
    private val namesPanel: Boolean = SwapFlags.namesPanel(source.readWhich)

    private val mutex = Mutex()
    private val kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var started = false
    private var restoreRequested = false

    /** The planner input a set last failed for: not retried (nor logged again) until something changes. */
    private var failedFor: SwapInput? = null

    /**
     * Whether the last set was made in a settled inner state, where its id could be read right away. If such a
     * set is then found not to have reached the inner screen, setting it again would only miss again: the miss is
     * treated like a failed set. A set staged while folded gets one more try, late, at the unfold.
     */
    private var lastSetReadable = false

    // Main thread only (the scope is Main.immediate; every IO step returns to it before touching these).
    private var mark: SettleMark? = null
    private var sighting: Sighting? = null
    private val checks = SettleCheckTimer(scope, uptime) { recheck() }
    private var prepareTask: PrepareTask? = null
    private var preparedKeep: Set<String>? = null
    private var cleanMatch: Pair<Pair<PreparedSource, SwapFrame>, Boolean>? = null

    /** `setStream` failed once (One UI may treat it differently from `setBitmap`): `setBitmap` for this process. */
    @Volatile
    private var streamFailed = false

    // Any wallpaper change, the user's or ours: a reconcile checks whose it is once the inner screen is settled. A
    // change on Samsung's cover panel (the flags carry its bit) is none of this mode's business.
    private val colorsListener = WallpaperManager.OnColorsChangedListener { _, changed ->
        if (SwapFlags.concernsInner(isSamsung, changed)) recheck()
    }

    /** Idempotent; called from `Application.onCreate` after the controller has started. */
    fun start() {
        if (started) return
        started = true
        // The settle clock follows every posture change as it happens, even while a reconcile is busy.
        scope.launch {
            combine(controller.state, monitor.snapshot) { crack, snapshot -> keyOf(crack, snapshot) }
                .distinctUntilChanged()
                .collect { observe(it) }
        }
        scope.launch {
            merge(
                combine(settings.settings, controller.state, monitor.snapshot) { _, _, _ -> },
                kicks,
            ).conflate().collect { reconcileSafely() }
        }
        wallpaperManager.addOnColorsChangedListener(colorsListener, Handler(Looper.getMainLooper()))
    }

    /** Re-checks the wallpaper (the app came to the front: the user may have changed it in the meantime). */
    fun recheck() {
        kicks.tryEmit(Unit)
    }

    /** "Put my wallpaper back": clears the crack, then puts the clean original back if a crack is staged. */
    fun restore() {
        // Set first: the reconcile that follows the cleared crack is the one that puts the original back.
        restoreRequested = true
        scope.launch {
            controller.clearNow()
            recheck()
        }
    }

    /**
     * "Use my current wallpaper": captures it as the original. Only in a settled inner state — a tap during the
     * settle period waits for it — with All files access, and not when the current wallpaper is one Hingegate
     * set. Fails with a [SwapException].
     */
    suspend fun capture(): Result<Unit> {
        if (!awaitSettled()) return fail(SwapFailure.NOT_UNFOLDED)
        return mutex.withLock { captureByHand() }.also { if (it.isSuccess) recheck() }
    }

    private suspend fun captureByHand(): Result<Unit> {
        val key = currentKey()
        if (!settledNow()) return fail(SwapFailure.NOT_UNFOLDED)
        if (!source.isGranted()) return fail(SwapFailure.NO_PERMISSION)
        val state = store.load()
        val reads = source.readIds()
        val id = reads.inner
        Log.i(TAG, "capture asked: ${describe(key)} ${describeIds(reads)} ${describe(state)}")
        // Without an id nothing could ever tell this capture from Hingegate's own copies.
        if (id <= 0) return fail(SwapFailure.READ_FAILED)
        when (SwapIds.check(id, state)) {
            IdCheck.OURS -> return fail(SwapFailure.ALREADY_OURS)
            IdCheck.ADOPT -> {
                // An image of ours not confirmed yet, reported under a new id: ours, never a new original.
                append(LogEntry(clock(), LogKind.SWAP_ID_ADOPTED, swapId = id))
                save(SwapIds.settle(IdAction.ADOPT, state, id))
                return fail(SwapFailure.ALREADY_OURS)
            }
            IdCheck.NONE, IdCheck.ORIGINAL, IdCheck.CHANGED -> Unit
        }
        return captureLocked(state, id, key).map { captured ->
            sighting = null
            save(captured)
            append(LogEntry(clock(), LogKind.SWAP_CAPTURED, swapId = captured.capture?.wallpaperId))
        }
    }

    /**
     * Waits, outside the lock, until the inner screen is settled. False when it is not in use, stops being, or
     * keeps changing for longer than two settle periods.
     */
    private suspend fun awaitSettled(): Boolean {
        val deadline = uptime() + 2 * SwapSettle.SETTLE_MS
        while (true) {
            val remaining = SwapSettle.remainingMs(observe(currentKey()), uptime()) ?: return false
            if (remaining == 0L) return true
            if (uptime() >= deadline) return false
            delay(remaining + SettleCheckTimer.SLACK_MS)
        }
    }

    // ── Settle ───────────────────────────────────────────────────────────────

    private fun keyOf(crack: CrackState, snapshot: PostureSnapshot) = SettleKey(crack.posture, crack.epoch, snapshot.posture)

    private fun currentKey() = keyOf(controller.state.value, monitor.snapshot.value)

    /** Records [key] in the settle clock (restarting it on a change) and returns the mark. */
    private fun observe(key: SettleKey): SettleMark {
        val previous = mark
        val next = SwapSettle.observe(previous, key, uptime())
        if (next !== previous) {
            mark = next
            Log.i(TAG, "posture: ${describe(key)}")
        }
        return next
    }

    /** Whether a read taken now may be acted on: the CURRENT postures, settled. */
    private fun settledNow(): Boolean = SwapSettle.settled(observe(currentKey()), uptime())

    /** One check after [delayMs] ([SettleCheckTimer]: a check already waiting that is due no later covers it). */
    private fun scheduleCheck(delayMs: Long) = checks.schedule(delayMs)

    // ── Reconcile ────────────────────────────────────────────────────────────

    /**
     * One reconcile, never fatal: an unexpected exception from the system (One UI may reject an undocumented flag)
     * is logged and the next change tries again, instead of crashing the process — which would restart into the
     * same reconcile and crash again.
     */
    private suspend fun reconcileSafely() {
        try {
            reconcile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.e(TAG, "Reconcile failed", e)
        }
    }

    private suspend fun reconcile() = mutex.withLock {
        val now = settings.settings.first()
        val crack = controller.state.value
        val snapshot = monitor.snapshot.value
        val swapOn = now.mode == CrackMode.SWAP && now.enabled
        val key = keyOf(crack, snapshot)
        val settleMark = observe(key)
        val settled = SwapSettle.settled(settleMark, uptime())
        var state = store.load()

        if (settled && swapOn && state.capture != null) {
            // The panel's frame, measured once the inner screen has settled; Window Manager's hinge replaces an estimate.
            frameNow(snapshot)?.let { measured ->
                val kept = SwapFrames.better(state.frame, measured)
                if (kept != state.frame) state = save(state.copy(frame = kept))
            }
        }
        // Whose wallpaper is showing, before anything is set (§3: recapture before anything else) — only once settled.
        var missed = false
        if (SwapIds.shouldCheck(state, swapOn)) {
            if (settled) {
                val checked = checkId(state, swapOn, key)
                state = checked.first
                missed = checked.second
            } else {
                deferCheck(state, settleMark)
            }
        }

        // A put-back is labelled as one once the crack it clears is gone.
        val restore = SwapPlanner.restoreDue(restoreRequested, crack.active)
        if (restore) restoreRequested = false
        val input = SwapInput(
            swapMode = now.mode == CrackMode.SWAP,
            enabled = now.enabled,
            posture = crack.posture,
            pending = crack.pending,
            active = crack.active,
            staged = state.staged,
            isSamsung = isSamsung,
            hasOriginal = state.hasOriginal,
        )
        val action = SwapPlanner.plan(input)
        when {
            action !is SwapAction.Apply -> failedFor = null
            input == failedFor -> Unit
            // The set just checked was made where it should have shown at once, and it did not: setting it again
            // would miss again (and the colours listener would loop). Wait until something changes.
            missed && lastSetReadable -> failedFor = input
            else -> state = apply(state, action.copy(timing = SwapPlanner.timingFor(action, restore)), input)
        }
        keepPrepared(state, swapOn)
    }

    /** Not settled yet: the ids are read for the log only, and one check is scheduled for the end of the settle period. */
    private fun deferCheck(state: SwapState, settleMark: SettleMark) {
        val remaining = SwapSettle.remainingMs(settleMark, uptime())
        val reads = source.readIds()
        val due = remaining?.let { "settles in $it ms" } ?: "inner screen not in use"
        Log.i(TAG, "check deferred ($due): ${describe(settleMark.key)} ${describeIds(reads)} ${describe(state)} (not acted on)")
        remaining?.let { scheduleCheck(it) }
    }

    /**
     * Compares the inner screen's current id with ours and the captured one, in a settled state, and acts on it
     * (decided by [SwapIds.decide]): adopts the id of a set whose id the inner screen reports differently, notices
     * a set that never reached the inner screen, suspects — and on a second agreeing read recaptures (or, outside
     * the mode, stops claiming the screen) — a wallpaper the user changed. A read during which the posture moved is
     * thrown away. Returns the new state and whether a set of ours was found to have missed the inner screen.
     */
    private suspend fun checkId(state: SwapState, swapOn: Boolean, key: SettleKey): Pair<SwapState, Boolean> {
        val reads = source.readIds()
        if (currentKey() != key || !settledNow()) {
            Log.i(TAG, "check discarded: the posture changed during the read (${describe(currentKey())}) ${describeIds(reads)}")
            recheck()
            return state to false
        }
        val id = reads.inner
        val nowMs = uptime()
        val decision = SwapIds.decide(id, state, swapOn, sighting, key.epoch, nowMs)
        sighting = SwapIds.nextSighting(sighting, decision.action, id, key.epoch, nowMs)
        val since = mark?.let { nowMs - it.sinceMs }
        Log.i(
            TAG,
            "check: ${describe(key)} settled (${since} ms) ${describeIds(reads)} ${describe(state)} → " +
                "${decision.action}${if (decision.missed) " (missed)" else ""}",
        )
        if (decision.missed) append(LogEntry(clock(), LogKind.SWAP_STAGE_MISSED, swapId = id))
        val next = when (decision.action) {
            IdAction.KEEP -> state
            IdAction.SUSPECT -> {
                // Read again a moment later in this same unfold; until then nothing is staged or put back.
                sighting?.let { scheduleCheck(SwapIds.confirmDelayMs(it, nowMs)) }
                saveIfChanged(SwapIds.settle(IdAction.SUSPECT, state, id), state)
            }
            IdAction.RECAPTURE -> recapture(state, id, key)
            IdAction.ADOPT -> {
                append(LogEntry(clock(), LogKind.SWAP_ID_ADOPTED, swapId = id))
                save(SwapIds.settle(IdAction.ADOPT, state, id))
            }
            IdAction.CONFIRM, IdAction.RESET, IdAction.FORGET -> saveIfChanged(SwapIds.settle(decision.action, state, id), state)
        }
        return next to decision.missed
    }

    /**
     * The user set a new wallpaper (two settled reads agree on [expectedId]): it becomes the original. A failure
     * for a passing reason keeps the old capture, marked stale; a final-looking one (live wallpaper, permission,
     * unreadable) keeps it too the first time and reads again in a moment — only a second settled failure lets go
     * of the original (decided by [SwapIds.afterRecaptureFailure]).
     */
    private suspend fun recapture(state: SwapState, expectedId: Int, key: SettleKey): SwapState =
        captureLocked(state, expectedId, key).fold(
            onSuccess = { captured ->
                sighting = null
                append(LogEntry(clock(), LogKind.SWAP_RECAPTURED, swapId = captured.capture?.wallpaperId))
                save(captured)
            },
            onFailure = { error ->
                val failure = failureOf(error)
                val (outcome, next) = SwapIds.afterRecaptureFailure(state, failure)
                Log.i(TAG, "recapture of id $expectedId failed: $failure → $outcome")
                when (outcome) {
                    RecaptureOutcome.KEEP_STALE -> {
                        append(LogEntry(clock(), LogKind.SWAP_FAILED, swapFailure = failure))
                        scheduleCheck(SwapSettle.CONFIRM_MS)
                    }
                    RecaptureOutcome.RETRY -> scheduleCheck(SwapSettle.CONFIRM_MS)
                    RecaptureOutcome.LET_GO -> {
                        append(LogEntry(clock(), LogKind.SWAP_FAILED, swapFailure = failure))
                        source.delete()
                        prepared.deleteAll()
                    }
                }
                save(next)
            },
        )

    /**
     * Reads the current wallpaper and returns the state with it as the original (untouched: nothing staged). The
     * read is thrown away if the posture moved while it ran, if the id is not [expectedId] (the one the check saw)
     * or changed during the read, or if it is one of ours — an automatic recapture must never take Hingegate's own
     * copy for the original.
     */
    private suspend fun captureLocked(state: SwapState, expectedId: Int, key: SettleKey): Result<SwapState> {
        val frame = frameNow(monitor.snapshot.value)?.let { SwapFrames.better(state.frame, it) } ?: state.frame
            ?: return fail(SwapFailure.NO_PANEL_SIZE)
        val captured = source.capture().getOrElse { return Result.failure(it) }
        val stillSettled = currentKey() == key && settledNow()
        val idNow = source.currentId()
        val refused = SwapIds.captureVerdict(stillSettled, expectedId, captured.wallpaperId, idNow, state.ourIds)
        Log.i(TAG, "capture: expected id $expectedId, read ${captured.wallpaperId}, now $idNow, settled $stillSettled → ${refused ?: "kept"}")
        if (refused != null) {
            source.discard()
            return fail(refused)
        }
        source.commit().onFailure { return Result.failure(it) }
        return Result.success(
            state.copy(
                capture = SwapCapture(captured.wallpaperId, clock()),
                frame = frame,
                staged = null,
                // The new original is never one of ours (checked just above).
                ourIds = state.ourIds - captured.wallpaperId,
                unconfirmedSet = false,
                needsRecapture = false,
                lastSet = null,
                suspectId = null,
                recaptureFailure = null,
            ),
        )
    }

    // ── Setting ──────────────────────────────────────────────────────────────

    /** An image ready for the system: a finished file to stream, or a bitmap rendered on the spot. */
    private sealed interface SetImage {
        val via: String

        class Stream(val file: File, override val via: String) : SetImage

        class Rendered(val bitmap: Bitmap, override val via: String) : SetImage
    }

    /** Sets [action]'s image and returns the state after it (saved), or [state] when nothing was set. */
    private suspend fun apply(state: SwapState, action: SwapAction.Apply, input: SwapInput): SwapState {
        val frame = state.frame ?: return state
        val captureId = state.capture?.wallpaperId ?: return state
        val started = uptime()
        val image = imageFor(captureId, frame, action).getOrElse {
            Log.w(TAG, "No image for ${action.target}", it)
            failed(input, failureOf(it))
            return state
        }
        val prepareMs = uptime() - started
        try {
            // Right before the binder set: the controller may have moved on while the image was found or made.
            val fresh = controller.state.value
            if (!SwapPlanner.stillWanted(input, action.target, fresh.posture, fresh.pending, fresh.active)) {
                Log.i(TAG, "set dropped: ${action.target} is no longer wanted (controller ${fresh.posture}, epoch ${fresh.epoch})")
                recheck()
                return state
            }
            // Never over a wallpaper no settled check has seen: it may be the user's new one (§10.27). The read only
            // holds the set back; the settled check decides.
            val settledBefore = settledNow()
            val keyBefore = currentKey()
            val before = source.readIds()
            if (SwapIds.blocksSet(before.inner, state)) {
                Log.i(TAG, "set held: ${action.target} (${action.timing}); an unknown wallpaper shows ${describeIds(before)}; the settled check decides")
                failedFor = input
                SwapSettle.remainingMs(observe(keyBefore), uptime())?.let { scheduleCheck(it) }
                return state
            }
            // Marked before the binder set (§10.26): if the process dies after the system applied the image but
            // before the save below, the copy's id — never recorded — must be adopted at the next settled check, not
            // recaptured as the user's wallpaper. A failed set takes the mark back.
            val markStarted = uptime()
            val marked = saveIfChanged(SwapIds.beforeSet(state, action.target), state)
            val markMs = uptime() - markStarted
            val setStarted = uptime()
            val (id, path) = setImage(image)
            val setMs = uptime() - setStarted
            if (id <= 0) {
                Log.i(TAG, "set failed: ${action.target} (${action.timing}) which=$which via $path in $setMs ms")
                saveIfChanged(state, marked)
                failed(input, SwapFailure.SET_FAILED)
                return state
            }
            failedFor = null
            // The id the inner screen reports right after. It counts as our copy only when the reads mean the inner
            // screen — they name its panel (Samsung, folded or not: §10.33), or both were settled — it changed, and it
            // is not the original's; anything else stays unconfirmed for the next settled check, which confirms it,
            // adopts it, or reports the miss.
            val settledAfter = settledNow() && currentKey() == keyBefore
            val after = source.readIds()
            val readable = SwapIds.readBackCounts(namesPanel, settledBefore, settledAfter)
            val confirmed = readable && SwapIds.confirmsSet(before.inner, after.inner, captureId)
            lastSetReadable = readable
            val postureNow = controller.state.value.posture
            val timing = SwapPlanner.loggedTiming(action.timing, postureNow)
            Log.i(
                TAG,
                "set: ${action.target} planned ${action.timing}, logged $timing, which=$which via $path; " +
                    "image ${prepareMs} ms, mark ${markMs} ms, set ${setMs} ms; id $id; " +
                    "before ${describeIds(before)}, after ${describeIds(after)}; " +
                    "read-back counts $readable (settled $settledBefore/$settledAfter), confirmed $confirmed; controller now $postureNow",
            )
            val next = save(
                state.copy(
                    staged = action.target,
                    lastSet = action.target,
                    ourIds = SwapIds.remember(state.ourIds, id, after.inner.takeIf { confirmed }),
                    unconfirmedSet = SwapIds.unconfirmedAfterSet(isSamsung, namesPanel, confirmed),
                ),
            )
            append(
                LogEntry(
                    timeEpochMs = clock(),
                    kind = LogPhrase.kindOf(timing),
                    swapCracked = action.target is Staged.Cracked,
                    swapWhich = which,
                    swapId = id,
                ),
            )
            if (confirmed && after.inner != id) append(LogEntry(clock(), LogKind.SWAP_ID_ADOPTED, swapId = after.inner))
            // Settle the set as soon as the inner screen is settled (confirm it, or notice the miss) rather than
            // whenever something else changes: an unconfirmed mark left standing would adopt a wallpaper the user
            // sets meanwhile instead of capturing it.
            recheck()
            return next
        } finally {
            if (image is SetImage.Rendered) image.bitmap.recycle()
        }
    }

    /**
     * The image for [action]: the original's own bytes for the clean copy when they already fit the panel, else a
     * prepared file (waiting for a prepare that is making exactly this one right now), else — nothing prepared
     * yet — rendered on the spot.
     */
    private suspend fun imageFor(captureId: Int, frame: SwapFrame, action: SwapAction.Apply): Result<SetImage> {
        val key = prepared.sourceFor(captureId)
        if (key != null) {
            val target = action.target
            if (target == Staged.Clean && cleanIsOriginal(key, frame)) return Result.success(SetImage.Stream(source.originalFile, "original"))
            val name = when (target) {
                Staged.Clean -> SwapPrepared.cleanName(key, frame)
                is Staged.Cracked -> SwapPrepared.crackedName(key, frame, target.seed)
            }
            prepared.existing(name)?.let { return Result.success(SetImage.Stream(it, "prepared")) }
            prepareTask?.result(name)?.let { inProgress ->
                val waitStarted = uptime()
                // A cancelled prepare may still have finished its file: look again whatever the answer.
                val file = inProgress.await() ?: prepared.existing(name)
                if (file != null) return Result.success(SetImage.Stream(file, "prepared, waited ${uptime() - waitStarted} ms"))
            }
        }
        val renderStarted = uptime()
        return try {
            withContext(Dispatchers.IO) { renderer.render(frame, action.spec) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Result.failure(e)
        }.map { SetImage.Rendered(it, "rendered on the spot in ${uptime() - renderStarted} ms") }
    }

    /** Whether the original's own bytes are the clean copy for [frame] (cached per original and frame). */
    private suspend fun cleanIsOriginal(key: PreparedSource, frame: SwapFrame): Boolean {
        val id = key to frame
        cleanMatch?.takeIf { it.first == id }?.let { return it.second }
        val matches = source.originalMatches(frame)
        cleanMatch = id to matches
        return matches
    }

    /** Hands [image] to the system; returns the id it gave the new wallpaper (0 on failure) and how it went. */
    private suspend fun setImage(image: SetImage): Pair<Int, String> = when (image) {
        is SetImage.Stream -> {
            val streamed = if (streamFailed) {
                0
            } else {
                withContext(Dispatchers.IO) {
                    guardedSet("setStream") { FileInputStream(image.file).use { wallpaperManager.setStream(it, null, false, which) } }
                }
            }
            if (streamed > 0) {
                streamed to "setStream (${image.via})"
            } else {
                if (!streamFailed) {
                    streamFailed = true
                    Log.w(TAG, "setStream with which=$which gave no id: setBitmap from now on in this process")
                }
                val bitmap = prepared.decodeFull(image.file)
                if (bitmap == null) {
                    0 to "setBitmap (${image.via}, undecodable)"
                } else {
                    try {
                        withContext(Dispatchers.IO) {
                            guardedSet("setBitmap") { wallpaperManager.setBitmap(bitmap, null, false, which) }
                        } to "setBitmap (${image.via}, decoded)"
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        is SetImage.Rendered -> withContext(Dispatchers.IO) {
            guardedSet("setBitmap") { wallpaperManager.setBitmap(image.bitmap, null, false, which) }
        } to "setBitmap (${image.via})"
    }

    /** A system set that can only fail as "no id": the system's exceptions are logged, never thrown (§10.19). */
    private inline fun guardedSet(what: String, block: () -> Int): Int = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "$what failed", e)
        0
    } catch (e: SecurityException) {
        Log.w(TAG, "$what refused", e)
        0
    } catch (e: RuntimeException) {
        // A server-side rejection rethrown here, e.g. of the panel bit.
        Log.w(TAG, "$what rejected", e)
        0
    }

    private suspend fun failed(input: SwapInput, failure: SwapFailure) {
        failedFor = input
        append(LogEntry(clock(), LogKind.SWAP_FAILED, swapFailure = failure))
    }

    // ── Preparing ────────────────────────────────────────────────────────────

    /**
     * One run of the preparer: the images of one plan, made in order, each with a result a set can wait for. A run
     * that starts during the settle period (right after an unfold that showed the next crack) first waits for its
     * end, out of the way of the unfold — unless a set asks for one of its images meanwhile.
     */
    private inner class PrepareTask(plan: PreparePlan, key: PreparedSource?, frame: SwapFrame?, startDelayMs: Long) {
        val names: List<String> = plan.toPrepare.map { it.name }
        private val results: Map<String, CompletableDeferred<File?>> = names.associateWith { CompletableDeferred() }
        private val hurry = CompletableDeferred<Unit>()

        val job: Job = scope.launch {
            try {
                prepared.keepOnly(plan.keep)
                if (key == null || frame == null) return@launch
                if (startDelayMs > 0 && plan.toPrepare.any { prepared.existing(it.name) == null }) {
                    withTimeoutOrNull(startDelayMs) { hurry.await() }
                }
                for (image in plan.toPrepare) {
                    val done = results.getValue(image.name)
                    prepared.existing(image.name)?.let {
                        done.complete(it)
                        continue
                    }
                    val result = prepared.prepare(image, key, frame)
                    result.onSuccess {
                        Log.i(
                            TAG,
                            "prepare: ${if (image.spec == null) "clean" else "cracked seed ${image.spec.seed}"} for capture " +
                                "${key.captureId}, ${frame.width}x${frame.height}: render ${it.renderMs} ms, encode ${it.encodeMs} ms, " +
                                "${it.bytes} bytes",
                        )
                    }.onFailure { Log.w(TAG, "prepare of ${image.name} failed", it) }
                    done.complete(result.getOrNull()?.file)
                }
            } finally {
                results.values.forEach { it.complete(null) }
            }
        }

        /** The result for [name], if this run makes it; asking means a set is waiting, so the run starts now. */
        fun result(name: String): Deferred<File?>? = results[name]?.also { hurry.complete(Unit) }
    }

    /**
     * Keeps the prepared images in line with what the next fold may need (decided by [SwapPrepared.plan]): the
     * clean copy and the next crack while the mode is on and the capture is trusted, the cracks showing kept, the
     * rest — every copy, once the mode is off with nothing to put back or the capture is gone — deleted. A running
     * prepare is only replaced when the images to make change (never at a fold: the pending crack IS the next one).
     */
    private suspend fun keepPrepared(state: SwapState, swapOn: Boolean) {
        val crack = controller.state.value
        val key = state.capture?.wallpaperId?.let { prepared.sourceFor(it) }
        val frame = state.frame
        val plan = SwapPrepared.planFor(
            source = key,
            swapOn = swapOn,
            cleanIsOriginal = key != null && frame != null && cleanIsOriginal(key, frame),
            state = state,
            pending = crack.pending,
            nextSpec = crack.nextSpec,
            active = crack.active,
        )
        val names = plan.toPrepare.map { it.name }
        val task = prepareTask
        if (task != null && task.names == names) {
            if (plan.keep != preparedKeep) {
                preparedKeep = plan.keep
                scope.launch { prepared.keepOnly(plan.keep) }
            }
            return
        }
        task?.job?.cancel()
        preparedKeep = plan.keep
        val startDelay = mark?.let { SwapSettle.remainingMs(it, uptime()) } ?: 0L
        prepareTask = PrepareTask(plan, key, frame, startDelay)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** The inner panel's frame from the current display, or null when it cannot be measured right now. */
    private fun frameNow(snapshot: PostureSnapshot): SwapFrame? {
        if (snapshot.posture != Posture.UNFOLDED) return null
        val w = snapshot.displayWidth
        val h = snapshot.displayHeight
        if (w <= 0 || h <= 0) return null
        val hinge = snapshot.hinge ?: monitor.hingeFor(w, h, snapshot.rotation)
        return SwapFrames.fromDisplay(w, h, snapshot.rotation, hinge, innerDensity(), snapshot.innerWidth, snapshot.innerHeight)
    }

    /** The density of the display in use (the inner panel's while unfolded), read fresh: it changes with the panel. */
    private fun innerDensity(): Float {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return appContext.resources.displayMetrics.density
        return appContext.createDisplayContext(display).resources.displayMetrics.density
    }

    private fun describe(key: SettleKey) = "controller=${key.controller} display=${key.display} epoch=${key.epoch}"

    private fun describeIds(reads: IdReadout): String =
        if (reads.plain == null) "id[which=$which]=${reads.inner}" else "id[which=$which]=${reads.inner} id[which=1]=${reads.plain}"

    private fun describe(state: SwapState): String =
        "(capture ${state.capture?.wallpaperId}, ours ${state.ourIds.takeLast(LOGGED_IDS)}, staged ${state.staged}, " +
            "unconfirmed ${state.unconfirmedSet}, suspect ${state.suspectId}, stale ${state.needsRecapture}, " +
            "failure ${state.recaptureFailure})"

    private suspend fun saveIfChanged(next: SwapState, previous: SwapState): SwapState = if (next == previous) previous else save(next)

    private suspend fun save(state: SwapState): SwapState {
        store.save(state).onFailure { Log.w(TAG, "Could not save the swap state", it) }
        return state
    }

    private suspend fun append(entry: LogEntry) {
        log.append(entry).onFailure { Log.w(TAG, "Could not write the log", it) }
    }

    private fun <T> fail(failure: SwapFailure): Result<T> = Result.failure(SwapException(failure))

    private fun failureOf(error: Throwable): SwapFailure = (error as? SwapException)?.failure ?: SwapFailure.READ_FAILED

    private companion object {
        /** Every diagnostic line of this mode carries this tag ("Hingegate" prefix, for a filtered logcat). */
        const val TAG = "HingegateSwap"

        /** How many of our ids a log line shows. */
        const val LOGGED_IDS = 4
    }
}
