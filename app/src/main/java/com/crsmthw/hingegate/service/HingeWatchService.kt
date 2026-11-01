package com.crsmthw.hingegate.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.crsmthw.hingegate.HingegateApplication
import com.crsmthw.hingegate.MainActivity
import com.crsmthw.hingegate.R
import com.crsmthw.hingegate.data.CrackMode
import com.crsmthw.hingegate.data.Settings
import com.crsmthw.hingegate.data.SurfaceRules
import com.crsmthw.hingegate.di.AppContainer
import com.crsmthw.hingegate.domain.ActiveCrack
import com.crsmthw.hingegate.overlay.CrackOverlayView
import com.crsmthw.hingegate.posture.PostureSnapshot
import com.crsmthw.hingegate.render.CrackRenderer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.provider.Settings as SystemSettings

/**
 * The one foreground service (`specialUse`), for the two modes nothing else keeps alive (docs/DESIGN.md §11,
 * docs/SWAP-MODE.md §4):
 * - **Over everything**: only while a crack is showing on the unfolded screen, it adds a full-display overlay
 *   window that draws it. No idle window: with nothing to show, there is no window at all.
 * - **Real wallpaper**: it only keeps the process (and so the wallpaper swapper) alive across folds; no window.
 * (The live wallpaper is bound by the system and needs no service.)
 */
class HingeWatchService : LifecycleService() {

    private lateinit var container: AppContainer
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private var attachment: AutoCloseable? = null
    private var view: CrackOverlayView? = null
    private var renderer: CrackRenderer? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as HingegateApplication).container
        container.serviceRunning.value = true
        lifecycleScope.launch {
            combine(
                container.settingsRepository.settings,
                container.crackController.active,
                container.postureMonitor.snapshot,
            ) { settings, active, snapshot -> Frame(settings, active, snapshot) }
                .collect(::render)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Promoted on EVERY start, first thing: a service started with startForegroundService must call
        // startForeground even if it is about to stop, or the system crashes the app.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeView()
        releaseWindowContext()
        container.serviceRunning.value = false
        super.onDestroy()
    }

    private fun render(frame: Frame) {
        if (!ServiceStarter.shouldRun(this, frame.settings)) {
            removeView()
            stopSelf()
            return
        }
        val overlayMode = frame.settings.mode == CrackMode.OVERLAY && SystemSettings.canDrawOverlays(this)
        if (!overlayMode) {
            // The Real wallpaper mode draws nothing here; the overlay's window context goes with the mode.
            removeView()
            releaseWindowContext()
            return
        }
        // Created while the overlay mode is on (it is the posture monitor's Window Manager source then, too).
        ensureWindowContext()
        val active = frame.active
        if (active != null && SurfaceRules.drawsCrack(CrackMode.OVERLAY, frame.settings, active, frame.snapshot.posture)) {
            ensureView()?.show(active, frame.snapshot)
        } else {
            removeView()
        }
    }

    /**
     * A window context of the overlay type: its configuration follows the display (not the process), it is the
     * UI context Window Manager needs, and overlay windows are added through it. Only created with "Display over
     * other apps" granted: the system may refuse an overlay window context without it.
     */
    private fun ensureWindowContext() {
        if (windowContext != null) return
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val context = try {
            createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "Overlay window context refused", e)
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "Overlay window context not allowed", e)
            return
        }
        windowContext = context
        windowManager = context.getSystemService(WindowManager::class.java)
        attachment = container.postureMonitor.attach(context)
    }

    private fun releaseWindowContext() {
        attachment?.close()
        attachment = null
        windowManager = null
        windowContext = null
    }

    private fun ensureView(): CrackOverlayView? {
        view?.let { return it }
        val context = windowContext ?: return null
        val manager = windowManager ?: return null
        val params = layoutParams(context)
        val created = CrackOverlayView(context).apply {
            bind(
                renderer = renderer ?: CrackRenderer(container.crackPalette).also { renderer = it },
                hingeFor = container.postureMonitor::hingeFor,
                onResize = container.postureMonitor::refresh,
                windowAlpha = params.alpha,
            )
        }
        return try {
            manager.addView(created, params)
            view = created
            created
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Overlay window refused", e)
            null
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the add.
            Log.w(TAG, "Overlay permission missing", e)
            null
        }
    }

    private fun removeView() {
        val current = view ?: return
        view = null
        try {
            windowManager?.removeView(current)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay window already gone", e)
        }
    }

    private fun layoutParams(context: Context): WindowManager.LayoutParams {
        // Since Android 12, touches only pass through another app's overlay window if its opacity is at most
        // the system's obscuring limit (0.8 by default). Above it, every touch under the crack would be blocked,
        // so the window is capped at that limit; the view scales the crack's own alphas up to make up for it, so
        // only its near-opaque parts end up slightly translucent.
        val touchLimit = context.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch ?: MAX_ALPHA
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // Windows added from a service are software-rendered unless they ask.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            alpha = minOf(MAX_ALPHA, touchLimit)
            title = getString(R.string.app_name)
        }
    }

    /** False when the system refuses (a sticky restart or boot start without an exemption). */
    private fun enterForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Not allowed to run in the foreground now", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Foreground service type refused", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val manager = NotificationManagerCompat.from(this)
        // Same channel id as before the service was renamed, so an existing channel (and the user's choice for
        // it) carries over; only its name and description are updated.
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.service_channel_name))
                .setDescription(getString(R.string.service_channel_description))
                .setShowBadge(false)
                .build(),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private data class Frame(val settings: Settings, val active: ActiveCrack?, val snapshot: PostureSnapshot)

    private companion object {
        const val TAG = "HingeWatchService"
        const val CHANNEL_ID = "overlay"
        const val NOTIFICATION_ID = 1
        const val MAX_ALPHA = 0.8f
    }
}
