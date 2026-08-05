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
    // X button — above A (SNES 4-face button layout)
    val btnX: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.62f, sizeDp = 60),
    // Y button — left of B (SNES 4-face button layout)
    val btnY: ButtonLayout = ButtonLayout(x = 0.60f, y = 0.76f, sizeDp = 60),
    // Global settings
    val opacity: Float = 0.7f,     // 0.3 – 1.0
    val showPad: Boolean = true,
    // Core options — values MUST match FCEUmm's libretro_core_options.h
    val ntscFilter: String = "disabled",  // disabled | composite | svideo | rgb | monochrome
    val aspectRatio: String = "8:7 PAR",  // "8:7 PAR" | "4:3" | "PP"  (FCEUmm exact values)
    val palette: String = "default",      // default | dq | nx | asq | rp2 | ...
    val region: String = "Auto",          // Auto | NTSC | PAL | Dendy
    val soundQuality: String = "Low",     // Low | High | Very High
    val cropOverscan: String = "disabled",// disabled | enabled  (maps to 4 individual overscan keys)
    // Video scaling — controls SurfaceView layout aspect ratio (frontend-level, not FCEUmm option)
    val videoScale: String = "stretch",   // stretch | 4:3 | 8:7 | 16:9
    // Video filter — applied in the native blit function (frontend-level post-processing)
    val videoFilter: String = "none",     // none | scanline | crt | dot | xbr | hq2x | hq4x | xbr_dot
    // Overclocking — adds dummy scanlines to the PPU loop, reducing slowdowns
    val overclocking: String = "disabled" // disabled | 2x-Postrender | 2x-VBlank
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
                p.getFloat(KEY_X_X, 0.87f),
                p.getFloat(KEY_X_Y, 0.62f),
                p.getInt(KEY_X_SIZE, 60)
            ),
            btnY = ButtonLayout(
                p.getFloat(KEY_Y_X, 0.60f),
                p.getFloat(KEY_Y_Y, 0.76f),
                p.getInt(KEY_Y_SIZE, 60)
            ),
            opacity = p.getFloat(KEY_OPACITY, 0.7f),
            showPad = p.getBoolean(KEY_SHOW_PAD, true),
            ntscFilter = p.getString(KEY_NTSC_FILTER, "disabled") ?: "disabled",
            aspectRatio = p.getString(KEY_ASPECT_RATIO, "8:7 PAR") ?: "8:7 PAR",
            palette = p.getString(KEY_PALETTE, "default") ?: "default",
            region = p.getString(KEY_REGION, "Auto") ?: "Auto",
            soundQuality = p.getString(KEY_SOUND_QUALITY, "Low") ?: "Low",
            cropOverscan = p.getString(KEY_CROP_OVERSCAN, "disabled") ?: "disabled",
            videoScale = p.getString(KEY_VIDEO_SCALE, "stretch") ?: "stretch",
            videoFilter = p.getString(KEY_VIDEO_FILTER, "none") ?: "none",
            overclocking = p.getString(KEY_OVERCLOCKING, "disabled") ?: "disabled"
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
        }.apply()
    }
}
