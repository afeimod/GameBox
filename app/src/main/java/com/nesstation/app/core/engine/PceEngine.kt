package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.PceNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [PceNative] (Geargrafx core for PC-Engine /
 * TurboGrafx-16 / SuperGrafx / PCE-CD).
 *
 * Architecture mirrors [GenesisEngine] / [FbNeoEngine] / [SnesEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from native ring buffer (resampled to 48000 Hz in
 *     native code), writes to AudioTrack with BLOCKING mode
 *
 * Audio pipeline:
 *   Geargrafx (44100/48000 Hz) → libretro callback → AudioRingBuffer
 *     → AudioResampler → 48000 Hz → readAudio() JNI → AudioTrack
 *
 * Lifecycle:
 *  - [ensureLoaded] loads the native library (call once at app startup).
 *  - [loadRom] boots a native session and starts the emulation thread.
 *  - [setSurface] attaches a Surface for direct ANativeWindow blitting.
 *  - [unload] / [shutdown] stop the thread and release audio.
 *  - [setPad1] pushes controller state to the core for the next frame.
 *
 * ROM loading: Geargrafx accepts file paths directly. For PCE-CD games
 * (.cue/.chd), BIOS files (syscard1.pce, syscard2.pce, syscard3.pce,
 * gexpress.pce) must be present in the system directory (set via
 * [setPaths]). Cartridge games (.pce/.sgx) and HES rips (.hes) do NOT
 * require BIOS.
 *
 * Button bit layout (12 buttons, SNES-style mapped to PCE):
 *   bit0=A(PCE II), bit1=B(PCE I), bit2=Select, bit3=Start(Run),
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right
 *   (PCE only has 2 face buttons; bits 8-11 are unused)
 *
 * Video: Geargrafx outputs RGB565 at 256×242 (NTSC) / 256×263 (PAL) max.
 * Filter buffers sized to 512×512 max.
 */
class PceEngine private constructor() : EmulatorEngine {

    /**
     * PCE frame buffer. Resolutions vary by system:
     *   PCE:     256×224 (typical) / 256×242 (with overscan)
     *   SuperGrafx: 256×224 / 256×239
     *   PCE-CD:  256×224
     * We size for the largest common case (512×512 with margin).
     */
    override val frameBuffer = IntArray(512 * 512)

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var audioTrack: AudioTrack? = null

    private val audioRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _ffSpeed = 0
    @Volatile private var hasSurface = false
    @Volatile private var _paused = false

    // === Netplay (lockstep hook) ===
    @Volatile private var _frameHook: NetplayHook? = null
    @Volatile private var _netFrame: Long = 0L
    override var frameHook: NetplayHook?
        get() = _frameHook
        set(value) {
            _frameHook = value
            _netFrame = 0L
        }

    /** Lock for all lifecycle methods to prevent restart conflicts. */
    private val lifecycleLock = Any()

    override fun ensureLoaded(): Boolean = PceNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        cleanup()

        PceNative.setPaths(systemDir, saveDir)

        if (!PceNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        PceNative.setFastForward(_ffSpeed)

        val rate = PceNative.audioTargetSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "pcecore-loop", isDaemon = true) {
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                )
                while (running.get()) {
                    if (_paused) {
                        try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                        continue
                    }

                    val t0 = System.nanoTime()

                    if (!running.get()) break

                    // === Netplay lockstep ===
                    val npHook = _frameHook
                    if (npHook != null) {
                        val pads = npHook.beforeFrame(_netFrame)
                        if (pads != null) {
                            PceNative.setPad1(pads.first)
                            PceNative.setPad2(pads.second)
                        }
                    }

                    PceNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    if (!running.get()) break

                    if (!hasSurface) {
                        PceNative.getFrameBuffer(frameBuffer)
                    }

                    onFrame()

                    if (_ffSpeed > 0) {
                        val targetNs = 1_000_000_000L / (60 * _ffSpeed)
                        val elapsed = System.nanoTime() - t0
                        val sleep = targetNs - elapsed
                        if (sleep > 0) {
                            try { Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt()) }
                            catch (_: InterruptedException) { break }
                        }
                    } else {
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
                android.util.Log.e("PceEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        PceNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        PceNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        PceNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) PceNative.videoWidth() else 256
    override fun videoHeight(): Int = if (isLoaded) PceNative.videoHeight() else 240

    override fun setVideoFilter(filter: Int) = PceNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = PceNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) PceNative.setFastForward(speed)
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

        audioRunning.set(true)
        audioThread = thread(name = "pce-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = PceNative.readAudio(buf)
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
                android.util.Log.e("PceEngine", "Audio thread crashed", t)
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

    override fun reset(hard: Boolean) = synchronized(lifecycleLock) {
        PceNative.reset(hard)
    }

    override fun unload() = synchronized(lifecycleLock) {
        cleanup()
    }

    override fun shutdown() = synchronized(lifecycleLock) {
        cleanup()
    }

    private fun cleanup() {
        try { setSurface(null) } catch (_: Throwable) {}

        running.set(false)
        thread?.let { t ->
            t.interrupt()
            for (attempt in 0 until 6) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
            }
        }
        thread = null

        try { stopAudio() } catch (_: Throwable) {}

        if (isLoaded) {
            try { PceNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }

        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

    override fun setPad1(bits: Int) = PceNative.setPad1(bits)
    override fun setPad2(bits: Int) = PceNative.setPad2(bits)
    override fun setRegion(region: Int) = PceNative.setRegion(region)
    override fun setSampleRate(rate: Int) = PceNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = PceNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = PceNative.loadState(slot, src.absolutePath)

    override fun flushSaveRam(): Boolean = PceNative.flushSaveRam()
    override fun reloadSaveRam(): Boolean = PceNative.reloadSaveRam()

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        val buf = IntArray(w * h)
        val ok = PceNative.getFrameBuffer(buf)
        if (!ok) return null
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = PceNative.lastError()

    companion object {
        @Volatile private var instance: PceEngine? = null
        fun get(): PceEngine = instance ?: synchronized(this) {
            instance ?: PceEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
