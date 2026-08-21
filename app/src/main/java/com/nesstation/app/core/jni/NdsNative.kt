package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libndscore.so (melonDS — Nintendo DS / DSi core).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The native side dlopen()s the prebuilt
 * libmelonds_libretro_android.so at runtime and forwards retro_* calls.
 *
 * DS uses the standard 12-button libretro gamepad layout (same bit layout
 * as SNES):
 *   bit0  = A (Right face button — Nintendo layout: B is left, A is right)
 *   bit1  = B (Left face button)
 *   bit2  = Select
 *   bit3  = Start
 *   bit4  = Up    bit5  = Down    bit6  = Left    bit7  = Right
 *   bit8  = X (top face button — upper of the 4)
 *   bit9  = Y (left face button — left of the 4)
 *   bit10 = L (left shoulder)
 *   bit11 = R (right shoulder)
 *
 * NOTE: On a real DS, A/B/X/Y are arranged in a diamond (Y top, X left,
 * A right, B bottom) — similar to SNES. The libretro port maps these to
 * the standard SNES bit layout, so the same keymap used for SNES works here.
 *
 * DS also has a touchscreen (bottom screen). Touch input is exposed via
 * [setTouchInput] — pass signed coordinates (-0x8000..0x7FFF) and a pressed
 * flag. The native bridge forwards these to the core via RETRO_DEVICE_POINTER.
 *
 * ## BIOS files
 * melonDS 0.9.3 uses FreeBIOS (built-in BIOS replacement) whenever the real
 * files are absent from the system directory (set via [setPaths]):
 *   bios7.bin      — ARM7 BIOS (loaded if present)
 *   bios9.bin      — ARM9 BIOS (loaded if present)
 *   firmware.bin   — DS firmware (loaded if present)
 *   dsi_arm7.bin   — (DSi only) ARM7 binary
 *   dsi_bios7.bin  — (DSi only) ARM7 BIOS
 *   dsi_bios9.bin  — (DSi only) ARM9 BIOS
 *   dsi_firmware.bin — (DSi only) DSi firmware
 *   dsi_nand.bin   — (DSi only) DSi NAND image
 *
 * The core option "melonds_console_mode" = "DS" (default) uses the NDS
 * BIOS set; "DSi" requires the DSi files.
 * These BIOS files have copyright and cannot be bundled with the app.
 *
 * ## ROM files
 * DS ROMs come as .nds (cartridge dump), .app (DSiWare), or .ids (some
 * ROM hacks). All are loaded into memory (max 512 MB). melonDS does NOT
 * support .zip / .7z archives — extract the ROM first.
 */
object NdsNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("ndscore")
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("NdsNative", "Failed to load libndscore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to `libmelonds_libretro_android.so` in the
     * app's native library directory. Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val libFile = java.io.File(nativeDir, "libmelonds_libretro_android.so")
            if (libFile.exists()) libFile.absolutePath else null
        } catch (_: Throwable) { null }
    }

    /** Set the absolute path to libmelonds_libretro_android.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /** App context — set by NesApp.onCreate so NdsNative can locate the lib. */
    @Volatile var appContext: android.content.Context? = null

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** Standard libretro gamepad (port 0). See class doc for bit layout. */
    @JvmStatic external fun setPad1(bits: Int)
    /** Second controller (port 1, player 2). Same bit layout as [setPad1]. */
    @JvmStatic external fun setPad2(bits: Int)
    /** Third controller (port 2). Same bit layout as setPad1. */
    @JvmStatic external fun setPad3(bits: Int)
    /** Fourth controller (port 3). Same bit layout as setPad1. */
    @JvmStatic external fun setPad4(bits: Int)

    /**
     * Touchscreen input via RETRO_DEVICE_POINTER.
     * @param x Normalized X coordinate (0..0xFFFF, maps to 0..255 by the core).
     * @param y Normalized Y coordinate (0..0xFFFF, maps to 0..191 by the core).
     * @param pressed true = touching the screen, false = released.
     */
    @JvmStatic external fun setTouchInput(x: Int, y: Int, pressed: Boolean)

    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(speed: Int)

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
     * Keys/values MUST match the prebuilt melonDS 0.9.3 libretro core.
     * Valid options (verified against the .so):
     *   "melonds_boot_directly"         -> "enabled" | "disabled"  (enabled = jump straight into the game; disabled = boot into the grey FW menu)
     *   "melonds_screen_layout"         -> "Top/Bottom" | "Bottom/Top" | "Left/Right" | "Right/Left" | "Top Only" | "Bottom Only"
     *   "melonds_console_mode"          -> "DS" | "DSi"  (anything except "DSi" = DS)
     *   "melonds_use_fw_settings"       -> "enabled" | "disabled"
     *   "melonds_touch_mode"            -> "Mouse" | "Touch" | "Joystick"
     *   "melonds_threaded_renderer"     -> "enabled" | "disabled"
     *   "melonds_dsi_sdcard"            -> "disabled" | "enabled"
     *   "melonds_randomize_mac_address" -> "disabled" | "enabled"
     *   "melonds_jit_enable"            -> "enabled" | "disabled"
     *   "melonds_audio_interpolation"   -> (0.9.3: Cosine | Linear | Sinc | ...)
     * NOTE: "melonds_use_fw_bios" / "melonds_opengl_*" / "melonds_screensaver" /
     * "melonds_mouse_speed" / "melonds_sysfile_directory" do NOT exist in 0.9.3
     * and are silently ignored by the core.
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** Video filter types (same as other cores): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether libmelonds_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean
}
