package com.nesstation.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Individual button layout — each button has its own position and size.
 * Position is stored as a fraction of the screen (0.0–1.0).
 * Size is stored in dp (diameter for round buttons, width for pill buttons).
 */
data class ButtonLayout(
    val x: Float,       // 0.0 = left edge, 1.0 = right edge (center of button)
    val y: Float,       // 0.0 = top, 1.0 = bottom (center of button)
    val sizeDp: Int     // diameter/width in dp
)

/**
 * Extra key entry for dynamically added DOS keys (letters, numbers, symbols, F-keys, etc.).
 * These are not part of the fixed DosBtnType enum and can be freely added/removed.
 * Serialized as JSON for storage in PadLayout.
 */
data class DosExtraKeyEntry(
    val keyCode: Int,       // libretro RETROK_* constant
    val label: String,      // Display label (e.g. "A", "1", "F1")
    val x: Float = 0.5f,   // 0.0–1.0 fraction of screen width
    val y: Float = 0.5f,   // 0.0–1.0 fraction of screen height
    val sizeDp: Int = 36   // diameter in dp
) {
    fun toJson(): String {
        val arr = JSONArray()
        arr.put(keyCode); arr.put(label); arr.put(x); arr.put(y); arr.put(sizeDp)
        return arr.toString()
    }

    companion object {
        fun fromJson(json: String): DosExtraKeyEntry? {
            return try {
                val arr = JSONArray(json)
                DosExtraKeyEntry(
                    keyCode = arr.getInt(0),
                    label = arr.getString(1),
                    x = arr.getDouble(2).toFloat(),
                    y = arr.getDouble(3).toFloat(),
                    sizeDp = arr.getInt(4)
                )
            } catch (_: Exception) { null }
        }

        /** Parse a JSON array string into a list of DosExtraKeyEntry. */
        fun parseList(json: String): List<DosExtraKeyEntry> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { fromJson(arr.getString(it)) }
            } catch (_: Exception) { emptyList() }
        }

        /** Serialize a list of DosExtraKeyEntry into a JSON array string. */
        fun formatList(entries: List<DosExtraKeyEntry>): String {
            val arr = JSONArray()
            entries.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}

/**
 * Complete on-screen controller layout with per-button positioning.
 * Every button can be individually positioned and resized.
 * Includes L/R shoulder buttons (GBA/SNES) and X/Y face buttons (SNES).
 *
 * IMPORTANT: 横屏 / 竖屏的按键位置互相独立，互不干扰。
 *   - landscape 布局：横向玩游戏时手柄排布（dpad 左下，A/B 右下，L/R 顶部）
 *   - portrait  布局：竖屏玩游戏时手柄排布（默认值适合竖屏，可单独调整）
 * 用户在横屏编辑手柄位置后切到竖屏，竖屏布局保持自己原来的设置；反之亦然。
 * 全局设置（透明度、是否显示手柄、核心选项等）两个方向共享。
 */
