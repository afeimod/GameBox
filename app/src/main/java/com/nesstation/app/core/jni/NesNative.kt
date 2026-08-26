package com.nesstation.app.core.jni

import android.view.Surface

/**
 * Raw JNI surface to libnescore.so. Kept intentionally tiny and side-effect free.
 * All heavy work happens on the engine thread (see [NesEngine]).
 *
 * IMPORTANT: [ensureLoaded] must be called once at app startup before any
 * external method is invoked. The native library is NOT loaded in the init
 * block to avoid ExceptionInInitializerError crashing the whole app.
 *
 * Pull model: Kotlin calls [runFrame] to step the core, [getFrameBuffer] to
 * read the latest ARGB frame (fallback), and [readAudio] to pull stereo PCM
 * for AudioTrack.
 *
 * Hardware acceleration: call [setSurface] with a [Surface] to enable direct
 * ANativeWindow blitting — the core writes frames straight to the surface
 * buffer, eliminating the JNI copy + Compose Canvas redraw overhead.
 */
object NesNative {

    @Volatile private var loaded = false

    /**
     * Load libnescore.so. Returns true on success.
     * Safe to call multiple times.
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("nescore")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** Bit layout: bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down, bit6=Left, bit7=Right */
    @JvmStatic external fun setPad1(bits: Int)
    /** Second controller (port 1, player 2). Same bit layout as setPad1. */
    @JvmStatic external fun setPad2(bits: Int)
    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(speed: Int)

    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean

    /**
     * Flush the core's SAVE_RAM (battery save / .srm) to disk atomically.
     * Safe to call mid-emulation (the native loader takes a state mutex
     * so retro_run() can't race). Returns false if no game is loaded,
     * the game has no SAVE_RAM region (e.g. some homebrew), or the write
     * fails (in which case the previous .srm is left intact via the
     * temp-file + rename atomic write).
     *
     * Used by the global "存档机制" setting when "核心sav存档" is selected
     * — the menu's save button calls this directly instead of opening
     * the slot picker.
     */
    @JvmStatic external fun flushSaveRam(): Boolean

    /**
     * Reload the per-game .srm file into the core's SAVE_RAM buffer,
     * discarding any unsaved in-game progress. Safe to call mid-emulation.
     * Returns false if no game is loaded or no .srm exists.
     *
     * Used by the global "存档机制" setting when "核心sav存档" is selected
     * — the menu's load button calls this directly instead of opening
     * the slot picker.
     */
    @JvmStatic external fun reloadSaveRam(): Boolean

    /**
     * Copy the latest 256×240 ARGB frame into `out` (must be ≥ 61440 elements).
     * Returns true if a fresh frame was produced since the previous call.
     * NOTE: Only needed for fallback Bitmap rendering. When [setSurface] is
     * used, frames go directly to the surface and this is not required.
     */
    @JvmStatic external fun getFrameBuffer(out: IntArray): Boolean

    /**
     * Pull stereo PCM into `out` (interleaved L,R,L,R…). Returns the number of
     * *stereo frames* written. Underrun samples are zero-filled.
     */
    @JvmStatic external fun readAudio(out: ShortArray): Int

    /** Core-reported sample rate (e.g. 44100). 0 before a ROM is loaded. */
    @JvmStatic external fun audioSampleRate(): Int

    /**
     * Target output sample rate for AudioTrack (48000 Hz on Android).
     * Audio is resampled from the core's native rate (typically 44100 Hz
     * for FCEUmm) to this rate in the native layer, matching the GB/GBC/GBA
     * core. Using the core's native rate directly with AudioTrack causes
     * poor-quality resampling in AudioFlinger on TV boxes (HDMI output
     * always runs at 48000 Hz), producing buzzing/crackling/muffled audio.
     */
    @JvmStatic external fun audioTargetSampleRate(): Int

    /** Set system (FDS BIOS) and save (SRAM) directories. */
    @JvmStatic external fun setPaths(systemDir: String, saveDir: String)

    /**
     * Set an explicit .srm basename for the next ROM load.
     *
     * Pass a stable per-game identifier (e.g. the game's DB id, or a sanitized
     * game title) so that each game gets its own `<name>.srm` file even when
     * the ROM is loaded from a content:// URI and copied to a shared temp file
     * (cacheDir/temp_rom.<ext>). Without this, all content:// URI games would
     * share the same temp_rom.srm and clobber each other's saves.
     *
     * Must be called BEFORE [loadRom]. Pass an empty string to revert to
     * deriving the .srm name from the ROM file path.
     */
    @JvmStatic external fun setSaveName(name: String)

    @JvmStatic external fun lastError(): String

    // --- Hardware-accelerated rendering ---

    /**
     * Attach a [Surface] for direct framebuffer blitting via ANativeWindow.
     * Pass null to detach the surface (e.g. when the SurfaceView is destroyed).
     * When a surface is attached, the core blits each frame directly to the
     * surface buffer — no JNI frame copy or Compose Canvas redraw needed.
     */
    @JvmStatic external fun setSurface(surface: Surface?)

    // --- Core options ---

    /**
     * Set a core option by key and value.
     * Common keys:
     *   "fceumm_ntsc_filter"  -> "disabled" | "composite" | "svideo" | "rgb"
     *   "fceumm_aspect"       -> "8:7" | "4:3" | "NTSC" | "PAL"
     *   "fceumm_palette"      -> "default" | "dq" | "nx" | "asq" | "rp2" | ...
     *   "fceumm_region"       -> "Auto" | "NTSC" | "PAL" | "Dendy"
     *   "fceumm_sndquality"   -> "Low" | "High" | "Very High"
     *   "fceumm_cropoverscan" -> "disabled" | "enabled"
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    // --- Video geometry ---

    /** Current video width reported by the core (e.g. 256, or 302 with NTSC filter). */
    @JvmStatic external fun videoWidth(): Int

    /** Current video height reported by the core (e.g. 240). */
    @JvmStatic external fun videoHeight(): Int

    /**
     * Set the frontend video post-processing filter.
     *   0  = none (nearest-neighbor)
     *   1  = scanline
     *   2  = CRT (scanline + vignette)
     *   3  = dot (LCD dot grid)
     *   4  = XBR (2x edge-preserving smooth scaling)
     *   5  = HQ2X (high-quality 2x scaler)
     *   6  = HQ4X (high-quality 4x scaler)
     *   7  = XBR + dot (XBR upscale with dot overlay)
     *   8  = 4XBR (4x cascade two 2xBR passes)
     *   9  = 4XBR + dot (4xBR with dot overlay)
     *   10 = HQ4X + dot (HQ4X with dot overlay)
     */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /**
     * Control the native surface buffer geometry.
     * - `false`: buffer = source resolution (256x240) → fast 1:1 blit + GPU upscale
     * - `true`:  buffer = display resolution → sharp C++ per-pixel scale (heavier CPU)
     */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)
}
