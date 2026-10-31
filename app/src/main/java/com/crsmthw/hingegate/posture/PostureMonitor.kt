package com.crsmthw.hingegate.posture

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.crsmthw.hingegate.data.SettingsRepository
import com.crsmthw.hingegate.domain.HingeInference
import com.crsmthw.hingegate.domain.HingeLine
import com.crsmthw.hingegate.domain.HingeOrientation
import com.crsmthw.hingegate.domain.HingeSource
import com.crsmthw.hingegate.domain.Posture
import com.crsmthw.hingegate.domain.PostureAuthority
import com.crsmthw.hingegate.domain.PostureMemory
import com.crsmthw.hingegate.domain.PostureResolver
import com.crsmthw.hingegate.domain.Resolution
import com.crsmthw.hingegate.domain.Transition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What every surface needs to know about the fold right now. [hinge] is in the CURRENT display coordinates
 * (logical pixels at [rotation]) and is only set while UNFOLDED. [coverAspect] and [innerWidth]/[innerHeight]
 * are the learned panels (the inner one in its natural, physical size), for previews and image choice.
 */
data class PostureSnapshot(
    val posture: Posture = Posture.UNKNOWN,
    val authority: PostureAuthority = PostureAuthority.NONE,
    val hinge: HingeLine? = null,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val rotation: Int = 0,
    val coverAspect: Float? = null,
    val innerWidth: Int? = null,
    val innerHeight: Int? = null,
)

/**
 * The process's single view of the fold (docs/DESIGN.md §4.3): Android glue around [PostureResolver]. Lives in
 * the AppContainer, so the activity, the overlay service and every wallpaper engine share one monitor — and the
 * controller sees each unfold exactly once.
 *
 * Main-thread only: the display listener runs on the main looper and the container's scope is
 * `Dispatchers.Main.immediate`, so no state here needs locking.
 */
