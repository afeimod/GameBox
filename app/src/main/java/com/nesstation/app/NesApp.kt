package com.nesstation.app

import android.app.Application
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
        NesEngine.ensureLoaded()
        SettingsRepository.init(this)
    }

    companion object {
        @Volatile private var instance: NesApp? = null
        fun get(): NesApp = instance ?: error("NesApp not yet created")
    }
}
