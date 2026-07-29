package com.nesstation.app

import android.app.Application
import android.util.Log
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.storage.AppContainer
import com.nesstation.app.core.storage.SettingsRepository
import java.io.File

/**
 * Application entry point.
 *
 * Design rules (learned from crash logs):
 *  1. onCreate() must NEVER throw — no matter what fails, the UI must load.
 *  2. NO eager initialisation of third-party libs (Room, DataStore, JNI) in
 *     onCreate(). Everything is lazy so a missing/stripped class degrades
 *     gracefully instead of producing ExceptionInInitializerError.
 *  3. A global UncaughtExceptionHandler logs every uncaught throw and
 *     swallows non-fatal ones so a rogue background thread can't kill the app.
 */
class NesApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Install global crash guard FIRST — before anything else.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("NesApp", "Uncaught on ${thread.name}", throwable)
            // For the main thread we still let the default handler run so the
            // user sees the dialog; for background threads we swallow to keep
            // the app alive.
            if (thread === Thread.currentThread() && thread.name == "main") {
                previous?.uncaughtException(thread, throwable)
            }
        }

        // 2. Set the singleton reference — this is safe, just an assignment.
        instance = this

        // 3. Initialise subsystems ONE BY ONE. Each is wrapped in its own
        //    try-catch so a failure in one doesn't prevent the others.
        tryInit("SettingsRepository") { SettingsRepository.init(this) }
        tryInit("AppContainer")       { _container = AppContainer(this) }
        tryInit("NesEngine")          { NesEngine.ensureLoaded() }
        tryInit("FdsBios")            { cleanupFdsBios() }
    }

    /**
     * Check and clean up the FDS BIOS file. The previous version deployed a
     * corrupted disksys.rom from assets (all-zero data, wrong MD5). This
     * function removes any corrupted BIOS so users can import a valid one
     * via Settings → FDS BIOS导入.
     *
     * The FDS BIOS (disksys.rom, 8KB, MD5: ca30b50f880eb660a320674ed365ef7a)
     * is Nintendo's copyrighted code and cannot be bundled with the app.
     * Users must obtain it from a legal source and import it manually.
     */
    private fun cleanupFdsBios() {
        val dest = File(filesDir, "disksys.rom")
        if (!dest.exists()) return

        // Check if the existing BIOS is corrupted (wrong size or all zeros)
        var corrupted = false
        if (dest.length() != 8192L) {
            corrupted = true
        } else {
            try {
                dest.inputStream().use { input ->
                    val header = ByteArray(64)
                    input.read(header)
                    if (header.all { it == 0.toByte() }) {
                        corrupted = true
                    }
                }
            } catch (e: Exception) {
                corrupted = true
            }
        }

        if (corrupted) {
            dest.delete()
            Log.w("NesApp", "Removed corrupted FDS BIOS. Please import a valid disksys.rom via Settings.")
        }
    }

    /** Container is lazy-nullable: null if init failed, created on first successful init. */
    val container: AppContainer?
        get() = _container ?: tryInit("AppContainer-lazy") {
            _container = AppContainer(this)
        }.let { _container }

    private var _container: AppContainer? = null

    private fun tryInit(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("NesApp", "Init [$tag] failed", t)
        }
    }

    companion object {
        @Volatile private var instance: NesApp? = null

        /** Returns the Application instance, or null if onCreate hasn't run yet. */
        fun get(): NesApp? = instance

        /**
         * Returns the Application instance, throwing if not yet created.
         * Use only in contexts where the app is guaranteed to be running.
         */
        fun require(): NesApp =
            instance ?: throw IllegalStateException("NesApp not yet created")
    }
}
