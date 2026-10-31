package com.crsmthw.hingegate.crack

import android.util.Log
import com.crsmthw.hingegate.data.CrackLog
import com.crsmthw.hingegate.data.SettingsRepository
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.CrackCause
import com.crsmthw.hingegate.domain.CrackSpec
import com.crsmthw.hingegate.domain.CrackTrigger
import com.crsmthw.hingegate.domain.LogEntry
import com.crsmthw.hingegate.domain.LogKind
import com.crsmthw.hingegate.domain.PendingRoll
import com.crsmthw.hingegate.domain.Posture
import com.crsmthw.hingegate.domain.RollPlanner
import com.crsmthw.hingegate.domain.Transition
import com.crsmthw.hingegate.domain.TriggerInput
import com.crsmthw.hingegate.posture.PostureMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * The controller's whole view in one value, published under its lock: the posture it last ACTED on (a
 * transition, or the first known posture after start-up), the next unfold's pending roll and the crack showing.
 * The Real wallpaper mode plans from this, never from the monitor's snapshot, which changes a moment before the
 * controller has handled the transition.
 *
 * [nextSpec] is the crack the next hit will show, chosen ahead so the Real wallpaper mode can render it while the
 * inner screen is in use (docs/SWAP-MODE.md §10.28); null only until the stored one is restored. [epoch] counts
 * the postures acted on, so two states either side of a quick fold-and-unfold never look alike (§10.24).
 */
data class CrackState(
    val posture: Posture = Posture.UNKNOWN,
    val pending: PendingRoll? = null,
    val active: ActiveCrack? = null,
    val nextSpec: CrackSpec? = null,
    val epoch: Long = 0L,
)

/**
 * Owns "is a crack showing" and "will the next unfold crack" (docs/DESIGN.md §9.2, docs/SWAP-MODE.md §2). One per
 * process, like the monitor it listens to, so each unfold is decided exactly once however many surfaces are alive.
 * The mode does not matter here: each surface decides for itself whether it draws.
 *
 * Pre-rolling: while folded a [PendingRoll] for the next unfold always exists (unless the app is off) — made at
 * the fold, at start-up, and again whenever the odds or "crack on the next unfold" change; the unfold consumes it
 * and commits the miss streak and the log exactly as before.
 */