data class PadLayout(
    // === 横屏布局（landscape） ===
    val dpad: ButtonLayout = ButtonLayout(x = 0.13f, y = 0.78f, sizeDp = 140),
    val btnA: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.76f, sizeDp = 72),
    val btnB: ButtonLayout = ButtonLayout(x = 0.72f, y = 0.82f, sizeDp = 72),
    val btnTurboA: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.60f, sizeDp = 48),
    val btnTurboB: ButtonLayout = ButtonLayout(x = 0.72f, y = 0.66f, sizeDp = 48),
    val btnStart: ButtonLayout = ButtonLayout(x = 0.62f, y = 0.92f, sizeDp = 56),
    val btnSelect: ButtonLayout = ButtonLayout(x = 0.38f, y = 0.92f, sizeDp = 56),
    val btnL: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.15f, sizeDp = 56),
    val btnR: ButtonLayout = ButtonLayout(x = 0.90f, y = 0.15f, sizeDp = 56),
    val btnX: ButtonLayout = ButtonLayout(x = 0.88f, y = 0.54f, sizeDp = 60),
    val btnY: ButtonLayout = ButtonLayout(x = 0.73f, y = 0.60f, sizeDp = 60),
    // === 竖屏布局（portrait）—— 默认值给竖屏一个更舒服的排布 ===
    // dpad 放左下、A/B 放右下，跟横屏差不多但 y 坐标稍微上移避开屏幕底部
    val dpadP: ButtonLayout = ButtonLayout(x = 0.18f, y = 0.74f, sizeDp = 130),
    val btnAP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.72f, sizeDp = 68),
    val btnBP: ButtonLayout = ButtonLayout(x = 0.68f, y = 0.80f, sizeDp = 68),
    val btnTurboAP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.56f, sizeDp = 46),
    val btnTurboBP: ButtonLayout = ButtonLayout(x = 0.68f, y = 0.62f, sizeDp = 46),
    val btnStartP: ButtonLayout = ButtonLayout(x = 0.62f, y = 0.90f, sizeDp = 54),
    val btnSelectP: ButtonLayout = ButtonLayout(x = 0.38f, y = 0.90f, sizeDp = 54),
    val btnLP: ButtonLayout = ButtonLayout(x = 0.12f, y = 0.12f, sizeDp = 54),
    val btnRP: ButtonLayout = ButtonLayout(x = 0.88f, y = 0.12f, sizeDp = 54),
    val btnXP: ButtonLayout = ButtonLayout(x = 0.83f, y = 0.50f, sizeDp = 56),
    val btnYP: ButtonLayout = ButtonLayout(x = 0.69f, y = 0.56f, sizeDp = 56),
    // === 全局设置（横竖屏共享） ===
    val opacity: Float = 0.7f,     // 0.3 – 1.0
    val showPad: Boolean = true,
    // Core options — values MUST match FCEUmm's libretro_core_options.h
    val ntscFilter: String = "disabled",  // disabled | composite | svideo | rgb | monochrome
    val aspectRatio: String = "4:3",  // SNES9x: "4:3" | "uncorrected" | "auto" | "ntsc" | "pal"
    val palette: String = "default",      // default | dq | nx | asq | rp2 | ...
    val region: String = "Auto",          // Auto | NTSC | PAL | Dendy
    val soundQuality: String = "Low",     // Low | High | Very High
    val cropOverscan: String = "disabled",// disabled | enabled  (maps to 4 individual overscan keys)
    // Video scaling — controls SurfaceView layout aspect ratio (frontend-level, not FCEUmm option)
    // "custom" = free-form rect controlled by the 4-corner drag editor
    val videoScale: String = "stretch",   // stretch | 4:3 | 8:7 | 16:9 | custom
    // Custom free-form layout rect (normalized 0..1, relative to the game
    // surface container). Used when videoScale == "custom": left/top is the
    // top-left corner, right/bottom is the bottom-right corner. The user
    // drags the 4 corners to resize and the rectangle body to move.
    val customLayoutLeft: Float = 0f,
    val customLayoutTop: Float = 0f,
    val customLayoutRight: Float = 1f,
    val customLayoutBottom: Float = 1f,
    // 竖屏版自由布局矩形：横竖屏分别保存，旋转屏幕时不互相覆盖
    //（与手柄按钮 padLayoutP 等竖屏字段的 p_ 前缀命名保持一致）。
    val customLayoutLeftP: Float = 0f,
    val customLayoutTopP: Float = 0f,
    val customLayoutRightP: Float = 1f,
    val customLayoutBottomP: Float = 1f,
    // Video filter — applied in the native blit function (frontend-level post-processing)
    val videoFilter: String = "none",     // none | scanline | crt | dot | xbr | hq2x | hq4x | xbr_dot
    // Overclocking — adds dummy scanlines to the PPU loop, reducing slowdowns
    val overclocking: String = "disabled", // disabled | 2x-Postrender | 2x-VBlank
    // --- SFC/SNES (snes9x) specific options ---
    val sfcReduceSpriteFlicker: String = "disabled",  // disabled | enabled
    val sfcReduceSlowdown: String = "disabled",       // disabled | light | compatible | max
    val sfcAudioInterpolation: String = "gaussian",   // gaussian | cubic | sinc | none | linear
    val sfcGfxTransparency: String = "enabled",       // enabled | disabled
    val sfcGfxHires: String = "enabled",              // enabled | disabled
    val sfcGfxClip: String = "enabled",               // enabled | disabled
    val sfcBlockInvalidVram: String = "disabled",      // disabled(allow) | enabled(block) — allow by default to fix font garbling
    val sfcSoundOutput: String = "disabled",           // disabled | enabled (echo buffer hack)
    val sfcOverscan: String = "enabled",              // enabled | disabled | auto
    val sfcSideBySide: String = "disabled",            // disabled | merge | blur (hires blend)
    val sfcUpDownAllowed: String = "disabled",        // disabled | enabled
    val sfcSuperScope: String = "disabled",            // disabled | enabled (randomize memory)
    val sfcLayer1: String = "enabled",                // BG layer 1
    val sfcLayer2: String = "enabled",                // BG layer 2
    val sfcLayer3: String = "enabled",                // BG layer 3
    val sfcLayer4: String = "enabled",                // BG layer 4
    val sfcLayer5: String = "enabled",                // OBJ/sprite layer
    val sfcOverclock: String = "100%",                // 50%-500% (SuperFX frequency)
    // --- GB/GBA (mGBA) specific options ---
    val gbColorCorrection: String = "enabled",        // enabled | disabled
    val gbcColorPreset: String = "default",           // default | various presets
    val gbaColorCorrection: String = "enabled",       // enabled | disabled
    val gbaColorPreset: String = "default",           // default | various presets
    val gbaFrameBlending: String = "OFF",             // OFF | ON | fast
    val gbaAudioResampler: String = "sinc",         // sinc | nearest | cosine | cubic
    val gbaAudioLowPass: String = "enabled",          // disabled | enabled
    val gbaAudioLowPassRange: String = "50",          // 0-100 (50 = balanced for GBA)
    val gbaFrameskipType: String = "disabled",        // disabled | auto | fixed
    val gbaFrameskipCount: String = "0",              // 0-10
    val gbaSolarSensor: String = "0",                 // 0-10
    val gbaIdleOptimization: String = "disabled",     // disabled | enabled (GBA only)
    val gbaForceRTC: String = "disabled",             // disabled | enabled
    val gbaAllowOpposite: String = "OFF",             // OFF | ON
    // --- Additional GB/GBA (mGBA) options ---
    val gbModel: String = "Autodetect",               // Autodetect | Game Boy | Super Game Boy | Game Boy Color | Game Boy Advance
    val gbSgbBorders: String = "ON",                  // ON | OFF
    val gbaFrameskipThreshold: String = "33",         // 0-100 (audio buffer threshold for auto frameskip)
    // --- DOSBox-Pure (DOS) specific options ---
    val dosMachine: String = "svga_s3",               // svga_s3 | hercules | cga | tandy | pcjr | ega | vgaonly | none
    val dosCycles: String = "auto",                    // auto | max | 6000 | 10000 | 20000 | 40000 | 80000 | custom
    val dosCyclesMax: String = "50000",                // string (used when dosCycles = custom)
    val dosSbType: String = "sb16",                    // sb1 | sb2 | sbpro1 | sbpro2 | sb16 | gb | none
    val dosSbAdlibMode: String = "off",                // on | off
    val dosSbAdlibEmu: String = "default",             // default | cms | dual
    val dosGus: String = "off",                        // off | on
    val dosMouseInput: String = "touchpad",            // touchpad | auto | virtual | direct | off
    val dosMouseTimeout: String = "off",               // off | 3 | 5 | 10
    val dosKeyboardLayout: String = "us",              // us | uk | br | de | it | fr | ru | es | ...
    val dosKeyboardDelay: String = "300",              // 100 | 200 | 300 | 400 | 500
    val dosKeyboardRate: String = "10",                // 5 | 10 | 15 | 20 | 30
    val dosAutoMapping: String = "on",                 // on | off
    val dosSavestate: String = "on",                   // on | 500 | 1000 | 2000 | 4000 | 8000 | 0
    val dosDimScreen: String = "off",                  // off | 5 | 10 | 20 | 30 | 60
    val dosResolution: String = "original",            // custom | 640x480 | 800x600 | 1024x768 | 1280x720 | 1600x900 | 1920x1080 | original
    val dosScale: String = "2",                        // 1 | 2 | 3 | 4 | 5
    val dosAspectRatio: String = "auto",               // auto | 4:3 | 16:9 | 16:10 | stretch
    val dosCgaColors: String = "default",              // default | amber | green | white | bright
    val dosVoodoo: String = "off",                     // off | on
    val dosForce60fps: String = "on",                  // off | on
    val dosTimeAnnounce: String = "none",              // none | boot | quiet
    // DOS on-screen controller mode: "gamepad" (circular buttons, transparent)
    // or "keyboard" (full QWERTY layout). Switchable at runtime via a button.
    val dosInputMode: String = "gamepad",              // gamepad | keyboard
    // === DOS gamepad overlay button positions (landscape) ===
    // Each button has x/y (0.0-1.0 of screen) and sizeDp.
    // dosBtnEnabled controls whether the button is shown (user can hide/add).
    val dosDpad: ButtonLayout = ButtonLayout(x = 0.13f, y = 0.78f, sizeDp = 140),
    val dosBtnEsc: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.62f, sizeDp = 56),
    val dosBtnEnter: ButtonLayout = ButtonLayout(x = 0.92f, y = 0.76f, sizeDp = 56),
    val dosBtnSpace: ButtonLayout = ButtonLayout(x = 0.78f, y = 0.82f, sizeDp = 56),
    val dosBtnTab: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.92f, sizeDp = 56),
    val dosBtnCtrl: ButtonLayout = ButtonLayout(x = 0.30f, y = 0.92f, sizeDp = 48),
    val dosBtnAlt: ButtonLayout = ButtonLayout(x = 0.42f, y = 0.92f, sizeDp = 48),
    val dosBtnShift: ButtonLayout = ButtonLayout(x = 0.54f, y = 0.92f, sizeDp = 48),
    val dosBtnBack: ButtonLayout = ButtonLayout(x = 0.66f, y = 0.92f, sizeDp = 48),
    val dosBtnMouseL: ButtonLayout = ButtonLayout(x = 0.92f, y = 0.40f, sizeDp = 40),
    val dosBtnMouseR: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.40f, sizeDp = 40),
    // === DOS gamepad overlay button positions (portrait - independent) ===
    val dosDpadP: ButtonLayout = ButtonLayout(x = 0.18f, y = 0.74f, sizeDp = 130),
    val dosBtnEscP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.58f, sizeDp = 52),
    val dosBtnEnterP: ButtonLayout = ButtonLayout(x = 0.88f, y = 0.72f, sizeDp = 52),
    val dosBtnSpaceP: ButtonLayout = ButtonLayout(x = 0.74f, y = 0.80f, sizeDp = 52),
    val dosBtnTabP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.90f, sizeDp = 52),
    val dosBtnCtrlP: ButtonLayout = ButtonLayout(x = 0.30f, y = 0.92f, sizeDp = 46),
    val dosBtnAltP: ButtonLayout = ButtonLayout(x = 0.42f, y = 0.92f, sizeDp = 46),
    val dosBtnShiftP: ButtonLayout = ButtonLayout(x = 0.54f, y = 0.92f, sizeDp = 46),
    val dosBtnBackP: ButtonLayout = ButtonLayout(x = 0.66f, y = 0.92f, sizeDp = 46),
    val dosBtnMouseLP: ButtonLayout = ButtonLayout(x = 0.92f, y = 0.36f, sizeDp = 38),
    val dosBtnMouseRP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.36f, sizeDp = 38),
    // === DOS extra buttons (addable via editor, hidden by default) ===
    val dosBtnInsert: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.40f, sizeDp = 40),
    val dosBtnDelete: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.50f, sizeDp = 40),
    val dosBtnHome: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.30f, sizeDp = 40),
    val dosBtnEnd: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.60f, sizeDp = 40),
    val dosBtnPageUp: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.20f, sizeDp = 40),
    val dosBtnPageDown: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.70f, sizeDp = 40),
    // Extra button portrait positions
    val dosBtnInsertP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.36f, sizeDp = 38),
    val dosBtnDeleteP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.46f, sizeDp = 38),
    val dosBtnHomeP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.26f, sizeDp = 38),
    val dosBtnEndP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.56f, sizeDp = 38),
    val dosBtnPageUpP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.16f, sizeDp = 38),
    val dosBtnPageDownP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.66f, sizeDp = 38),
    // === DOS button visibility toggles (which buttons are shown) ===
    val dosShowDpad: Boolean = true,
    val dosShowEsc: Boolean = true,
    val dosShowEnter: Boolean = true,
    val dosShowSpace: Boolean = true,
    val dosShowTab: Boolean = true,
    val dosShowCtrl: Boolean = true,
    val dosShowAlt: Boolean = true,
    val dosShowShift: Boolean = true,
    val dosShowBack: Boolean = true,
    val dosShowMouseL: Boolean = true,
    val dosShowMouseR: Boolean = true,
    // Extra button visibility (hidden by default — user adds them via editor)
    val dosShowInsert: Boolean = false,
    val dosShowDelete: Boolean = false,
    val dosShowHome: Boolean = false,
    val dosShowEnd: Boolean = false,
    val dosShowPageUp: Boolean = false,
    val dosShowPageDown: Boolean = false,
    // --- Dynamic extra keys (letters, numbers, symbols, F-keys, etc.) ---
    // JSON-encoded list of DosExtraKeyEntry — can be freely added/removed by the user.
    val dosExtraKeys: String = "",          // landscape extra keys (JSON)
    val dosExtraKeysP: String = "",         // portrait extra keys (JSON)
    // --- Display orientation ---
    val screenOrientation: String = "sensor",          // sensor | landscape | portrait
    // --- Performance ---
    // When true, the native surface buffer matches the source resolution
    // (256x240 / 240x160) and the Android hardware compositor does GPU
    // upscaling — fast on TV/low-end devices but slightly softer image.
    // When false, the native buffer matches the display resolution and
    // the C++ blit does per-pixel nearest-neighbor scaling — sharper but
    // much heavier on CPU (can cause lag on low-power devices).
    val highQualityScaling: Boolean = false,           // false = native-res buffer (fast), true = display-res buffer (sharp)

    // === FBNeo (Arcade) core options ===
    // Keys must match fbneo's libretro_core_options.h exactly.
    val arcadeAspect: String = "auto",                  // auto | 4:3 | 3:4 | 16:9 | 16:15
    val arcadeRotate: String = "norotate",              // norotate | cw | ccw | flip
    val arcadeVerticalMode: String = "disabled",        // disabled | enabled
    val arcadeCropOverscan: String = "enabled",         // enabled | disabled
    val arcadeCpuSpeed: String = "100",                 // 100 | 75 | 50 | 150 | 200 | 250
    val arcadeFrameskip: String = "0",                  // 0..10
    val arcadeForce60hz: String = "disabled",           // disabled | enabled
    val arcadeSampleRate: String = "48000",             // 48000 | 44100 | 22050
    val arcadeAudioInterp: String = "2",                // 0=off 1=nearest 2=linear 3=cubic
    val arcadeLowpass: String = "disabled",             // disabled | enabled
    val arcadeNeogeomode: String = "MVS",               // MVS | AES
    val arcadeMemcard: String = "enabled",              // enabled | disabled

    // === Genesis-Plus-GX (MD/SEGA) core options ===
    // Keys must match genesis_plus_gx's libretro_core_options.h exactly.
    val mdRegion: String = "auto",                      // auto | ntsc-u | pal | ntsc-j
    val mdSystem: String = "auto",                      // auto | md | sms | gg | sg
    val mdAspect: String = "auto",                      // auto | 4:3 | 16:9 | stretch
    val mdRender: String = "normal",                    // normal | double | interlaced
    val mdNtscFilter: String = "disabled",              // disabled | monochrome | rf | composite | s-video | rgb
    val mdLcdFilter: String = "disabled",               // disabled | enabled
    val mdOverscan: String = "disabled",                // disabled | enabled
    val mdGgExtra: String = "disabled",                 // disabled | enabled (GG extended screen)
    val mdLeftBorder: String = "disabled",              // disabled | enabled
    val mdInput: String = "6 button",                   // 3 button | 6 button
    val mdAllowUpDown: String = "disabled",             // disabled | enabled
    val mdOverclock: String = "100%",                   // 100% | 125% | 150% | 200%
    val mdFrameskip: String = "0",                      // 0..5
    val mdCdFastboot: String = "enabled",               // enabled | disabled
    val mdSmsFm: String = "auto",                       // auto | on | off (SMS FM sound)
    val mdGgStretch: String = "disabled",               // disabled | enabled (Game Gear stretch)

    // === Geargrafx (PCE/TG16) core options ===
    // Keys AND values must match geargrafx's libretro_core_options.h exactly.
    // Geargrafx uses case-sensitive strcmp() to compare option values, so
    // "disabled" (lowercase) will NOT match "Disabled" and the option is
    // ignored. Values below are copied from the reference source's defaults.
    val pceConsoleType: String = "Auto",                // Auto | PC Engine (JAP) | SuperGrafx (JAP) | TurboGrafx-16 (USA)
    val pceAspect: String = "4:3 DAR",                  // 1:1 PAR | 4:3 DAR | 6:5 DAR | 16:9 DAR | 16:10 DAR
    val pceOverscan: String = "Disabled",               // Disabled | Enabled
    val pceNoSpriteLimit: String = "Disabled",          // Disabled | Enabled
    val pcePalette: String = "Standard RGB",            // Standard RGB | Turboxray | Kitrinx
    val pceCdromBios: String = "Auto",                  // Auto | System Card 1 | System Card 2 | System Card 3 | Game Express
    val pceTurbotap: String = "Disabled",               // Disabled | Enabled (5-player multitap)
    val pceMb128: String = "Auto",                      // Auto | Enabled | Disabled (Memory Base 128 save)
    val pceAllowUpDown: String = "Disabled",            // Disabled | Enabled

    // === NDS (melonDS) core options ===
    // Keys must match melonDS libretro frontend's option declarations.
    val ndsConsoleMode: String = "ds",                  // ds | dsi
    val ndsScreenLayout: String = "top_bottom",          // top_bottom | bottom_top | left_right | right_left | top_only | bottom_only | turnscreen
    val ndsResolution: String = "1",                    // 1 | 2 | 3 | 4 | 5 (software renderer upscale factor)
    val ndsFiltering: String = "nearest",               // nearest | linear
    val ndsScreensaver: String = "disabled",            // disabled | enabled
    val ndsTouchMode: String = "mouse",                 // mouse | touch | disabled
    val ndsMouseSpeed: String = "100",                  // 50 | 75 | 100 | 125 | 150 | 175 | 200
    val ndsDsiSdcard: String = "disabled",              // disabled | enabled (DSi mode SD card)
    val ndsRandomizeMac: String = "disabled",           // disabled | enabled (randomize MAC for online play)

    // === PSX (PCSX-ReARMed) core options ===
    // Keys must match PCSX-ReARMed libretro_core_options.h.
    val pscxBios: String = "auto",                      // auto | HLE | scph1000 | scph1001 | scph1002 | scph5500 | scph5501 | scph5502 | psxonpsp660
    val pscxRegion: String = "auto",                    // auto | ntsc | pal
    val pscxFrameskipType: String = "disabled",        // disabled | auto | fixed
    val pscxFrameskip: String = "0",                    // 0..10 (only when frameskip_type=fixed)
    val pscxPad1Type: String = "standard",              // standard | analog | negcon | gun
    val pscxPad2Type: String = "standard",              // standard | analog | negcon | gun
    val pscxVibration: String = "enabled",              // enabled | disabled
    val pscxDithering: String = "enabled",              // enabled | disabled
    val pscxSpuInterp: String = "simple",               // simple | gaussian | cubic | off
    val pscxSpuReverb: String = "enabled",              // enabled | disabled
    val pscxShowBootlogo: String = "disabled",          // disabled | enabled (show PSX BIOS boot logo)
    val pscxCdReadahead: String = "12",                 // 0..30 (CD read-ahead in sectors)
    val pscxMemcard1: String = "libretro",              // libretro | shared | disabled
    val pscxMemcard2: String = "shared",                // libretro | shared | disabled
    // === Additional PSX/PCSX-ReARMed options ===
    val pscxDrc: String = "enabled",                 // enabled | disabled (dynarec JIT compiler)
    val pscxClock: String = "auto",                  // auto | 30..100 (CPU overclock %)
    val pscxRgb32: String = "disabled",              // disabled | enabled (32-bit color output)
    val pscxScaleHires: String = "disabled",         // disabled | enabled (downscale 480i/512i to 320x240)
    val pscxShowOverscan: String = "disabled",       // disabled | enabled (show overscan area)
    val pscxMultitap: String = "disabled",           // disabled | port1 | port2 | both
    val pscxGpuOddEven: String = "disabled",         // disabled | enabled (Peops odd/even GPU hack)
    val pscxAnalogAxis: String = "square",           // circle | square (analog stick bounds)

    // === Arcade (FBNeo) on-screen pad extras ===
    // L2/R2 button positions (bit12/bit13 in the libretro joypad word).
    // Used for 6-button fight-stick layouts and as Coin/Start shortcuts.
    val btnL2: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.32f, sizeDp = 48),
    val btnR2: ButtonLayout = ButtonLayout(x = 0.92f, y = 0.32f, sizeDp = 48),
    val btnL2P: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.28f, sizeDp = 46),
    val btnR2P: ButtonLayout = ButtonLayout(x = 0.90f, y = 0.28f, sizeDp = 46),
    // Whether to show L2/R2 buttons on the arcade overlay (hidden by default —
    // 4 face buttons + L/R is enough for most arcade games; 6-button fight
    // games like SFII/KOF benefit from L2/R2 mapped to strong punch/kick).
    val arcadeShowL2R2: Boolean = false,
    // Arcade input mode: "dpad" (digital D-pad) or "analog" (left stick →
    // D-pad bits). FBNeo uses the same bit layout for both; this toggle only
    // affects which on-screen control is drawn — "analog" draws a circular
    // analog-stick widget whose 8 directions map to the same bits as D-pad.
    val arcadeInputMode: String = "dpad",               // dpad | analog

    // === Combo buttons (per-platform) ===
    // JSON-encoded list of ComboButton entries. Each combo button is a single
    // on-screen button that, when pressed, activates multiple pad bits
    // simultaneously — e.g. "AB" (jump+attack in NES Mario), "A+B+↓" (slide
    // in some MD games), "L+R" (special move charge in SNES).
    //
    // Format: [{"id":"combo1","label":"AB","bits":3,"x":0.5,"y":0.85,"size":56,"color":-14031360}]
    // bits is the OR'd bit mask (BTN_A|BTN_B = 0x01|0x02 = 3).
    // This is per-platform: each platform tab has its own combo list.
    val comboButtons: String = "",         // NES combo list (JSON)
    val comboButtonsSfc: String = "",      // SNES combo list (JSON)
    val comboButtonsGba: String = "",      // GBA combo list (JSON)
    val comboButtonsArcade: String = "",   // Arcade combo list (JSON)
    val comboButtonsMd: String = "",       // MD combo list (JSON)
    val comboButtonsPce: String = "",      // PCE combo list (JSON)

    // === PCE button visibility toggles (which on-screen buttons are shown) ===
    // PCE uses the shared SNES/Arcade/MD layout slots (D-pad, I/II, RUN,
    // SELECT, V/VI, IV/III, Turbo I/II). Each can be individually shown or
    // hidden from the layout editor so the user can declutter the overlay.
    // These flags are global (shared between landscape and portrait).
    val pceShowDpad: Boolean = true,
    val pceShowA: Boolean = true,          // PCE "I"
    val pceShowB: Boolean = true,          // PCE "II"
    val pceShowStart: Boolean = true,      // PCE "RUN"
    val pceShowSelect: Boolean = true,
    val pceShowL: Boolean = true,          // PCE "V"
    val pceShowR: Boolean = true,          // PCE "VI"
    val pceShowX: Boolean = true,          // PCE "IV"
    val pceShowY: Boolean = true,          // PCE "III"
    val pceShowL2: Boolean = true,         // PCE "TURBO II"
    val pceShowR2: Boolean = true,         // PCE "TURBO I"

    // === Per-platform hidden button lists ===
    // Comma-separated button key names that the user has hidden via the
    // "显隐按键" dialog in the pad layout editor. Each platform has its own
    // list so hiding a button in NES doesn't affect SNES, etc.
    // Valid keys: dpad, a, b, ta, tb, start, select, l, r, x, y, l2, r2
    // (ta/tb = turbo A/B; only shown on NES/GB when X/Y hidden).
    val hiddenButtons: String = "",         // NES/GB hidden button keys
    val hiddenButtonsSfc: String = "",     // SNES hidden button keys
    val hiddenButtonsGba: String = "",     // GBA hidden button keys
    val hiddenButtonsArcade: String = "",  // Arcade/FBNeo hidden button keys
    val hiddenButtonsMd: String = "",      // MD/SEGA hidden button keys
    val hiddenButtonsPce: String = "",     // PCE hidden button keys
    val hiddenButtonsNds: String = "",     // NDS hidden button keys
    val hiddenButtonsPsx: String = "",     // PSX hidden button keys

    // === Input mode (joystick vs D-pad) ===
    // "dpad" = cross-shaped digital D-pad (default); "analog" = circular
    // analog stick. Applies to ALL platforms, not just Arcade. The rendering
    // difference is purely visual — both produce the same UP/DOWN/LEFT/RIGHT
    // bits. This implements the user's request for joystick/d-pad switching
    // across all engines.
    val inputMode: String = "dpad"         // dpad | analog
)

