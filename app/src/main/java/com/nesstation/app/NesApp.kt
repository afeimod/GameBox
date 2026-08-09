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
     * Strategy:
     *   - If filesDir/disksys.rom already exists AND is valid (size 8192,
     *     reset vector points into BIOS region 0xE000-0xFFFF), keep it —
     *     the user may have imported a real BIOS via Settings.
     *   - Otherwise, extract from assets and validate.
     *   - If the assets BIOS is also invalid, delete it so the user gets
     *     a clear "BIOS missing" error instead of a silent gray screen.
     *
     * Why validate the reset vector:
     *   A corrupted/fake disksys.rom (e.g. one filled with NOP padding with
     *   a reset vector pointing to zero-page RAM 0x00xx) will be accepted
     *   by FCEUmm without complaint, but the CPU will never boot the BIOS
     *   and the screen stays gray. The reset vector check catches this.
     *   A REAL FDS BIOS always has its reset vector in 0xE000-0xFFFF because
     *   that's where the BIOS is mapped in the CPU address space.
     */
    private fun ensureFdsBios() {
        val dest = File(filesDir, "disksys.rom")

        // If a valid BIOS already exists (imported via Settings), keep it.
        // This prevents the assets BIOS from overwriting a user-imported one.
        if (dest.exists() && isValidFdsBios(dest)) {
            Log.i("NesApp", "FDS BIOS already present and valid: ${dest.absolutePath}")
            return
        }

        // Try to extract from assets
        try {
            assets.open("disksys.rom").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (!isValidFdsBios(dest)) {
                dest.delete()
                Log.w("NesApp", "FDS BIOS in assets is invalid (bad reset vector or wrong size), deleted. " +
                        "Please place a real disksys.rom (MD5 ca30b50f880eb660a4062209e9986140) in assets/ " +
                        "or import via Settings.")
                return
            }
            Log.i("NesApp", "FDS BIOS extracted from assets to ${dest.absolutePath}")
        } catch (e: java.io.FileNotFoundException) {
            // No disksys.rom in assets — user must import manually
            Log.i("NesApp", "No disksys.rom in assets; user must import via Settings")
        } catch (e: Exception) {
            Log.w("NesApp", "Failed to extract FDS BIOS from assets", e)
        }
    }

    /**
     * Validates an FDS BIOS file:
     *   1. Size == 8192 bytes
     *   2. Reset vector (offset 0x1FFC-0x1FFD, little-endian) points into
     *      0xE000-0xFFFF — the BIOS region where FDSInit maps the BIOS.
     *
     * A real FDS BIOS always has its reset vector in this range. A corrupted/
     * fake BIOS (e.g. NOP-padded stub) has a reset vector pointing to 0x00xx
     * (RAM), causing the CPU to execute garbage and produce a gray screen.
     */
    private fun isValidFdsBios(file: File): Boolean {
        if (file.length() != 8192L) return false
        try {
            file.inputStream().use { input ->
                val bytes = input.readBytes()
                if (bytes.size != 8192) return false
                // Reset vector at offset 0x1FFC-0x1FFD (CPU addr 0xFFFC-0xFFFD)
                val resetLo = bytes[0x1FFC].toInt() and 0xFF
                val resetHi = bytes[0x1FFD].toInt() and 0xFF
                val resetVec = (resetHi shl 8) or resetLo
                // Must point into BIOS region 0xE000-0xFFFF
                if (resetVec < 0xE000 || resetVec > 0xFFFF) {
                    Log.w("NesApp", "FDS BIOS reset vector 0x%04X is invalid (must be 0xE000-0xFFFF)".format(resetVec))
                    return false
                }
            }
        } catch (_: Exception) {
            return false
        }
        return true
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
