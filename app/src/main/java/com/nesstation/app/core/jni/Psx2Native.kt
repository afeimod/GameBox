package com.nesstation.app.core.jni

import android.view.Surface
import kr.co.iefriends.pcsx2.NativeApp
import java.io.File
import kotlin.concurrent.thread

/**
 * JNI surface to the ARMSX2 emucore (`libemucore_4k.so` — PCSX2 fork with
 * ARM64 JIT via vixl), compiled from source in this repo.
 *
 * This is a **forwarding layer**: GameBox's high-level engine (Psx2Engine)
 * and UI keep using the same method names/signatures as the previous
 * PCEE2/libretro build, but every call now forwards to the ARMSX2
 * `kr.co.iefriends.pcsx2.NativeApp` SDK. The core model is push-based:
 *   - the VM runs on its own thread (started by [loadRom] → `runVMThread`)
 *   - rendering goes straight to the Surface passed via [setSurface]
 *   - audio is played internally via Oboe (no [readAudio] pull anymore)
 *   - input is pushed per-button via Android keycodes (see [PAD_KEYS])
 *   - settings are pushed as PCSX2 ini keys, see [setCoreOption]
 *
 * PS2 16-button DualShock layout (consistent with previous versions):
 *   bit0  = × (Cross)     bit1  = □ (Square)   bit2  = Select
 *   bit3  = Start         bit4  = Up    bit5  = Down
 *   bit6  = Left          bit7  = Right
 *   bit8  = ○ (Circle)    bit9  = △ (Triangle)
 *   bit10 = L1            bit11 = R1
 *   bit12 = L2            bit13 = R2
 *   bit14 = L3            bit15 = R3
 *
 * Analog sticks (setAnalog1/2) take int16 values (-32768..32767) for
 * LX/LY/RX/RY and are mapped to ARMSX2's stick keycodes with magnitude
 * in `range` (110..113 = L-stick U/R/D/L, 120..123 = R-stick).
 *
 * ## BIOS files
 * ARMSX2 requires a real PS2 BIOS (scph10000.bin / scph39001.bin etc),
 * placed in `<systemDir>/pcsx2/bios/` (systemDir is passed to [setPaths]
 * and forwarded to NativeApp.initialize as the bios folder).
 *
 * ## Disc images
 * .iso / .chd / .cso / .zso / .cue+bin / .gz / .mdf / .nrg / .elf — the
 * path is passed straight to `runVMThread`.
 */
object Psx2Native {

    @Volatile private var loaded = false
    @Volatile private var vmThread: Thread? = null

