package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.Psx2Native
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [Psx2Native] (PCEE2 — PCSX2 PlayStation 2 core).
 *
 * Architecture mirrors [PsxEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to the
 *     core-reported refresh rate (NTSC 59.94 / PAL 50)
 *   - Audio thread: reads from the native ring buffer (core-rate
 *     passthrough — no resampling)
 *
 * PS2 BIOS files (scph10000.bin / scph39001.bin etc.) MUST be placed in
 * `<systemDir>/pcsx2/bios/` by the user — PCSX2 requires a real BIOS and
 * cannot run without one (loadRom returns a detailed error otherwise; a
 * legacy `<systemDir>/bios/` folder is auto-migrated by the native loader).
 *
 * Disc images (.iso/.chd/.cso/.zso/.cue+bin/.gz/.mdf/.nrg/.elf) are passed
 * by path to the core — PCEE2 opens the image itself.
 *
 * Input: 16-button DualShock (bit0=×, bit1=□, bit8=○, bit9=△, bit12..15 =
 * L2/R2/L3/R3) plus dual analog sticks via [setAnalogAxes] — the on-screen
 * twin-stick UI feeds int16 libretro axis values.
 */
class Psx2Engine private constructor() : EmulatorEngine {

    /**
     * PS2 frame buffer. PS2 GS output at 1x: 640x448 NTSC / 640x512 PAL.
     * The pcsx2_upscale_multiplier option scales internal resolution up to
     * 4x = 2560x1792 NTSC / 2560x2048 worst-case PAL — size this buffer to
     * match the native loader's 2560x2048 cap exactly.
     */
    override val frameBuffer = IntArray(2560 * 2048)

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

    /** Core-reported refresh rate; used for pacing (NTSC 59.94 / PAL 50). */
    @Volatile private var _targetHz: Int = 60

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

    override fun ensureLoaded(): Boolean = Psx2Native.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        cleanup()

        Psx2Native.setPaths(systemDir, saveDir)

        if (!Psx2Native.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        Psx2Native.setFastForward(_ffSpeed)

        // Pace to the core's own refresh rate: NTSC games run at 59.94 fps,
        // PAL games at 50.0.
        val coreFps = Psx2Native.videoFps()
        _targetHz = if (coreFps > 10.0 && coreFps < 500.0) Math.round(coreFps).toInt() else 60

        // Default audio — open the AudioTrack at the core's own sample rate
        // (PS2 SPU2 = 48000 Hz; passthrough, no forced resampling).
        val rate = Psx2Native.audioSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "psx2core-loop", isDaemon = true) {
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
                            Psx2Native.setPad1(pads.first)
                            Psx2Native.setPad2(pads.second)
                        }
                    }

                    Psx2Native.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    if (!running.get()) break

                    if (!hasSurface) {
                        Psx2Native.getFrameBuffer(frameBuffer)
                    }

                    onFrame()

                    // Pacing — fast-forward divides the frame budget by the
                    // multiplier; native applySpeed() also skips blits on
                    // non-presented frames.
                    val ff = _ffSpeed
                    val hz = if (ff > 0) _targetHz * ff else _targetHz
                    val paced = FramePacer.pace(t0, hz.coerceIn(30, 600))
                    if (!paced) break
                }
            } catch (t: Throwable) {
                android.util.Log.e("Psx2Engine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        Psx2Native.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        Psx2Native.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        Psx2Native.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) Psx2Native.videoWidth() else 640
    override fun videoHeight(): Int = if (isLoaded) Psx2Native.videoHeight() else 448

    override fun setVideoFilter(filter: Int) = Psx2Native.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = Psx2Native.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) Psx2Native.setFastForward(speed)
    }

    override fun setPaused(paused: Boolean) {
        _paused = paused
    }

    /**
     * Push dual analog stick axes for player 1. All values are int16 libretro
     * range (-32768..32767) in LX, LY, RX, RY order. Safe to call at gesture
     * rate from the UI thread — the native side stores atomics that
     * cb_input_state reads on the emulation thread.
     */
    fun setAnalogAxes(lx: Int, ly: Int, rx: Int, ry: Int) {
        if (isLoaded) Psx2Native.setAnalog1(lx, ly, rx, ry)
    }

    /** Push dual analog stick axes for player 2 (local co-op / netplay local). */
    fun setAnalogAxesP2(lx: Int, ly: Int, rx: Int, ry: Int) {
        if (isLoaded) Psx2Native.setAnalog2(lx, ly, rx, ry)
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
        audioThread = thread(name = "psx2-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = Psx2Native.readAudio(buf)
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
                android.util.Log.e("Psx2Engine", "Audio thread crashed", t)
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
        Psx2Native.reset(hard)
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
            try { Psx2Native.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
        _targetHz = 60
    }

    override fun setPad1(bits: Int) = Psx2Native.setPad1(bits)
    override fun setPad2(bits: Int) = Psx2Native.setPad2(bits)
    fun setPad3(bits: Int) = Psx2Native.setPad3(bits)
    fun setPad4(bits: Int) = Psx2Native.setPad4(bits)

    /**
     * Switch controller ports between digital pad and DualShock (analog).
     * Values map to libretro device ids:
     *   RETRO_DEVICE_NONE = 0, RETRO_DEVICE_JOYPAD = 1, RETRO_DEVICE_ANALOG = 5.
     * All ports default to ANALOG after load (PS2 DualShock native).
     */
    fun setControllerDevice(port: Int, device: Int) = Psx2Native.setControllerDevice(port, device)

    override fun setRegion(region: Int) = Psx2Native.setRegion(region)
    override fun setSampleRate(rate: Int) = Psx2Native.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = Psx2Native.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = Psx2Native.loadState(slot, src.absolutePath)

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        if (w * h > frameBuffer.size) return null
        // getFrameBuffer 返回值仅表示"自上次读取后是否有新帧"（psx 同款处理）：
        // 渲染循环每帧都会消费该标志，UI 侧截图不以返回值判定成败。
        Psx2Native.getFrameBuffer(frameBuffer)
        return FrameCapture(frameBuffer.copyOf(w * h), w, h)
    }

    override fun lastError(): String = Psx2Native.lastError()

    companion object {
        @Volatile private var instance: Psx2Engine? = null
        fun get(): Psx2Engine = instance ?: synchronized(this) {
            instance ?: Psx2Engine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
