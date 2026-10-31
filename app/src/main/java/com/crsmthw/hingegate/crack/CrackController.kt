package com.crsmthw.hingegate.crack

import android.util.Log
import com.crsmthw.hingegate.data.CrackLog
import com.crsmthw.hingegate.data.SettingsRepository
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.CrackCause
import com.crsmthw.hingegate.domain.CrackSpecGenerator
import com.crsmthw.hingegate.domain.CrackTrigger
import com.crsmthw.hingegate.domain.HitReason
import com.crsmthw.hingegate.domain.LogEntry
import com.crsmthw.hingegate.domain.LogKind
import com.crsmthw.hingegate.domain.Transition
import com.crsmthw.hingegate.domain.TriggerInput
import com.crsmthw.hingegate.posture.PostureMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Owns "is a crack showing" (docs/DESIGN.md §9.2). One per process, like the monitor it listens to, so each unfold
 * is rolled exactly once however many surfaces are alive. The mode does not matter here: each surface decides for
 * itself whether it draws.
 */
class CrackController(
    private val settings: SettingsRepository,
    private val log: CrackLog,
    private val monitor: PostureMonitor,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _active = MutableStateFlow<ActiveCrack?>(null)
    val active: StateFlow<ActiveCrack?> = _active.asStateFlow()

    private val restored = CompletableDeferred<Unit>()

    // Transitions, the buttons and the master switch all read-modify-write the same state across suspensions.
    private val mutex = Mutex()
    private var started = false

    /** Idempotent; called from `Application.onCreate` BEFORE the monitor starts, so no transition is missed. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            _active.value = settings.loadActiveCrack()
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
        // Switching the app off clears a crack that is showing.
        scope.launch {
            settings.settings.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) {
                    restored.await()
                    mutex.withLock { if (_active.value != null) clearLocked(LogKind.MANUAL_CLEAR) }
                }
            }
        }
    }

    /** "Show crack now": a fresh crack, regardless of the odds. */
    fun showNow() {
        scope.launch {
            restored.await()
            mutex.withLock {
                val crack = newCrack(CrackCause.MANUAL)
                _active.value = crack
                settings.saveActiveCrack(crack).logFailure()
                append(LogEntry(clock(), LogKind.MANUAL_SHOW, authority = monitor.snapshot.value.authority, hingeSource = hingeSource()))
            }
        }
    }

    fun clear() {
        scope.launch {
            restored.await()
            mutex.withLock { if (_active.value != null) clearLocked(LogKind.MANUAL_CLEAR) }
        }
    }

    private suspend fun onUnfold() {
        val current = settings.settings.first()
        val authority = monitor.snapshot.value.authority
        if (!current.enabled) {
            append(LogEntry(clock(), LogKind.UNFOLD_DISABLED, authority = authority, hingeSource = hingeSource()))
            return
        }
        val streak = settings.missStreak.first()
        val result = CrackTrigger.decide(
            TriggerInput(current.chancePercent, current.guaranteeAfter, streak, current.crackNextUnfold),
            random,
        )
        // Every unfold decides afresh: a crack left over from a fold this process never saw is replaced or cleared.
        val crack = if (result.hit) newCrack(CrackCause.UNFOLD) else null
        _active.value = crack
        settings.recordUnfold(result.newMissStreak, consumeCrackNext = current.crackNextUnfold, active = crack).logFailure()
        append(
            LogEntry(
                timeEpochMs = clock(),
                kind = if (result.hit) LogKind.UNFOLD_HIT else LogKind.UNFOLD_MISS,
                reason = result.reason,
                roll = result.roll.takeIf { it != CrackTrigger.NOT_ROLLED },
                chancePercent = current.chancePercent,
                missStreak = if (result.reason == HitReason.MISSED) result.newMissStreak else streak,
                authority = authority,
                hingeSource = hingeSource(),
            ),
        )
    }

    private suspend fun onFold() {
        if (_active.value != null) {
            clearLocked(LogKind.FOLD_CLEARED)
        } else {
            append(LogEntry(clock(), LogKind.FOLD, authority = monitor.snapshot.value.authority))
        }
    }

    private suspend fun clearLocked(kind: LogKind) {
        _active.value = null
        settings.saveActiveCrack(null).logFailure()
        append(LogEntry(clock(), kind, authority = monitor.snapshot.value.authority))
    }

    private fun newCrack(cause: CrackCause): ActiveCrack {
        val spec = CrackSpecGenerator.generate(Random(random.nextLong()))
        return ActiveCrack(spec, clock(), cause)
    }

    private fun hingeSource() = monitor.snapshot.value.hinge?.source

    private suspend fun append(entry: LogEntry) = log.append(entry).logFailure()

    private fun Result<Unit>.logFailure() = onFailure { Log.w(TAG, "Could not save crack state", it) }

    private companion object {
        const val TAG = "CrackController"
    }
}
