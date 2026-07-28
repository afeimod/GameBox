package com.nesstation.app

import android.app.Application
import android.util.Log
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.storage.AppContainer
import com.nesstation.app.core.storage.SettingsRepository

class NesApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        // Preload the native engine so the first launch is snappy.
        // Wrapped in try-catch: if the .so fails to load, the app still
        // starts — the emulator screen will show an error message.
        try {
            NesEngine.ensureLoaded()
        } catch (e: Throwable) {
            Log.e("NesApp", "Native core load failed", e)
        }
        SettingsRepository.init(this)
    }

    companion object {
        @Volatile private var instance: NesApp? = null
        fun get(): NesApp = instance ?: error("NesApp not yet created")
    }
}