class PostureMonitor(
    context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val displayManager: DisplayManager = checkNotNull(appContext.getSystemService(DisplayManager::class.java))

    private val _snapshot = MutableStateFlow(PostureSnapshot())
    val snapshot: StateFlow<PostureSnapshot> = _snapshot.asStateFlow()

    // No replay: a late subscriber must not act on an old unfold. The buffer covers the controller still
    // restoring its state when the first transition arrives.
    private val _transitions = MutableSharedFlow<Transition>(extraBufferCapacity = TRANSITION_BUFFER)
    val transitions: SharedFlow<Transition> = _transitions.asSharedFlow()

    private var started = false
    private var ready = false
    private var memory = PostureMemory()
    private var lastWritten: PostureMemory? = null
    private var posture = Posture.UNKNOWN
    private var authority = PostureAuthority.NONE
    private var lastSample: Sample? = null
    private var lastAggregate: Boolean? = null
    private var displayContext: Context? = null
    private val sources = LinkedHashMap<Source, SourceState>()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) sample()
        }
    }

    /**
     * Idempotent; called from `Application.onCreate`. The posture memory is loaded BEFORE the listener is
     * registered, so the first observation is judged against what was learned before the restart.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            memory = settings.loadPostureMemory()
            lastWritten = memory
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.let { displayContext = appContext.createDisplayContext(it) }
            displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            ready = true
            sample()
            // Sources attached while the memory was loading have been waiting for this.
            feedWindowManager()
        }
    }

    /** Re-reads the display; surfaces call it when their own size changes (a configuration can trail the display event). */
    fun refresh() = sample()

    /**
     * Adds a Window Manager source for as long as the returned handle is open (docs/DESIGN.md §4.3). [uiContext]
     * must be a UI context: an Activity, a window context, or a wallpaper engine's display context. Each source is
     * tracked separately; only sources whose window spans the whole display count, because a FoldingFeature is
     * only reported to windows that cross the hinge.
     */
    fun attach(uiContext: Context): AutoCloseable {
        val source = Source(uiContext)
        sources[source] = SourceState()
        source.job = scope.launch {
            try {
                WindowInfoTracker.getOrCreate(uiContext).windowLayoutInfo(uiContext).collect { onLayout(source, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // The OEM extension may refuse a non-Activity context; display size still works without it.
                Log.w(TAG, "Window Manager unavailable for $uiContext", e)
            }
        }
        // A UI context knows the display's logical size better than the process-wide display context.
        sample()
        return source
    }

    /**
     * The hinge for a [width] × [height] frame in the current display orientation: Window Manager's, from a
     * qualifying source whose window has exactly that size, else the estimate. Surfaces call this with their own
     * size when the snapshot was taken at a different size (its configuration had not caught up yet).
     */
    fun hingeFor(width: Int, height: Int): HingeLine {
        sources.values.firstOrNull { it.qualifies && it.present && it.windowW == width && it.windowH == height }
            ?.hinge
            ?.let { return it }
        return HingeInference.estimate(width, height, memory.coverAspect)
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private fun sample() {
        if (!ready) return
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val mode = display.mode
        val (logicalW, logicalH) = logicalSize()
        val sample = Sample(
            area = mode.physicalWidth.toLong() * mode.physicalHeight,
            physicalW = mode.physicalWidth,
            physicalH = mode.physicalHeight,
            logicalW = logicalW,
            logicalH = logicalH,
            rotation = display.rotation,
        )
        // Change-only: this panel has adaptive refresh, and onDisplayChanged fires on every refresh-rate switch.
        if (sample == lastSample) return
        lastSample = sample
        apply(PostureResolver.onDisplay(memory, sample.area, sample.physicalW, sample.physicalH))
    }

    /** The newest UI source's window metrics are kept current by the system; the display context is the fallback. */
    private fun logicalSize(): Pair<Int, Int> {
        val context = sources.keys.lastOrNull()?.context ?: displayContext ?: return 0 to 0
        val bounds = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds ?: return 0 to 0
        return bounds.width() to bounds.height()
    }

    // ── Window Manager ───────────────────────────────────────────────────────

    private fun onLayout(source: Source, info: WindowLayoutInfo) {
        if (source !in sources) return
        val windowManager = source.context.getSystemService(WindowManager::class.java) ?: return
        val current = windowManager.currentWindowMetrics.bounds
        val maximum = windowManager.maximumWindowMetrics.bounds
        val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        val present = feature != null &&
            (feature.state == FoldingFeature.State.FLAT || feature.state == FoldingFeature.State.HALF_OPENED)
        val hinge = feature?.let {
            // Bounds are relative to the source's window, which for a qualifying source is the whole display.
            if (it.orientation == FoldingFeature.Orientation.VERTICAL) {
                HingeLine(HingeOrientation.VERTICAL, it.bounds.exactCenterX(), it.bounds.width().toFloat(), HingeSource.WINDOW_MANAGER)
            } else {
                HingeLine(HingeOrientation.HORIZONTAL, it.bounds.exactCenterY(), it.bounds.height().toFloat(), HingeSource.WINDOW_MANAGER)
            }
        }
        sources[source] = SourceState(
            reported = true,
            // A pop-up or split-screen window does not span the display and never sees the hinge.
            qualifies = current == maximum,
            present = present,
            hinge = hinge,
            windowW = maximum.width(),
            windowH = maximum.height(),
        )
        sample()
        feedWindowManager()
        publish()
    }

    /** Feeds "any qualifying source sees the fold" to the resolver, only when it changes. */
    private fun feedWindowManager() {
        if (!ready) return
        val qualifying = sources.values.filter { it.reported && it.qualifies }
        if (qualifying.isEmpty()) return
        val aggregate = qualifying.any { it.present }
        if (aggregate == lastAggregate) return
        lastAggregate = aggregate
        apply(PostureResolver.onWindowManager(memory, aggregate))
    }

    // ── Results ──────────────────────────────────────────────────────────────

    private fun apply(resolution: Resolution) {
        memory = resolution.memory
        posture = resolution.posture
        authority = resolution.authority
        persist()
        // The snapshot first, so whoever handles the transition already sees the new posture and hinge.
        publish()
        resolution.transition?.let { _transitions.tryEmit(it) }
    }

    private fun persist() {
        val toWrite = memory
        if (toWrite == lastWritten) return
        lastWritten = toWrite
        scope.launch {
            settings.savePostureMemory(toWrite).onFailure { Log.w(TAG, "Could not save the posture memory", it) }
        }
    }

    private fun publish() {
        val sample = lastSample
        val width = sample?.logicalW ?: 0
        val height = sample?.logicalH ?: 0
        val hinge = if (posture == Posture.UNFOLDED && width > 0 && height > 0) hingeFor(width, height) else null
        _snapshot.value = PostureSnapshot(
            posture = posture,
            authority = authority,
            hinge = hinge,
            displayWidth = width,
            displayHeight = height,
            rotation = sample?.rotation ?: 0,
            coverAspect = memory.coverAspect,
            innerWidth = memory.largeWidth.takeIf { memory.displayAuthoritative },
            innerHeight = memory.largeHeight.takeIf { memory.displayAuthoritative },
        )
    }

    private data class Sample(
        val area: Long,
        val physicalW: Int,
        val physicalH: Int,
        val logicalW: Int,
        val logicalH: Int,
        val rotation: Int,
    )

    private data class SourceState(
        val reported: Boolean = false,
        val qualifies: Boolean = false,
        val present: Boolean = false,
        val hinge: HingeLine? = null,
        val windowW: Int = 0,
        val windowH: Int = 0,
    )

    /** One attached UI context; identity is the handle itself, so the same context attached twice counts twice. */
    private inner class Source(val context: Context) : AutoCloseable {
        var job: Job? = null

        override fun close() {
            job?.cancel()
            if (sources.remove(this) != null) {
                feedWindowManager()
                publish()
            }
        }
    }

    private companion object {
        const val TAG = "PostureMonitor"
        const val TRANSITION_BUFFER = 8
    }
}
