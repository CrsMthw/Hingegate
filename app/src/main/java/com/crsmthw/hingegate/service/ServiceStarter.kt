package com.crsmthw.hingegate.service

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.crsmthw.hingegate.data.Settings
import com.crsmthw.hingegate.data.SurfaceRules
import android.provider.Settings as SystemSettings

/**
 * Starts or stops [HingeWatchService] to match the settings and the permissions (docs/DESIGN.md §11,
 * docs/SWAP-MODE.md §4): the overlay mode with "Display over other apps", or the Real wallpaper mode with All
 * files access.
 */
object ServiceStarter {

    private const val TAG = "ServiceStarter"

    fun shouldRun(context: Context, settings: Settings): Boolean = SurfaceRules.serviceShouldRun(
        settings,
        canDrawOverlays = SystemSettings.canDrawOverlays(context),
        allFilesAccess = Environment.isExternalStorageManager(),
    )

    /** [reason] only labels the logcat line (process start, boot or update, the app's screen). */
    fun sync(context: Context, settings: Settings, reason: String) {
        val intent = Intent(context, HingeWatchService::class.java)
        if (shouldRun(context, settings)) {
            try {
                ContextCompat.startForegroundService(context, intent)
                Log.i(TAG, "Service started ($reason)")
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (an IllegalStateException): the app is in the
                // background without an exemption. The next resume of the app, or the next boot, starts it.
                Log.w(TAG, "Service not allowed to start now ($reason)", e)
            }
        } else {
            context.stopService(intent)
        }
    }
}
