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
 *
 * Architecture:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from native ring buffer, writes to AudioTrack with
 *     BLOCKING mode (prevents sample drops / crackling)
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

    // Audio thread state
    private val audioRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _ffSpeed = 0
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

        SnesNative.setFastForward(_ffSpeed)

        startAudio(SnesNative.audioSampleRate().takeIf { it > 0 } ?: 32040)

        running.set(true)
        thread = thread(name = "snescore-loop", isDaemon = true) {
            try {
                // Boost emulation thread priority for smooth 60fps on TV.
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                )
                while (running.get()) {
                    if (_paused) {
                        try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                        continue
                    }

                    val t0 = System.nanoTime()

                    // Re-check running right before the native call — unload()
                    // may have set it to false while we were sleeping.
                    if (!running.get()) break

                    SnesNative.runFrame()

                    // Re-check running right after the native call — if
                    // unload() ran during runFrame(), the core is now freed
                    // and we must NOT touch it any further.
                    if (!running.get()) break

                    if (!hasSurface) {
                        SnesNative.getFrameBuffer(frameBuffer)
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
                        // Normal: pace to ~60fps (NTSC)
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
            } catch (t: Throwable) {
                android.util.Log.e("SnesEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        SnesNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        SnesNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        SnesNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) SnesNative.videoWidth() else 256
    override fun videoHeight(): Int = if (isLoaded) SnesNative.videoHeight() else 224

    override fun setVideoFilter(filter: Int) = SnesNative.setVideoFilter(filter)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) SnesNative.setFastForward(speed)
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

        // Dedicated audio thread with BLOCKING writes — no crackling.
        audioRunning.set(true)
        audioThread = thread(name = "snes-audio-loop", isDaemon = true) {
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = SnesNative.readAudio(buf)
                        if (n > 0) {
                            audioTrack?.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                        } else {
                            Thread.sleep(2)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("SnesEngine", "Audio thread crashed", t)
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

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        val buf = IntArray(w * h)
        val ok = SnesNative.getFrameBuffer(buf)
        if (!ok) return null
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = SnesNative.lastError()

    private fun stop() {
        running.set(false)
        thread?.let { t ->
            t.interrupt()
            // Join with retries — the emulation thread may be inside a
            // long retro_run() call. Without this, unload() could call
            // SnesNative.unload() while the thread is still inside
            // retro_run(), causing a crash.
            for (attempt in 0 until 3) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
            }
        }
        thread = null
    }

    companion object {
        @Volatile private var instance: SnesEngine? = null
        fun get(): SnesEngine = instance ?: synchronized(this) {
            instance ?: SnesEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
