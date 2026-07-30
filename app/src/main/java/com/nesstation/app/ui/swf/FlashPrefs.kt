package com.nesstation.app.ui.swf

import android.content.Context
import android.content.SharedPreferences

/**
 * Flash 引擎偏好设置。
 *
 * 管理 SWF 播放器的引擎选择、画质、自动播放等设置。
 * 使用 SharedPreferences 存储。
 */
object FlashPrefs {

    private const val PREFS_NAME = "flash_engine_prefs"
    private const val KEY_ENGINE = "flash_engine"       // "ruffle" | "waflash"
    private const val KEY_QUALITY = "flash_quality"     // "low" | "medium" | "high" | "best"
    private const val KEY_AUTOPLAY = "flash_autoplay"   // true | false
    private const val KEY_SCALE = "flash_scale"         // "showAll" | "noBorder" | "exactFit" | "noScale"

    /** 引擎类型 */
    enum class Engine(val value: String, val displayName: String) {
        RUFFLE("ruffle", "Ruffle (AS1/2/3, 内置中文字体)"),
        WAFLASH("waflash", "WAFlash (AS2/AS3, Canvas渲染)");

        companion object {
            fun fromValue(v: String?): Engine = entries.firstOrNull { it.value == v } ?: RUFFLE
        }
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getEngine(ctx: Context): Engine = Engine.fromValue(prefs(ctx).getString(KEY_ENGINE, null))

    fun setEngine(ctx: Context, engine: Engine) {
        prefs(ctx).edit().putString(KEY_ENGINE, engine.value).apply()
    }

    fun getQuality(ctx: Context): String = prefs(ctx).getString(KEY_QUALITY, "high") ?: "high"

    fun setQuality(ctx: Context, quality: String) {
        prefs(ctx).edit().putString(KEY_QUALITY, quality).apply()
    }

    fun isAutoplay(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTOPLAY, true)

    fun setAutoplay(ctx: Context, autoplay: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTOPLAY, autoplay).apply()
    }

    fun getScale(ctx: Context): String = prefs(ctx).getString(KEY_SCALE, "showAll") ?: "showAll"

    fun setScale(ctx: Context, scale: String) {
        prefs(ctx).edit().putString(KEY_SCALE, scale).apply()
    }
}
