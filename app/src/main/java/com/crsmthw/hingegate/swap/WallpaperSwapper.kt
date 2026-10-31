package com.crsmthw.hingegate.swap

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import com.crsmthw.hingegate.domain.SwapState
import com.crsmthw.hingegate.posture.PostureMonitor
import com.crsmthw.hingegate.posture.PostureSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The Real wallpaper mode (docs/SWAP-MODE.md §3): puts the user's own wallpaper, with or without the crack, on the
 * inner screen's home wallpaper. A process singleton in the AppContainer, started with the app.
 *
 * It applies a desired STATE, decided by [SwapPlanner] from the controller's consistent [CrackState] and the
 * settings: every change (settings, controller, display, a wallpaper change, the app coming to the front) asks
 * for one reconcile, reconciles are serialized by a lock and conflated (rapid changes collapse into one), and the
 * heavy work (decode, draw, `setBitmap`) runs on Dispatchers.IO. The state already staged is never set again.
 *
 * Safety rules, because this replaces the user's wallpaper:
 * - The wallpaper is only READ while the inner screen is in use (Samsung answers for the panel in use).
 * - An id that is neither ours nor the captured one means the user changed the wallpaper: it is captured again
 *   before anything else; if that fails the mode stops touching the wallpaper. A set made while folded is
 *   "unconfirmed" until the unfolded screen reports its id, so Hingegate's own cracked image can never be taken
 *   for a new original.
 * - With nothing of ours staged, nothing is ever put back.
 */
