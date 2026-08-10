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
 * Architecture:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from native ring buffer, writes to AudioTrack with
 *     BLOCKING mode (prevents sample drops / crackling)
 *
 * Lifecycle:
 *  - [ensureLoaded] loads the native library (call once at app startup).
 *  - [loadRom] boots a native session and starts the emulation thread.
 *  - [setSurface] attaches a Surface for direct ANativeWindow blitting.
 *  - [unload] / [shutdown] stop the thread and release audio.
 *  - [setPad1] pushes controller state to the core for the next frame.
 */
class NesEngine private constructor() : EmulatorEngine {

    override val frameBuffer = IntArray(256 * 240)

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

    override fun ensureLoaded(): Boolean = NesNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean {
        if (!ensureLoaded()) return false

        stop()

        NesNative.setPaths(systemDir, saveDir)

        if (!NesNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        NesNative.setFastForward(_ffSpeed)

        startAudio(NesNative.audioSampleRate().takeIf { it > 0 } ?: 44100)

        running.set(true)
        thread = thread(name = "nescore-loop", isDaemon = true) {
            try {
                // Boost the emulation thread to a high priority so it doesn't
                // get starved by the UI thread or background GC on low-power
                // TV boxes. THREAD_PRIORITY_URGENT_DISPLAY (-8) is the same
                // priority used by SurfaceFlinger's render thread.
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

                    NesNative.runFrame()

                    // Re-check running right after the native call — if
                    // unload() ran during runFrame(), isLoaded is now false
                    // and we must NOT touch the core any further.
                    if (!running.get()) break

                    if (!hasSurface) {
                        NesNative.getFrameBuffer(frameBuffer)
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
                // Swallow native crashes on the emulation thread so the UI
                // thread doesn't get killed. The user will see a frozen
                // frame instead of a full app crash.
                android.util.Log.e("NesEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        NesNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        NesNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        NesNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) NesNative.videoWidth() else 256
    override fun videoHeight(): Int = if (isLoaded) NesNative.videoHeight() else 240

    override fun setVideoFilter(filter: Int) = NesNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = NesNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) NesNative.setFastForward(speed)
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
            // Use a larger buffer (minBuf * 8) to absorb jitter from the
            // emulation thread. This is the same pattern used by Android's
            // default phone audio path — a big enough buffer that brief
            // emulation stalls don't cause underruns, but small enough that
            // latency stays under ~100ms.
            val bufSize = (minBuf * 8).coerceAtLeast(16384)
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

        // Dedicated audio thread — mirrors the phone audio path:
        // 1. High priority (THREAD_PRIORITY_AUDIO = -16) so the audio
        //    thread is never starved by the emulation or UI threads.
        // 2. Moderate read size (2048 stereo frames = ~46ms @44.1k) —
        //    small enough for low latency, large enough to amortize JNI overhead.
        // 3. Non-blocking read from the native ring buffer, then blocking
        //    write to AudioTrack. When no audio is available, sleep 10ms
        //    (matching one video frame at 60fps) to avoid busy-spinning.
        audioRunning.set(true)
        audioThread = thread(name = "nes-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(2048)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = NesNative.readAudio(buf)
                        if (n > 0) {
                            // Blocking write — AudioTrack will buffer internally
                            // and play at the correct rate. This is the key to
                            // smooth audio: the AudioTrack's large internal buffer
                            // absorbs timing jitter from the emulation thread.
                            audioTrack?.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                        } else {
                            // No audio available — sleep one video frame (~16ms
                            // at 60fps) to let the emulation thread produce audio.
                            // This matches the emulation thread's frame rate and
                            // avoids waking the audio thread faster than the
                            // emulator can produce samples.
                            Thread.sleep(16)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NesEngine", "Audio thread crashed", t)
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
        running.set(false)
        thread?.let { t ->
            t.interrupt()
            // Join with retries — the emulation thread may be inside a
            // long retro_run() call (16-33ms). If it doesn't stop within
            // 500ms, try again up to 3 times before giving up. Without
            // this, unload() could call NesNative.unload() while the
            // thread is still inside retro_run(), causing a crash.
            for (attempt in 0 until 3) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
            }
        }
        thread = null
    }

    companion object {
        @Volatile private var instance: NesEngine? = null
        fun get(): NesEngine = instance ?: synchronized(this) {
            instance ?: NesEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
