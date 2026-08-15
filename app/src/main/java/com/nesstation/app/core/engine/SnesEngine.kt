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
 *   - Audio thread: reads from native ring buffer (resampled to 48000 Hz in
 *     native code), writes to AudioTrack with BLOCKING mode (prevents sample
 *     drops / crackling)
 *
 * Audio pipeline (matches the GB/GBC/GBA core):
 *   SNES9x core (~32040 Hz) → libretro callback → AudioRingBuffer
 *     → AudioResampler (32040→48000 Hz, linear interpolation)
 *     → readAudio() JNI → AudioTrack (48000 Hz)
 *
 * The resampler runs in the native layer (snes_loader.cpp). AudioTrack is
 * always created at 48000 Hz, which is Android's native audio sample rate.
 * This eliminates the buzzing/crackling/muffled audio that occurred on TV
 * boxes (HDMI output always runs at 48000 Hz) when AudioTrack was created
 * at the SNES native rate (~32040 Hz) and AudioFlinger was forced to
 * resample. Phones often accept 32040 Hz natively or have a higher-quality
 * resampler, so the bug was invisible there — which is why the issue only
 * appeared in TV mode.
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

    /** Lock for all lifecycle methods to prevent restart conflicts. */
    private val lifecycleLock = Any()

    override fun ensureLoaded(): Boolean = SnesNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return@synchronized false

        // Full cleanup of any previous session before loading new ROM.
        // Critical for "exit game → launch another game" — without it the
        // previous session's threads/state can collide with the new load.
        cleanup()

        SnesNative.setPaths(systemDir, saveDir)

        if (!SnesNative.loadRom(rom.absolutePath)) {
            return@synchronized false
        }
        isLoaded = true

        SnesNative.setFastForward(_ffSpeed)

        // Use the target sample rate (48000 Hz) for AudioTrack, not the SNES
        // native rate (~32040 Hz). The native resampler in snes_loader.cpp
        // converts from the core rate to 48000 Hz before returning samples
        // via readAudio().
        //
        // WHY: On TV boxes with HDMI output, the hardware native audio rate is
        // always 48000 Hz. If AudioTrack is created at ~32040 Hz, Android's
        // AudioFlinger performs low-quality resampling to 48000 Hz internally,
        // producing audible buzzing, crackling, and muffled audio. On phones
        // the artifact is milder (the phone's audio HAL may accept 32040 Hz
        // natively or have a higher-quality resampler), which is why the bug
        // only appeared in TV mode. This matches the GB/GBC/GBA core (mGBA)
        // which already does the same.
        val rate = SnesNative.audioTargetSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

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
        true
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
    override fun setHighQualityScaling(enabled: Boolean) = SnesNative.setHighQualityScaling(enabled)

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
            // Use a larger buffer for 48000 Hz output to prevent underruns on
            // weak TV boxes. At 48kHz stereo 16-bit, 1 frame = 4 bytes.
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

        // Dedicated audio thread with BLOCKING writes — no crackling.
        // The native readAudio() returns samples already resampled to 48000 Hz
        // by the AudioResampler in snes_loader.cpp. Blocking write paces the
        // loop at the hardware sample rate.
        audioRunning.set(true)
        audioThread = thread(name = "snes-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
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

    override fun unload() = synchronized(lifecycleLock) {
        cleanup()
    }

    override fun shutdown() = synchronized(lifecycleLock) {
        cleanup()
    }

    /**
     * Complete, idempotent resource cleanup. Mirrors [FbNeoEngine.cleanup].
     * Safe to call multiple times — ensures "exit → relaunch" flow is crash-free.
     */
    private fun cleanup() {
        // === 卸载顺序很重要，避免闪退（同 NesEngine）===
        // 先 setSurface(null) 让 native blit 提前退出，再停线程，再卸载核心。
        try { setSurface(null) } catch (_: Throwable) {}
        try { stop() } catch (_: Throwable) {}
        try { stopAudio() } catch (_: Throwable) {}
        if (isLoaded) {
            try { SnesNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

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
            // 6 次 × 500ms = 3s（之前 1.5s 对慢速设备不够，偶发闪退）
            for (attempt in 0 until 6) {
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
