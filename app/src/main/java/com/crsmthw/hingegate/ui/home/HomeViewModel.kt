package com.crsmthw.hingegate.ui.home

import android.app.WallpaperManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.hingegate.data.CrackMode
import com.crsmthw.hingegate.data.Settings
import com.crsmthw.hingegate.di.AppContainer
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.domain.ImageSlot
import com.crsmthw.hingegate.domain.LogEntry
import com.crsmthw.hingegate.overlay.OverlayStarter
import com.crsmthw.hingegate.posture.PostureSnapshot
import com.crsmthw.hingegate.wallpaper.HingegateWallpaperService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.provider.Settings as SystemSettings

/** What only the system knows, re-read on every resume (the user may have just granted something). */
data class SystemStatus(
    val canDrawOverlays: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val wallpaperSet: Boolean = false,
)

/** A one-shot message for the snackbar. */
enum class HomeMessage { SAVE_FAILED, IMAGE_FAILED }

data class HomeUiState(
    val loaded: Boolean = false,
    val settings: Settings = Settings(),
    val active: ActiveCrack? = null,
    val snapshot: PostureSnapshot = PostureSnapshot(),
    val log: List<LogEntry> = emptyList(),
    val system: SystemStatus = SystemStatus(),
    val overlayRunning: Boolean = false,
    /** Small decodes of the two wallpaper images, for the slot rows. */
    val thumbnails: Map<ImageSlot, Bitmap> = emptyMap(),
    /** The main image at preview size (the cover one when only that is set), or null for the theme gradient. */
    val previewImage: Bitmap? = null,
)

/**
 * The one screen's state (docs/DESIGN.md §13). Writes are fire-and-forget in [viewModelScope]: DataStore
 * serialises them and the change comes back through the flows; a failed write surfaces as a [HomeMessage].
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val appContext = container.appContext
    private val settingsRepository = container.settingsRepository

    private val system = MutableStateFlow(readSystem())

    private val _message = MutableStateFlow<HomeMessage?>(null)
    val message: StateFlow<HomeMessage?> = _message.asStateFlow()

    private val images: Flow<Pair<Map<ImageSlot, Bitmap>, Bitmap?>> = container.wallpaperImages.versions
        .map { versions ->
            val thumbnails = versions.keys.mapNotNull { slot ->
                container.wallpaperImages.decode(slot, THUMBNAIL_PX, THUMBNAIL_PX)?.let { slot to it }
            }.toMap()
            val previewSlot = if (ImageSlot.MAIN in versions) ImageSlot.MAIN else versions.keys.firstOrNull()
            val preview = previewSlot?.let { container.wallpaperImages.decode(it, PREVIEW_PX, PREVIEW_PX) }
            thumbnails to preview
        }

    private val core: Flow<HomeUiState> = combine(
        settingsRepository.settings,
        container.crackController.active,
        container.postureMonitor.snapshot,
        container.crackLog.entries,
        system,
    ) { settings, active, snapshot, log, system ->
        HomeUiState(loaded = true, settings = settings, active = active, snapshot = snapshot, log = log, system = system)
    }

    val state: StateFlow<HomeUiState> = combine(core, container.overlayRunning, images) { base, running, (thumbs, preview) ->
        base.copy(overlayRunning = running, thumbnails = thumbs, previewImage = preview)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState())

    init {
        // The overlay service follows the settings and the permission; starting it from here is allowed
        // because the app is in the foreground while this screen is.
        viewModelScope.launch {
            combine(settingsRepository.settings, system) { settings, status -> settings to status.canDrawOverlays }
                .distinctUntilChanged()
                .collect { (settings, _) -> OverlayStarter.sync(appContext, settings) }
        }
    }

    /** On every resume: permissions and the wallpaper may have changed in Settings or the picker. */
    fun refreshSystem() {
        system.value = readSystem()
        // Also re-sync when nothing changed: a service the system refused to restart in the background comes
        // back as soon as the app is opened.
        viewModelScope.launch { OverlayStarter.sync(appContext, settingsRepository.settings.first()) }
    }

    fun setEnabled(enabled: Boolean) = save { settingsRepository.setEnabled(enabled) }

    fun setMode(mode: CrackMode) = save { settingsRepository.setMode(mode) }

    fun setChance(percent: Int) = save { settingsRepository.setChancePercent(percent) }

    fun setGuaranteeAfter(count: Int) = save { settingsRepository.setGuaranteeAfter(count) }

    fun setCrackNextUnfold(enabled: Boolean) = save { settingsRepository.setCrackNextUnfold(enabled) }

    fun showCrackNow() = container.crackController.showNow()

    fun clearCrack() = container.crackController.clear()

    fun clearLog() = save { container.crackLog.clear() }

    fun importImage(slot: ImageSlot, uri: Uri) {
        viewModelScope.launch {
            container.wallpaperImages.import(slot, uri).onFailure { _message.value = HomeMessage.IMAGE_FAILED }
        }
    }

    fun removeImage(slot: ImageSlot) = save { container.wallpaperImages.clear(slot) }

    fun messageShown() {
        _message.value = null
    }

    private fun save(block: suspend () -> Result<Unit>) {
        viewModelScope.launch { block().onFailure { _message.value = HomeMessage.SAVE_FAILED } }
    }

    private fun readSystem(): SystemStatus {
        val ours = ComponentName(appContext, HingegateWallpaperService::class.java)
        return SystemStatus(
            canDrawOverlays = SystemSettings.canDrawOverlays(appContext),
            notificationsAllowed = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
            wallpaperSet = WallpaperManager.getInstance(appContext).wallpaperInfo?.component == ours,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val THUMBNAIL_PX = 192
        const val PREVIEW_PX = 720
    }
}

/**
 * Builds [HomeViewModel] from the app container. `Class.cast` rather than an unchecked `as T`: the factory is only
 * ever asked for this one class, and asking for another fails loudly here instead of later.
 */
class HomeViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            "HomeViewModelFactory cannot create ${modelClass.name}"
        }
        // `Class.cast` is annotated nullable (it passes a null through); the instance here never is.
        return checkNotNull(modelClass.cast(HomeViewModel(container)))
    }
}
