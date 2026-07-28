package com.nesstation.app.core.jni

/**
 * Raw JNI surface to libnescore.so. Kept intentionally tiny and side-effect free.
 * All heavy work happens on the engine thread (see [NesEngine]).
 */
object NesNative {
    init { System.loadLibrary("nescore") }
    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()
    @JvmStatic external fun setPad1(bits: Int)
    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(on: Boolean)
    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean
    @JvmStatic external fun lastError(): String
}
