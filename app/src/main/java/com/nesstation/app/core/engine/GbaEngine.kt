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
 *   - Audio thread: reads from native ring buffer (resampled to 48000 Hz in
 *     native code), writes to AudioTrack with BLOCKING mode (prevents sample
 *     drops / crackling)
 *
 * Audio pipeline (fixed):
 *   mGBA core (32768 Hz) → libretro callback → AudioRingBuffer
 *     → AudioResampler (32768→48000 Hz, linear interpolation)
 *     → readAudio() JNI → AudioTrack (48000 Hz)
 *
 * The resampler runs in the native layer (gba_loader.cpp). AudioTrack is
 * always created at 48000 Hz, which is Android's native audio sample rate.
 * This matches the mGBA Android reference project that uses Oboe at 48000 Hz.
 * Previously, AudioTrack was created at 32768 Hz, which caused poor-quality
 * resampling in AudioFlinger (pitch errors, crackling, muffled audio).
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
    // Audio thread state
    private val audioRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _ffSpeed = 0
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

        GbaNative.setFastForward(_ffSpeed)

        // Use the target sample rate (48000 Hz) for AudioTrack, not the core's
        // native rate (32768 Hz). The native resampler in gba_loader.cpp converts
        // from the core rate to 48000 Hz before returning samples via readAudio().
        // This matches the mGBA Android reference project (Oboe at 48000 Hz).
        val rate = GbaNative.audioTargetSampleRate().takeIf { it > 0 } ?: 48000
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

                if (_ffSpeed > 0) {
                    // Fast-forward: pace to target speed
                    val targetNs = 1_000_000_000L / (60 * _ffSpeed)
                    val elapsed = System.nanoTime() - t0
                    val sleep = targetNs - elapsed
                    if (sleep > 0) {
                        try { Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt()) }
                        catch (_: InterruptedException) { break }
                    }
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

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) GbaNative.setFastForward(speed)
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
            // Use a larger buffer for 48000 Hz output to prevent underruns.
            // At 48kHz stereo 16-bit, 1 frame = 4 bytes.
            // minBuf is typically ~4800 bytes (~1200 frames) on most devices.
            // A multiplier of 4 gives ~4800 frames, enough for ~100ms of audio.
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

        // Dedicated audio thread with BLOCKING writes.
        // The native readAudio() now returns samples already resampled to 48000 Hz
        // by the AudioResampler in gba_loader.cpp, so AudioTrack receives data
        // at the correct rate with no further conversion needed.
        // Use a larger intermediate buffer (6144 = ~128ms at 48kHz stereo)
        // to match the increased output rate.
        audioRunning.set(true)
        audioThread = thread(name = "gba-audio-loop", isDaemon = true) {
            val buf = ShortArray(6144)
            while (audioRunning.get()) {
                try {
                    val n = GbaNative.readAudio(buf)
                    if (n > 0) {
                        audioTrack?.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                    } else {
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
