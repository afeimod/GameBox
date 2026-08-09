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
     * Validation:
     *   1. Size must be exactly 8192 bytes
     *   2. Must not be all zeros
     *   3. Reset vector (at offset 0x1FFC-0x1FFD) must point into the BIOS
     *      region 0xE000-0xFFFF. A corrupted/stub BIOS has a reset vector
     *      pointing to zero-page RAM (0x00xx), causing the CPU to never
     *      boot the BIOS and producing a permanent gray screen.
     *
     * If the extracted BIOS fails validation, it is deleted and the user
     * must import a valid one manually via Settings.
     */
    private fun ensureFdsBios() {
        val dest = File(filesDir, "disksys.rom")

        // Always try to extract from assets — this picks up BIOS updates
        // (e.g. when the developer replaces a stub with a real BIOS).
        try {
            assets.open("disksys.rom").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (!isValidFdsBios(dest)) {
                dest.delete()
                Log.w("NesApp", "FDS BIOS in assets failed validation, deleted")
                return
            }
            Log.i("NesApp", "FDS BIOS extracted from assets to ${dest.absolutePath}")
            return
        } catch (e: java.io.FileNotFoundException) {
            // No disksys.rom in assets — keep any existing valid BIOS
            if (dest.exists() && !isValidFdsBios(dest)) {
                dest.delete()
                Log.w("NesApp", "Existing FDS BIOS failed validation, deleted")
            }
        } catch (e: Exception) {
            Log.w("NesApp", "Failed to extract FDS BIOS from assets", e)
            if (dest.exists() && !isValidFdsBios(dest)) {
                dest.delete()
            }
        }
    }

    /**
     * Validates an FDS BIOS file:
     *   1. Size == 8192 bytes
     *   2. Not all zeros
     *   3. Reset vector (offset 0x1FFC-0x1FFD, little-endian) points into
     *      0xE000-0xFFFF (the BIOS region where the BIOS is mapped by FDSInit)
     *
     * A corrupted/stub BIOS often has a reset vector pointing to 0x00xx (RAM),
     * which causes the CPU to execute garbage instead of the BIOS boot code,
     * producing a permanent gray screen.
     */
    private fun isValidFdsBios(file: File): Boolean {
        if (file.length() != 8192L) return false
        try {
            file.inputStream().use { input ->
                val bytes = input.readBytes()
                if (bytes.size != 8192) return false

                // Check not all zeros
                var hasNonZero = false
                for (b in bytes) {
                    if (b != 0.toByte()) { hasNonZero = true; break }
                }
                if (!hasNonZero) return false

                // Check reset vector points into BIOS region (0xE000-0xFFFF)
                // Reset vector is at offset 0x1FFC-0x1FFD (CPU addr 0xFFFC-0xFFFD)
                val resetLo = bytes[0x1FFC].toInt() and 0xFF
                val resetHi = bytes[0x1FFD].toInt() and 0xFF
                val resetVec = (resetHi shl 8) or resetLo
                if (resetVec < 0xE000 || resetVec > 0xFFFF) {
                    Log.w("NesApp", "FDS BIOS reset vector 0x%04X invalid (not in 0xE000-0xFFFF)".format(resetVec))
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
