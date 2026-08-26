package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.PsxNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [PsxNative] (PCSX-ReARMed PlayStation 1 core).
 *
 * Architecture mirrors [FbNeoEngine] / [NesEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from native ring buffer (resampled to 48000 Hz in
 *     native code), writes to AudioTrack with BLOCKING mode
 *
 * PSX BIOS files (scph1000/1/2.bin, scph5500/1/2.bin, psxonpsp660.bin) are
 * looked up by the core in the system directory (set via [setPaths]).
 * Without a BIOS the core falls back to HLE BIOS (less compatible).
 *
 * CD images (.cue/.bin/.chd/.pbp/.m3u/.ecm) are passed by path to the core —
 * the core opens the CD image itself to parse the TOC and locate tracks.
 *
 * Button bit layout (12 buttons, same as SNES):
 *   bit0=□(Square), bit1=✕(Cross), bit2=Select, bit3=Start,
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
 *   bit8=△(Triangle), bit9=○(Circle), bit10=L1, bit11=R1
 */
class PsxEngine private constructor() : EmulatorEngine {

    /**
     * PSX frame buffer. PSX resolutions vary: 320x240 (NTSC), 320x256 (PAL),
     * 640x480 (hi-res). Size for the largest common case (640x480).
     */
    override val frameBuffer = IntArray(640 * 480)

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

    override fun ensureLoaded(): Boolean = PsxNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        cleanup()

        PsxNative.setPaths(systemDir, saveDir)

        if (!PsxNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        PsxNative.setFastForward(_ffSpeed)

        val rate = PsxNative.audioTargetSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "psxcore-loop", isDaemon = true) {
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
                            PsxNative.setPad1(pads.first)
                            PsxNative.setPad2(pads.second)
                        }
                    }

                    PsxNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    if (!running.get()) break

                    if (!hasSurface) {
                        PsxNative.getFrameBuffer(frameBuffer)
                    }

                    onFrame()

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
            } catch (t: Throwable) {
                android.util.Log.e("PsxEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        PsxNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        PsxNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        PsxNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) PsxNative.videoWidth() else 320
    override fun videoHeight(): Int = if (isLoaded) PsxNative.videoHeight() else 240

    override fun setVideoFilter(filter: Int) = PsxNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = PsxNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) PsxNative.setFastForward(speed)
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
        audioThread = thread(name = "psx-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = PsxNative.readAudio(buf)
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
                android.util.Log.e("PsxEngine", "Audio thread crashed", t)
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
        PsxNative.reset(hard)
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
            try { PsxNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

    override fun setPad1(bits: Int) = PsxNative.setPad1(bits)
    override fun setPad2(bits: Int) = PsxNative.setPad2(bits)
    fun setPad3(bits: Int) = PsxNative.setPad3(bits)
    fun setPad4(bits: Int) = PsxNative.setPad4(bits)
    override fun setRegion(region: Int) = PsxNative.setRegion(region)
    override fun setSampleRate(rate: Int) = PsxNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = PsxNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = PsxNative.loadState(slot, src.absolutePath)

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        val buf = IntArray(w * h)
        val ok = PsxNative.getFrameBuffer(buf)
        if (!ok) return null
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = PsxNative.lastError()

    companion object {
        @Volatile private var instance: PsxEngine? = null
        fun get(): PsxEngine = instance ?: synchronized(this) {
            instance ?: PsxEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
