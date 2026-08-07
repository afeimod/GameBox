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
    private var audioSrcRate = 32768
    private var audioDstRate = 48000

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
        audioSrcRate = sampleRate
        try {
            // Always use 48000 Hz for AudioTrack — this is the most universally
            // supported rate on Android and avoids low-quality HAL resampling.
            // We resample from the core's native rate (e.g., 32768 Hz for GBA)
            // to 48000 Hz ourselves for better quality.
            var rate = 48000
            var minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0 && rate != 44100) {
                rate = 44100
                minBuf = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
                )
            }
            if (minBuf <= 0) return
            audioDstRate = rate
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

        audioRunning.set(true)
        audioThread = thread(name = "gba-audio-loop", isDaemon = true) {
            val srcBuf = ShortArray(4096)
            // Resampling state
            val ratio = audioSrcRate.toDouble() / audioDstRate.toDouble()
            var srcPos = 0.0
            var lastL: Short = 0
            var lastR: Short = 0
            val dstBuf = ShortArray(8192)

            while (audioRunning.get()) {
                try {
                    val n = GbaNative.readAudio(srcBuf)
                    if (n > 0) {
                        // Linear interpolation resampling from srcRate to dstRate
                        var dstIdx = 0
                        val maxDst = dstBuf.size / 2
                        for (i in 0 until n) {
                            val curL = srcBuf[i * 2]
                            val curR = srcBuf[i * 2 + 1]
                            while (srcPos < (i + 1).toDouble() && dstIdx < maxDst) {
                                val frac = srcPos - i.toDouble()
                                val l = lastL + ((curL - lastL).toFloat() * frac.toFloat()).toInt()
                                val r = lastR + ((curR - lastR).toFloat() * frac.toFloat()).toInt()
                                dstBuf[dstIdx * 2] = l.toShort()
                                dstBuf[dstIdx * 2 + 1] = r.toShort()
                                dstIdx++
                                srcPos += ratio
                            }
                            if (dstIdx >= maxDst) break
                            lastL = curL
                            lastR = curR
                        }
                        srcPos -= n
                        if (dstIdx > 0) {
                            audioTrack?.write(dstBuf, 0, dstIdx * 2, AudioTrack.WRITE_BLOCKING)
                        }
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