class WallpaperSwapper(
    context: Context,
    private val settings: SettingsRepository,
    private val store: SwapRepository,
    private val source: CurrentWallpaperSource,
    private val renderer: SwapRenderer,
    private val controller: CrackController,
    private val monitor: PostureMonitor,
    private val log: CrackLog,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val wallpaperManager = WallpaperManager.getInstance(appContext)
    private val displayManager: DisplayManager? = appContext.getSystemService(DisplayManager::class.java)

    /** Samsung keeps one wallpaper per panel, which is what makes staging while folded possible (§1). */
    val isSamsung: Boolean = SwapFlags.isSamsung(Build.MANUFACTURER)

    /** `FLAG_SYSTEM`, plus Samsung's inner-panel bit there (§1): the home screen of the inner panel only. */
    private val which: Int = SwapFlags.which(isSamsung, WallpaperManager.FLAG_SYSTEM)

    private val mutex = Mutex()
    private val kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var started = false
    private var restoreRequested = false

    /** The planner input a set last failed for: not retried (nor logged again) until something changes. */
    private var failedFor: SwapInput? = null

    /**
     * Whether the last set was made where its id could be read right away (the inner screen in use, or a phone
     * with one wallpaper). If such a set is then found not to have reached the inner screen, setting it again
     * would only miss again: the miss is treated like a failed set. A set staged while folded on Samsung gets one
     * more try, late, at the unfold.
     */
    private var lastSetReadable = false

    // Any wallpaper change, the user's or ours: a reconcile checks whose it is while the inner screen is in use.
    private val colorsListener = WallpaperManager.OnColorsChangedListener { _, _ -> recheck() }

    /** Idempotent; called from `Application.onCreate` after the controller has started. */
    fun start() {
        if (started) return
        started = true
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
     * "Use my current wallpaper": captures it as the original. Only while the inner screen is in use, with All
     * files access, and not when the current wallpaper is one Hingegate set. Fails with a [SwapException].
     */
    suspend fun capture(): Result<Unit> = mutex.withLock {
        val snapshot = monitor.snapshot.value
        if (!innerInUse(controller.state.value, snapshot)) return@withLock fail(SwapFailure.NOT_UNFOLDED)
        if (!source.isGranted()) return@withLock fail(SwapFailure.NO_PERMISSION)
        val state = store.load()
        val id = source.currentId()
        when (SwapIds.check(id, state)) {
            IdCheck.OURS -> return@withLock fail(SwapFailure.ALREADY_OURS)
            IdCheck.ADOPT -> {
                // An image of ours not confirmed yet, reported under a new id: ours, never a new original.
                append(LogEntry(clock(), LogKind.SWAP_ID_ADOPTED, swapId = id))
                save(SwapIds.settle(IdAction.ADOPT, state, id))
                return@withLock fail(SwapFailure.ALREADY_OURS)
            }
            IdCheck.NONE, IdCheck.ORIGINAL, IdCheck.CHANGED -> Unit
        }
        captureLocked(state, snapshot).map { captured ->
            save(captured)
            append(LogEntry(clock(), LogKind.SWAP_CAPTURED, swapId = captured.capture?.wallpaperId))
        }
    }.also { if (it.isSuccess) recheck() }

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
        val inner = innerInUse(crack, snapshot)
        var state = store.load()

        if (inner && swapOn && state.capture != null) {
            // The panel's frame, while it can be measured; Window Manager's hinge replaces an estimate.
            frameNow(snapshot)?.let { measured ->
                val kept = SwapFrames.better(state.frame, measured)
                if (kept != state.frame) state = save(state.copy(frame = kept))
            }
        }
        // Whose wallpaper is showing, before anything is set (§3: recapture before anything else).
        val checked = checkId(state, swapOn, inner, snapshot)
        state = checked.first
        val missed = checked.second

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
        if (action !is SwapAction.Apply) {
            failedFor = null
            return@withLock
        }
        if (input == failedFor) return@withLock
        if (missed && lastSetReadable) {
            // The set just checked was made where it should have shown at once, and it did not: setting it again
            // would miss again (and the colours listener would loop). Wait until something changes.
            failedFor = input
            return@withLock
        }
        apply(state, action.copy(timing = SwapPlanner.timingFor(action, restore)), input)
    }

    /**
     * Compares the system's current id with ours and the captured one, and acts on it (decided by
     * [SwapIds.decide]): adopts the id of a set whose id the inner screen reports differently, notices a set that
     * never reached the inner screen, and recaptures (or, outside the mode, stops claiming the screen) when the
     * user changed the wallpaper. Ids are read only when they mean the inner screen ([SwapIds.readable]). Returns
     * the new state and whether a set of ours was found to have missed the inner screen.
     */
    private suspend fun checkId(state: SwapState, swapOn: Boolean, inner: Boolean, snapshot: PostureSnapshot): Pair<SwapState, Boolean> {
        val readable = SwapIds.readable(inner, isSamsung, controller.state.value.posture)
        if (!SwapIds.shouldCheck(state, swapOn, readable)) return state to false
        val id = source.currentId()
        val decision = SwapIds.decide(id, state, swapOn, inner)
        if (decision.missed) append(LogEntry(clock(), LogKind.SWAP_STAGE_MISSED, swapId = id))
        val next = when (decision.action) {
            IdAction.KEEP -> state
            IdAction.RECAPTURE -> recapture(state, snapshot)
            IdAction.ADOPT -> {
                append(LogEntry(clock(), LogKind.SWAP_ID_ADOPTED, swapId = id))
                save(SwapIds.settle(IdAction.ADOPT, state, id))
            }
            IdAction.CONFIRM, IdAction.RESET, IdAction.FORGET -> {
                val settled = SwapIds.settle(decision.action, state, id)
                if (settled == state) state else save(settled)
            }
        }
        return next to decision.missed
    }

    /**
     * The user set a new wallpaper: it becomes the original. If it cannot be read because of something passing
     * (the phone folded during the read, the panel size not known yet), the old capture is kept but marked stale —
     * nothing is staged from it — and the read is tried again at the next chance; if it cannot be read at all
     * (a live wallpaper, the permission revoked, an unreadable file), the mode lets go of it.
     */
    private suspend fun recapture(state: SwapState, snapshot: PostureSnapshot): SwapState =
        captureLocked(state, snapshot).fold(
            onSuccess = { captured ->
                append(LogEntry(clock(), LogKind.SWAP_RECAPTURED, swapId = captured.capture?.wallpaperId))
                save(captured)
            },
            onFailure = { error ->
                val failure = failureOf(error)
                append(LogEntry(clock(), LogKind.SWAP_FAILED, swapFailure = failure))
                if (failure == SwapFailure.NOT_UNFOLDED || failure == SwapFailure.NO_PANEL_SIZE) {
                    save(state.copy(staged = null, unconfirmedSet = false, needsRecapture = true))
                } else {
                    source.delete()
                    save(SwapState(frame = state.frame, ourIds = state.ourIds))
                }
            },
        )

    /**
     * Reads the current wallpaper and returns the state with it as the original (untouched: nothing staged). The
     * read is thrown away if the phone folded or the wallpaper changed while it ran.
     */
    private suspend fun captureLocked(state: SwapState, snapshot: PostureSnapshot): Result<SwapState> {
        val frame = frameNow(snapshot)?.let { SwapFrames.better(state.frame, it) } ?: state.frame
            ?: return fail(SwapFailure.NO_PANEL_SIZE)
        val captured = source.capture().getOrElse { return Result.failure(it) }
        val stillInner = innerInUse(controller.state.value, monitor.snapshot.value) && source.currentId() == captured.wallpaperId
        if (!stillInner) {
            source.discard()
            return fail(SwapFailure.NOT_UNFOLDED)
        }
        source.commit().onFailure { return Result.failure(it) }
        return Result.success(
            state.copy(
                capture = SwapCapture(captured.wallpaperId, clock()),
                frame = frame,
                staged = null,
                // The new original is never one of ours (the check runs before any capture).
                ourIds = state.ourIds - captured.wallpaperId,
                unconfirmedSet = false,
                needsRecapture = false,
                lastSet = null,
            ),
        )
    }

    // ── Setting ──────────────────────────────────────────────────────────────

    private suspend fun apply(state: SwapState, action: SwapAction.Apply, input: SwapInput) {
        val frame = state.frame ?: return
        val bitmap = try {
            withContext(Dispatchers.IO) { renderer.render(frame, action.spec) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Result.failure<Bitmap>(e)
        }.getOrElse {
            Log.w(TAG, "Render failed", it)
            failed(input, failureOf(it))
            return
        }
        // The id the inner screen reports before the set, where it means the inner screen: what the set must change.
        val readableBefore = SwapIds.readable(innerInUse(controller.state.value, monitor.snapshot.value), isSamsung, controller.state.value.posture)
        val before = if (readableBefore) source.currentId() else null
        val id = try {
            withContext(Dispatchers.IO) { wallpaperManager.setBitmap(bitmap, null, false, which) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "setBitmap failed", e)
            0
        } catch (e: SecurityException) {
            Log.w(TAG, "setBitmap refused", e)
            0
        } catch (e: RuntimeException) {
            // A server-side rejection rethrown here, e.g. of the panel bit (docs/SWAP-MODE.md §1: not yet proven).
            Log.w(TAG, "setBitmap rejected", e)
            0
        } finally {
            bitmap.recycle()
        }
        if (id <= 0) {
            failed(input, SwapFailure.SET_FAILED)
            return
        }
        failedFor = null
        // The id the system reports right after, where it means the inner screen (on Samsung only while the inner
        // panel is in use). It counts as our copy only when it changed and is not the original's; anything else
        // stays unconfirmed for the next check, which adopts it, confirms it, or reports the miss.
        val inner = innerInUse(controller.state.value, monitor.snapshot.value)
        val readable = SwapIds.readable(inner, isSamsung, controller.state.value.posture)
        val readId = if (readable) source.currentId() else null
        val confirmed = SwapIds.confirmsSet(before, readId, state.capture?.wallpaperId)
        lastSetReadable = readableBefore && readable
        Log.i(TAG, "Set ${action.target} (${action.timing}) with which=$which: id $id, before $before, reported $readId, confirmed $confirmed")
        save(
            state.copy(
                staged = action.target,
                lastSet = action.target,
                ourIds = SwapIds.remember(state.ourIds, id, readId.takeIf { confirmed }),
                unconfirmedSet = SwapIds.unconfirmedAfterSet(isSamsung, confirmed),
            ),
        )
        append(
            LogEntry(
                timeEpochMs = clock(),
                kind = LogPhrase.kindOf(action.timing),
                swapCracked = action.target is Staged.Cracked,
                swapWhich = which,
                swapId = id,
            ),
        )
        if (confirmed && readId != null && readId != id) append(LogEntry(clock(), LogKind.SWAP_ID_ADOPTED, swapId = readId))
        // Settle the set right away (confirm it, or notice the miss) rather than whenever something else changes:
        // an unconfirmed mark left standing would adopt a wallpaper the user sets meanwhile instead of capturing it.
        recheck()
    }

    private suspend fun failed(input: SwapInput, failure: SwapFailure) {
        failedFor = input
        append(LogEntry(clock(), LogKind.SWAP_FAILED, swapFailure = failure))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** The inner panel is in use: both the controller and the display say unfolded. */
    private fun innerInUse(crack: CrackState, snapshot: PostureSnapshot): Boolean =
        crack.posture == Posture.UNFOLDED && snapshot.posture == Posture.UNFOLDED

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
        const val TAG = "WallpaperSwapper"
    }
}
