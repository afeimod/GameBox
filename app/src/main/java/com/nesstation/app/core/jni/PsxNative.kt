package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libpsxcore.so (PCSX-ReARMed — Sony PlayStation 1 core).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The native side dlopen()s the prebuilt
 * libpcsx_rearmed_libretro_android.so at runtime and forwards retro_* calls.
 *
 * PSX uses the standard 12-button libretro gamepad layout (same bit
 * layout as SNES):
 *   bit0  = □ (Square)    bit1  = ✕ (Cross)    bit2  = Select
 *   bit3  = Start         bit4  = Up    bit5  = Down
 *   bit6  = Left           bit7  = Right
 *   bit8  = △ (Triangle)   bit9  = ○ (Circle)
 *   bit10 = L1 / L2 (left shoulder)   bit11 = R1 / R2 (right shoulder)
 *
 * For 2D games (most PSX games) only D-pad + □✕△○ + L/R + Start/Select are used.
 * For analog games use the analog pad type (set via core option
 * "pcsx_rearmed_pad1type" = "analog").
 *
 * ## BIOS files
 * PCSX-ReARMed can run without a BIOS (HLE BIOS built-in), but for full
 * compatibility a real BIOS is recommended. Place one of these in the
 * system directory (set via [setPaths]):
 *   scph1000.bin  — Japanese BIOS (for JP region games)
 *   scph1001.bin  — American BIOS (for US region games)
 *   scph1002.bin  — European BIOS (for EU region games)
 *   scph5500.bin / scph5501.bin / scph5502.bin — newer variants
 *   psxonpsp660.bin — PSP-derived BIOS (no copyright issues in some regions)
 *
 * The core option "pcsx_rearmed_bios" = "auto" auto-detects region.
 * These BIOS files have copyright and cannot be bundled with the app.
 *
 * ## CD images
 * PSX games come as CD images: .cue + .bin pairs, .chd, .pbp (PSP eboot),
 * .m3u (playlist), .ecm (compressed). The loader passes the file path
 * directly to the core (no in-memory pre-load) so the core can open the
 * CD image and parse the TOC itself.
 */
object PsxNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("psxcore")
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("PsxNative", "Failed to load libpsxcore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to `libpcsx_rearmed_libretro_android.so` in
     * the app's native library directory. Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val libFile = java.io.File(nativeDir, "libpcsx_rearmed_libretro_android.so")
            if (libFile.exists()) libFile.absolutePath else null
        } catch (_: Throwable) { null }
    }

    /** Set the absolute path to libpcsx_rearmed_libretro_android.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /** App context — set by NesApp.onCreate so PsxNative can locate the lib. */
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

    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(speed: Int)

    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean

    /**
     * Flush the core's SAVE_RAM (battery save / .srm) to disk atomically.
     * Safe to call mid-emulation (the native loader takes a state mutex
     * so retro_run() can't race). Returns false if no game is loaded,
     * the game has no SAVE_RAM region, or the write fails.
     *
     * Used by the global "存档机制" setting when "核心sav存档" is selected.
     */
    @JvmStatic external fun flushSaveRam(): Boolean

    /**
     * Reload the per-game .srm file into the core's SAVE_RAM buffer,
     * discarding any unsaved in-game progress. Safe to call mid-emulation.
     * Returns false if no game is loaded or no .srm exists.
     */
    @JvmStatic external fun reloadSaveRam(): Boolean

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
     * Common PCSX-ReARMed keys (must match libretro_core_options.h):
     *   "pcsx_rearmed_bios"              -> "auto" | "HLE" | "scph1000" | "scph1001" | ...
     *   "pcsx_rearmed_region"            -> "auto" | "ntsc" | "pal"
     *   "pcsx_rearmed_frameskip_type"    -> "disabled" | "auto" | "fixed"
     *   "pcsx_rearmed_frameskip"         -> "0".."10"
     *   "pcsx_rearmed_pad1type"          -> "standard" | "analog" | "negcon" | "gun"
     *   "pcsx_rearmed_pad2type"          -> "standard" | "analog" | "negcon" | "gun"
     *   "pcsx_rearmed_vibration"         -> "enabled" | "disabled"
     *   "pcsx_rearmed_dithering"         -> "enabled" | "disabled"
     *   "pcsx_rearmed_spu_interpolation" -> "simple" | "gaussian" | "cubic" | "off"
     *   "pcsx_rearmed_spu_reverb"        -> "enabled" | "disabled"
     *   "pcsx_rearmed_show_bios_bootlogo"-> "disabled" | "enabled"
     *   "pcsx_rearmed_cd_readahead"      -> "0".."30"
     *   "pcsx_rearmed_memcard1"          -> "libretro" | "shared" | "disabled"
     *   "pcsx_rearmed_memcard2"          -> "libretro" | "shared" | "disabled"
     *   "pcsx_rearmed_psxclock"          -> "auto" | "50".."200"
     *   "pcsx_rearmed_icache_emulation"  -> "disabled" | "enabled"
     *   "pcsx_rearmed_nocompathacks"      -> "disabled" | "enabled"
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** Video filter types (same as other cores): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether libpcsx_rearmed_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean
}
