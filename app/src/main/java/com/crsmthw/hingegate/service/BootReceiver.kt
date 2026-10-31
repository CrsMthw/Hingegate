package com.crsmthw.hingegate.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.crsmthw.hingegate.HingegateApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the foreground service after a reboot or an app update. Both broadcasts are exempt from the
 * background foreground-service start restrictions, and `specialUse` is not one of the types Android 15 bars
 * from BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Only the two system broadcasts this receiver is declared for; anything else is ignored.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as HingegateApplication).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                ServiceStarter.sync(context.applicationContext, container.settingsRepository.settings.first())
            } finally {
                pending.finish()
            }
        }
    }
}