class CrackController(
    private val settings: SettingsRepository,
    private val log: CrackLog,
    private val monitor: PostureMonitor,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(CrackState())
    val state: StateFlow<CrackState> = _state.asStateFlow()

    private val _active = MutableStateFlow<ActiveCrack?>(null)
    val active: StateFlow<ActiveCrack?> = _active.asStateFlow()

    private val restored = CompletableDeferred<Unit>()

    // Transitions, the buttons, the settings and the master switch all read-modify-write the same state across
    // suspensions; every change happens under this lock and is published with publish().
    private val mutex = Mutex()
    private var started = false
    private var posture = Posture.UNKNOWN
    private var pending: PendingRoll? = null
    private var current: ActiveCrack? = null
    private var nextSpec: CrackSpec? = null
    private var epoch = 0L

    /** Idempotent; called from `Application.onCreate` BEFORE the monitor starts, so no transition is missed. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            mutex.withLock {
                current = settings.loadActiveCrack()
                pending = settings.loadPendingRoll()
                nextSpec = settings.loadNextSpec() ?: RollPlanner.newSpec(random).also {
                    settings.saveNextSpec(it).logFailure()
                    Log.i(DIAG_TAG, "next crack chosen: seed=${it.seed} (first start)")
                }
                publish()
            }
            restored.complete(Unit)
        }
        // UNDISPATCHED: subscribed before start() returns; transitions wait for the restore, never act before it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            monitor.transitions.collect { transition ->
                restored.await()
                mutex.withLock {
                    when (transition) {
                        Transition.UNFOLDED -> onUnfold()
                        Transition.FOLDED -> onFold()
                    }
                }
            }
        }
        // A process started with the phone already folded or unfolded sees no transition: the first known posture
        // is adopted once (later changes always come as transitions).
        scope.launch {
            val first = monitor.snapshot.map { it.posture }.first { it != Posture.UNKNOWN }
            restored.await()
            mutex.withLock {
                if (posture == Posture.UNKNOWN) {
                    posture = first
                    epoch++
                    keepPendingLocked()
                    publish()
                }
            }
        }
        // While folded the pending roll follows the settings: rolled again when the odds, the guarantee or "crack
        // on the next unfold" change, dropped when the app is switched off.
        scope.launch {
            combine(settings.settings, settings.missStreak) { s, streak -> s to streak }
                .distinctUntilChanged()
                .collect {
                    restored.await()
                    mutex.withLock { if (keepPendingLocked()) publish() }
                }
        }
        // Switching the app off clears a crack that is showing.
        scope.launch {
            settings.settings.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) {
                    restored.await()
                    mutex.withLock { if (current != null) clearLocked(LogKind.MANUAL_CLEAR) }
                }
            }
        }
    }

    /**
     * "Show crack now": a fresh crack, regardless of the odds. Nothing while the app is switched off: no surface
     * would draw it, and it would pop up later, the moment the app is switched on.
     */
    fun showNow() {
        scope.launch {
            restored.await()
            mutex.withLock {
                if (!settings.settings.first().enabled) return@withLock
                val crack = ActiveCrack(RollPlanner.newSpec(random), clock(), CrackCause.MANUAL)
                current = crack
                publish()
                settings.saveActiveCrack(crack).logFailure()
                append(LogEntry(clock(), LogKind.MANUAL_SHOW, authority = monitor.snapshot.value.authority, hingeSource = hingeSource()))
            }
        }
    }

    fun clear() {
        scope.launch { clearNow() }
    }

    /** [clear], finished when it returns: the Real wallpaper's "Put my wallpaper back" runs right after it. */
    suspend fun clearNow() {
        restored.await()
        mutex.withLock { if (current != null) clearLocked(LogKind.MANUAL_CLEAR) }
    }

    private suspend fun onUnfold() {
        posture = Posture.UNFOLDED
        epoch++
        val now = settings.settings.first()
        val authority = monitor.snapshot.value.authority
        if (!now.enabled) {
            if (pending != null) {
                pending = null
                settings.savePendingRoll(null).logFailure()
            }
            publish()
            append(LogEntry(clock(), LogKind.UNFOLD_DISABLED, authority = authority, hingeSource = hingeSource()))
            return
        }
        val streak = settings.missStreak.first()
        val chosen = nextSpecLocked()
        val outcome = RollPlanner.atUnfold(
            pending,
            TriggerInput(now.chancePercent, now.guaranteeAfter, streak, now.crackNextUnfold),
            random,
            chosen,
        )
        val result = outcome.result
        // Every unfold decides afresh: a crack left over from a fold this process never saw is replaced or cleared.
        // The crack appears NOW, whenever it was rolled, so the appear flicker plays.
        val crack = outcome.spec?.let { ActiveCrack(it, clock(), CrackCause.UNFOLD) }
        current = crack
        pending = null
        // The pre-chosen crack is shown now: the next hit gets a new one, chosen while the inner screen is in use.
        val next = RollPlanner.nextSpecAfter(outcome, chosen, random)
        nextSpec = next
        publish()
        if (outcome.consumedNext) Log.i(DIAG_TAG, "next crack chosen: seed=${next.seed} (seed ${chosen.seed} shown)")
        settings.recordUnfold(result.newMissStreak, consumeCrackNext = now.crackNextUnfold, active = crack, nextSpec = next).logFailure()
        append(
            LogEntry(
                timeEpochMs = clock(),
                kind = if (result.hit) LogKind.UNFOLD_HIT else LogKind.UNFOLD_MISS,
                reason = result.reason,
                roll = result.roll.takeIf { it != CrackTrigger.NOT_ROLLED },
                chancePercent = now.chancePercent,
                missStreak = RollPlanner.logStreak(result, streak),
                authority = authority,
                hingeSource = hingeSource(),
            ),
        )
    }

    private suspend fun onFold() {
        posture = Posture.FOLDED
        epoch++
        val hadCrack = current != null
        current = null
        // The decision for the next unfold, made now so the Real wallpaper mode can stage it while folded.
        pending = nextPending()
        publish()
        settings.recordFold(pending).logFailure()
        append(LogEntry(clock(), if (hadCrack) LogKind.FOLD_CLEARED else LogKind.FOLD, authority = monitor.snapshot.value.authority))
    }

    /**
     * While folded: makes sure the pending roll is the right one for the current settings (rolled when missing or
     * stale, dropped when the app is off). Returns whether it changed. Not folded: nothing to do.
     */
    private suspend fun keepPendingLocked(): Boolean {
        if (posture != Posture.FOLDED) return false
        val next = nextPending()
        if (next == pending) return false
        pending = next
        settings.savePendingRoll(next).logFailure()
        return true
    }

    /** The pending roll the current settings call for: the existing one while it is still current. */
    private suspend fun nextPending(): PendingRoll? {
        val now = settings.settings.first()
        if (!now.enabled) return null
        val input = TriggerInput(now.chancePercent, now.guaranteeAfter, settings.missStreak.first(), now.crackNextUnfold)
        return pending.takeIf { RollPlanner.isCurrent(it, input) } ?: RollPlanner.preRoll(input, random, nextSpecLocked())
    }

    /**
     * The pre-chosen next crack. Restored (or chosen) before any transition is handled; the fallback only covers
     * a call that would otherwise have nothing, and keeps it for the next call.
     */
    private fun nextSpecLocked(): CrackSpec = nextSpec ?: RollPlanner.newSpec(random).also { nextSpec = it }

    private suspend fun clearLocked(kind: LogKind) {
        current = null
        publish()
        settings.saveActiveCrack(null).logFailure()
        append(LogEntry(clock(), kind, authority = monitor.snapshot.value.authority))
    }

    private fun publish() {
        _state.value = CrackState(posture, pending, current, nextSpec, epoch)
        _active.value = current
    }

    private fun hingeSource() = monitor.snapshot.value.hinge?.source

    private suspend fun append(entry: LogEntry) = log.append(entry).logFailure()

    private fun Result<Unit>.logFailure() = onFailure { Log.w(TAG, "Could not save crack state", it) }

    private companion object {
        const val TAG = "CrackController"

        /** Diagnostics a filtered logcat capture reads alongside the Real wallpaper mode's (prefix "Hingegate"). */
        const val DIAG_TAG = "HingegateCrack"
    }
}
