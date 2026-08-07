package com.nesstation.app.core.storage

import android.content.Context
import android.content.SharedPreferences

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
 * Complete on-screen controller layout with per-button positioning.
 * Every button can be individually positioned and resized.
 * Includes L/R shoulder buttons (GBA/SNES) and X/Y face buttons (SNES).
 */
data class PadLayout(
    // D-pad — cross-shaped, positioned on the left
    val dpad: ButtonLayout = ButtonLayout(x = 0.13f, y = 0.78f, sizeDp = 140),
    // A button — right side, lower
    val btnA: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.76f, sizeDp = 72),
    // B button — right side, lower-left of A
    val btnB: ButtonLayout = ButtonLayout(x = 0.72f, y = 0.82f, sizeDp = 72),
    // Turbo A (rapid-fire) — above A
    val btnTurboA: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.60f, sizeDp = 48),
    // Turbo B (rapid-fire) — above B
    val btnTurboB: ButtonLayout = ButtonLayout(x = 0.72f, y = 0.66f, sizeDp = 48),
    // Start — center-right, bottom
    val btnStart: ButtonLayout = ButtonLayout(x = 0.62f, y = 0.92f, sizeDp = 56),
    // Select — center-left, bottom
    val btnSelect: ButtonLayout = ButtonLayout(x = 0.38f, y = 0.92f, sizeDp = 56),
    // L button — top-left shoulder (GBA/SNES)
    val btnL: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.15f, sizeDp = 56),
    // R button — top-right shoulder (GBA/SNES)
    val btnR: ButtonLayout = ButtonLayout(x = 0.90f, y = 0.15f, sizeDp = 56),
    // X button — above A (SNES 4-face button layout, top-right of diamond)
    val btnX: ButtonLayout = ButtonLayout(x = 0.88f, y = 0.54f, sizeDp = 60),
    // Y button — above B (SNES 4-face button layout, top-left of diamond)
    val btnY: ButtonLayout = ButtonLayout(x = 0.73f, y = 0.60f, sizeDp = 60),
    // Global settings
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
    val videoScale: String = "stretch",   // stretch | 4:3 | 8:7 | 16:9
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
    val gbaFrameskipThreshold: String = "33",          // 0-100 (audio buffer threshold for auto frameskip)
    // Screen orientation preference — unspecified (follow system), landscape, portrait
    val orientation: String = "unspecified"            // unspecified | landscape | portrait
)

/**
 * Persistent on-screen controller layout + core option settings.
 * Stores per-button positions (0.0–1.0), sizes (dp), and global options.
 */
object PadLayoutStore {
    private const val PREFS_NAME = "pad_layout_v2"

    // Button keys
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

    // Global keys
    private const val KEY_OPACITY = "opacity"
    private const val KEY_SHOW_PAD = "show_pad"

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

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): PadLayout {
        val p = prefs(ctx)
        return PadLayout(
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
            opacity = p.getFloat(KEY_OPACITY, 0.7f),
            showPad = p.getBoolean(KEY_SHOW_PAD, true),
            ntscFilter = p.getString(KEY_NTSC_FILTER, "disabled") ?: "disabled",
            aspectRatio = p.getString(KEY_ASPECT_RATIO, "4:3") ?: "4:3",
            palette = p.getString(KEY_PALETTE, "default") ?: "default",
            region = p.getString(KEY_REGION, "Auto") ?: "Auto",
            soundQuality = p.getString(KEY_SOUND_QUALITY, "Low") ?: "Low",
            cropOverscan = p.getString(KEY_CROP_OVERSCAN, "disabled") ?: "disabled",
            videoScale = p.getString(KEY_VIDEO_SCALE, "stretch") ?: "stretch",
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
            orientation = p.getString("orientation", "unspecified") ?: "unspecified"
        )
    }

    fun save(ctx: Context, layout: PadLayout) {
        prefs(ctx).edit().apply {
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

            putFloat(KEY_OPACITY, layout.opacity)
            putBoolean(KEY_SHOW_PAD, layout.showPad)

            putString(KEY_NTSC_FILTER, layout.ntscFilter)
            putString(KEY_ASPECT_RATIO, layout.aspectRatio)
            putString(KEY_PALETTE, layout.palette)
            putString(KEY_REGION, layout.region)
            putString(KEY_SOUND_QUALITY, layout.soundQuality)
            putString(KEY_CROP_OVERSCAN, layout.cropOverscan)
            putString(KEY_VIDEO_SCALE, layout.videoScale)
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
            putString("orientation", layout.orientation)
        }.apply()
    }
}