/**
 * Persistent on-screen controller layout + core option settings.
 * Stores per-button positions (0.0–1.0), sizes (dp), and global options.
 *
 * 横屏 / 竖屏的按键位置用不同的 key 前缀持久化：
 *   - 横屏：`dpad_x` / `a_x` / `b_x` / ... (旧 key，兼容旧版本)
 *   - 竖屏：`p_dpad_x` / `p_a_x` / `p_b_x` / ... (新 key)
 * 全局设置（opacity / showPad / 核心选项 / 滤镜等）不分方向共享。
 */
object PadLayoutStore {
    private const val PREFS_NAME = "pad_layout_v2"

    // === 横屏 Button keys (旧 key 兼容旧版本) ===
    private const val KEY_DPAD_X = "dpad_x"
    private const val KEY_DPAD_Y = "dpad_y"
    private const val KEY_DPAD_SIZE = "dpad_size"
    private const val KEY_A_X = "a_x"
    private const val KEY_A_Y = "a_y"
    private const val KEY_A_SIZE = "a_size"
    private const val KEY_B_X = "b_x"
    private const val KEY_B_Y = "b_y"
    private const val KEY_B_SIZE = "b_size"
    private const val KEY_TA_X = "ta_x"
    private const val KEY_TA_Y = "ta_y"
    private const val KEY_TA_SIZE = "ta_size"
    private const val KEY_TB_X = "tb_x"
    private const val KEY_TB_Y = "tb_y"
    private const val KEY_TB_SIZE = "tb_size"
    private const val KEY_START_X = "start_x"
    private const val KEY_START_Y = "start_y"
    private const val KEY_START_SIZE = "start_size"
    private const val KEY_SELECT_X = "select_x"
    private const val KEY_SELECT_Y = "select_y"
    private const val KEY_SELECT_SIZE = "select_size"

    // L/R shoulder button keys
    private const val KEY_L_X = "l_x"
    private const val KEY_L_Y = "l_y"
    private const val KEY_L_SIZE = "l_size"
    private const val KEY_R_X = "r_x"
    private const val KEY_R_Y = "r_y"
    private const val KEY_R_SIZE = "r_size"

    // X/Y face button keys (SNES)
    private const val KEY_X_X = "x_x"
    private const val KEY_X_Y = "x_y"
    private const val KEY_X_SIZE = "x_size"
    private const val KEY_Y_X = "y_x"
    private const val KEY_Y_Y = "y_y"
    private const val KEY_Y_SIZE = "y_size"

    // === 竖屏 Button keys (新 key，p_ 前缀) ===
    private const val KEY_PDAD_X = "p_dpad_x"
    private const val KEY_PDAD_Y = "p_dpad_y"
    private const val KEY_PDAD_SIZE = "p_dpad_size"
    private const val KEY_PA_X = "p_a_x"
    private const val KEY_PA_Y = "p_a_y"
    private const val KEY_PA_SIZE = "p_a_size"
    private const val KEY_PB_X = "p_b_x"
    private const val KEY_PB_Y = "p_b_y"
    private const val KEY_PB_SIZE = "p_b_size"
    private const val KEY_PTA_X = "p_ta_x"
    private const val KEY_PTA_Y = "p_ta_y"
    private const val KEY_PTA_SIZE = "p_ta_size"
    private const val KEY_PTB_X = "p_tb_x"
    private const val KEY_PTB_Y = "p_tb_y"
    private const val KEY_PTB_SIZE = "p_tb_size"
    private const val KEY_PSTART_X = "p_start_x"
    private const val KEY_PSTART_Y = "p_start_y"
    private const val KEY_PSTART_SIZE = "p_start_size"
    private const val KEY_PSELECT_X = "p_select_x"
    private const val KEY_PSELECT_Y = "p_select_y"
    private const val KEY_PSELECT_SIZE = "p_select_size"
    private const val KEY_PL_X = "p_l_x"
    private const val KEY_PL_Y = "p_l_y"
    private const val KEY_PL_SIZE = "p_l_size"
    private const val KEY_PR_X = "p_r_x"
    private const val KEY_PR_Y = "p_r_y"
    private const val KEY_PR_SIZE = "p_r_size"
    private const val KEY_PX_X = "p_x_x"
    private const val KEY_PX_Y = "p_x_y"
    private const val KEY_PX_SIZE = "p_x_size"
    private const val KEY_PY_X = "p_y_x"
    private const val KEY_PY_Y = "p_y_y"
    private const val KEY_PY_SIZE = "p_y_size"

