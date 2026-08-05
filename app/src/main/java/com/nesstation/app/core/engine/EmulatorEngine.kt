package com.nesstation.app.core.engine

import android.view.Surface
import com.nesstation.app.core.model.GamePlatform
import java.io.File

/**
 * Common interface for all emulator engines (NES, SNES, GB/GBC/GBA).
 * EmulatorScreen uses this interface instead of a concrete engine class,
 * allowing any platform's engine to drive the same UI.
 */
interface EmulatorEngine {

    /** Frame buffer for fallback Bitmap rendering (when no Surface is attached). */
    val frameBuffer: IntArray

    /** Whether a ROM is currently loaded. */
    val isLoaded: Boolean

    /** Load the native library. Returns true on success. */
    fun ensureLoaded(): Boolean

    /**
     * Load a ROM and start the emulation thread.
     * @return true if the ROM loaded and emulation started
     */
    fun loadRom(rom: File, systemDir: String, saveDir: String, onFrame: () -> Unit): Boolean

    /** Attach/detach a Surface for hardware-accelerated direct rendering. */
    fun setSurface(surface: Surface?)

    /** Set a core option by key/value. */
    fun setCoreOption(key: String, value: String)

    /** Current video width from the core. */
    fun videoWidth(): Int

    /** Current video height from the core. */
    fun videoHeight(): Int

    /** Set the frontend video post-processing filter (0-10). */
    fun setVideoFilter(filter: Int)

    /** Enable/disable fast-forward. */
    fun setFastForward(on: Boolean)

    /** Pause or resume emulation. */
    fun setPaused(paused: Boolean)

    /** Reset the emulation. */
    fun reset(hard: Boolean = false)

    /** Unload the current ROM and stop the emulation thread. */
    fun unload()

    /** Full shutdown. */
    fun shutdown()

    /** Push controller state. Bit layout depends on platform. */
    fun setPad1(bits: Int)

    /** Set region hint (0=NTSC, 1=PAL). */
    fun setRegion(region: Int)

    /** Set audio sample rate hint. */
    fun setSampleRate(rate: Int)

    /** Save state to a file. */
    fun saveState(slot: Int, dst: File)

    /** Load state from a file. */
    fun loadState(slot: Int, src: File)

    /** Last error message from the core. */
    fun lastError(): String

    companion object {
        /**
         * Factory: return the appropriate engine for the given platform.
         * NES  -> NesEngine
         * SFC  -> SnesEngine
         * GB/GBC/GBA -> GbaEngine
         */
        fun forPlatform(platform: GamePlatform): EmulatorEngine = when (platform) {
            GamePlatform.NES  -> NesEngine.get()
            GamePlatform.SFC  -> SnesEngine.get()
            GamePlatform.GB   -> GbaEngine.get()
            GamePlatform.GBC  -> GbaEngine.get()
            GamePlatform.GBA  -> GbaEngine.get()
            GamePlatform.JAVA -> NesEngine.get() // fallback, should not be used
        }
    }
}
