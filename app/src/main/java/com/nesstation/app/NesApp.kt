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
        tryInit("FdsBios")            { ensureFdsBios() }
    }

    /**
     * Auto-extract FDS BIOS (disksys.rom) from APK assets to filesDir.
     *
     * If a valid disksys.rom is bundled in app/src/main/assets/, it is
     * automatically copied to filesDir on first launch, enabling FDS games
     * to run without manual BIOS import.
     *
     * If the file is not in assets, or is invalid, this is a no-op and the
     * user can still manually import via Settings.
     */
    private fun ensureFdsBios() {
        val dest = File(filesDir, "disksys.rom")

        // If a valid BIOS already exists, keep it
        if (dest.exists() && dest.length() == 8192L) {
            try {
                dest.inputStream().use { input ->
                    val header = ByteArray(64)
                    input.read(header)
                    if (!header.all { it == 0.toByte() }) {
                        // Valid BIOS already present
                        return
                    }
                }
            } catch (_: Exception) { }
        }

        // Try to extract from assets
        try {
            assets.open("disksys.rom").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            // Verify the extracted file
            if (dest.length() != 8192L) {
                dest.delete()
                Log.w("NesApp", "FDS BIOS in assets has wrong size, deleted")
                return
            }
            // Verify it's not all zeros (corrupted)
            dest.inputStream().use { input ->
                val header = ByteArray(64)
                input.read(header)
                if (header.all { it == 0.toByte() }) {
                    dest.delete()
                    Log.w("NesApp", "FDS BIOS in assets is corrupted (all zeros), deleted")
                    return
                }
            }
            Log.i("NesApp", "FDS BIOS extracted from assets to ${dest.absolutePath}")
        } catch (e: java.io.FileNotFoundException) {
            // No disksys.rom in assets — user must import manually
            // Also clean up any leftover corrupted file
            if (dest.exists() && dest.length() != 8192L) {
                dest.delete()
            }
        } catch (e: Exception) {
            Log.w("NesApp", "Failed to extract FDS BIOS from assets", e)
            if (dest.exists() && dest.length() != 8192L) {
                dest.delete()
            }
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