    // Global keys
    private const val KEY_OPACITY = "opacity"
    private const val KEY_SHOW_PAD = "show_pad"
    private const val KEY_HIGH_QUALITY_SCALING = "high_quality_scaling"

    // Core option keys
    private const val KEY_NTSC_FILTER = "ntsc_filter"
    private const val KEY_ASPECT_RATIO = "aspect_ratio"
    private const val KEY_PALETTE = "palette"
    private const val KEY_REGION = "region"
    private const val KEY_SOUND_QUALITY = "sound_quality"
    private const val KEY_CROP_OVERSCAN = "crop_overscan"
    private const val KEY_VIDEO_SCALE = "video_scale"
    private const val KEY_VIDEO_FILTER = "video_filter"
    private const val KEY_OVERCLOCKING = "overclocking"
    // Custom free-form layout rect keys (used when videoScale == "custom")
    private const val KEY_CUSTOM_LAYOUT_LEFT = "custom_layout_left"
    private const val KEY_CUSTOM_LAYOUT_TOP = "custom_layout_top"
    private const val KEY_CUSTOM_LAYOUT_RIGHT = "custom_layout_right"
    private const val KEY_CUSTOM_LAYOUT_BOTTOM = "custom_layout_bottom"
    // 竖屏版自由布局 rect keys（横竖屏分别保存）
    private const val KEY_CUSTOM_LAYOUT_LEFT_P = "custom_layout_left_p"
    private const val KEY_CUSTOM_LAYOUT_TOP_P = "custom_layout_top_p"
    private const val KEY_CUSTOM_LAYOUT_RIGHT_P = "custom_layout_right_p"
    private const val KEY_CUSTOM_LAYOUT_BOTTOM_P = "custom_layout_bottom_p"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Helper: load a ButtonLayout from SharedPreferences with a default fallback.
    // Uses key prefix "<prefix>_x" / "<prefix>_y" / "<prefix>_size".
    private fun loadBtn(p: SharedPreferences, prefix: String, default: ButtonLayout): ButtonLayout {
        return ButtonLayout(
            x = p.getFloat("${prefix}_x", default.x),
            y = p.getFloat("${prefix}_y", default.y),
            sizeDp = p.getInt("${prefix}_size", default.sizeDp)
        )
    }

    // Helper: save a ButtonLayout to SharedPreferences with a key prefix.
    private fun SharedPreferences.Editor.saveBtn(prefix: String, layout: ButtonLayout) {
        putFloat("${prefix}_x", layout.x)
        putFloat("${prefix}_y", layout.y)
        putInt("${prefix}_size", layout.sizeDp)
    }

