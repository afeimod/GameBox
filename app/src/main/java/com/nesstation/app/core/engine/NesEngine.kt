package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.NesNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [NesNative]. Owns the emulation thread, the
 * AudioTrack for sound, and optional hardware-accelerated surface rendering.
 *
 * Lifecycle:
 *  - [ensureLoaded] loads the native library (call once at app startup).
 *  - [loadRom] boots a native session and starts the emulation thread.
 *  - [setSurface] attaches a Surface for direct ANativeWindow blitting.
 *  - [unload] / [shutdown] stop the thread and release audio.
 *  - [setPad1] pushes controller state to the core for the next frame.
 *
 * When a Surface is attached via [setSurface], the native core blits each
 * frame directly to the surface buffer (hardware-accelerated path). The
 * Kotlin-side frame buffer copy is skipped entirely, giving smooth 60fps.
 */
class NesEngine private constructor() : EmulatorEngine {

    override val frameBuffer = IntArray(256 * 240)

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private val audioBuf = ShortArray(8192) // pulled per frame, ~93ms @44.1k stereo

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _fastForward = false
    @Volatile private var hasSurface = false
    @Volatile private var _paused = false

    override fun ensureLoaded(): Boolean = NesNative.ensureLoaded()

    /**
     * Load a ROM and start the emulation thread.
     * @param rom the .nes ROM file
     * @param systemDir directory for FDS BIOS etc. (app files dir)
     * @param saveDir directory for SRAM / save states
     * @param onFrame called on the emulation thread after each produced frame
     * @return true if the ROM loaded and emulation started
     */
    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean {
        if (!ensureLoaded()) return false

        // Stop any existing emulation thread first.
        stop()

        NesNative.setPaths(systemDir, saveDir)

        if (!NesNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        // Apply current fast-forward state
        NesNative.setFastForward(_fastForward)

        // Set up AudioTrack at the core's native sample rate.
        startAudio(NesNative.audioSampleRate().takeIf { it > 0 } ?: 44100)

        running.set(true)
        thread = thread(name = "nescore-loop", isDaemon = true) {
            while (running.get()) {
                if (_paused) {
                    // Paused: drain audio to prevent buffer overflow, but don't step the core
                    val n = NesNative.readAudio(audioBuf)
                    if (n > 0) {
                        audioTrack?.write(audioBuf, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING)
                    }
                    try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                    continue
                }

                val t0 = System.nanoTime()

                NesNative.runFrame()

                // Only copy the frame buffer to Kotlin if we DON'T have a
                // hardware-accelerated surface (the native code already blitted
                // the frame directly to the ANativeWindow when a surface is set).
                if (!hasSurface) {
                    NesNative.getFrameBuffer(frameBuffer)
                }

                // Pull and play audio
                val n = NesNative.readAudio(audioBuf)
                if (n > 0) {
                    audioTrack?.write(audioBuf, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING)
                }

                onFrame()

                // Pace to ~60fps (NTSC) unless fast-forward
                val targetNs = if (_fastForward) 1_000_000L else 1_000_000_000L / 60
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
        return true
    }

    /**
     * Attach a [Surface] for hardware-accelerated direct rendering.
     * The native core will blit each frame directly to the surface via
     * ANativeWindow, bypassing the JNI frame buffer copy entirely.
     * Pass null to detach (e.g. when the SurfaceView is destroyed).
     */
    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        NesNative.setSurface(surface)
    }

    /**
     * Set a core option (e.g. NTSC filter, aspect ratio, palette).
     * The change takes effect on the next frame.
     */
    override fun setCoreOption(key: String, value: String) {
        NesNative.setCoreOption(key, value)
    }

    /** Current video width from the core (e.g. 256, or 302 with NTSC filter). */
    override fun videoWidth(): Int = if (isLoaded) NesNative.videoWidth() else 256

    /** Current video height from the core (e.g. 240). */
    override fun videoHeight(): Int = if (isLoaded) NesNative.videoHeight() else 240

    /**
     * Set the frontend video post-processing filter.
     *   0 = none, 1 = scanline, 2 = crt, 3 = dot, 4 = xbr,
     *   5 = hq2x, 6 = hq4x, 7 = xbr+dot
     */
    override fun setVideoFilter(filter: Int) = NesNative.setVideoFilter(filter)

    override fun setFastForward(on: Boolean) {
        _fastForward = on
        if (isLoaded) NesNative.setFastForward(on)
    }

    /**
     * Pause or resume emulation. When paused, the emulation thread stays alive
     * but doesn't call runFrame(), so the game freezes while the UI remains responsive.
     */
    override fun setPaused(paused: Boolean) {
        _paused = paused
    }

    private fun startAudio(sampleRate: Int) {
        stopAudio()
        try {
            val bufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
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
            // Audio is non-fatal — game still runs without sound
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

    override fun reset(hard: Boolean) = NesNative.reset(hard)

    override fun unload() {
        stop()
        stopAudio()
        setSurface(null)
        if (isLoaded) {
            NesNative.unload()
            isLoaded = false
        }
    }

    override fun shutdown() = unload()

    override fun setPad1(bits: Int) = NesNative.setPad1(bits)
    override fun setRegion(region: Int) = NesNative.setRegion(region)
    override fun setSampleRate(rate: Int) = NesNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File) { NesNative.saveState(slot, dst.absolutePath) }
    override fun loadState(slot: Int, src: File) { NesNative.loadState(slot, src.absolutePath) }

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        val buf = IntArray(w * h)
        val ok = NesNative.getFrameBuffer(buf)
        if (!ok) return null
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = NesNative.lastError()

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
        @Volatile private var instance: NesEngine? = null
        fun get(): NesEngine = instance ?: synchronized(this) {
            instance ?: NesEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
