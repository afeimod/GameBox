package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.NdsNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [NdsNative] (melonDS Nintendo DS / DSi core).
 *
 * Architecture mirrors [FbNeoEngine] / [NesEngine] / [PsxEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from native ring buffer (resampled to 48000 Hz in
 *     native code), writes to AudioTrack with BLOCKING mode
 *
 * melonDS BIOS files (bios7.bin, bios9.bin, firmware.bin) are looked up by
 * the core in the system directory (set via [setPaths]). For DSi mode
 * ("melonds_console_mode" = "dsi") additional BIOS files are required
 * (dsi_arm7.bin, dsi_bios7.bin, dsi_bios9.bin, dsi_firmware.bin, dsi_nand.bin).
 *
 * DS ROMs (.nds / .app / .ids) are pre-loaded into memory by the loader
 * (max 512 MB). The core does NOT support .zip / .7z archives — extract first.
 *
 * Button bit layout (12 buttons, same as SNES):
 *   bit0=A, bit1=B, bit2=Select, bit3=Start,
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
 *   bit8=X, bit9=Y, bit10=L, bit11=R
 *
 * Touchscreen input is supported via [setTouchInput] — pass signed
 * coordinates (-0x8000..0x7FFF) and a pressed flag. The core maps
 * these to the bottom screen pixel coordinates (0..255, 0..191)
 * internally. The state is stored in atomics on the native side and
 * read by the core on each frame.
 */
class NdsEngine private constructor() : EmulatorEngine {

    /**
     * NDS frame buffer as presented to the dual-screen custom-layout view.
     * In custom-layout mode (no surface) this holds the FILTERED composite
     * frame (HQ2X/HQ4X/XBR upscaled when such a filter is active — sized to
     * [filteredVideoWidth] x [filteredVideoHeight]); in surface mode it keeps
     * the raw frame (pulled only when no surface is attached, see the loop
     * in [loadRom]). DS screens are 256x192 each; the default top+bottom
     * stacked layout produces 256x384. With the GL (OpenGL) compositor the
     * core emits 256x386 (384 + 2-row gap between the screens), so this
     * buffer is grown on demand before each JNI frame pull.
     */
    @Volatile override var frameBuffer: IntArray = IntArray(256 * 384)

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

    override fun ensureLoaded(): Boolean = NdsNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        cleanup()

        NdsNative.setPaths(systemDir, saveDir)

        if (!NdsNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        NdsNative.setFastForward(_ffSpeed)

        val rate = NdsNative.audioTargetSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "ndscore-loop", isDaemon = true) {
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
                            NdsNative.setPad1(pads.first)
                            NdsNative.setPad2(pads.second)
                        }
                    }

                    NdsNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    if (!running.get()) break

                    // Frame pull strategy:
                    //  - Custom dual-screen mode (no surface attached): pull
                    //    the (possibly filter-upscaled) frame so
                    //    NdsDualScreenView can slice the two screens out of
                    //    it. This is the ONLY consumer of frameBuffer.
                    //  - Surface mode: the native cb_video already applied
                    //    the filter and blitted straight to the ANativeWindow
                    //    inside runFrame(). Pulling the frame again here
                    //    would copy ~400 KB through JNI per frame for nothing
                    //    (screenshots pull on demand via captureFrame()).
                    if (!hasSurface) {
                        val fw = NdsNative.filteredVideoWidth().coerceAtLeast(1)
                        val fh = NdsNative.filteredVideoHeight().coerceAtLeast(1)
                        if (fw > 0 && fh > 0 && frameBuffer.size < fw * fh) {
                            frameBuffer = IntArray(fw * fh)
                        }
                        NdsNative.getFilteredFrameBuffer(frameBuffer)
                    }

                    onFrame()

                    // Frame pacing — matches NesEngine.kt's pattern.
                    // CRITICAL: NDS fast-forward was previously a no-op because
                    // this loop ALWAYS paced to 60fps regardless of _ffSpeed.
                    // The C++ side (nds_loader.cpp::cb_video) only skips the
                    // video blit during FF — it does NOT run multiple frames.
                    // So the visible FF effect comes entirely from this pacing:
                    // when _ffSpeed > 0, we pace to 60 * _ffSpeed fps
                    // (e.g., 6x = 360 fps = ~2.78 ms / frame, far faster than
                    // the native ~16.67 ms / frame). On devices that can't
                    // sustain that throughput, the loop runs as fast as the
                    // CPU allows and Thread.sleep(0) is a no-op.
                    //
                    // NOTE: Audio thread keeps producing samples at 1x; the
                    // C++ side skips cb_audio_batch push during FF to avoid
                    // the ring buffer overflowing (which would otherwise
                    // drop samples and create crackle on FF resume).
                    if (_ffSpeed > 0) {
                        val targetNs = 1_000_000_000L / (60L * _ffSpeed)
                        val elapsed = System.nanoTime() - t0
                        val sleep = targetNs - elapsed
                        if (sleep > 0) {
                            try {
                                Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    } else {
                        // Normal: pace to ~60fps (NDS is 59.83 Hz but 60 is
                        // close enough; the audio resampler absorbs drift).
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
                android.util.Log.e("NdsEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        NdsNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        NdsNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        NdsNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) NdsNative.videoWidth() else 256
    override fun videoHeight(): Int = if (isLoaded) NdsNative.videoHeight() else 384

    /** Width of the frame stored in [frameBuffer] (custom-layout mode: the
     *  filter-upscaled width when an upscale filter is active). */
    fun filteredVideoWidth(): Int = if (isLoaded) NdsNative.filteredVideoWidth() else 256

    /** Height of the frame stored in [frameBuffer] (custom-layout mode: the
     *  filter-upscaled height when an upscale filter is active). */
    fun filteredVideoHeight(): Int = if (isLoaded) NdsNative.filteredVideoHeight() else 384

    /** Monotonic core frame counter — NdsDualScreenView uses it to skip
     *  redundant redraws on high-refresh displays. */
    fun frameStamp(): Long = if (isLoaded) NdsNative.frameStamp() else 0L

    override fun setVideoFilter(filter: Int) = NdsNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = NdsNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) NdsNative.setFastForward(speed)
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
            // Increased from minBuf*4 to minBuf*6 for NDS specifically.
            // The 32768→48000 Hz resampling (ratio 0.6827) is asymmetric, so
            // melonDS's SPU produces samples in slightly-variable chunks per
            // frame (~546 ± 2). The smaller buffer (minBuf*4 ≈ 128ms) was
            // tight enough that any thread-scheduling jitter (GC pause,
            // AudioTrack.write blocking longer than expected, foreground app
            // switch) caused the AudioTrack buffer to underrun → silent gap
            // → click when audio resumed. minBuf*6 ≈ 192ms gives ~64ms more
            // headroom without introducing noticeable latency (game audio is
            // ~16ms/frame, so 192ms buffer = ~12 frames, still snappy).
            val bufSize = (minBuf * 6).coerceAtLeast(12288)
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
        audioThread = thread(name = "nds-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            // Reduced buf from 4096 to 2048 shorts (1024 stereo frames).
            // 4096 shorts = 2048 frames = ~42ms at 48kHz — that's larger than
            // one frame's worth of audio (~16ms), so a single readResampled
            // call could drain up to 2.5 frames worth from the ring buffer at
            // once, risking an underrun on the next call. 2048 shorts =
            // 1024 frames = ~21ms, so each call drains at most ~1.3 frames —
            // the ring buffer (which holds ~4 frames at 16kHz headroom)
            // always has enough for back-to-back reads.
            val buf = ShortArray(2048)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = NdsNative.readAudio(buf)
                        if (n > 0) {
                            audioTrack?.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                        } else {
                            // Reduced sleep from 2ms → 1ms. NDS produces a
                            // batch of ~547 source frames per emulation frame
                            // (every ~16.67ms). With 2ms sleep, we polled
                            // ~8 times per frame period before getting data,
                            // wasting CPU and increasing latency. 1ms sleep
                            // doubles poll frequency without measurably
                            // increasing CPU (the thread still blocks on
                            // AudioTrack.write the vast majority of the time).
                            Thread.sleep(1)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NdsEngine", "Audio thread crashed", t)
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
        NdsNative.reset(hard)
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
            try { NdsNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

    override fun setPad1(bits: Int) = NdsNative.setPad1(bits)
    override fun setPad2(bits: Int) = NdsNative.setPad2(bits)
    fun setPad3(bits: Int) = NdsNative.setPad3(bits)
    fun setPad4(bits: Int) = NdsNative.setPad4(bits)
    /**
     * Set touchscreen input state (legacy composite-frame path, Hybrid
     * layouts only — see NdsNative.setTouchInput).
     * @param x Normalized X (0..0xFFFF, maps to 0..255 by the core).
     * @param y Normalized Y (0..0xFFFF, maps to 0..191 by the core).
     * @param pressed true = touching, false = released.
     */
    fun setTouchInput(x: Int, y: Int, pressed: Boolean) = NdsNative.setTouchInput(x, y, pressed)

    /**
     * Set touchscreen input with DIRECT bottom-screen pixel coordinates —
     * the official melonDS Android frontend architecture. Preferred for
     * every non-hybrid layout including the custom free-form layout.
     * @param x Bottom-screen pixel X (0..255).
     * @param y Bottom-screen pixel Y (0..191).
     * @param pressed true = touching, false = released.
     */
    fun setTouchInputDirect(x: Int, y: Int, pressed: Boolean) = NdsNative.setTouchInputDirect(x, y, pressed)
    override fun setRegion(region: Int) = NdsNative.setRegion(region)
    override fun setSampleRate(rate: Int) = NdsNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File) { NdsNative.saveState(slot, dst.absolutePath) }
    override fun loadState(slot: Int, src: File) { NdsNative.loadState(slot, src.absolutePath) }

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        val buf = IntArray(w * h)
        val ok = NdsNative.getFrameBuffer(buf)
        if (!ok) return null
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = NdsNative.lastError()

    companion object {
        @Volatile private var instance: NdsEngine? = null
        fun get(): NdsEngine = instance ?: synchronized(this) {
            instance ?: NdsEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
