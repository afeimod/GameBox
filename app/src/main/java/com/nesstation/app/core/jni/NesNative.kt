package com.nesstation.app.core.jni

/**
 * Raw JNI surface to libnescore.so. Kept intentionally tiny and side-effect free.
 * All heavy work happens on the engine thread (see [NesEngine]).
 *
 * Pull model: Kotlin calls [runFrame] to step the core, [getFrameBuffer] to
 * read the latest ARGB frame, and [readAudio] to pull stereo PCM for AudioTrack.
 */
object NesNative {
    init { System.loadLibrary("nescore") }

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** Bit layout: bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down, bit6=Left, bit7=Right */
    @JvmStatic external fun setPad1(bits: Int)
    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(on: Boolean)

    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean

    /**
     * Copy the latest 256×240 ARGB frame into `out` (must be ≥ 61440 elements).
     * Returns true if a fresh frame was produced since the previous call.
     */
    @JvmStatic external fun getFrameBuffer(out: IntArray): Boolean

    /**
     * Pull stereo PCM into `out` (interleaved L,R,L,R…). Returns the number of
     * *stereo frames* written. Underrun samples are zero-filled.
     */
    @JvmStatic external fun readAudio(out: ShortArray): Int

    /** Core-reported sample rate (e.g. 44100). 0 before a ROM is loaded. */
    @JvmStatic external fun audioSampleRate(): Int

    /** Set system (FDS BIOS) and save (SRAM) directories. */
    @JvmStatic external fun setPaths(systemDir: String, saveDir: String)

    @JvmStatic external fun lastError(): String
}