    fun load(ctx: Context): PadLayout {
        val p = prefs(ctx)
        return PadLayout(
            // === 横屏布局 ===
            dpad = ButtonLayout(
                p.getFloat(KEY_DPAD_X, 0.13f),
                p.getFloat(KEY_DPAD_Y, 0.78f),
                p.getInt(KEY_DPAD_SIZE, 140)
            ),
            btnA = ButtonLayout(
                p.getFloat(KEY_A_X, 0.87f),
                p.getFloat(KEY_A_Y, 0.76f),
                p.getInt(KEY_A_SIZE, 72)
            ),
            btnB = ButtonLayout(
                p.getFloat(KEY_B_X, 0.72f),
                p.getFloat(KEY_B_Y, 0.82f),
                p.getInt(KEY_B_SIZE, 72)
            ),
            btnTurboA = ButtonLayout(
                p.getFloat(KEY_TA_X, 0.87f),
                p.getFloat(KEY_TA_Y, 0.60f),
                p.getInt(KEY_TA_SIZE, 48)
            ),
            btnTurboB = ButtonLayout(
                p.getFloat(KEY_TB_X, 0.72f),
                p.getFloat(KEY_TB_Y, 0.66f),
                p.getInt(KEY_TB_SIZE, 48)
            ),
            btnStart = ButtonLayout(
                p.getFloat(KEY_START_X, 0.62f),
                p.getFloat(KEY_START_Y, 0.92f),
                p.getInt(KEY_START_SIZE, 56)
            ),
            btnSelect = ButtonLayout(
                p.getFloat(KEY_SELECT_X, 0.38f),
                p.getFloat(KEY_SELECT_Y, 0.92f),
                p.getInt(KEY_SELECT_SIZE, 56)
            ),
            btnL = ButtonLayout(
                p.getFloat(KEY_L_X, 0.10f),
                p.getFloat(KEY_L_Y, 0.15f),
                p.getInt(KEY_L_SIZE, 56)
            ),
            btnR = ButtonLayout(
                p.getFloat(KEY_R_X, 0.90f),
                p.getFloat(KEY_R_Y, 0.15f),
                p.getInt(KEY_R_SIZE, 56)
            ),
            btnX = ButtonLayout(
                p.getFloat(KEY_X_X, 0.88f),
                p.getFloat(KEY_X_Y, 0.54f),
                p.getInt(KEY_X_SIZE, 60)
            ),
            btnY = ButtonLayout(
                p.getFloat(KEY_Y_X, 0.73f),
                p.getFloat(KEY_Y_Y, 0.60f),
                p.getInt(KEY_Y_SIZE, 60)
            ),
            // === 竖屏布局（独立持久化，跟横屏互不干扰） ===
            dpadP = ButtonLayout(
                p.getFloat(KEY_PDAD_X, 0.18f),
                p.getFloat(KEY_PDAD_Y, 0.74f),
                p.getInt(KEY_PDAD_SIZE, 130)
            ),
            btnAP = ButtonLayout(
                p.getFloat(KEY_PA_X, 0.82f),
                p.getFloat(KEY_PA_Y, 0.72f),
                p.getInt(KEY_PA_SIZE, 68)
            ),
            btnBP = ButtonLayout(
                p.getFloat(KEY_PB_X, 0.68f),
                p.getFloat(KEY_PB_Y, 0.80f),
                p.getInt(KEY_PB_SIZE, 68)
            ),
            btnTurboAP = ButtonLayout(
                p.getFloat(KEY_PTA_X, 0.82f),
                p.getFloat(KEY_PTA_Y, 0.56f),
                p.getInt(KEY_PTA_SIZE, 46)
            ),
            btnTurboBP = ButtonLayout(
                p.getFloat(KEY_PTB_X, 0.68f),
                p.getFloat(KEY_PTB_Y, 0.62f),
                p.getInt(KEY_PTB_SIZE, 46)
            ),
            btnStartP = ButtonLayout(
                p.getFloat(KEY_PSTART_X, 0.62f),
                p.getFloat(KEY_PSTART_Y, 0.90f),
                p.getInt(KEY_PSTART_SIZE, 54)
            ),
            btnSelectP = ButtonLayout(
                p.getFloat(KEY_PSELECT_X, 0.38f),
                p.getFloat(KEY_PSELECT_Y, 0.90f),
                p.getInt(KEY_PSELECT_SIZE, 54)
            ),
            btnLP = ButtonLayout(
                p.getFloat(KEY_PL_X, 0.12f),
                p.getFloat(KEY_PL_Y, 0.12f),
                p.getInt(KEY_PL_SIZE, 54)
            ),
            btnRP = ButtonLayout(
                p.getFloat(KEY_PR_X, 0.88f),
                p.getFloat(KEY_PR_Y, 0.12f),
                p.getInt(KEY_PR_SIZE, 54)
            ),
            btnXP = ButtonLayout(
                p.getFloat(KEY_PX_X, 0.83f),
                p.getFloat(KEY_PX_Y, 0.50f),
                p.getInt(KEY_PX_SIZE, 56)
            ),
            btnYP = ButtonLayout(
                p.getFloat(KEY_PY_X, 0.69f),
                p.getFloat(KEY_PY_Y, 0.56f),
                p.getInt(KEY_PY_SIZE, 56)
            ),
            // === 全局设置 ===
            opacity = p.getFloat(KEY_OPACITY, 0.7f),
            showPad = p.getBoolean(KEY_SHOW_PAD, true),
            highQualityScaling = p.getBoolean(KEY_HIGH_QUALITY_SCALING, false),
            ntscFilter = p.getString(KEY_NTSC_FILTER, "disabled") ?: "disabled",
            aspectRatio = p.getString(KEY_ASPECT_RATIO, "4:3") ?: "4:3",
            palette = p.getString(KEY_PALETTE, "default") ?: "default",
            region = p.getString(KEY_REGION, "Auto") ?: "Auto",
            soundQuality = p.getString(KEY_SOUND_QUALITY, "Low") ?: "Low",
            cropOverscan = p.getString(KEY_CROP_OVERSCAN, "disabled") ?: "disabled",
            videoScale = p.getString(KEY_VIDEO_SCALE, "stretch") ?: "stretch",
            customLayoutLeft = p.getFloat(KEY_CUSTOM_LAYOUT_LEFT, 0f),
            customLayoutTop = p.getFloat(KEY_CUSTOM_LAYOUT_TOP, 0f),
            customLayoutRight = p.getFloat(KEY_CUSTOM_LAYOUT_RIGHT, 1f),
            customLayoutBottom = p.getFloat(KEY_CUSTOM_LAYOUT_BOTTOM, 1f),
            customLayoutLeftP = p.getFloat(KEY_CUSTOM_LAYOUT_LEFT_P, 0f),
            customLayoutTopP = p.getFloat(KEY_CUSTOM_LAYOUT_TOP_P, 0f),
            customLayoutRightP = p.getFloat(KEY_CUSTOM_LAYOUT_RIGHT_P, 1f),
            customLayoutBottomP = p.getFloat(KEY_CUSTOM_LAYOUT_BOTTOM_P, 1f),
            videoFilter = p.getString(KEY_VIDEO_FILTER, "none") ?: "none",
            overclocking = p.getString(KEY_OVERCLOCKING, "disabled") ?: "disabled",
            sfcReduceSpriteFlicker = p.getString("sfc_reduce_sprite_flicker", "disabled") ?: "disabled",
            sfcReduceSlowdown = p.getString("sfc_reduce_slowdown", "disabled") ?: "disabled",
            sfcAudioInterpolation = p.getString("sfc_audio_interpolation", "gaussian") ?: "gaussian",
            sfcGfxTransparency = p.getString("sfc_gfx_transparency", "enabled") ?: "enabled",
            sfcGfxHires = p.getString("sfc_gfx_hires", "enabled") ?: "enabled",
            sfcGfxClip = p.getString("sfc_gfx_clip", "enabled") ?: "enabled",
            sfcBlockInvalidVram = p.getString("sfc_block_invalid_vram", "disabled") ?: "disabled",
            sfcSoundOutput = p.getString("sfc_sound_output", "disabled") ?: "disabled",
            sfcOverscan = p.getString("sfc_overscan", "enabled") ?: "enabled",
            sfcSideBySide = p.getString("sfc_side_by_side", "disabled") ?: "disabled",
            sfcUpDownAllowed = p.getString("sfc_up_down_allowed", "disabled") ?: "disabled",
            sfcSuperScope = p.getString("sfc_superscope", "disabled") ?: "disabled",
            sfcLayer1 = p.getString("sfc_layer_1", "enabled") ?: "enabled",
            sfcLayer2 = p.getString("sfc_layer_2", "enabled") ?: "enabled",
            sfcLayer3 = p.getString("sfc_layer_3", "enabled") ?: "enabled",
            sfcLayer4 = p.getString("sfc_layer_4", "enabled") ?: "enabled",
            sfcLayer5 = p.getString("sfc_layer_5", "enabled") ?: "enabled",
            sfcOverclock = p.getString("sfc_overclock", "100%") ?: "100%",
            gbColorCorrection = p.getString("gb_color_correction", "enabled") ?: "enabled",
            gbcColorPreset = p.getString("gbc_color_preset", "default") ?: "default",
            gbaColorCorrection = p.getString("gba_color_correction", "enabled") ?: "enabled",
            gbaColorPreset = p.getString("gba_color_preset", "default") ?: "default",
            gbaFrameBlending = p.getString("gba_frame_blending", "OFF") ?: "OFF",
            gbaAudioResampler = p.getString("gba_audio_resampler", "sinc") ?: "sinc",
            gbaAudioLowPass = p.getString("gba_audio_low_pass", "enabled") ?: "enabled",
            gbaAudioLowPassRange = p.getString("gba_audio_low_pass_range", "50") ?: "50",
            gbaFrameskipType = p.getString("gba_frameskip_type", "disabled") ?: "disabled",
            gbaFrameskipCount = p.getString("gba_frameskip_count", "0") ?: "0",
            gbaSolarSensor = p.getString("gba_solar_sensor", "0") ?: "0",
            gbaIdleOptimization = p.getString("gba_idle_optimization", "disabled") ?: "disabled",
            gbaForceRTC = p.getString("gba_force_rtc", "disabled") ?: "disabled",
            gbaAllowOpposite = p.getString("gba_allow_opposite", "OFF") ?: "OFF",
            gbModel = p.getString("gb_model", "Autodetect") ?: "Autodetect",
            gbSgbBorders = p.getString("gb_sgb_borders", "ON") ?: "ON",
            gbaFrameskipThreshold = p.getString("gba_frameskip_threshold", "33") ?: "33",
            // DOSBox-Pure options
            dosMachine = p.getString("dos_machine", "svga_s3") ?: "svga_s3",
            dosCycles = p.getString("dos_cycles", "auto") ?: "auto",
            dosCyclesMax = p.getString("dos_cycles_max", "50000") ?: "50000",
            dosSbType = p.getString("dos_sb_type", "sb16") ?: "sb16",
            dosSbAdlibMode = p.getString("dos_sb_adlib_mode", "off") ?: "off",
            dosSbAdlibEmu = p.getString("dos_sb_adlib_emu", "default") ?: "default",
            dosGus = p.getString("dos_gus", "off") ?: "off",
            // Migrate the old invalid dosbox_pure_mouse_input values
            // ("emulated"/"absolute"/"ps2"/"none") to the valid "touchpad".
            dosMouseInput = run {
                val v = p.getString("dos_mouse_input", "touchpad") ?: "touchpad"
                if (v in setOf("emulated", "absolute", "ps2", "none")) "touchpad" else v
            },
            dosMouseTimeout = p.getString("dos_mouse_timeout", "off") ?: "off",
            dosKeyboardLayout = p.getString("dos_keyboard_layout", "us") ?: "us",
            dosKeyboardDelay = p.getString("dos_keyboard_delay", "300") ?: "300",
            dosKeyboardRate = p.getString("dos_keyboard_rate", "10") ?: "10",
            dosAutoMapping = p.getString("dos_auto_mapping", "on") ?: "on",
            dosSavestate = p.getString("dos_savestate", "on") ?: "on",
            dosDimScreen = p.getString("dos_dim_screen", "off") ?: "off",
            dosResolution = p.getString("dos_resolution", "original") ?: "original",
            dosScale = p.getString("dos_scale", "2") ?: "2",
            dosAspectRatio = p.getString("dos_aspect_ratio", "auto") ?: "auto",
            dosCgaColors = p.getString("dos_cga_colors", "default") ?: "default",
            dosVoodoo = p.getString("dos_voodoo", "off") ?: "off",
            dosForce60fps = p.getString("dos_force60fps", "on") ?: "on",
            dosTimeAnnounce = p.getString("dos_time_announce", "none") ?: "none",
            dosInputMode = p.getString("dos_input_mode", "gamepad") ?: "gamepad",
            // DOS gamepad overlay button positions (landscape)
            dosDpad = loadBtn(p, "dos_dpad", ButtonLayout(x = 0.13f, y = 0.78f, sizeDp = 140)),
            dosBtnEsc = loadBtn(p, "dos_btn_esc", ButtonLayout(x = 0.87f, y = 0.62f, sizeDp = 56)),
            dosBtnEnter = loadBtn(p, "dos_btn_enter", ButtonLayout(x = 0.92f, y = 0.76f, sizeDp = 56)),
            dosBtnSpace = loadBtn(p, "dos_btn_space", ButtonLayout(x = 0.78f, y = 0.82f, sizeDp = 56)),
            dosBtnTab = loadBtn(p, "dos_btn_tab", ButtonLayout(x = 0.87f, y = 0.92f, sizeDp = 56)),
            dosBtnCtrl = loadBtn(p, "dos_btn_ctrl", ButtonLayout(x = 0.30f, y = 0.92f, sizeDp = 48)),
            dosBtnAlt = loadBtn(p, "dos_btn_alt", ButtonLayout(x = 0.42f, y = 0.92f, sizeDp = 48)),
            dosBtnShift = loadBtn(p, "dos_btn_shift", ButtonLayout(x = 0.54f, y = 0.92f, sizeDp = 48)),
            dosBtnBack = loadBtn(p, "dos_btn_back", ButtonLayout(x = 0.66f, y = 0.92f, sizeDp = 48)),
            dosBtnMouseL = loadBtn(p, "dos_btn_mouse_l", ButtonLayout(x = 0.92f, y = 0.40f, sizeDp = 40)),
            dosBtnMouseR = loadBtn(p, "dos_btn_mouse_r", ButtonLayout(x = 0.82f, y = 0.40f, sizeDp = 40)),
            // DOS gamepad overlay button positions (portrait)
            dosDpadP = loadBtn(p, "dos_dpad_p", ButtonLayout(x = 0.18f, y = 0.74f, sizeDp = 130)),
            dosBtnEscP = loadBtn(p, "dos_btn_esc_p", ButtonLayout(x = 0.82f, y = 0.58f, sizeDp = 52)),
            dosBtnEnterP = loadBtn(p, "dos_btn_enter_p", ButtonLayout(x = 0.88f, y = 0.72f, sizeDp = 52)),
            dosBtnSpaceP = loadBtn(p, "dos_btn_space_p", ButtonLayout(x = 0.74f, y = 0.80f, sizeDp = 52)),
            dosBtnTabP = loadBtn(p, "dos_btn_tab_p", ButtonLayout(x = 0.82f, y = 0.90f, sizeDp = 52)),
            dosBtnCtrlP = loadBtn(p, "dos_btn_ctrl_p", ButtonLayout(x = 0.30f, y = 0.92f, sizeDp = 46)),
            dosBtnAltP = loadBtn(p, "dos_btn_alt_p", ButtonLayout(x = 0.42f, y = 0.92f, sizeDp = 46)),
            dosBtnShiftP = loadBtn(p, "dos_btn_shift_p", ButtonLayout(x = 0.54f, y = 0.92f, sizeDp = 46)),
            dosBtnBackP = loadBtn(p, "dos_btn_back_p", ButtonLayout(x = 0.66f, y = 0.92f, sizeDp = 46)),
            dosBtnMouseLP = loadBtn(p, "dos_btn_mouse_l_p", ButtonLayout(x = 0.92f, y = 0.36f, sizeDp = 38)),
            dosBtnMouseRP = loadBtn(p, "dos_btn_mouse_r_p", ButtonLayout(x = 0.82f, y = 0.36f, sizeDp = 38)),
            // DOS extra buttons (landscape)
            dosBtnInsert = loadBtn(p, "dos_btn_insert", ButtonLayout(x = 0.08f, y = 0.40f, sizeDp = 40)),
            dosBtnDelete = loadBtn(p, "dos_btn_delete", ButtonLayout(x = 0.08f, y = 0.50f, sizeDp = 40)),
            dosBtnHome = loadBtn(p, "dos_btn_home", ButtonLayout(x = 0.08f, y = 0.30f, sizeDp = 40)),
            dosBtnEnd = loadBtn(p, "dos_btn_end", ButtonLayout(x = 0.08f, y = 0.60f, sizeDp = 40)),
            dosBtnPageUp = loadBtn(p, "dos_btn_pageup", ButtonLayout(x = 0.08f, y = 0.20f, sizeDp = 40)),
            dosBtnPageDown = loadBtn(p, "dos_btn_pagedown", ButtonLayout(x = 0.08f, y = 0.70f, sizeDp = 40)),
            // DOS extra buttons (portrait)
            dosBtnInsertP = loadBtn(p, "dos_btn_insert_p", ButtonLayout(x = 0.10f, y = 0.36f, sizeDp = 38)),
            dosBtnDeleteP = loadBtn(p, "dos_btn_delete_p", ButtonLayout(x = 0.10f, y = 0.46f, sizeDp = 38)),
            dosBtnHomeP = loadBtn(p, "dos_btn_home_p", ButtonLayout(x = 0.10f, y = 0.26f, sizeDp = 38)),
            dosBtnEndP = loadBtn(p, "dos_btn_end_p", ButtonLayout(x = 0.10f, y = 0.56f, sizeDp = 38)),
            dosBtnPageUpP = loadBtn(p, "dos_btn_pageup_p", ButtonLayout(x = 0.10f, y = 0.16f, sizeDp = 38)),
            dosBtnPageDownP = loadBtn(p, "dos_btn_pagedown_p", ButtonLayout(x = 0.10f, y = 0.66f, sizeDp = 38)),
            // DOS button visibility toggles
            dosShowDpad = p.getBoolean("dos_show_dpad", true),
            dosShowEsc = p.getBoolean("dos_show_esc", true),
            dosShowEnter = p.getBoolean("dos_show_enter", true),
            dosShowSpace = p.getBoolean("dos_show_space", true),
            dosShowTab = p.getBoolean("dos_show_tab", true),
            dosShowCtrl = p.getBoolean("dos_show_ctrl", true),
            dosShowAlt = p.getBoolean("dos_show_alt", true),
            dosShowShift = p.getBoolean("dos_show_shift", true),
            dosShowBack = p.getBoolean("dos_show_back", true),
            dosShowMouseL = p.getBoolean("dos_show_mouse_l", true),
            dosShowMouseR = p.getBoolean("dos_show_mouse_r", true),
            dosShowInsert = p.getBoolean("dos_show_insert", false),
            dosShowDelete = p.getBoolean("dos_show_delete", false),
            dosShowHome = p.getBoolean("dos_show_home", false),
            dosShowEnd = p.getBoolean("dos_show_end", false),
            dosShowPageUp = p.getBoolean("dos_show_pageup", false),
            dosShowPageDown = p.getBoolean("dos_show_pagedown", false),
            dosExtraKeys = p.getString("dos_extra_keys", "") ?: "",
            dosExtraKeysP = p.getString("dos_extra_keys_p", "") ?: "",
            screenOrientation = p.getString("screen_orientation", "sensor") ?: "sensor",
            // FBNeo (Arcade) options
            arcadeAspect = p.getString("arcade_aspect", "auto") ?: "auto",
            arcadeRotate = p.getString("arcade_rotate", "norotate") ?: "norotate",
            arcadeVerticalMode = p.getString("arcade_vertical_mode", "disabled") ?: "disabled",
            arcadeCropOverscan = p.getString("arcade_crop_overscan", "enabled") ?: "enabled",
            arcadeCpuSpeed = p.getString("arcade_cpu_speed", "100") ?: "100",
            arcadeFrameskip = p.getString("arcade_frameskip", "0") ?: "0",
            arcadeForce60hz = p.getString("arcade_force_60hz", "disabled") ?: "disabled",
            arcadeSampleRate = p.getString("arcade_sample_rate", "48000") ?: "48000",
            arcadeAudioInterp = p.getString("arcade_audio_interp", "2") ?: "2",
            arcadeLowpass = p.getString("arcade_lowpass", "disabled") ?: "disabled",
            arcadeNeogeomode = p.getString("arcade_neogeo_mode", "MVS") ?: "MVS",
            arcadeMemcard = p.getString("arcade_memcard", "enabled") ?: "enabled",
            // Genesis-Plus-GX (MD/SEGA) options
            mdRegion = p.getString("md_region", "auto") ?: "auto",
            mdSystem = p.getString("md_system", "auto") ?: "auto",
            mdAspect = p.getString("md_aspect", "auto") ?: "auto",
            mdRender = p.getString("md_render", "normal") ?: "normal",
            mdNtscFilter = p.getString("md_ntsc_filter", "disabled") ?: "disabled",
            mdLcdFilter = p.getString("md_lcd_filter", "disabled") ?: "disabled",
            mdOverscan = p.getString("md_overscan", "disabled") ?: "disabled",
            mdGgExtra = p.getString("md_gg_extra", "disabled") ?: "disabled",
            mdLeftBorder = p.getString("md_left_border", "disabled") ?: "disabled",
            mdInput = p.getString("md_input", "6 button") ?: "6 button",
            mdAllowUpDown = p.getString("md_allow_up_down", "disabled") ?: "disabled",
            mdOverclock = p.getString("md_overclock", "100%") ?: "100%",
            mdFrameskip = p.getString("md_frameskip", "0") ?: "0",
            mdCdFastboot = p.getString("md_cd_fastboot", "enabled") ?: "enabled",
            mdSmsFm = p.getString("md_sms_fm", "auto") ?: "auto",
            mdGgStretch = p.getString("md_gg_stretch", "disabled") ?: "disabled",
            // PSX (PCSX-ReARMed) options
            pscxBios = p.getString("psx_bios", "auto") ?: "auto",
            pscxRegion = p.getString("psx_region", "auto") ?: "auto",
            pscxFrameskipType = p.getString("psx_frameskip_type", "disabled") ?: "disabled",
            pscxFrameskip = p.getString("psx_frameskip", "0") ?: "0",
            pscxPad1Type = p.getString("psx_pad1_type", "standard") ?: "standard",
            pscxPad2Type = p.getString("psx_pad2_type", "standard") ?: "standard",
            pscxVibration = p.getString("psx_vibration", "enabled") ?: "enabled",
            pscxDithering = p.getString("psx_dithering", "enabled") ?: "enabled",
            pscxSpuInterp = p.getString("psx_spu_interp", "simple") ?: "simple",
            pscxSpuReverb = p.getString("psx_spu_reverb", "enabled") ?: "enabled",
            pscxShowBootlogo = p.getString("psx_show_bootlogo", "disabled") ?: "disabled",
            pscxCdReadahead = p.getString("psx_cd_readahead", "12") ?: "12",
            pscxMemcard1 = p.getString("psx_memcard1", "libretro") ?: "libretro",
            pscxMemcard2 = p.getString("psx_memcard2", "shared") ?: "shared",
            pscxDrc = p.getString("psx_drc", "enabled") ?: "enabled",
            pscxClock = p.getString("psx_clock", "auto") ?: "auto",
            pscxRgb32 = p.getString("psx_rgb32", "disabled") ?: "disabled",
            pscxScaleHires = p.getString("psx_scale_hires", "disabled") ?: "disabled",
            pscxShowOverscan = p.getString("psx_show_overscan", "disabled") ?: "disabled",
            pscxMultitap = p.getString("psx_multitap", "disabled") ?: "disabled",
            pscxGpuOddEven = p.getString("psx_gpu_odd_even", "disabled") ?: "disabled",
            pscxAnalogAxis = p.getString("psx_analog_axis", "square") ?: "square",
            // === Arcade extras ===
            btnL2 = loadBtn(p, "btn_l2", ButtonLayout(x = 0.08f, y = 0.32f, sizeDp = 48)),
            btnR2 = loadBtn(p, "btn_r2", ButtonLayout(x = 0.92f, y = 0.32f, sizeDp = 48)),
            btnL2P = loadBtn(p, "p_btn_l2", ButtonLayout(x = 0.10f, y = 0.28f, sizeDp = 46)),
            btnR2P = loadBtn(p, "p_btn_r2", ButtonLayout(x = 0.90f, y = 0.28f, sizeDp = 46)),
            arcadeShowL2R2 = p.getBoolean("arcade_show_l2r2", false),
            arcadeInputMode = p.getString("arcade_input_mode", "dpad") ?: "dpad",
            // === Combo buttons (per-platform JSON) ===
            comboButtons = p.getString("combo_buttons", "") ?: "",
            comboButtonsSfc = p.getString("combo_buttons_sfc", "") ?: "",
            comboButtonsGba = p.getString("combo_buttons_gba", "") ?: "",
            comboButtonsArcade = p.getString("combo_buttons_arcade", "") ?: "",
            comboButtonsMd = p.getString("combo_buttons_md", "") ?: "",
            comboButtonsPce = p.getString("combo_buttons_pce", "") ?: "",
            // PCE button visibility toggles
            pceShowDpad = p.getBoolean("pce_show_dpad", true),
            pceShowA = p.getBoolean("pce_show_a", true),
            pceShowB = p.getBoolean("pce_show_b", true),
            pceShowStart = p.getBoolean("pce_show_start", true),
            pceShowSelect = p.getBoolean("pce_show_select", true),
            pceShowL = p.getBoolean("pce_show_l", true),
            pceShowR = p.getBoolean("pce_show_r", true),
            pceShowX = p.getBoolean("pce_show_x", true),
            pceShowY = p.getBoolean("pce_show_y", true),
            pceShowL2 = p.getBoolean("pce_show_l2", true),
            pceShowR2 = p.getBoolean("pce_show_r2", true),
            // === Per-platform hidden button lists ===
            hiddenButtons = p.getString("hidden_buttons", "") ?: "",
            hiddenButtonsSfc = p.getString("hidden_buttons_sfc", "") ?: "",
            hiddenButtonsGba = p.getString("hidden_buttons_gba", "") ?: "",
            hiddenButtonsArcade = p.getString("hidden_buttons_arcade", "") ?: "",
            hiddenButtonsMd = p.getString("hidden_buttons_md", "") ?: "",
            hiddenButtonsPce = p.getString("hidden_buttons_pce", "") ?: "",
            hiddenButtonsNds = p.getString("hidden_buttons_nds", "") ?: "",
            hiddenButtonsPsx = p.getString("hidden_buttons_psx", "") ?: "",
            // === Input mode ===
            inputMode = p.getString("input_mode", "dpad") ?: "dpad"
        )
    }

