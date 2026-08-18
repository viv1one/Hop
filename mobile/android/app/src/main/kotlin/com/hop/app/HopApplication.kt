package com.hop.app

import android.app.Application

/**
 * App entry point. Constructs the single [AppContainer] instance for the
 * process's lifetime -- every screen reaches its dependencies via
 * `(application as HopApplication).container`, never by constructing its own.
 */
class HopApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}
