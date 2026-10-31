package com.crsmthw.hingegate.ui.home

import android.Manifest
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crsmthw.hingegate.R
import com.crsmthw.hingegate.data.CrackMode
import com.crsmthw.hingegate.domain.CrackSpecGenerator
import com.crsmthw.hingegate.domain.ImageSlot
import com.crsmthw.hingegate.render.CrackPalette
import com.crsmthw.hingegate.wallpaper.HingegateWallpaperService
import kotlin.random.Random
import android.provider.Settings as SystemSettings

/**
 * The app's one screen (docs/DESIGN.md §13). On the unfolded inner screen (600 dp and wider) the sections sit in
 * two columns — the preview and the setup on the left, the odds, the buttons and the log on the right; on the
 * cover screen they stack in one.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, palette: CrackPalette, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permissions and the wallpaper can change while the app is in the background (Settings, the picker).
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshSystem()
        onPauseOrDispose { }
    }

    val snackbarHost = remember { SnackbarHostState() }
    val saveFailed = stringResource(R.string.error_save)
    val imageFailed = stringResource(R.string.error_image)
    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(if (shown == HomeMessage.SAVE_FAILED) saveFailed else imageFailed)
        viewModel.messageShown()
    }

    val pickMain = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.importImage(ImageSlot.MAIN, it) }
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.importImage(ImageSlot.COVER, it) }
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Denied before: the system no longer asks, so the app's notification settings are the way.
        if (!granted) openNotificationSettings(context)
        viewModel.refreshSystem()
    }

    var previewSeed by rememberSaveable { mutableLongStateOf(INITIAL_PREVIEW_SEED) }
    var flickerKey by rememberSaveable { mutableIntStateOf(0) }
    val previewSpec = remember(previewSeed) { CrackSpecGenerator.generate(Random(previewSeed)) }
    val windowSize = LocalWindowInfo.current.containerSize

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                subtitle = { Text(statusText(HomeStatus.of(state.settings, state.active, state.snapshot.posture))) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { innerPadding ->
        // Nothing until DataStore's first read: the defaults would flash before the stored settings.
        if (!state.loaded) return@Scaffold

        val primary: @Composable ColumnScope.() -> Unit = {
            PreviewSection(
                spec = previewSpec,
                palette = palette,
                snapshot = state.snapshot,
                image = state.previewImage,
                fallbackPanelWidth = windowSize.width,
                fallbackPanelHeight = windowSize.height,
                flickerKey = flickerKey,
                onShuffle = {
                    previewSeed = Random.nextLong()
                    flickerKey += 1
                },
            )
            MasterSection(enabled = state.settings.enabled, onEnabled = viewModel::setEnabled)
            ModeSection(mode = state.settings.mode, onMode = viewModel::setMode) {
                when (state.settings.mode) {
                    CrackMode.OVERLAY -> OverlaySetup(
                        canDrawOverlays = state.system.canDrawOverlays,
                        notificationsAllowed = state.system.notificationsAllowed,
                        serviceRunning = state.overlayRunning,
                        onAllowOverlay = { openOverlaySettings(context) },
                        onAllowNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                openNotificationSettings(context)
                            }
                        },
                    )
                    CrackMode.WALLPAPER -> WallpaperSetup(
                        thumbnails = state.thumbnails,
                        wallpaperSet = state.system.wallpaperSet,
                        onChoose = { slot ->
                            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            if (slot == ImageSlot.MAIN) pickMain.launch(request) else pickCover.launch(request)
                        },
                        onRemove = viewModel::removeImage,
                        onSetWallpaper = { openLiveWallpaperPicker(context) },
                    )
                }
            }
        }
        val secondary: @Composable ColumnScope.() -> Unit = {
            FrequencySection(
                chancePercent = state.settings.chancePercent,
                guaranteeAfter = state.settings.guaranteeAfter,
                onChance = viewModel::setChance,
                onGuarantee = viewModel::setGuaranteeAfter,
            )
            TryItSection(
                crackNextUnfold = state.settings.crackNextUnfold,
                posture = state.snapshot.posture,
                crackActive = state.active != null,
                onCrackNext = viewModel::setCrackNextUnfold,
                onShowNow = viewModel::showCrackNow,
                onClear = viewModel::clearCrack,
            )
            DetectionSection(snapshot = state.snapshot)
            LogSection(entries = state.log, onClear = viewModel::clearLog)
        }

        BoxWithConstraints(Modifier.fillMaxSize().padding(innerPadding)) {
            val twoColumns = maxWidth >= TWO_COLUMN_MIN_WIDTH
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding, vertical = ScreenPadding / 2),
            ) {
                if (twoColumns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ScreenPadding), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ScreenPadding), content = primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ScreenPadding), content = secondary)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(ScreenPadding)) {
                        primary()
                        secondary()
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(status: HomeStatus): String = stringResource(
    when (status) {
        HomeStatus.OFF -> R.string.status_off
        HomeStatus.LEARNING -> R.string.status_learning
        HomeStatus.FOLDED -> R.string.status_folded
        HomeStatus.UNFOLDED_CRACKED -> R.string.status_unfolded_cracked
        HomeStatus.UNFOLDED_CLEAN -> R.string.status_unfolded_clean
    },
)

private fun openOverlaySettings(context: Context) {
    start(context, Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
}

private fun openNotificationSettings(context: Context) {
    start(context, Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName))
}

/** Straight to Hingegate's page in the live-wallpaper picker, else the plain picker (some launchers lack the first). */
private fun openLiveWallpaperPicker(context: Context) {
    val ours = ComponentName(context, HingegateWallpaperService::class.java)
    val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ours)
    if (!start(context, direct)) start(context, Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
}

/** Tried rather than resolved first: resolving would need a package-visibility query (lint QueryPermissionsNeeded). */
private fun start(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    Log.w(TAG, "No activity for ${intent.action}", e)
    false
}

private const val TAG = "HomeScreen"

/** The first preview a fresh screen shows: a fixed crack, so the screen looks the same on every open. */
private const val INITIAL_PREVIEW_SEED = 31_10_2026L

private val TWO_COLUMN_MIN_WIDTH = 600.dp
private val ScreenPadding = 16.dp
