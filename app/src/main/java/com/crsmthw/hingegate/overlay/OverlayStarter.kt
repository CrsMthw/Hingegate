package com.crsmthw.hingegate.overlay

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.crsmthw.hingegate.data.Settings
import com.crsmthw.hingegate.data.SurfaceRules
import android.provider.Settings as SystemSettings

/** Starts or stops the overlay's foreground service to match the settings and the permission (docs/DESIGN.md §11). */
object OverlayStarter {

    private const val TAG = "OverlayStarter"

    fun sync(context: Context, settings: Settings) {
        val intent = Intent(context, CrackOverlayService::class.java)
        if (SurfaceRules.overlayShouldRun(settings, SystemSettings.canDrawOverlays(context))) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (an IllegalStateException): the app is in the
                // background without an exemption. The next resume of the app, or the next boot, starts it.
                Log.w(TAG, "Overlay service not allowed to start now", e)
            }
        } else {
            context.stopService(intent)
        }
    }
}
