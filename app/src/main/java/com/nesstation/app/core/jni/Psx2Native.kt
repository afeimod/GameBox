package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libpsx2core.so (PCEE2 — PCSX2 Sony PlayStation 2 core).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The native side dlopen()s the prebuilt
 * libpcee2_libretro_android.so at runtime and forwards retro_* calls.
 *
 * NOTE on the core choice: PCEE2 is the libretro core build of the CURRENT
 * upstream PCSX2 codebase (v2.7.523), shipped by the official libretro
 * buildbot for Android arm64-v8a. It replaces Play! as the PS2 core because
 * Play!'s game compatibility had too many issues.
 *
 * PS2 uses the 16-button DualShock layout plus DUAL ANALOG STICKS:
 *   bit0  = × (Cross)     bit1  = □ (Square)   bit2  = Select
 *   bit3  = Start         bit4  = Up    bit5  = Down
 *   bit6  = Left          bit7  = Right
 *   bit8  = ○ (Circle)    bit9  = △ (Triangle)
 *   bit10 = L1            bit11 = R1
 *   bit12 = L2            bit13 = R2
 *   bit14 = L3            bit15 = R3
 *
 * Analog sticks (setAnalog1/2) take int16 libretro axis values
 * (-32768..32767) for LX/LY/RX/RY — driven by the on-screen twin-stick UI.
 *
 * ## BIOS files
 * PCEE2 REQUIRES a real PS2 BIOS. Place scph10000.bin / scph39001.bin etc.
 * in `<systemDir>/pcsx2/bios/` (systemDir is set via [setPaths]). Without a
 * BIOS most games will not boot — loadRom() returns a detailed error
 * explaining where to put the file; a legacy `<systemDir>/bios/` folder from
 * previous versions is migrated automatically on first load.
 *
 * ## Disc images
 * PS2 games come as .iso / .chd / .cso / .zso / .cue+bin / .gz / .mdf /
 * .nrg / .elf. The loader passes the file path directly to the core (PCEE2
 * opens the image itself).
 */
object Psx2Native {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("psx2core")
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("Psx2Native", "Failed to load libpsx2core.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to `libpcee2_libretro_android.so` in
     * the app's native library directory. Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val libFile = java.io.File(nativeDir, "libpcee2_libretro_android.so")
            if (libFile.exists()) libFile.absolutePath else null
        } catch (_: Throwable) { null }
    }

    /** App context — set by NesApp.onCreate so Psx2Native can locate the lib. */
    @Volatile var appContext: android.content.Context? = null

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** 16-button DualShock state (port 0). See class doc for bit layout. */
    @JvmStatic external fun setPad1(bits: Int)
    /** Second controller (port 1, player 2). Same bit layout as [setPad1]. */
    @JvmStatic external fun setPad2(bits: Int)
    /** Third controller (port 2). Same bit layout as setPad1. */
    @JvmStatic external fun setPad3(bits: Int)
    /** Fourth controller (port 3). Same bit layout as setPad1. */
    @JvmStatic external fun setPad4(bits: Int)

    /**
     * Dual analog sticks (port 0). All values are int16 libretro range
     * (-32768..32767): LX, LY, RX, RY.
     */
    @JvmStatic external fun setAnalog1(lx: Int, ly: Int, rx: Int, ry: Int)
    /** Dual analog sticks (port 1, player 2). Same axis order as [setAnalog1]. */
    @JvmStatic external fun setAnalog2(lx: Int, ly: Int, rx: Int, ry: Int)

    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(speed: Int)

    /**
     * Switch a controller port device.
     * @param port 0..3
     * @param device RETRO_DEVICE_JOYPAD (1) or RETRO_DEVICE_ANALOG (5).
     *        Queued natively; applied on the emulation thread before the next
     *        frame — safe to call from the UI thread at any time.
     */
    @JvmStatic external fun setControllerDevice(port: Int, device: Int)

    /** Core-reported refresh rate in Hz (59.94 NTSC / 50.0 PAL). */
    @JvmStatic external fun videoFps(): Double

    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean

    @JvmStatic external fun getFrameBuffer(out: IntArray): Boolean
    @JvmStatic external fun readAudio(out: ShortArray): Int
    @JvmStatic external fun audioSampleRate(): Int
    @JvmStatic external fun audioTargetSampleRate(): Int
    @JvmStatic external fun setPaths(systemDir: String, saveDir: String)
    @JvmStatic external fun setSaveName(name: String)
    @JvmStatic external fun lastError(): String

    @JvmStatic external fun setSurface(surface: Surface?)

    /**
     * Set a core option by key and value.
     * PCEE2 (PCSX2) keys (verified against the core's own option table):
     *   "pcsx2_renderer"           -> "vulkan" | "software"
     *   "pcsx2_upscale_multiplier" -> "1" | "2" | "3" | "4"  (分辨率倍数)
     *   "pcsx2_texture_filtering"  -> "nearest" | "bilinear_ps2" |
     *                                 "bilinear_forced" | "bilinear_forced_sprite"
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** Video filter types (same as other cores): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether libpcee2_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean
}
