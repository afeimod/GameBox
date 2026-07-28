package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nesstation.app.core.jni.NesNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [NesNative]. Owns the emulation thread, the
 * AudioTrack for sound, and a shared frame buffer that the UI reads.
 *
 * Lifecycle:
 *  - [loadRom] boots a native session and starts the emulation thread.
 *  - [unload] / [shutdown] stop the thread and release audio.
 *  - [setPad1] pushes controller state to the core for the next frame.
 *
 * The emulation loop (60 Hz):
 *  1. [NesNative.runFrame] — step the FCEUmm core one frame.
 *  2. [NesNative.getFrameBuffer] — pull 256×240 ARGB into [frameBuffer].
 *  3. [NesNative.readAudio] — pull stereo PCM and feed [AudioTrack].
 *  4. Fire [onFrame] callback so the UI can recompose.
 */
class NesEngine private constructor() {

    val frameBuffer = IntArray(256 * 240)

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private val audioBuf = ShortArray(8192) // pulled per frame, ~93ms @44.1k stereo

    @Volatile var isLoaded = false
        private set

    @Volatile var fastForward = false

    fun ensureLoaded(): Boolean = runCatching { System.loadLibrary("nescore") }.isSuccess

    /**
     * Load a ROM and start the emulation thread.
     * @param rom the .nes ROM file
     * @param systemDir directory for FDS BIOS etc. (app files dir)
     * @param saveDir directory for SRAM / save states
     * @param onFrame called on the emulation thread after each produced frame
     * @return true if the ROM loaded and emulation started
     */
    fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean {
        if (running.getAndSet(true)) {
            stop()
        }

        NesNative.setPaths(systemDir, saveDir)

        if (!NesNative.loadRom(rom.absolutePath)) {
            running.set(false)
            return false
        }
        isLoaded = true

        // Set up AudioTrack at the core's native sample rate.
        startAudio(NesNative.audioSampleRate().takeIf { it > 0 } ?: 44100)

        thread = thread(name = "nescore-loop", isDaemon = true) {
            var frameCount = 0L
            while (running.get()) {
                val t0 = System.nanoTime()

                NesNative.runFrame()
                NesNative.getFrameBuffer(frameBuffer)

                // Pull and play audio
                val n = NesNative.readAudio(audioBuf)
                if (n > 0) {
                    audioTrack?.write(audioBuf, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING)
                }

                onFrame()
                frameCount++

                // Pace to ~60fps (NTSC) unless fast-forward
                val targetNs = if (fastForward) 1_000_000L else 1_000_000_000L / 60
                val elapsed = System.nanoTime() - t0
                val sleep = targetNs - elapsed
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        return true
    }

    private fun startAudio(sampleRate: Int) {
        stopAudio()
        try {
            val bufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufSize,
                AudioTrack.MODE_STREAM,
                AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            )
            audioTrack?.play()
        } catch (e: Exception) {
            // Audio is non-fatal — game still runs without sound
            audioTrack = null
        }
    }

    private fun stopAudio() {
        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioTrack = null
    }

    fun reset(hard: Boolean = false) = NesNative.reset(hard)

    fun unload() {
        stop()
        stopAudio()
        NesNative.unload()
        isLoaded = false
    }

    fun shutdown() = unload()

    fun setPad1(bits: Int) = NesNative.setPad1(bits)
    fun setRegion(region: Int) = NesNative.setRegion(region)
    fun setSampleRate(rate: Int) = NesNative.setSampleRate(rate)
    fun setFastForward(on: Boolean) { fastForward = on; NesNative.setFastForward(on) }
    fun saveState(slot: Int, dst: File) = NesNative.saveState(slot, dst.absolutePath)
    fun loadState(slot: Int, src: File) = NesNative.loadState(slot, src.absolutePath)
    fun lastError(): String = NesNative.lastError()

    private fun stop() {
        if (running.getAndSet(false)) {
            thread?.let {
                it.interrupt()
                try { it.join(300) } catch (_: InterruptedException) {}
            }
            thread = null
        }
    }

    companion object {
        @Volatile private var instance: NesEngine? = null
        fun get(): NesEngine = instance ?: synchronized(this) {
            instance ?: NesEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
