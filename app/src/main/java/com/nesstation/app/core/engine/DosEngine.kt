package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.DosNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [DosNative] (DOSBox-Pure core for DOS/PC games).
 *
 * Architecture mirrors [GbaEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from native ring buffer (resampled to 48000 Hz in
 *     native code), writes to AudioTrack with BLOCKING mode
 *
 * Audio pipeline:
 *   DOSBox-Pure (44100/48000 Hz) → libretro callback → AudioRingBuffer
 *     → AudioResampler → 48000 Hz → readAudio() JNI → AudioTrack
 *
 * Input pipeline (unique to DOS):
 *   - Standard gamepad bits via [setPad1] (auto-mapped to DOS keys by the core)
 *   - Full keyboard via [injectKeyDown] / [injectKeyUp] (RETROK_* codes)
 *   - Mouse via [injectMouseMove] / [injectMouseButton]
 *   - Input device mode selectable via [setInputDeviceMode] (default = combined)
 *
 * Gamepad bit layout (12 buttons, see DosNative doc):
 *   bit0=A(Enter), bit1=B(Esc), bit2=Select, bit3=Start,
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
 *   bit8=L(mouse left), bit9=R(mouse right),
 *   bit10=X(Space), bit11=Y(Tab)
 */
class DosEngine private constructor() : EmulatorEngine {

    /**
     * DOSBox video frame buffer. DOS resolutions can be up to 1024x768 (SVGA),
     * so we size for the worst case. The native code zero-pads if the actual
     * frame is smaller.
     */
    override val frameBuffer = IntArray(1024 * 768)

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

    override fun ensureLoaded(): Boolean = DosNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean {
        if (!ensureLoaded()) return false

        stop()

        DosNative.setPaths(systemDir, saveDir)

        if (!DosNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        DosNative.setFastForward(_ffSpeed)

        // Default to combined input device mode — gamepad + keyboard + mouse.
        // This lets the on-screen overlay dispatch all three input types.
        DosNative.setInputDeviceMode(3)

        val rate = DosNative.audioTargetSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "doscore-loop", isDaemon = true) {
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
                    DosNative.runFrame()
                    if (!running.get()) break

                    if (!hasSurface) {
                        DosNative.getFrameBuffer(frameBuffer)
                    }

                    onFrame()

                    // DOSBox-Pure typically runs at 60-70 fps; pace to 60 fps.
                    // The core's `dosbox_pure_force60fps` option (default on)
                    // already normalizes output to 60 fps.
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
                android.util.Log.e("DosEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        DosNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        DosNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        DosNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) DosNative.videoWidth() else 320
    override fun videoHeight(): Int = if (isLoaded) DosNative.videoHeight() else 200

    override fun setVideoFilter(filter: Int) = DosNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = DosNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) DosNative.setFastForward(speed)
    }

    override fun setPaused(paused: Boolean) {
        _paused = paused
    }

    // --- DOSBox-specific input methods ---

    /** Inject a keyboard key-down event. [keyCode] is a DosKeys constant. */
    fun injectKeyDown(keyCode: Int, modifiers: Int = 0) =
        DosNative.injectKeyDown(keyCode, modifiers)

    /** Inject a keyboard key-up event. [keyCode] is a DosKeys constant. */
    fun injectKeyUp(keyCode: Int, modifiers: Int = 0) =
        DosNative.injectKeyUp(keyCode, modifiers)

    /** Inject a mouse move event (relative delta). */
    fun injectMouseMove(dx: Int, dy: Int) = DosNative.injectMouseMove(dx, dy)

    /** Inject a mouse button event. [button] is 0=LEFT, 1=RIGHT, 2=MIDDLE. */
    fun injectMouseButton(button: Int, pressed: Boolean) =
        DosNative.injectMouseButton(button, pressed)

    /**
     * Set input device mode on port 0.
     *   0 = JOYPAD only
     *   1 = KEYBOARD only
     *   2 = MOUSE only
     *   3 = Combined (default — all three)
     */
    fun setInputDeviceMode(mode: Int) = DosNative.setInputDeviceMode(mode)

    private fun startAudio(sampleRate: Int) {
        stopAudio()
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // LOW-LATENCY: Use the smallest buffer Android will allow.
            // Previously this was `minBuf * 4` which introduced ~80-150ms of
            // latency on top of the ring buffer. For DOS games (especially
            // games with sound effects tied to gameplay like PAL, StarControl,
            // etc.) this made audio feel noticeably delayed.
            //
            // We now use `minBuf` directly (clamped to a safe lower bound of
            // 2048 samples = ~21ms at 48kHz stereo). Combined with the smaller
            // read buffer below, total round-trip latency drops to <30ms.
            val bufSize = minBuf.coerceIn(2048, 8192)
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
        audioThread = thread(name = "dos-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            // Smaller read buffer = lower latency. 512 stereo frames = ~10ms
            // at 48kHz. The native ring buffer can supply this without
            // underrunning as long as the emulation thread keeps up.
            val buf = ShortArray(1024)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = DosNative.readAudio(buf)
                        if (n > 0) {
                            audioTrack?.write(buf, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING)
                        } else {
                            Thread.sleep(1)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("DosEngine", "Audio thread crashed", t)
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

    override fun reset(hard: Boolean) = DosNative.reset(hard)

    override fun unload() {
        // Same unload order as GbaEngine — surface first, then stop, then audio,
        // then core. Prevents blit-after-unload SIGSEGV.
        try { setSurface(null) } catch (_: Throwable) {}
        try { stop() } catch (_: Throwable) {}
        try { stopAudio() } catch (_: Throwable) {}
        if (isLoaded) {
            try { DosNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        // Reset frontend-side state so a new game starts clean.
        // Without this, _paused=true or _ffSpeed=N from the previous session
        // would carry over and make the new game start paused or fast-forwarding.
        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

    override fun shutdown() = unload()

    // EmulatorEngine interface implementations — use DOS-specific methods above
    override fun setPad1(bits: Int) = DosNative.setPad1(bits)
    override fun setRegion(region: Int) = DosNative.setRegion(region)
    override fun setSampleRate(rate: Int) = DosNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File) { DosNative.saveState(slot, dst.absolutePath) }
    override fun loadState(slot: Int, src: File) { DosNative.loadState(slot, src.absolutePath) }

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        val buf = IntArray(w * h)
        val ok = DosNative.getFrameBuffer(buf)
        if (!ok) return null
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = DosNative.lastError()

    private fun stop() {
        running.set(false)
        thread?.let { t ->
            t.interrupt()
            for (attempt in 0 until 6) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
            }
        }
        thread = null
    }

    companion object {
        @Volatile private var instance: DosEngine? = null
        fun get(): DosEngine = instance ?: synchronized(this) {
            instance ?: DosEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
