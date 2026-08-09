package com.nesstation.app

import android.app.Application
import android.util.Log
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.engine.SnesEngine
import com.nesstation.app.core.engine.GbaEngine
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

        // 3. Initialize J2ME ContextHolder FIRST so Config's static block
        //    can get a valid app context when any J2ME class is loaded.
        tryInit("J2ME-ContextHolder") { javax.microedition.util.ContextHolder.setApplication(this) }

        // 4. Initialise subsystems ONE BY ONE. Each is wrapped in its own
        //    try-catch so a failure in one doesn't prevent the others.
        tryInit("SettingsRepository") { SettingsRepository.init(this) }
        tryInit("AppContainer")       { _container = AppContainer(this) }
        tryInit("NesEngine")          { NesEngine.ensureLoaded() }
        tryInit("SnesEngine")         { SnesEngine.ensureLoaded() }
        tryInit("GbaEngine")          { GbaEngine.ensureLoaded() }
        tryInit("FdsBios")            { ensureFdsBios() }
    }

    /**
     * Auto-extract FDS BIOS (disksys.rom) from APK assets to filesDir.
     *
     * Called on every app start. Always re-extracts from assets to pick up
     * any BIOS update the developer made to app/src/main/assets/disksys.rom.
     *
     * We intentionally do NOT validate BIOS content (reset vector, NOP
     * counting, hash, etc.) because:
     *   1. The FCEUmm core itself validates the BIOS during retro_load_game
     *      and reports a clear error if the BIOS is bad
     *   2. Overly strict frontend validation risks rejecting valid BIOS dumps
     *      and causing "BIOS missing" errors
     *   3. The developer bundles the BIOS in assets — we trust their file
     *
     * The only checks are: size == 8192 (sanity) and file is readable.
     */
    private fun ensureFdsBios() {
        val dest = File(filesDir, "disksys.rom")

        // Always try to extract from assets — this picks up BIOS updates
        // (e.g. when the developer replaces the file in assets/).
        try {
            assets.open("disksys.rom").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            // Basic sanity check: size must be 8192 bytes.
            // If it's wrong, delete so the user can import manually.
            if (dest.length() != 8192L) {
                dest.delete()
                Log.w("NesApp", "FDS BIOS in assets has wrong size ${dest.length()}, deleted")
                return
            }
            Log.i("NesApp", "FDS BIOS extracted from assets to ${dest.absolutePath} (${dest.length()} bytes)")
            return
        } catch (e: java.io.FileNotFoundException) {
            // No disksys.rom in assets — keep any existing file (user may
            // have imported one manually via Settings).
            Log.i("NesApp", "No disksys.rom in assets; using existing file if present")
        } catch (e: Exception) {
            Log.w("NesApp", "Failed to extract FDS BIOS from assets", e)
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
