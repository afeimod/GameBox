package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libgbacore.so (mGBA core for GB/GBC/GBA).
 * Same pull-model interface as [NesNative].
 *
 * Button bit layout (10 buttons for GBA, 8 for GB/GBC):
 *   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down, bit6=Left, bit7=Right
 *   bit8=L, bit9=R  (GBA only)
 */
object GbaNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("gbacore")
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

    /** Bit layout: bit0=A..bit9=R (see class doc) */
    @JvmStatic external fun setPad1(bits: Int)
    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(speed: Int)

    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean

    @JvmStatic external fun getFrameBuffer(out: IntArray): Boolean
    @JvmStatic external fun readAudio(out: ShortArray): Int
    @JvmStatic external fun audioSampleRate(): Int

    /**
     * Target output sample rate for AudioTrack (48000 Hz on Android).
     * Audio is resampled from the core's native rate (e.g. 32768 Hz for GBA)
     * to this rate in the native layer, matching the mGBA Android reference
     * project which uses 48000 Hz for Oboe output. Using the core's native
     * rate directly with AudioTrack causes poor-quality resampling in
     * AudioFlinger, leading to pitch errors and audio artifacts.
     */
    @JvmStatic external fun audioTargetSampleRate(): Int
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

    @JvmStatic external fun setSurface(surface: Surface?)

    /**
     * Set a core option by key and value.
     * Common mGBA keys:
     *   "mgba_gb_model"             -> "Autodetect" | "Game Boy" | "Super Game Boy" | "Game Boy Color" | "Game Boy Advance"
     *   "mgba_gb_colors"            -> "enabled" | "disabled"
     *   "mgba_gb_colors_preset"     -> "default" | "gb" | "gbc" | "sgb" | "ags" | "smb" | "pocket"
     *   "mgba_gba_colors"           -> "enabled" | "disabled"
     *   "mgba_gba_colors_preset"    -> "default" | "gba" | "ags" | "spb" | "nsp"
     *   "mgba_interframe_blending"  -> "OFF" | "ON" | "fast"
     *   "mgba_frameskip"            -> "0".."10"
     *   "mgba_frameskip_type"       -> "disabled" | "auto" | "manual"
     *   "mgba_audio_resampler"      -> "nearest" | "cosine" | "cubic" | "sinc"
     *   "mgba_audio_low_pass_filter"-> "disabled" | "enabled"
     *   "mgba_sgb_borders"          -> "ON" | "OFF"
     *   "mgba_allow_opposite_directions" -> "OFF" | "ON"
     *   "mgba_gba_forceRTC"         -> "disabled" | "enabled"
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /**
     * Video filter types (same as NES):
     *   0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x,
     *   7=xbr+dot, 8=4xbr, 9=4xbr+dot, 10=hq4x+dot
     */
    @JvmStatic external fun setVideoFilter(filter: Int)
}
