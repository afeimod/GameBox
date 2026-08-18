package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libfbneocore.so (FBNeo arcade core).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The native side dlopen()s the prebuilt
 * libfbneo_libretro_android.so at runtime and forwards retro_* calls.
 *
 * FBNeo uses the standard 12-button libretro gamepad layout (same bit
 * layout as SNES). The core maps these to arcade button labels:
 *   A=Button1, B=Button2, X=Button3, Y=Button4, L=Button5, R=Button6,
 *   Select=Coin, Start=Start.
 *
 * ## Gamepad bit layout (port 0, RETRO_DEVICE_JOYPAD)
 *   bit0  = A      → arcade Button 1 (e.g. Weak Punch / Weak Kick)
 *   bit1  = B      → arcade Button 2 (e.g. Medium Punch / Medium Kick)
 *   bit2  = Select → Coin (insert coin on arcade machines)
 *   bit3  = Start  → Start (also opens service menu on some games)
 *   bit4  = Up     bit5  = Down    bit6  = Left    bit7  = Right
 *   bit8  = X      → arcade Button 3 (e.g. Strong Punch)
 *   bit9  = Y      → arcade Button 4 (e.g. Strong Kick)
 *   bit10 = L      → arcade Button 5 (e.g. Drive / EX / 5th button)
 *   bit11 = R      → arcade Button 6 (e.g. 6th button, rarely used)
 *
 * For 4-button fighters (KOF, Street Fighter II) only A/B/X/Y are used.
 * For 6-button fighters (Street Fighter Alpha, Vampire Savior) all six are used.
 *
 * ## BIOS files
 * FBNeo requires BIOS zip files in the system directory (set via
 * [setPaths]) for certain hardware platforms:
 *   - neogeo.zip  — required for ALL NeoGeo games (MVS/AES)
 *   - pgm.zip     — required for ALL PGM games (Knights of Valour, Demon
 *                   Front, Espgaluda, etc.)
 *   - cvs2.zip    — Capcom VS SNK 2 decryption key
 *   - neocdz.zip  — NeoGeo CD BIOS (rare, used by CD-based games)
 *   - Various CPS1/CPS2/ST-V BIOS files (cps1.zip, cps2.zip, stvbios.zip)
 *
 * These BIOS files have copyright and cannot be bundled with the app.
 * Users must provide them via the BIOS import UI in Settings, or by
 * manually copying the zip files to the system directory.
 */
object FbNeoNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("fbneocore")
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("FbNeoNative", "Failed to load libfbneocore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to `libfbneo_libretro_android.so` in the
     * app's native library directory. Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val libFile = java.io.File(nativeDir, "libfbneo_libretro_android.so")
            if (libFile.exists()) libFile.absolutePath else null
        } catch (_: Throwable) { null }
    }

    /** Set the absolute path to libfbneo_libretro_android.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /** App context — set by NesApp.onCreate so FbNeoNative can locate the lib. */
    @Volatile var appContext: android.content.Context? = null

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** Standard libretro gamepad (port 0). See class doc for bit layout. */
    @JvmStatic external fun setPad1(bits: Int)

    /**
     * Second controller (port 1, player 2). Same bit layout as [setPad1].
     * Used for netplay: injects the remote opponent's input each frame.
     */
    @JvmStatic external fun setPad2(bits: Int)
    /** Third controller (port 2, player 3). Same bit layout as setPad1. */
    @JvmStatic external fun setPad3(bits: Int)
    /** Fourth controller (port 3, player 4). Same bit layout as setPad1. */
    @JvmStatic external fun setPad4(bits: Int)

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
     * Common FBNeo keys (must match libretro_core_options.h):
     *   "fbneo-vertical-mode"          -> "disabled" | "enabled"
     *   "fbneo-rotate-mode"            -> "norotate" | "cw" | "ccw" | "flip"
     *   "fbneo-aspect"                 -> "auto" | "4:3" | "3:4" | "16:9" | "16:15"
     *   "fbneo-crop-overscan"          -> "enabled" | "disabled"
     *   "fbneo-cpu-speed"              -> "100" | "75" | "50" | "150" | "200" | "250"
     *   "fbneo-cpu-frameskip"          -> "0".."10"
     *   "fbneo-force-60hz"             -> "disabled" | "enabled"
     *   "fbneo-samplerate"             -> "48000" | "44100" | "22050" | "11025"
     *   "fbneo-audio-quality"          -> "1" | "2"
     *   "fbneo-audio-interpolation"    -> "0" | "1" | "2" | "3"
     *   "fbneo-lowpass"                -> "disabled" | "enabled"
     *   "fbneo-lowpass-range"          -> "0".."100"
     *   "fbneo-neogeo-mode"            -> "MVS" | "AES"
     *   "fbneo-memcard-mode"           -> "enabled" | "disabled"
     *   "fbneo-debug-text"             -> "disabled" | "enabled"
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** Video filter types (same as NES/GBA): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether libfbneo_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean
}
