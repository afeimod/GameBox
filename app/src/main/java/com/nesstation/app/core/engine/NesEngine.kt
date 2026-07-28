package com.nesstation.app.core.engine

import com.nesstation.app.core.jni.NesNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [NesNative]. Owns the emulation thread and
 * exposes a tiny Kotlin API for the Compose UI to call.
 *
 * Lifecycle:
 *  - [loadRom] boots a native session and starts a worker thread.
 *  - [unload] / [shutdown] stop the worker.
 *  - [setPad1] pushes controller state to the core for the next frame.
 */
class NesEngine private constructor() {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var lastFrameNs: Long = 0L

    fun ensureLoaded(): Boolean = runCatching { System.loadLibrary("nescore") }.isSuccess

    fun loadRom(rom: File, onFrame: (frameIndex: Long) -> Unit): Boolean {
        if (running.getAndSet(true)) {
            stop()
        }
        if (!NesNative.loadRom(rom.absolutePath)) {
            running.set(false)
            return false
        }
        thread = thread(name = "nescore-loop", isDaemon = true) {
            var i = 0L
            while (running.get()) {
                val t0 = System.nanoTime()
                NesNative.runFrame()
                onFrame(i++)
                // Pace to ~60fps unless fast-forwarded.
                val target = 1_000_000_000L / 60
                val elapsed = System.nanoTime() - t0
                val sleep = target - elapsed
                if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
            }
        }
        return true
    }

    fun reset(hard: Boolean = false) = NesNative.reset(hard)
    fun unload() { stop(); NesNative.unload() }
    fun shutdown() { stop(); NesNative.unload() }

    fun setPad1(bits: Int) = NesNative.setPad1(bits)
    fun setRegion(region: Int) = NesNative.setRegion(region)
    fun setSampleRate(rate: Int) = NesNative.setSampleRate(rate)
    fun setFastForward(on: Boolean) = NesNative.setFastForward(on)
    fun saveState(slot: Int, dst: File) = NesNative.saveState(slot, dst.absolutePath)
    fun loadState(slot: Int, src: File) = NesNative.loadState(slot, src.absolutePath)
    fun lastError(): String = NesNative.lastError()

    private fun stop() {
        if (running.getAndSet(false)) {
            thread?.let {
                it.interrupt()
                try { it.join(200) } catch (_: InterruptedException) {}
            }
            thread = null
        }
    }

    companion object {
        @Volatile private var instance: NesEngine? = null
        fun get(): NesEngine = instance ?: synchronized(this) {
            instance ?: NesEngine().also { instance = it }
        }

        // Just here so the library is loaded eagerly at app start.
        fun ensureLoaded() = get().ensureLoaded()
    }
}
