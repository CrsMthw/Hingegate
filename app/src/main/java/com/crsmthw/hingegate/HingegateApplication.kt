package com.crsmthw.hingegate

import android.app.Application
import com.crsmthw.hingegate.di.AppContainer

/**
 * Process entry point: builds the [AppContainer] once, before any Activity, service or wallpaper engine exists,
 * and starts watching the fold. The controller starts FIRST so it is subscribed to the monitor's transitions
 * before the monitor can emit one.
 */
class HingegateApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.crackController.start()
        container.postureMonitor.start()
    }
}
