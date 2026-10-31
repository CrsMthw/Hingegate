package com.crsmthw.hingegate

import android.app.Application
import com.crsmthw.hingegate.di.AppContainer
import com.crsmthw.hingegate.service.ServiceStarter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Process entry point: builds the [AppContainer] once, before any Activity, service or wallpaper engine exists,
 * and starts watching the fold. The controller starts FIRST so it is subscribed to the monitor's transitions
 * before the monitor can emit one; the wallpaper swapper follows the controller.
 *
 * Every process start also brings the foreground service in line with the settings. The update broadcast alone
 * is not enough: One UI force-stops an app that is on screen while it is updated, and Android does not deliver
 * MY_PACKAGE_REPLACED to a force-stopped app (it carries FLAG_EXCLUDE_STOPPED_PACKAGES). When One UI then
 * relaunches the app's process, this start is what brings the service back (if the system allows a start at that
 * moment; otherwise opening the app does, as before).
 */
class HingegateApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.crackController.start()
        container.wallpaperSwapper.start()
        container.postureMonitor.start()
        container.appScope.launch {
            ServiceStarter.sync(this@HingegateApplication, container.settingsRepository.settings.first(), "process start")
        }
    }
}
