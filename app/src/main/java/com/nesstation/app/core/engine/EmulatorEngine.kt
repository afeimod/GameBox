package com.nesstation.app.core.engine

import android.view.Surface
import com.nesstation.app.core.model.GamePlatform
import java.io.File

/**
 * Captured frame data for screenshots.
 */
data class FrameCapture(
    val pixels: IntArray,
    val width: Int,
    val height: Int
)

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

    /**
     * Set an explicit .srm basename for the next ROM load.
     * Pass a stable per-game identifier (e.g. the game's DB id) so that
     * content:// URI games (which share a temp ROM file) get per-game .srm
     * files instead of clobbering each other. Must be called before loadRom().
     */
    fun setSaveName(name: String)

    /** Set a core option by key/value. */
    fun setCoreOption(key: String, value: String)

    /** Current video width from the core. */
    fun videoWidth(): Int

    /** Current video height from the core. */
    fun videoHeight(): Int

    /** Set the frontend video post-processing filter (0-10). */
    fun setVideoFilter(filter: Int)

    /**
     * Control the native surface buffer geometry for performance vs quality.
     * - `false` (default): buffer = source resolution (256x240 / 240x160),
     *   1:1 blit + hardware-compositor GPU upscale — fast, slightly softer.
     * - `true`: buffer = display resolution, C++ per-pixel nearest-neighbor
     *   scale — sharper, much heavier CPU (can lag on low-power devices).
     */
    fun setHighQualityScaling(enabled: Boolean)

    /** Set fast-forward speed (0 = off, 2/4/6/8 = speed multiplier). */
    fun setFastForward(speed: Int)

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

    /**
     * Capture the current frame as an ARGB bitmap (0xAARRGGBB).
     * Returns the bitmap array and dimensions, or null if no frame is available.
     * Works even with hardware-accelerated surface rendering by requesting
     * a fresh frame buffer copy from the native core.
     */
    fun captureFrame(): FrameCapture?

    /** Last error message from the core. */
    fun lastError(): String

    companion object {
        /**
         * Factory: return the appropriate engine for the given platform.
         * NES  -> NesEngine
         * SFC  -> SnesEngine
         * GB/GBA -> GbaEngine
         * DOS  -> DosEngine (DOSBox-Pure)
         */
        fun forPlatform(platform: GamePlatform): EmulatorEngine = when (platform) {
            GamePlatform.NES  -> NesEngine.get()
            GamePlatform.SFC  -> SnesEngine.get()
            GamePlatform.GB   -> GbaEngine.get()
            GamePlatform.GBA  -> GbaEngine.get()
            GamePlatform.DOS  -> DosEngine.get()
            GamePlatform.JAVA -> NesEngine.get() // fallback, should not be used
        }
    }
}
