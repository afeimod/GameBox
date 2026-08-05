package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libsnescore.so (snes9x core).
 * Same pull-model interface as [NesNative].
 *
 * Button bit layout (12 buttons for SNES):
 *   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down, bit6=Left, bit7=Right
 *   bit8=X, bit9=Y, bit10=L, bit11=R
 */
object SnesNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("snescore")
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

    /** Bit layout: bit0=A..bit11=R (see class doc) */
    @JvmStatic external fun setPad1(bits: Int)
    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(on: Boolean)

    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean

    @JvmStatic external fun getFrameBuffer(out: IntArray): Boolean
    @JvmStatic external fun readAudio(out: ShortArray): Int
    @JvmStatic external fun audioSampleRate(): Int
    @JvmStatic external fun setPaths(systemDir: String, saveDir: String)
    @JvmStatic external fun lastError(): String

    @JvmStatic external fun setSurface(surface: Surface?)

    /**
     * Set a core option by key and value.
     * Common snes9x keys:
     *   "snes9x_aspect"             -> "4:3" | "8:7" | "PP"
     *   "snes9x_overclock_superfx"  -> "disabled" | "enabled"
     *   "snes9x_blargg_filter"      -> "disabled" | "composite" | "svideo" | "rgb" | "monochrome"
     *   "snes9x_audio_interpolation"-> "disabled" | "linear" | "cubic" | "sinc" | "gaussian"
     *   "snes9x_reduce_sprite_flicker" -> "disabled" | "enabled"
     *   "snes9x_reduce_slowdown"    -> "disabled" | "enabled" | "max"
     *   "snes9x_up_down_allowed"    -> "disabled" | "enabled"
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
