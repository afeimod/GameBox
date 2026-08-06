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
 *
 * Architecture:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from native ring buffer, writes to AudioTrack with
 *     BLOCKING mode (prevents sample drops / crackling)
 *
 * Separating audio into its own thread eliminates the tradeoff between
 * BLOCKING (causes lag when buffer full) and NON_BLOCKING (drops samples,
 * causing "滋滋" crackling). The audio thread blocks on AudioTrack.write,
 * which naturally throttles to the playback rate, while the emulation
 * thread runs independently at full speed.
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
    private var audioSampleRate = 44100

    // Audio thread state
    private val audioRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null

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
                    try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                    continue
                }

                val t0 = System.nanoTime()

                GbaNative.runFrame()

                if (!hasSurface) {
                    GbaNative.getFrameBuffer(frameBuffer)
                }

                onFrame()

                if (_fastForward) {
                    // Fast-forward: minimal sleep so the game runs much faster.
                    // Audio is handled by the separate audio thread and plays
                    // at normal speed (ring buffer absorbs overflow).
                    try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                } else {
                    // Normal: pace to ~60fps
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
            // Use 4x min buffer for smooth playback with BLOCKING writes.
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

        // Start dedicated audio thread — uses BLOCKING writes so no samples
        // are dropped. This eliminates the "滋滋" crackling that NON_BLOCKING
        // caused. The thread reads from the native ring buffer (which is
        // mutex-protected, safe to call from a different thread).
        audioRunning.set(true)
        audioThread = thread(name = "gba-audio-loop", isDaemon = true) {
            val buf = ShortArray(4096)
            while (audioRunning.get()) {
                try {
                    val n = GbaNative.readAudio(buf)
                    if (n > 0) {
                        // BLOCKING write: waits if AudioTrack buffer is full.
                        // This prevents sample drops and crackling.
                        audioTrack?.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                    } else {
                        // No audio available (e.g. paused or starting up).
                        Thread.sleep(2)
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopAudio() {
        audioRunning.set(false)
        audioThread?.let {
            it.interrupt()
            try { it.join(200) } catch (_: InterruptedException) {}
        }
        audioThread = null

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