    fun save(ctx: Context, layout: PadLayout) {
        prefs(ctx).edit().apply {
            // === 横屏布局 ===
            putFloat(KEY_DPAD_X, layout.dpad.x)
            putFloat(KEY_DPAD_Y, layout.dpad.y)
            putInt(KEY_DPAD_SIZE, layout.dpad.sizeDp)

            putFloat(KEY_A_X, layout.btnA.x)
            putFloat(KEY_A_Y, layout.btnA.y)
            putInt(KEY_A_SIZE, layout.btnA.sizeDp)

            putFloat(KEY_B_X, layout.btnB.x)
            putFloat(KEY_B_Y, layout.btnB.y)
            putInt(KEY_B_SIZE, layout.btnB.sizeDp)

            putFloat(KEY_TA_X, layout.btnTurboA.x)
            putFloat(KEY_TA_Y, layout.btnTurboA.y)
            putInt(KEY_TA_SIZE, layout.btnTurboA.sizeDp)

            putFloat(KEY_TB_X, layout.btnTurboB.x)
            putFloat(KEY_TB_Y, layout.btnTurboB.y)
            putInt(KEY_TB_SIZE, layout.btnTurboB.sizeDp)

            putFloat(KEY_START_X, layout.btnStart.x)
            putFloat(KEY_START_Y, layout.btnStart.y)
            putInt(KEY_START_SIZE, layout.btnStart.sizeDp)

            putFloat(KEY_SELECT_X, layout.btnSelect.x)
            putFloat(KEY_SELECT_Y, layout.btnSelect.y)
            putInt(KEY_SELECT_SIZE, layout.btnSelect.sizeDp)

            putFloat(KEY_L_X, layout.btnL.x)
            putFloat(KEY_L_Y, layout.btnL.y)
            putInt(KEY_L_SIZE, layout.btnL.sizeDp)

            putFloat(KEY_R_X, layout.btnR.x)
            putFloat(KEY_R_Y, layout.btnR.y)
            putInt(KEY_R_SIZE, layout.btnR.sizeDp)

            putFloat(KEY_X_X, layout.btnX.x)
            putFloat(KEY_X_Y, layout.btnX.y)
            putInt(KEY_X_SIZE, layout.btnX.sizeDp)

            putFloat(KEY_Y_X, layout.btnY.x)
            putFloat(KEY_Y_Y, layout.btnY.y)
            putInt(KEY_Y_SIZE, layout.btnY.sizeDp)

            // === 竖屏布局（独立保存，跟横屏互不干扰） ===
            putFloat(KEY_PDAD_X, layout.dpadP.x)
            putFloat(KEY_PDAD_Y, layout.dpadP.y)
            putInt(KEY_PDAD_SIZE, layout.dpadP.sizeDp)

            putFloat(KEY_PA_X, layout.btnAP.x)
            putFloat(KEY_PA_Y, layout.btnAP.y)
            putInt(KEY_PA_SIZE, layout.btnAP.sizeDp)

            putFloat(KEY_PB_X, layout.btnBP.x)
            putFloat(KEY_PB_Y, layout.btnBP.y)
            putInt(KEY_PB_SIZE, layout.btnBP.sizeDp)

            putFloat(KEY_PTA_X, layout.btnTurboAP.x)
            putFloat(KEY_PTA_Y, layout.btnTurboAP.y)
            putInt(KEY_PTA_SIZE, layout.btnTurboAP.sizeDp)

            putFloat(KEY_PTB_X, layout.btnTurboBP.x)
            putFloat(KEY_PTB_Y, layout.btnTurboBP.y)
            putInt(KEY_PTB_SIZE, layout.btnTurboBP.sizeDp)

            putFloat(KEY_PSTART_X, layout.btnStartP.x)
            putFloat(KEY_PSTART_Y, layout.btnStartP.y)
            putInt(KEY_PSTART_SIZE, layout.btnStartP.sizeDp)

            putFloat(KEY_PSELECT_X, layout.btnSelectP.x)
            putFloat(KEY_PSELECT_Y, layout.btnSelectP.y)
            putInt(KEY_PSELECT_SIZE, layout.btnSelectP.sizeDp)

            putFloat(KEY_PL_X, layout.btnLP.x)
            putFloat(KEY_PL_Y, layout.btnLP.y)
            putInt(KEY_PL_SIZE, layout.btnLP.sizeDp)

            putFloat(KEY_PR_X, layout.btnRP.x)
            putFloat(KEY_PR_Y, layout.btnRP.y)
            putInt(KEY_PR_SIZE, layout.btnRP.sizeDp)

            putFloat(KEY_PX_X, layout.btnXP.x)
            putFloat(KEY_PX_Y, layout.btnXP.y)
            putInt(KEY_PX_SIZE, layout.btnXP.sizeDp)

            putFloat(KEY_PY_X, layout.btnYP.x)
            putFloat(KEY_PY_Y, layout.btnYP.y)
            putInt(KEY_PY_SIZE, layout.btnYP.sizeDp)

            // === 全局设置 ===
            putFloat(KEY_OPACITY, layout.opacity)
            putBoolean(KEY_SHOW_PAD, layout.showPad)
            putBoolean(KEY_HIGH_QUALITY_SCALING, layout.highQualityScaling)

            putString(KEY_NTSC_FILTER, layout.ntscFilter)
            putString(KEY_ASPECT_RATIO, layout.aspectRatio)
            putString(KEY_PALETTE, layout.palette)
            putString(KEY_REGION, layout.region)
            putString(KEY_SOUND_QUALITY, layout.soundQuality)
            putString(KEY_CROP_OVERSCAN, layout.cropOverscan)
            putString(KEY_VIDEO_SCALE, layout.videoScale)
            putFloat(KEY_CUSTOM_LAYOUT_LEFT, layout.customLayoutLeft)
            putFloat(KEY_CUSTOM_LAYOUT_TOP, layout.customLayoutTop)
            putFloat(KEY_CUSTOM_LAYOUT_RIGHT, layout.customLayoutRight)
            putFloat(KEY_CUSTOM_LAYOUT_BOTTOM, layout.customLayoutBottom)
            putFloat(KEY_CUSTOM_LAYOUT_LEFT_P, layout.customLayoutLeftP)
            putFloat(KEY_CUSTOM_LAYOUT_TOP_P, layout.customLayoutTopP)
            putFloat(KEY_CUSTOM_LAYOUT_RIGHT_P, layout.customLayoutRightP)
            putFloat(KEY_CUSTOM_LAYOUT_BOTTOM_P, layout.customLayoutBottomP)
            putString(KEY_VIDEO_FILTER, layout.videoFilter)
            putString(KEY_OVERCLOCKING, layout.overclocking)

            // SFC specific options
            putString("sfc_reduce_sprite_flicker", layout.sfcReduceSpriteFlicker)
            putString("sfc_reduce_slowdown", layout.sfcReduceSlowdown)
            putString("sfc_audio_interpolation", layout.sfcAudioInterpolation)
            putString("sfc_gfx_transparency", layout.sfcGfxTransparency)
            putString("sfc_gfx_hires", layout.sfcGfxHires)
            putString("sfc_gfx_clip", layout.sfcGfxClip)
            putString("sfc_block_invalid_vram", layout.sfcBlockInvalidVram)
            putString("sfc_sound_output", layout.sfcSoundOutput)
            putString("sfc_overscan", layout.sfcOverscan)
            putString("sfc_side_by_side", layout.sfcSideBySide)
            putString("sfc_up_down_allowed", layout.sfcUpDownAllowed)
            putString("sfc_superscope", layout.sfcSuperScope)
            putString("sfc_layer_1", layout.sfcLayer1)
            putString("sfc_layer_2", layout.sfcLayer2)
            putString("sfc_layer_3", layout.sfcLayer3)
            putString("sfc_layer_4", layout.sfcLayer4)
            putString("sfc_layer_5", layout.sfcLayer5)
            putString("sfc_overclock", layout.sfcOverclock)
            // GB/GBA specific options
            putString("gb_color_correction", layout.gbColorCorrection)
            putString("gbc_color_preset", layout.gbcColorPreset)
            putString("gba_color_correction", layout.gbaColorCorrection)
            putString("gba_color_preset", layout.gbaColorPreset)
            putString("gba_frame_blending", layout.gbaFrameBlending)
            putString("gba_audio_resampler", layout.gbaAudioResampler)
            putString("gba_audio_low_pass", layout.gbaAudioLowPass)
            putString("gba_audio_low_pass_range", layout.gbaAudioLowPassRange)
            putString("gba_frameskip_type", layout.gbaFrameskipType)
            putString("gba_frameskip_count", layout.gbaFrameskipCount)
            putString("gba_solar_sensor", layout.gbaSolarSensor)
            putString("gba_idle_optimization", layout.gbaIdleOptimization)
            putString("gba_force_rtc", layout.gbaForceRTC)
            putString("gba_allow_opposite", layout.gbaAllowOpposite)
            putString("gb_model", layout.gbModel)
            putString("gb_sgb_borders", layout.gbSgbBorders)
            putString("gba_frameskip_threshold", layout.gbaFrameskipThreshold)
            // DOSBox-Pure options
            putString("dos_machine", layout.dosMachine)
            putString("dos_cycles", layout.dosCycles)
            putString("dos_cycles_max", layout.dosCyclesMax)
            putString("dos_sb_type", layout.dosSbType)
            putString("dos_sb_adlib_mode", layout.dosSbAdlibMode)
            putString("dos_sb_adlib_emu", layout.dosSbAdlibEmu)
            putString("dos_gus", layout.dosGus)
            putString("dos_mouse_input", layout.dosMouseInput)
            putString("dos_mouse_timeout", layout.dosMouseTimeout)
            putString("dos_keyboard_layout", layout.dosKeyboardLayout)
            putString("dos_keyboard_delay", layout.dosKeyboardDelay)
            putString("dos_keyboard_rate", layout.dosKeyboardRate)
            putString("dos_auto_mapping", layout.dosAutoMapping)
            putString("dos_savestate", layout.dosSavestate)
            putString("dos_dim_screen", layout.dosDimScreen)
            putString("dos_resolution", layout.dosResolution)
            putString("dos_scale", layout.dosScale)
            putString("dos_aspect_ratio", layout.dosAspectRatio)
            putString("dos_cga_colors", layout.dosCgaColors)
            putString("dos_voodoo", layout.dosVoodoo)
            putString("dos_force60fps", layout.dosForce60fps)
            putString("dos_time_announce", layout.dosTimeAnnounce)
            putString("dos_input_mode", layout.dosInputMode)
            // DOS gamepad overlay button positions (landscape)
            saveBtn("dos_dpad", layout.dosDpad)
            saveBtn("dos_btn_esc", layout.dosBtnEsc)
            saveBtn("dos_btn_enter", layout.dosBtnEnter)
            saveBtn("dos_btn_space", layout.dosBtnSpace)
            saveBtn("dos_btn_tab", layout.dosBtnTab)
            saveBtn("dos_btn_ctrl", layout.dosBtnCtrl)
            saveBtn("dos_btn_alt", layout.dosBtnAlt)
            saveBtn("dos_btn_shift", layout.dosBtnShift)
            saveBtn("dos_btn_back", layout.dosBtnBack)
            saveBtn("dos_btn_mouse_l", layout.dosBtnMouseL)
            saveBtn("dos_btn_mouse_r", layout.dosBtnMouseR)
            // DOS gamepad overlay button positions (portrait)
            saveBtn("dos_dpad_p", layout.dosDpadP)
            saveBtn("dos_btn_esc_p", layout.dosBtnEscP)
            saveBtn("dos_btn_enter_p", layout.dosBtnEnterP)
            saveBtn("dos_btn_space_p", layout.dosBtnSpaceP)
            saveBtn("dos_btn_tab_p", layout.dosBtnTabP)
            saveBtn("dos_btn_ctrl_p", layout.dosBtnCtrlP)
            saveBtn("dos_btn_alt_p", layout.dosBtnAltP)
            saveBtn("dos_btn_shift_p", layout.dosBtnShiftP)
            saveBtn("dos_btn_back_p", layout.dosBtnBackP)
            saveBtn("dos_btn_mouse_l_p", layout.dosBtnMouseLP)
            saveBtn("dos_btn_mouse_r_p", layout.dosBtnMouseRP)
            // DOS extra buttons (landscape)
            saveBtn("dos_btn_insert", layout.dosBtnInsert)
            saveBtn("dos_btn_delete", layout.dosBtnDelete)
            saveBtn("dos_btn_home", layout.dosBtnHome)
            saveBtn("dos_btn_end", layout.dosBtnEnd)
            saveBtn("dos_btn_pageup", layout.dosBtnPageUp)
            saveBtn("dos_btn_pagedown", layout.dosBtnPageDown)
            // DOS extra buttons (portrait)
            saveBtn("dos_btn_insert_p", layout.dosBtnInsertP)
            saveBtn("dos_btn_delete_p", layout.dosBtnDeleteP)
            saveBtn("dos_btn_home_p", layout.dosBtnHomeP)
            saveBtn("dos_btn_end_p", layout.dosBtnEndP)
            saveBtn("dos_btn_pageup_p", layout.dosBtnPageUpP)
            saveBtn("dos_btn_pagedown_p", layout.dosBtnPageDownP)
            // DOS button visibility toggles
            putBoolean("dos_show_dpad", layout.dosShowDpad)
            putBoolean("dos_show_esc", layout.dosShowEsc)
            putBoolean("dos_show_enter", layout.dosShowEnter)
            putBoolean("dos_show_space", layout.dosShowSpace)
            putBoolean("dos_show_tab", layout.dosShowTab)
            putBoolean("dos_show_ctrl", layout.dosShowCtrl)
            putBoolean("dos_show_alt", layout.dosShowAlt)
            putBoolean("dos_show_shift", layout.dosShowShift)
            putBoolean("dos_show_back", layout.dosShowBack)
            putBoolean("dos_show_mouse_l", layout.dosShowMouseL)
            putBoolean("dos_show_mouse_r", layout.dosShowMouseR)
            putBoolean("dos_show_insert", layout.dosShowInsert)
            putBoolean("dos_show_delete", layout.dosShowDelete)
            putBoolean("dos_show_home", layout.dosShowHome)
            putBoolean("dos_show_end", layout.dosShowEnd)
            putBoolean("dos_show_pageup", layout.dosShowPageUp)
            putBoolean("dos_show_pagedown", layout.dosShowPageDown)
            // Dynamic extra keys
            putString("dos_extra_keys", layout.dosExtraKeys)
            putString("dos_extra_keys_p", layout.dosExtraKeysP)
            // Display orientation
            putString("screen_orientation", layout.screenOrientation)
            // FBNeo (Arcade) options
            putString("arcade_aspect", layout.arcadeAspect)
            putString("arcade_rotate", layout.arcadeRotate)
            putString("arcade_vertical_mode", layout.arcadeVerticalMode)
            putString("arcade_crop_overscan", layout.arcadeCropOverscan)
            putString("arcade_cpu_speed", layout.arcadeCpuSpeed)
            putString("arcade_frameskip", layout.arcadeFrameskip)
            putString("arcade_force_60hz", layout.arcadeForce60hz)
            putString("arcade_sample_rate", layout.arcadeSampleRate)
            putString("arcade_audio_interp", layout.arcadeAudioInterp)
            putString("arcade_lowpass", layout.arcadeLowpass)
            putString("arcade_neogeo_mode", layout.arcadeNeogeomode)
            putString("arcade_memcard", layout.arcadeMemcard)
            // Genesis-Plus-GX (MD/SEGA) options
            putString("md_region", layout.mdRegion)
            putString("md_system", layout.mdSystem)
            putString("md_aspect", layout.mdAspect)
            putString("md_render", layout.mdRender)
            putString("md_ntsc_filter", layout.mdNtscFilter)
            putString("md_lcd_filter", layout.mdLcdFilter)
            putString("md_overscan", layout.mdOverscan)
            putString("md_gg_extra", layout.mdGgExtra)
            putString("md_left_border", layout.mdLeftBorder)
            putString("md_input", layout.mdInput)
            putString("md_allow_up_down", layout.mdAllowUpDown)
            putString("md_overclock", layout.mdOverclock)
            putString("md_frameskip", layout.mdFrameskip)
            putString("md_cd_fastboot", layout.mdCdFastboot)
            putString("md_sms_fm", layout.mdSmsFm)
            putString("md_gg_stretch", layout.mdGgStretch)
            // === PSX (PCSX-ReARMed) ===
            putString("psx_bios", layout.pscxBios)
            putString("psx_region", layout.pscxRegion)
            putString("psx_frameskip_type", layout.pscxFrameskipType)
            putString("psx_frameskip", layout.pscxFrameskip)
            putString("psx_pad1_type", layout.pscxPad1Type)
            putString("psx_pad2_type", layout.pscxPad2Type)
            putString("psx_vibration", layout.pscxVibration)
            putString("psx_dithering", layout.pscxDithering)
            putString("psx_spu_interp", layout.pscxSpuInterp)
            putString("psx_spu_reverb", layout.pscxSpuReverb)
            putString("psx_show_bootlogo", layout.pscxShowBootlogo)
            putString("psx_cd_readahead", layout.pscxCdReadahead)
            putString("psx_memcard1", layout.pscxMemcard1)
            putString("psx_memcard2", layout.pscxMemcard2)
            putString("psx_drc", layout.pscxDrc)
            putString("psx_clock", layout.pscxClock)
            putString("psx_rgb32", layout.pscxRgb32)
            putString("psx_scale_hires", layout.pscxScaleHires)
            putString("psx_show_overscan", layout.pscxShowOverscan)
            putString("psx_multitap", layout.pscxMultitap)
            putString("psx_gpu_odd_even", layout.pscxGpuOddEven)
            putString("psx_analog_axis", layout.pscxAnalogAxis)
            // === Arcade extras ===
            saveBtn("btn_l2", layout.btnL2)
            saveBtn("btn_r2", layout.btnR2)
            saveBtn("p_btn_l2", layout.btnL2P)
            saveBtn("p_btn_r2", layout.btnR2P)
            putBoolean("arcade_show_l2r2", layout.arcadeShowL2R2)
            putString("arcade_input_mode", layout.arcadeInputMode)
            // === Combo buttons (per-platform JSON) ===
            putString("combo_buttons", layout.comboButtons)
            putString("combo_buttons_sfc", layout.comboButtonsSfc)
            putString("combo_buttons_gba", layout.comboButtonsGba)
            putString("combo_buttons_arcade", layout.comboButtonsArcade)
            putString("combo_buttons_md", layout.comboButtonsMd)
            putString("combo_buttons_pce", layout.comboButtonsPce)
            // PCE button visibility toggles
            putBoolean("pce_show_dpad", layout.pceShowDpad)
            putBoolean("pce_show_a", layout.pceShowA)
            putBoolean("pce_show_b", layout.pceShowB)
            putBoolean("pce_show_start", layout.pceShowStart)
            putBoolean("pce_show_select", layout.pceShowSelect)
            putBoolean("pce_show_l", layout.pceShowL)
            putBoolean("pce_show_r", layout.pceShowR)
            putBoolean("pce_show_x", layout.pceShowX)
            putBoolean("pce_show_y", layout.pceShowY)
            putBoolean("pce_show_l2", layout.pceShowL2)
            putBoolean("pce_show_r2", layout.pceShowR2)
            // === Per-platform hidden button lists ===
            putString("hidden_buttons", layout.hiddenButtons)
            putString("hidden_buttons_sfc", layout.hiddenButtonsSfc)
            putString("hidden_buttons_gba", layout.hiddenButtonsGba)
            putString("hidden_buttons_arcade", layout.hiddenButtonsArcade)
            putString("hidden_buttons_md", layout.hiddenButtonsMd)
            putString("hidden_buttons_pce", layout.hiddenButtonsPce)
            putString("hidden_buttons_nds", layout.hiddenButtonsNds)
            putString("hidden_buttons_psx", layout.hiddenButtonsPsx)
            // === Input mode ===
            putString("input_mode", layout.inputMode)
        }.apply()
    }

