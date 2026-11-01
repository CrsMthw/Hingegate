package com.crsmthw.hingegate.di

import android.content.Context
import android.os.Build
import com.crsmthw.hingegate.crack.CrackController
import com.crsmthw.hingegate.data.CrackLog
import com.crsmthw.hingegate.data.SettingsRepository
import com.crsmthw.hingegate.data.SwapRepository
import com.crsmthw.hingegate.data.WallpaperImages
import com.crsmthw.hingegate.data.hingegateStore
import com.crsmthw.hingegate.domain.SwapFlags
import com.crsmthw.hingegate.posture.PostureMonitor
import com.crsmthw.hingegate.render.CrackPalette
import com.crsmthw.hingegate.swap.CurrentWallpaperSource
import com.crsmthw.hingegate.swap.PreparedImages
import com.crsmthw.hingegate.swap.SwapRenderer
import com.crsmthw.hingegate.swap.WallpaperSwapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

/**
 * The app's object graph, built by hand: every app-scoped singleton is an eager `val`, created once in
 * `HingegateApplication.onCreate`, and the ViewModel receives what it needs through its factory.
 *
 * ONE process (docs/DESIGN.md §14): the activity, the foreground service and every wallpaper engine share this
 * container, so there is one [PostureMonitor], one [CrackController] and one [WallpaperSwapper] — the "one roll
 * per unfold" guarantee depends on it. Nothing here does I/O on the constructing (main) thread.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /**
     * Lives as long as the process. MAIN, unlike most app scopes: the posture monitor's display listener and
     * window-layout collectors, the controller and the surfaces all touch main-thread-only state, and keeping them
     * on one thread serialises them without locks. DataStore does its own I/O off this thread.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Persisted shapes may gain fields in later versions; unknown keys from a newer build are ignored. */
    val json: Json = Json { ignoreUnknownKeys = true }

    val settingsRepository = SettingsRepository(appContext.hingegateStore, json)

    val crackLog = CrackLog(appContext.hingegateStore, json)

    val wallpaperImages = WallpaperImages(appContext, appContext.hingegateStore)

    val postureMonitor = PostureMonitor(appContext, settingsRepository, appScope)

    val crackController = CrackController(settingsRepository, crackLog, postureMonitor, appScope)

    val swapRepository = SwapRepository(appContext.hingegateStore, json)

    /** Samsung keeps one wallpaper per panel and takes the inner-panel bit (docs/SWAP-MODE.md §1). */
    private val isSamsung: Boolean = SwapFlags.isSamsung(Build.MANUFACTURER)

    val currentWallpaper = CurrentWallpaperSource(appContext, isSamsung)

    /** The images prepared ahead of a fold (docs/SWAP-MODE.md §10.28), with a renderer of their own. */
    private val preparedImages = PreparedImages(appContext, currentWallpaper, SwapRenderer(currentWallpaper) { crackPalette })

    /** The Real wallpaper mode (docs/SWAP-MODE.md §3); renders with the palette, loaded on its first set. */
    val wallpaperSwapper = WallpaperSwapper(
        context = appContext,
        settings = settingsRepository,
        store = swapRepository,
        source = currentWallpaper,
        renderer = SwapRenderer(currentWallpaper) { crackPalette },
        prepared = preparedImages,
        controller = crackController,
        monitor = postureMonitor,
        log = crackLog,
        scope = appScope,
        isSamsung = isSamsung,
    )

    /** Whether the foreground service is running, for the UI's status line. Written by the service. */
    val serviceRunning = MutableStateFlow(false)

    /** Read from resources on first use, so a process started only for the boot receiver never loads it. */
    val crackPalette: CrackPalette by lazy { CrackPalette.from(appContext) }
}
