package kr.co.iefriends.pcsx2

import android.view.Surface

/**
 * JNI binding to ARMSX2's `libemucore_4k.so` (PCSX2 fork with ARM64 JIT
 * via vixl), compiled directly from source in this repo via
 * `ARMSX2-master/platforms/android/app/src/main/cpp`.
 *
 * The emucore is a **push-model** core — it does NOT use libretro
 * callbacks. Key semantics:
 *  - `initialize()` must be called once before boot: it pins the system /
 *    BIOS directories (EmuFolders::DataRoot etc).
 *  - `runVMThread(path)` blocks until the VM exits — call it on a
 *    background thread. GameBox wraps it in [Psx2Native.loadRom].
 *  - Rendering: pass the Android `Surface` via [onNativeSurfaceChanged];
 *    the core renders into it itself (Vulkan/GL/Software via renderXxx).
 *  - Audio: played internally via Oboe — no audio pull on the Kotlin side.
 *  - Input: [setPadButtonForPort] uses Android KeyEvent keycodes
 *    (19=DPadUp, 96=Cross, 97=Circle, 99=Square, 100=Triangle,
 *    108=Start, 109=Select, 102=L1, 103=R1, 104=L2, 105=R2,
 *    106=L3, 107=R3, 110..113 = L-stick U/R/D/L, 120..123 = R-stick,
 *    200=Analog toggle). `range` is 0..32767 stick magnitude (digital
 *    buttons pass 0/32767).
 *  - Settings: [setSetting] + [commitSettings] push PCSX2 ini keys
 *    ("EmuCore/Speedhacks/vuThread" etc). Several runtime knobs have
 *    dedicated JNI (renderVulkan/renderOpenGL/renderSoftware/renderAuto,
 *    renderUpscalemultiplier, setAspectRatio, speedhackEecyclerate,
 *    speedhackEecycleskip).
 *
 * Only a subset of the ARMSX2 NativeApp JNI surface is declared here —
 * JNI binds lazily per used method, so unused symbols are never resolved.
 */
object NativeApp {

    @Volatile private var libLoaded = false

    init {
        loadCore()
    }

    /**
     * Load `libemucore_4k.so`. On 16 KiB-page devices (Android 15+ on some
     * hardware) the 4k build refuses to load — fall back to the 16k build.
     */
    @Synchronized
    @JvmStatic
    fun ensureLoaded(): Boolean {
        if (libLoaded) return true
        loadCore()
        return libLoaded
    }

    private fun loadCore() {
        if (libLoaded) return
        for (lib in arrayOf("emucore_4k", "emucore_16k")) {
            try {
                System.loadLibrary(lib)
                libLoaded = true
                android.util.Log.i("NativeApp", "loaded $lib")
                return
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.w("NativeApp", "loadLibrary($lib) failed", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * @param path       system folder (memcards / savestates / configs land
     *                   here) — GameBox passes `<filesDir>/ps2`
     * @param biosFolder folder containing the PS2 BIOS (GameBox:
     *                   `<systemDir>/pcsx2/bios`)
     * @param apiVer     emucore API version
     */
    @JvmStatic external fun initialize(path: String?, biosFolder: String?, apiVer: Int)

    /** Blocks until VM exit — run on a background thread. Returns false on boot failure. */
    @JvmStatic external fun runVMThread(path: String): Boolean

    /** Pass the emulation Surface (or null to detach). width/height 0 = keep window size. */
    @JvmStatic external fun onNativeSurfaceChanged(surface: Surface?, width: Int, height: Int)

    @JvmStatic external fun pause()
    @JvmStatic external fun resume()
    @JvmStatic external fun shutdown()
    @JvmStatic external fun hasActiveVM(): Boolean

    @JvmStatic external fun changeDisc(path: String)

    // ------------------------------------------------------------------
    // Input (Android KeyEvent keycodes — see class doc)
    // ------------------------------------------------------------------

    @JvmStatic external fun setPadButtonForPort(port: Int, key: Int, range: Int, pressed: Boolean)
    @JvmStatic external fun resetKeyStatus()

    // ------------------------------------------------------------------
    // Settings — PCSX2 ini keys, pushed pre-boot via commitSettings
    // ------------------------------------------------------------------

    @JvmStatic external fun setSetting(section: String, key: String, type: String, value: String)
    @JvmStatic external fun commitSettings()

    /** Runtime renderer switch (no restart needed). */
    @JvmStatic external fun renderVulkan()
    @JvmStatic external fun renderOpenGL()
    @JvmStatic external fun renderSoftware()
    @JvmStatic external fun renderAuto()

    /** Internal resolution multiplier (1x..4x). Mirrors psx2_upscale_multiplier. */
    @JvmStatic external fun renderUpscalemultiplier(multiplier: Float)

    /** AspectRatioType index (0=Stretch .. 5=Custom). */
    @JvmStatic external fun setAspectRatio(mode: Int)

    /** Speedhack knobs: EECycleRate (-3..3) / EECycleSkip (0..3). */
    @JvmStatic external fun speedhackEecyclerate(rate: Int)
    @JvmStatic external fun speedhackEecycleskip(skip: Int)

    /** SPU2 master volume (0..200 percent) + mute. */
    @JvmStatic external fun setAudioVolume(volume: Int)
    @JvmStatic external fun setAudioMuted(muted: Boolean)

    // ------------------------------------------------------------------
    // State save / load — slots 1..5, stored under DataRoot/savestates
    // ------------------------------------------------------------------

    @JvmStatic external fun saveStateToSlot(slot: Int): Boolean
    @JvmStatic external fun loadStateFromSlot(slot: Int): Boolean
    @JvmStatic external fun saveAutosaveState(): Boolean
    @JvmStatic external fun loadAutosaveState(): Boolean

    // ------------------------------------------------------------------
    // Telemetry
    // ------------------------------------------------------------------

    /** Presented FPS of the currently running VM (0 if none). */
    @JvmStatic external fun getFPS(): Float

    /** Internal (GS draw-call) fps — closer to the game's own refresh rate. */
    @JvmStatic external fun getVPS(): Float

    /** 0..100+ emulation speed percentage. */
    @JvmStatic external fun getEmuSpeedPercent(): Float

    @JvmStatic external fun getGameTitle(): String
    @JvmStatic external fun getGameSerial(): String
}