    /**
     * Check if a specific button is hidden for a given platform.
     * For PCE, uses the legacy pceShow* booleans (backward compatible).
     * For all other platforms, checks the hiddenButtons* comma-separated string.
     *
     * @param platform The game platform
     * @param key Button key: "dpad", "a", "b", "ta", "tb", "start", "select", "l", "r", "x", "y", "l2", "r2"
     * @return true if the button should be hidden
     */
    fun isButtonHidden(layout: PadLayout, platform: GamePlatform, key: String): Boolean {
        return when (platform) {
            GamePlatform.PCE -> {
                // PCE uses the legacy per-button boolean toggles
                when (key) {
                    "dpad" -> !layout.pceShowDpad
                    "a" -> !layout.pceShowA
                    "b" -> !layout.pceShowB
                    "start" -> !layout.pceShowStart
                    "select" -> !layout.pceShowSelect
                    "l" -> !layout.pceShowL
                    "r" -> !layout.pceShowR
                    "x" -> !layout.pceShowX
                    "y" -> !layout.pceShowY
                    "l2" -> !layout.pceShowL2
                    "r2" -> !layout.pceShowR2
                    else -> false
                }
            }
            GamePlatform.NES, GamePlatform.GB -> isHiddenInList(layout.hiddenButtons, key)
            GamePlatform.SFC -> isHiddenInList(layout.hiddenButtonsSfc, key)
            GamePlatform.GBA -> isHiddenInList(layout.hiddenButtonsGba, key)
            GamePlatform.ARCADE -> isHiddenInList(layout.hiddenButtonsArcade, key)
            GamePlatform.MD -> isHiddenInList(layout.hiddenButtonsMd, key)
            GamePlatform.NDS -> isHiddenInList(layout.hiddenButtonsNds, key)
            GamePlatform.PSX -> isHiddenInList(layout.hiddenButtonsPsx, key)
            else -> false
        }
    }

