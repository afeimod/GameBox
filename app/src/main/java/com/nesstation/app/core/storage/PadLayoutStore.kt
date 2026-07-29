package com.nesstation.app.core.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent on-screen controller layout settings.
 * Stores D-pad position (0.0-1.0), size scale, and button sizes.
 */
object PadLayoutStore {
    private const val PREFS_NAME = "pad_layout"

    private const val KEY_DPAD_X = "dpad_x"        // 0.0=left, 1.0=right
    private const val KEY_DPAD_Y = "dpad_y"        // 0.0=top, 1.0=bottom
    private const val KEY_DPAD_SCALE = "dpad_scale"  // 0.5 - 2.0
    private const val KEY_BTN_SCALE = "btn_scale"    // 0.5 - 2.0
    private const val KEY_BTN_X = "btn_x"
    private const val KEY_BTN_Y = "btn_y"
    private const val KEY_OPACITY = "opacity"        // 0.3 - 1.0
    private const val KEY_SHOW_PAD = "show_pad"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): PadLayout = PadLayout(
        dpadX = prefs(ctx).getFloat(KEY_DPAD_X, 0.06f),
        dpadY = prefs(ctx).getFloat(KEY_DPAD_Y, 0.82f),
        dpadScale = prefs(ctx).getFloat(KEY_DPAD_SCALE, 1.0f),
        btnScale = prefs(ctx).getFloat(KEY_BTN_SCALE, 1.0f),
        btnX = prefs(ctx).getFloat(KEY_BTN_X, 0.82f),
        btnY = prefs(ctx).getFloat(KEY_BTN_Y, 0.82f),
        opacity = prefs(ctx).getFloat(KEY_OPACITY, 0.75f),
        showPad = prefs(ctx).getBoolean(KEY_SHOW_PAD, true)
    )

    fun save(ctx: Context, layout: PadLayout) {
        prefs(ctx).edit().apply {
            putFloat(KEY_DPAD_X, layout.dpadX)
            putFloat(KEY_DPAD_Y, layout.dpadY)
            putFloat(KEY_DPAD_SCALE, layout.dpadScale)
            putFloat(KEY_BTN_SCALE, layout.btnScale)
            putFloat(KEY_BTN_X, layout.btnX)
            putFloat(KEY_BTN_Y, layout.btnY)
            putFloat(KEY_OPACITY, layout.opacity)
            putBoolean(KEY_SHOW_PAD, layout.showPad)
        }.apply()
    }
}

data class PadLayout(
    val dpadX: Float = 0.06f,      // 0.0 = left edge, 1.0 = right edge
    val dpadY: Float = 0.82f,      // 0.0 = top, 1.0 = bottom
    val dpadScale: Float = 1.0f,   // 0.5x - 2.0x
    val btnScale: Float = 1.0f,    // 0.5x - 2.0x
    val btnX: Float = 0.82f,
    val btnY: Float = 0.82f,
    val opacity: Float = 0.75f,    // 0.3 - 1.0
    val showPad: Boolean = true
)
