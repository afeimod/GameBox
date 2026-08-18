package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.GenesisNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [GenesisNative] (Genesis-Plus-GX core for
 * SEGA Mega Drive / Genesis / Master System / Game Gear / SG-1000 / Mega-CD).
 *
 * Architecture mirrors [SnesEngine] / [FbNeoEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from native ring buffer (resampled to 48000 Hz in
 *     native code), writes to AudioTrack with BLOCKING mode
 *
 * Audio pipeline:
 *   Genesis-Plus-GX (44100/53267 Hz) → libretro callback → AudioRingBuffer
 *     → AudioResampler → 48000 Hz → readAudio() JNI → AudioTrack
 *
 * Lifecycle:
 *  - [ensureLoaded] loads the native library (call once at app startup).
 *  - [loadRom] boots a native session and starts the emulation thread.
 *  - [setSurface] attaches a Surface for direct ANativeWindow blitting.
 *  - [unload] / [shutdown] stop the thread and release audio.
 *  - [setPad1] pushes controller state to the core for the next frame.
 *
 * ROM loading: Genesis-Plus-GX accepts file paths directly. For Mega-CD
 * games (.cue/.chd/.iso), BIOS files (bios_CD_E.zip, bios_CD_J.zip,
 * bios_CD_U.zip) must be present in the system directory (set via
 * [setPaths]). Cartridge games (MD/SMS/GG/SG) do NOT require BIOS.
 *
 * Button bit layout (12 buttons, SNES-style remapped to SEGA):
 *   bit0=A(SEGA A), bit1=B(SEGA B), bit2=Select(Mode), bit3=Start,
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
 *   bit8=X(SEGA C), bit9=Y(SEGA X 6btn), bit10=L(SEGA Y 6btn), bit11=R(SEGA Z 6btn)
 *
 * NOTE: Genesis-Plus-GX does NOT support the SEGA Saturn (SS). Saturn
 * requires a separate Saturn core (Yabause / Mednafen).
 */
class GenesisEngine private constructor() : EmulatorEngine {

    /**
     * SEGA frame buffer. Resolutions vary by system:
     *   MD:    320x224 / 320x240 (H40), 256x224 / 256x240 (H32), 320x448 (interlaced)
     *   SMS:   256x192 / 256x224 (PAL)
     *   GG:    160x144
     *   SG-1k: 256x192
     * We size for the largest common case (512x512 with margin).
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

    /** Lock for all lifecycle methods to prevent restart conflicts. */
    private val lifecycleLock = Any()

    override fun ensureLoaded(): Boolean = GenesisNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        cleanup()

        GenesisNative.setPaths(systemDir, saveDir)

        if (!GenesisNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        GenesisNative.setFastForward(_ffSpeed)

        val rate = GenesisNative.audioTargetSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "genesicore-loop", isDaemon = true) {
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
                    GenesisNative.runFrame()
                    if (!running.get()) break

                    if (!hasSurface) {
                        GenesisNative.getFrameBuffer(frameBuffer)
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
                android.util.Log.e("GenesisEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        GenesisNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        GenesisNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        GenesisNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) GenesisNative.videoWidth() else 320
    override fun videoHeight(): Int = if (isLoaded) GenesisNative.videoHeight() else 224

    override fun setVideoFilter(filter: Int) = GenesisNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = GenesisNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) GenesisNative.setFastForward(speed)
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
        audioThread = thread(name = "genesis-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = GenesisNative.readAudio(buf)
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
                android.util.Log.e("GenesisEngine", "Audio thread crashed", t)
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
        GenesisNative.reset(hard)
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
            try { GenesisNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }

        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

    override fun setPad1(bits: Int) = GenesisNative.setPad1(bits)
    override fun setPad2(bits: Int) = GenesisNative.setPad2(bits)
    override fun setRegion(region: Int) = GenesisNative.setRegion(region)
    override fun setSampleRate(rate: Int) = GenesisNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File) { GenesisNative.saveState(slot, dst.absolutePath) }
    override fun loadState(slot: Int, src: File) { GenesisNative.loadState(slot, src.absolutePath) }

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        val buf = IntArray(w * h)
        val ok = GenesisNative.getFrameBuffer(buf)
        if (!ok) return null
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = GenesisNative.lastError()

    companion object {
        @Volatile private var instance: GenesisEngine? = null
        fun get(): GenesisEngine = instance ?: synchronized(this) {
            instance ?: GenesisEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