    /**
     * Set a button's hidden state for a given platform.
     * For PCE, updates the legacy pceShow* booleans.
     * For all other platforms, updates the hiddenButtons* comma-separated string.
     */
    fun setButtonHidden(layout: PadLayout, platform: GamePlatform, key: String, hidden: Boolean): PadLayout {
        return when (platform) {
            GamePlatform.PCE -> {
                when (key) {
                    "dpad" -> layout.copy(pceShowDpad = !hidden)
                    "a" -> layout.copy(pceShowA = !hidden)
                    "b" -> layout.copy(pceShowB = !hidden)
                    "start" -> layout.copy(pceShowStart = !hidden)
                    "select" -> layout.copy(pceShowSelect = !hidden)
                    "l" -> layout.copy(pceShowL = !hidden)
                    "r" -> layout.copy(pceShowR = !hidden)
                    "x" -> layout.copy(pceShowX = !hidden)
                    "y" -> layout.copy(pceShowY = !hidden)
                    "l2" -> layout.copy(pceShowL2 = !hidden)
                    "r2" -> layout.copy(pceShowR2 = !hidden)
                    else -> layout
                }
            }
            GamePlatform.NES, GamePlatform.GB -> layout.copy(hiddenButtons = updateHiddenList(layout.hiddenButtons, key, hidden))
            GamePlatform.SFC -> layout.copy(hiddenButtonsSfc = updateHiddenList(layout.hiddenButtonsSfc, key, hidden))
            GamePlatform.GBA -> layout.copy(hiddenButtonsGba = updateHiddenList(layout.hiddenButtonsGba, key, hidden))
            GamePlatform.ARCADE -> layout.copy(hiddenButtonsArcade = updateHiddenList(layout.hiddenButtonsArcade, key, hidden))
            GamePlatform.MD -> layout.copy(hiddenButtonsMd = updateHiddenList(layout.hiddenButtonsMd, key, hidden))
            GamePlatform.NDS -> layout.copy(hiddenButtonsNds = updateHiddenList(layout.hiddenButtonsNds, key, hidden))
            GamePlatform.PSX -> layout.copy(hiddenButtonsPsx = updateHiddenList(layout.hiddenButtonsPsx, key, hidden))
            else -> layout
        }
    }

    /**
     * Get the input mode ("dpad" or "analog") for a given platform.
     * Arcade still uses its legacy arcadeInputMode field for backward compatibility.
     * All other platforms use the global inputMode field.
     */
    fun getInputMode(layout: PadLayout, platform: GamePlatform): String {
        return if (platform == GamePlatform.ARCADE) layout.arcadeInputMode else layout.inputMode
    }

    /**
     * Set the input mode for a given platform.
     * Arcade updates arcadeInputMode; all others update the global inputMode.
     */
    fun setInputMode(layout: PadLayout, platform: GamePlatform, mode: String): PadLayout {
        return if (platform == GamePlatform.ARCADE) {
            layout.copy(arcadeInputMode = mode)
        } else {
            layout.copy(inputMode = mode)
        }
    }

    /** Check if a key exists in a comma-separated hidden list. */
    private fun isHiddenInList(list: String, key: String): Boolean {
        if (list.isBlank()) return false
        return list.split(",").map { it.trim() }.contains(key)
    }

    /** Add or remove a key from a comma-separated hidden list. */
    private fun updateHiddenList(list: String, key: String, hidden: Boolean): String {
        val keys = if (list.isBlank()) emptyList() else list.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val updated = if (hidden) {
            if (keys.contains(key)) keys else keys + key
        } else {
            keys.filter { it != key }
        }
        return updated.joinToString(",")
    }

    /**
     * Return the list of available button keys for a given platform,
     * in display order. Used by the visibility toggle dialog.
     */
    fun getAvailableButtons(platform: GamePlatform): List<Pair<String, String>> {
        // Each pair: (key, displayLabel)
        return when (platform) {
            GamePlatform.NES, GamePlatform.GB -> listOf(
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "ta" to "连射A", "tb" to "连射B",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.GBA -> listOf(
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "l" to "L键", "r" to "R键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.SFC, GamePlatform.NDS -> listOf(
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "x" to "X键", "y" to "Y键",
                "l" to "L键", "r" to "R键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.ARCADE -> listOf(
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "x" to "X键", "y" to "Y键",
                "l" to "L键", "r" to "R键",
                "l2" to "L2键", "r2" to "R2键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.MD -> listOf(
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "x" to "X键", "y" to "Y键",
                "l" to "L键", "r" to "R键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.PCE -> listOf(
                "dpad" to "十字键", "a" to "I键", "b" to "II键",
                "x" to "IV键", "y" to "III键",
                "l" to "V键", "r" to "VI键",
                "l2" to "TURBO II", "r2" to "TURBO I",
                "start" to "RUN", "select" to "SELECT"
            )
            GamePlatform.PSX -> listOf(
                "dpad" to "十字键", "a" to "×键", "b" to "○键",
                "x" to "□键", "y" to "△键",
                "l" to "L1键", "r" to "R1键",
                "l2" to "L2键", "r2" to "R2键",
                "start" to "START", "select" to "SELECT"
            )
            else -> emptyList()
        }
    }
}
