package com.crsmthw.hingegate.overlay

import android.app.ForegroundServiceStartNotAllowedException
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
import com.crsmthw.hingegate.posture.PostureSnapshot
import com.crsmthw.hingegate.render.CrackRenderer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.provider.Settings as SystemSettings

/**
 * The overlay mode (docs/DESIGN.md §11): a `specialUse` foreground service that keeps the process alive and, only
 * while a crack is showing on the unfolded screen, adds a full-display overlay window that draws it. No idle
 * window: when there is nothing to show, there is no window at all.
 */
class CrackOverlayService : LifecycleService() {

    private lateinit var container: AppContainer
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private var attachment: AutoCloseable? = null
    private var view: CrackOverlayView? = null
    private var renderer: CrackRenderer? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as HingegateApplication).container
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            // A window context of the overlay type: its configuration follows the display (not the process), it
            // is the UI context Window Manager needs, and overlay windows are added through it.
            val context = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            windowContext = context
            windowManager = context.getSystemService(WindowManager::class.java)
            attachment = container.postureMonitor.attach(context)
        }
        container.overlayRunning.value = true
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
        attachment?.close()
        attachment = null
        container.overlayRunning.value = false
        super.onDestroy()
    }

    private fun render(frame: Frame) {
        if (!SurfaceRules.overlayShouldRun(frame.settings, SystemSettings.canDrawOverlays(this))) {
            removeView()
            stopSelf()
            return
        }
        val active = frame.active
        if (active != null && SurfaceRules.drawsCrack(CrackMode.OVERLAY, frame.settings, active, frame.snapshot.posture)) {
            ensureView()?.show(active, frame.snapshot)
        } else {
            removeView()
        }
    }

    private fun ensureView(): CrackOverlayView? {
        view?.let { return it }
        val context = windowContext ?: return null
        val manager = windowManager ?: return null
        val created = CrackOverlayView(context).apply {
            bind(
                renderer = renderer ?: CrackRenderer(container.crackPalette).also { renderer = it },
                hingeFor = container.postureMonitor::hingeFor,
                onResize = container.postureMonitor::refresh,
            )
        }
        return try {
            manager.addView(created, layoutParams(context))
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
        // so the window is capped at that limit even though the crack is then slightly translucent.
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

    private fun buildNotification(): android.app.Notification {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.overlay_channel_name))
                .setDescription(getString(R.string.overlay_channel_description))
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
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
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
        const val TAG = "CrackOverlayService"
        const val CHANNEL_ID = "overlay"
        const val NOTIFICATION_ID = 1
        const val MAX_ALPHA = 0.8f
    }
}
