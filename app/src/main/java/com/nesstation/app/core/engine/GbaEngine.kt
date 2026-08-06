package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.GbaNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [GbaNative] (mGBA core for GB/GBC/GBA).
 * Same architecture as [NesEngine].
 *
 * GBA button bit layout (10 buttons):
 *   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down, bit6=Left, bit7=Right
 *   bit8=L, bit9=R
 * GB/GBC only uses bit0-bit7 (no L/R).
 */
class GbaEngine private constructor() : EmulatorEngine {

    override val frameBuffer = IntArray(240 * 160) // GBA max resolution

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private val audioBuf = ShortArray(8192)
    private var audioSampleRate = 44100
    @Volatile override var isLoaded = false
        private set

    @Volatile private var _fastForward = false
    @Volatile private var hasSurface = false
    @Volatile private var _paused = false

    override fun ensureLoaded(): Boolean = GbaNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean {
        if (!ensureLoaded()) return false

        stop()

        GbaNative.setPaths(systemDir, saveDir)

        if (!GbaNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        GbaNative.setFastForward(_fastForward)

        val rate = GbaNative.audioSampleRate().takeIf { it > 0 } ?: 32768
        audioSampleRate = rate
        startAudio(rate)

        running.set(true)
        thread = thread(name = "gbacore-loop", isDaemon = true) {
            while (running.get()) {
                if (_paused) {
                    val n = GbaNative.readAudio(audioBuf)
                    if (n > 0) {
                        audioTrack?.write(audioBuf, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING)
                    }
                    try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                    continue
                }

                val t0 = System.nanoTime()

                GbaNative.runFrame()

                if (!hasSurface) {
                    GbaNative.getFrameBuffer(frameBuffer)
                }

                if (_fastForward) {
                    // Fast-forward: skip audio, drain ring buffer, yield for max speed.
                    GbaNative.readAudio(audioBuf) // discard
                    onFrame()
                    Thread.yield()
                } else {
                    // Normal speed: read and play audio, pace to ~60fps.
                    // NON_BLOCKING writes prevent audio buffer stalls.
                    val n = GbaNative.readAudio(audioBuf)
                    if (n > 0) {
                        audioTrack?.write(audioBuf, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING)
                    }

                    onFrame()

                    // GB/GBC ~60fps, GBA ~60fps
                    val targetNs = 1_000_000_000L / 60
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
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        GbaNative.setSurface(surface)
    }

    override fun setCoreOption(key: String, value: String) {
        GbaNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) GbaNative.videoWidth() else 240
    override fun videoHeight(): Int = if (isLoaded) GbaNative.videoHeight() else 160

    override fun setVideoFilter(filter: Int) = GbaNative.setVideoFilter(filter)

    override fun setFastForward(on: Boolean) {
        _fastForward = on
        if (isLoaded) GbaNative.setFastForward(on)
    }

    override fun setPaused(paused: Boolean) {
        _paused = paused
    }

    private fun startAudio(sampleRate: Int) {
        stopAudio()
        try {
            var rate = sampleRate
            var minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // If the native sample rate is not supported (e.g., GBA's 65536 Hz
            // on some devices), fall back to standard rates.
            if (minBuf <= 0 && rate != 48000) {
                rate = 48000
                minBuf = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
                )
            }
            if (minBuf <= 0 && rate != 44100) {
                rate = 44100
                minBuf = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
                )
            }
            if (minBuf <= 0) return
            audioSampleRate = rate
            // Use 4x the minimum buffer size with NON_BLOCKING writes.
            val bufSize = (minBuf * 4).coerceAtLeast(8192)
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(rate)
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

    override fun reset(hard: Boolean) = GbaNative.reset(hard)

    override fun unload() {
        stop()
        stopAudio()
        setSurface(null)
        if (isLoaded) {
            GbaNative.unload()
            isLoaded = false
        }
    }

    override fun shutdown() = unload()

    override fun setPad1(bits: Int) = GbaNative.setPad1(bits)
    override fun setRegion(region: Int) = GbaNative.setRegion(region)
    override fun setSampleRate(rate: Int) = GbaNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File) { GbaNative.saveState(slot, dst.absolutePath) }
    override fun loadState(slot: Int, src: File) { GbaNative.loadState(slot, src.absolutePath) }

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        val buf = IntArray(w * h)
        val ok = GbaNative.getFrameBuffer(buf)
        if (!ok) return null
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = GbaNative.lastError()

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
        @Volatile private var instance: GbaEngine? = null
        fun get(): GbaEngine = instance ?: synchronized(this) {
            instance ?: GbaEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
