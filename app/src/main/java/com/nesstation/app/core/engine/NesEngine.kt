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
 *   - Audio thread: reads from native ring buffer (resampled to 48000 Hz in
 *     native code), writes to AudioTrack with BLOCKING mode (prevents sample
 *     drops / crackling)
 *
 * Audio pipeline (matches the GB/GBC/GBA core):
 *   FCEUmm core (44100 Hz) → libretro callback → AudioRingBuffer
 *     → AudioResampler (44100→48000 Hz, linear interpolation)
 *     → readAudio() JNI → AudioTrack (48000 Hz)
 *
 * The resampler runs in the native layer (rom_loader.cpp). AudioTrack is
 * always created at 48000 Hz, which is Android's native audio sample rate.
 * This eliminates the buzzing/crackling/muffled audio that occurred on TV
 * boxes (HDMI output always runs at 48000 Hz) when AudioTrack was created
 * at the core's native 44100 Hz and AudioFlinger was forced to resample.
 * Phones often have 44100 Hz as the native rate, so the bug was invisible
 * there — which is why the issue only appeared in TV mode.
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

        // Use the target sample rate (48000 Hz) for AudioTrack, not the core's
        // native rate (typically 44100 Hz for FCEUmm). The native resampler in
        // rom_loader.cpp converts from the core rate to 48000 Hz before
        // returning samples via readAudio().
        //
        // WHY: On TV boxes with HDMI output, the hardware native audio rate is
        // always 48000 Hz. If AudioTrack is created at 44100 Hz, Android's
        // AudioFlinger performs low-quality resampling to 48000 Hz internally,
        // producing audible buzzing, crackling, and muffled audio. On phones
        // the native rate is often 44100 Hz, so the bug was invisible there.
        // This matches the GB/GBC/GBA core (mGBA) which already does the same.
        val rate = NesNative.audioTargetSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

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
        // The audio thread reads from the native ring buffer (non-blocking,
        // already resampled to 48000 Hz by the native AudioResampler) and
        // writes to AudioTrack (blocking). AudioTrack plays at the hardware
        // sample rate, so the blocking write naturally paces the loop — when
        // AudioTrack's buffer is full, write() blocks until room is available,
        // preventing audio acceleration.
        audioRunning.set(true)
        audioThread = thread(name = "nes-audio-loop", isDaemon = true) {
            // Boost audio thread priority so it's not starved by the
            // emulation thread on low-power TV devices.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = NesNative.readAudio(buf)
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
        // === 卸载顺序很重要，避免闪退 ===
        // 1. 先 setSurface(null) —— 通知 native 不再 blit 到 surface，
        //    避免 emulation thread 在 surfaceDestroyed 之后还调用
        //    ANativeWindow_lock 操作已释放的窗口（这是最常见的闪退源）。
        //    native 端 setSurface 会获取 s_windowMtx，blitToSurface 也会
        //    获取同一把锁，所以这里返回后保证不会有 blit 在进行中。
        // 2. stop() —— 停 emulation thread，等线程退出 retro_run()。
        // 3. stopAudio() —— 停 audio thread，释放 AudioTrack。
        // 4. NesNative.unload() —— 卸载核心（retro_unload_game + retro_deinit）。
        //
        // 之前顺序是 stop → stopAudio → setSurface(null) → unload，
        // 问题：surfaceDestroyed 由 Compose 在 onDispose 之前或之后异步触发，
        // 可能发生 emulation thread 还在 retro_run() 里写 framebuffer、
        // native video callback 在 blitToSurface 里锁 s_windowMtx ——
        // 这时 setSurface(null) 还没执行，blit 到一个正在被销毁的 surface
        // 上 → ANativeWindow_lock 返回错误或直接 SIGSEGV。
        // 重新排序 + try/catch 兜底后这种偶发闪退消除。
        try { setSurface(null) } catch (_: Throwable) {}
        try { stop() } catch (_: Throwable) {}
        try { stopAudio() } catch (_: Throwable) {}
        if (isLoaded) {
            try { NesNative.unload() } catch (_: Throwable) {}
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
            // 500ms, try again up to 6 times (3s total) before giving up.
            // 给足时间让 retro_run() 返回后再释放核心 —— 否则 native
            // 端 retro_unload_game() 会释放 emulation thread 正在访问的
            // 内存，导致偶发 SIGSEGV（用户描述的"偶尔退出游戏闪退"）。
            // 之前只重试 3 次（1.5s），对某些慢速设备 / 长帧渲染不够。
            for (attempt in 0 until 6) {
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