    /**
     * GameBox 16-bit layout (bit0=Cross .. bit15=R3) → ARMSX2 Android
     * KeyEvent keycodes accepted by NativeApp.setPadButtonForPort.
     */
    private val PAD_KEYS = intArrayOf(
        96,  // bit0  × Cross
        99,  // bit1  □ Square
        109, // bit2  Select
        108, // bit3  Start
        19,  // bit4  Up
        20,  // bit5  Down
        21,  // bit6  Left
        22,  // bit7  Right
        97,  // bit8  ○ Circle
        100, // bit9  △ Triangle
        102, // bit10 L1
        103, // bit11 R1
        104, // bit12 L2
        105, // bit13 R2
        106, // bit14 L3
        107, // bit15 R3
    )

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            NativeApp.ensureLoaded()
        } catch (t: Throwable) {
            android.util.Log.e("Psx2Native", "Failed to load ARMSX2 emucore", t)
            false
        }
        return loaded
    }

    /**
     * Boot the game on the ARMSX2 VM thread. Blocks that background thread
     * until VM exit (runVMThread is blocking) — returns immediately here.
     */
    @JvmStatic fun loadRom(path: String): Boolean {
        if (!ensureLoaded()) return false
        stopVmThread()
        vmThread = thread(name = "armsx2-vm", isDaemon = true) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                NativeApp.runVMThread(path)
                android.util.Log.i("Psx2Native", "runVMThread exited")
            } catch (t: Throwable) {
                android.util.Log.e("Psx2Native", "VM thread crashed", t)
            }
        }
        return true
    }

    private fun stopVmThread() {
        vmThread?.let { t ->
            try { t.interrupt() } catch (_: Throwable) {}
            try { t.join(300) } catch (_: InterruptedException) {}
        }
        vmThread = null
    }

    @JvmStatic fun unload() {
        try { NativeApp.shutdown() } catch (t: Throwable) { android.util.Log.w("Psx2Native", "shutdown", t) }
        stopVmThread()
    }

    /** ARMSX2 has no libretro-style reset; the core handles resets internally. */
    @JvmStatic fun reset(hard: Boolean) { /* no-op */ }

    /** ARMSX2 runs its own VM loop — nothing to pull. */
    @JvmStatic fun runFrame() { /* no-op */ }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @JvmStatic fun setPad1(bits: Int) = pushPad(0, bits)
    @JvmStatic fun setPad2(bits: Int) = pushPad(1, bits)
    @JvmStatic fun setPad3(bits: Int) = pushPad(2, bits)
    @JvmStatic fun setPad4(bits: Int) = pushPad(3, bits)

    private fun pushPad(port: Int, bits: Int) {
        if (!loaded || !NativeApp.hasActiveVM()) return
        // Full re-push: ARMSX2 stores per-key state, so every bit change is
        // reflected by re-issuing all 16 buttons each time (cheap JNI call).
        for (i in 0 until 16) {
            val pressed = (bits ushr i) and 1 != 0
            NativeApp.setPadButtonForPort(port, PAD_KEYS[i], 0, pressed)
        }
    }

    /** Dual analog sticks (port 0). Values are int16: LX, LY, RX, RY. */
    @JvmStatic fun setAnalog1(lx: Int, ly: Int, rx: Int, ry: Int) = pushSticks(0, lx, ly, rx, ry)
    /** Dual analog sticks (port 1). Same order as [setAnalog1]. */
    @JvmStatic fun setAnalog2(lx: Int, ly: Int, rx: Int, ry: Int) = pushSticks(1, lx, ly, rx, ry)

    private fun pushSticks(port: Int, lx: Int, ly: Int, rx: Int, ry: Int) {
        if (!loaded || !NativeApp.hasActiveVM()) return
        // L-stick keycodes 110..113, R-stick 120..123 (UP/RIGHT/DOWN/LEFT).
        stickAxis(port, 110, 111, 112, 113, lx, ly)
        stickAxis(port, 120, 121, 122, 123, rx, ry)
    }

    /** Push one 2-axis stick as four directional buttons with magnitude range. */
    private fun stickAxis(port: Int, upKey: Int, rightKey: Int, downKey: Int, leftKey: Int, x: Int, y: Int) {
        val up = -y
        val down = y
        val left = -x
        val right = x
        NativeApp.setPadButtonForPort(port, upKey, if (up > 0) up else 0, up > 0)
        NativeApp.setPadButtonForPort(port, downKey, if (down > 0) down else 0, down > 0)
        NativeApp.setPadButtonForPort(port, leftKey, if (left > 0) left else 0, left > 0)
        NativeApp.setPadButtonForPort(port, rightKey, if (right > 0) right else 0, right > 0)
    }

    // ------------------------------------------------------------------
    // Audio / video — push model: core handles both internally
    // ------------------------------------------------------------------

    @JvmStatic fun setRegion(region: Int) { /* ARMSX2 auto-detects from disc */ }
    @JvmStatic fun setSampleRate(rate: Int) { /* core runs Oboe internally */ }

    /** Toggle full-speed mode: drop frame-limit when fast-forwarding. */
    @JvmStatic fun setFastForward(speed: Int) {
        if (!loaded) return
        try {
            if (speed > 0) {
                NativeApp.setSetting("EmuCore/GS", "FrameLimitEnable", "bool", "false")
            } else {
                NativeApp.setSetting("EmuCore/GS", "FrameLimitEnable", "bool", "true")
            }
            NativeApp.commitSettings()
        } catch (t: Throwable) { /* best-effort */ }
    }

    /** ARMSX2 always exposes both pads as DualShock2. */
    @JvmStatic fun setControllerDevice(port: Int, device: Int) { /* no-op */ }

    /** VP (internal draw-rate) of the running VM — used for HUD pacing. */
    @JvmStatic fun videoFps(): Double {
        return try {
            if (NativeApp.hasActiveVM()) {
                val vps = NativeApp.getVPS().toDouble()
                if (vps > 1.0 && vps < 500.0) vps else 60.0
            } else 60.0
        } catch (t: Throwable) { 60.0 }
    }

    @JvmStatic fun saveState(slot: Int, path: String): Boolean =
        try { NativeApp.saveStateToSlot(slot) } catch (t: Throwable) { false }

    @JvmStatic fun loadState(slot: Int, path: String): Boolean =
        try { NativeApp.loadStateFromSlot(slot) } catch (t: Throwable) { false }

    /** No frame buffer to pull — ARMSX2 renders straight to the Surface. */
    @JvmStatic fun getFrameBuffer(out: IntArray): Boolean = false

    /** No audio to pull — ARMSX2 plays internally via Oboe. */
    @JvmStatic fun readAudio(out: ShortArray): Int = 0
    @JvmStatic fun audioSampleRate(): Int = 48000
    @JvmStatic fun audioTargetSampleRate(): Int = 48000

    /** systemDir = <filesDir>/ps2; ARMSX2 reads BIOS from <systemDir>/pcsx2/bios. */
    @JvmStatic fun setPaths(systemDir: String, saveDir: String) {
        try {
            NativeApp.initialize(systemDir, File(systemDir, "pcsx2/bios").absolutePath, 1)
        } catch (t: Throwable) {
            android.util.Log.e("Psx2Native", "initialize failed", t)
        }
    }

    @JvmStatic fun setSaveName(name: String) { /* ARMSX2 keeps save slots itself */ }

    @JvmStatic fun lastError(): String = ""

    /** Pass the emulation Surface (or null). Size 0/0 → core keeps window size. */
    @JvmStatic fun setSurface(surface: Surface?) {
        if (loaded) {
            try { NativeApp.onNativeSurfaceChanged(surface, 0, 0) }
            catch (t: Throwable) { android.util.Log.w("Psx2Native", "onNativeSurfaceChanged", t) }
        }
    }

    // ------------------------------------------------------------------
    // Core options: pcsx2_* (previous libretro keys) → ARMSX2 ini keys
    // ------------------------------------------------------------------

    @JvmStatic fun setCoreOption(key: String, value: String) {
        if (!loaded) return
        try {
            when (key) {
                "pcsx2_renderer" -> when (value) {
                    "vulkan" -> NativeApp.renderVulkan()
                    "opengl" -> NativeApp.renderOpenGL()
                    "software" -> NativeApp.renderSoftware()
                    else -> NativeApp.renderAuto()
                }

                "pcsx2_upscale_multiplier" ->
                    NativeApp.renderUpscalemultiplier(value.toFloatOrNull() ?: 1f)

                "pcsx2_fast_boot" ->
                    NativeApp.setSetting("EmuCore", "EnableFastBoot", "bool", truthy(value))
                "pcsx2_rumble" ->
                    NativeApp.setSetting("Pad1", "ForceFeedback", "bool", truthy(value))

                "pcsx2_mtvu" ->
                    NativeApp.setSetting("EmuCore/Speedhacks", "vuThread", "bool", truthy(value))
                "pcsx2_instant_vu1" ->
                    NativeApp.setSetting("EmuCore/Speedhacks", "vu1Instant", "bool", truthy(value))
                "pcsx2_ee_cycle_rate" ->
                    NativeApp.setSetting("EmuCore/Speedhacks", "EECycleRate", "int", value)
                "pcsx2_ee_cycle_skip" ->
                    NativeApp.setSetting("EmuCore/Speedhacks", "EECycleSkip", "int", value)

                "pcsx2_aspect_ratio" ->
                    NativeApp.setAspectRatio(mapAspectRatio(value))

                "pcsx2_widescreen_patches" ->
                    NativeApp.setSetting("EmuCore", "EnableWideScreenPatches", "bool", truthy(value))
                "pcsx2_no_interlacing_patches" ->
                    NativeApp.setSetting("EmuCore", "EnableNoInterlacingPatches", "bool", truthy(value))

                // --- Pixel-level GS options (best-effort; keys follow the ARMSX2
                //     Settings.kt put() table, not all are exposed upstream) ---
                "pcsx2_texture_filtering" ->
                    NativeApp.setSetting("EmuCore/GS", "TriFilter", "int", if (value.contains("bilinear")) "1" else "0")
                "pcsx2_mipmapping" ->
                    NativeApp.setSetting("EmuCore/GS", "hw_mipmap", "bool", truthy(value))
                "pcsx2_dithering" ->
                    NativeApp.setSetting("EmuCore/GS", "Dithering", "int", value)
                "pcsx2_blending_accuracy" ->
                    NativeApp.setSetting("EmuCore/GS", "BlendingAccuracy", "int", value)
                "pcsx2_trilinear_filtering" ->
                    NativeApp.setSetting("EmuCore/GS", "TriFilter", "int", value)
                "pcsx2_anisotropic_filtering" ->
                    NativeApp.setSetting("EmuCore/GS", "AnisoFilter", "int", value)
                "pcsx2_deinterlace_mode" ->
                    NativeApp.setSetting("EmuCore/GS", "Deinterlace", "int", value)
                "pcsx2_hw_download_mode" -> { /* no ARMSX2 equivalent */ }

                else -> android.util.Log.w("Psx2Native", "unmapped core option: $key=$value")
            }
            // Persist to the core's ini layer; read at next VM boot.
            NativeApp.commitSettings()
        } catch (t: Throwable) {
            android.util.Log.w("Psx2Native", "setCoreOption($key=$value)", t)
        }
    }

    private fun truthy(value: String): String =
        if (value.equals("true", true) || value == "1" || value.equals("enabled", true)) "true" else "false"

    /** GameBox pcsx2_aspect_ratio strings → ARMSX2 AspectRatioType index. */
    private fun mapAspectRatio(value: String): Int = when (value.lowercase()) {
        "auto" -> 1
        "4:3" -> 2
        "16:9" -> 3
        "16:10" -> 4
        "stretch" -> 0
        else -> 1
    }

    @JvmStatic fun videoWidth(): Int = 640
    @JvmStatic fun videoHeight(): Int = 448

    /** ARMSX2 renders directly — video filters not applied on this path. */
    @JvmStatic fun setVideoFilter(filter: Int) { /* no-op */ }
    @JvmStatic fun setHighQualityScaling(enabled: Boolean) { /* no-op */ }

    /** Whether libemucore_4k.so was successfully loaded. */
    @JvmStatic fun isCoreLibLoaded(): Boolean = loaded

    /** App context — retained for API compatibility (previously used to locate the prebuilt lib). */
    @Volatile var appContext: android.content.Context? = null
}