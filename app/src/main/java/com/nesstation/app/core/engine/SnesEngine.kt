package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.SnesNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [SnesNative] (snes9x core).
 * Same architecture as [NesEngine]: owns the emulation thread, AudioTrack,
 * and optional hardware-accelerated surface rendering.
 *
 * SNES button bit layout (12 buttons):
 *   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down, bit6=Left, bit7=Right
 *   bit8=X, bit9=Y, bit10=L, bit11=R
 */
class SnesEngine private constructor() : EmulatorEngine {

    override val frameBuffer = IntArray(512 * 478) // max SNES resolution

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private val audioBuf = ShortArray(8192)

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _fastForward = false
    @Volatile private var hasSurface = false
    @Volatile private var _paused = false

    override fun ensureLoaded(): Boolean = SnesNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean {
        if (!ensureLoaded()) return false

        stop()

        SnesNative.setPaths(systemDir, saveDir)

        if (!SnesNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        SnesNative.setFastForward(_fastForward)

        startAudio(SnesNative.audioSampleRate().takeIf { it > 0 } ?: 32040)

        running.set(true)
        thread = thread(name = "snescore-loop", isDaemon = true) {
            while (running.get()) {
                if (_paused) {
                    val n = SnesNative.readAudio(audioBuf)
                    if (n > 0) {
                        audioTrack?.write(audioBuf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                    }
                    try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                    continue
                }

                val t0 = System.nanoTime()

                SnesNative.runFrame()

                if (!hasSurface) {
                    SnesNative.getFrameBuffer(frameBuffer)
                }

                val n = SnesNative.readAudio(audioBuf)
                if (n > 0) {
                    audioTrack?.write(audioBuf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                }

                onFrame()

                // SNES NTSC ~60fps, PAL ~50fps
                val targetNs = if (_fastForward) 1_000_000L else 1_000_000_000L / 60
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

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        SnesNative.setSurface(surface)
    }

    override fun setCoreOption(key: String, value: String) {
        SnesNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) SnesNative.videoWidth() else 256
    override fun videoHeight(): Int = if (isLoaded) SnesNative.videoHeight() else 224

    override fun setVideoFilter(filter: Int) = SnesNative.setVideoFilter(filter)

    override fun setFastForward(on: Boolean) {
        _fastForward = on
        if (isLoaded) SnesNative.setFastForward(on)
    }

    override fun setPaused(paused: Boolean) {
        _paused = paused
    }

    private fun startAudio(sampleRate: Int) {
        stopAudio()
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = (minBuf * 4).coerceAtLeast(8192)
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

    override fun reset(hard: Boolean) = SnesNative.reset(hard)

    override fun unload() {
        stop()
        stopAudio()
        setSurface(null)
        if (isLoaded) {
            SnesNative.unload()
            isLoaded = false
        }
    }

    override fun shutdown() = unload()

    override fun setPad1(bits: Int) = SnesNative.setPad1(bits)
    override fun setRegion(region: Int) = SnesNative.setRegion(region)
    override fun setSampleRate(rate: Int) = SnesNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File) { SnesNative.saveState(slot, dst.absolutePath) }
    override fun loadState(slot: Int, src: File) { SnesNative.loadState(slot, src.absolutePath) }
    override fun lastError(): String = SnesNative.lastError()

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
        @Volatile private var instance: SnesEngine? = null
        fun get(): SnesEngine = instance ?: synchronized(this) {
            instance ?: SnesEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
