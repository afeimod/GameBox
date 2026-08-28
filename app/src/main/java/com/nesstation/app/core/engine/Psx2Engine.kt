package com.nesstation.app.core.engine

import android.view.Surface
import com.nesstation.app.core.jni.Psx2Native
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [Psx2Native] — the ARMSX2 emucore
 * (`libemucore_4k.so`, PCSX2 fork with ARM64 JIT via vixl).
 *
 * Architecture differs fundamentally from the other engines (which run a
 * frame-pull loop + external AudioTrack): ARMSX2 is a self-contained
 * push-model core — it runs its own VM thread (`runVMThread`), renders
 * directly to the Surface given via [setSurface], and plays audio
 * internally via Oboe. So:
 *   - no emulation loop / FramePacer needed here,
 *   - no AudioTrack / audio thread,
 *   - the emulation thread from [loadRom] is owned by the core.
 *
 * A lightweight heartbeat thread still invokes the UI's `onFrame` callback
 * (e.g. the FPS counter) so the HUD keeps updating.
 *
 * PS2 BIOS files (scph10000.bin / scph39001.bin etc.) MUST be placed in
 * `<systemDir>/pcsx2/bios/`. Disc images (.iso/.chd/.cso/.zso/.cue+bin/...)
 * are passed by path to the core.
 *
 * Input: 16-button DualShock (bit0=×, bit1=□, bit8=○, bit9=△, bit12..15 =
 * L2/R2/L3/R3) plus dual analog sticks via [setAnalogAxes].
 *
 * State saves use the core's own slot files (saved under the system
 * folder), so the `path` argument of [saveState]/[loadState] is only kept
 * for UI compatibility.
 */
class Psx2Engine private constructor() : EmulatorEngine {

    /**
     * PS2 frame buffer. No longer used for rendering (the core draws to the
     * Surface directly) — retained only for API compatibility. ARMSX2 has no
     * frame-buffer read-back, so [captureFrame] returns null.
     */
    override val frameBuffer = IntArray(2560 * 2048)

    private val running = AtomicBoolean(false)
    private var heartbeatThread: Thread? = null

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _ffSpeed = 0
    @Volatile private var _paused = false

    /** Core-reported refresh rate; kept for compatibility with the UI. */
    @Volatile private var _targetHz: Int = 60

    // === Netplay (lockstep hook) — accepted but inert for the push-model core ===
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

        // Pace the HUD heartbeat to the core's own refresh rate
        // (NTSC 59.94 / PAL 50) — the core itself handles game pacing.
        val coreFps = Psx2Native.videoFps()
        _targetHz = if (coreFps > 10.0 && coreFps < 500.0) Math.round(coreFps).toInt() else 60

        running.set(true)
        heartbeatThread = thread(name = "psx2-hud-heartbeat", isDaemon = true) {
            val intervalMs = (1000L / _targetHz.coerceIn(30, 240)).coerceAtLeast(8L)
            try {
                while (running.get()) {
                    byFrameHook(_netFrame)
                    onFrame()
                    _netFrame++
                    try {
                        Thread.sleep(intervalMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("Psx2Engine", "Heartbeat thread crashed", t)
            }
        }
        return true
    }

    private fun byFrameHook(netFrame: Long) {
        val npHook = _frameHook ?: return
        val pads = npHook.beforeFrame(netFrame)
        if (pads != null) {
            setPad1(pads.first)
            setPad2(pads.second)
        }
    }

    override fun setSurface(surface: Surface?) {
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
        if (_paused == paused) return
        _paused = paused
        if (isLoaded) {
            try {
                if (paused) kr.co.iefriends.pcsx2.NativeApp.pause()
                else kr.co.iefriends.pcsx2.NativeApp.resume()
            } catch (t: Throwable) {
                android.util.Log.w("Psx2Engine", "pause/resume", t)
            }
        }
    }

    /**
     * Push dual analog stick axes for player 1. All values are int16
     * (-32768..32767) in LX, LY, RX, RY order.
     */
    fun setAnalogAxes(lx: Int, ly: Int, rx: Int, ry: Int) {
        if (isLoaded) Psx2Native.setAnalog1(lx, ly, rx, ry)
    }

    /** Push dual analog stick axes for player 2 (local co-op / netplay local). */
    fun setAnalogAxesP2(lx: Int, ly: Int, rx: Int, ry: Int) {
        if (isLoaded) Psx2Native.setAnalog2(lx, ly, rx, ry)
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
        heartbeatThread?.let { t ->
            t.interrupt()
            for (attempt in 0 until 6) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
            }
        }
        heartbeatThread = null
        if (isLoaded) {
            try { Psx2Native.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        _targetHz = 60
    }

    override fun setPad1(bits: Int) = Psx2Native.setPad1(bits)
    override fun setPad2(bits: Int) = Psx2Native.setPad2(bits)
    fun setPad3(bits: Int) = Psx2Native.setPad3(bits)
    fun setPad4(bits: Int) = Psx2Native.setPad4(bits)

    /**
     * Switch controller ports between digital pad and DualShock (analog).
     * ARMSX2 always exposes DualShock2 pads, so this is a no-op kept for
     * interface compatibility.
     */
    fun setControllerDevice(port: Int, device: Int) = Psx2Native.setControllerDevice(port, device)

    override fun setRegion(region: Int) = Psx2Native.setRegion(region)
    override fun setSampleRate(rate: Int) = Psx2Native.setSampleRate(rate)

    override fun saveState(slot: Int, dst: File): Boolean =
        Psx2Native.saveState(slot, dst.absolutePath).also {
            // UI checks stateFile.exists() before offering to load — the ARMSX2
            // core stores states in its own savestates folder, so touch a marker
            // file here to keep the slot picker flow working.
            if (it) {
                try { dst.parentFile?.mkdirs(); dst.createNewFile() } catch (_: Throwable) {}
            }
        }

    override fun loadState(slot: Int, src: File): Boolean =
        Psx2Native.loadState(slot, src.absolutePath)

    override fun captureFrame(): FrameCapture? {
        // ARMSX2 renders straight to the Surface and has no frame-buffer
        // read-back on this path — screenshots are unsupported for PS2.
        return null
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