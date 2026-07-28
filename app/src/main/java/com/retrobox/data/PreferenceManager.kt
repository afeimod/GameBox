package com.retrobox.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 应用偏好设置管理器
 *
 * 基于 SharedPreferences 管理应用配置：
 * - 各平台按键映射
 * - 主题
 * - 下载路径
 * - Gitee 仓库配置
 * - 音量 / 帧率等运行时设置
 */
class PreferenceManager(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    // ===== 主题 =====

    /** 主题模式：light / dark / system */
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    // ===== 下载路径 =====

    /**
     * ROM 下载根目录
     *
     * 默认 /sdcard/RetroBox/ROMs，需要 MANAGE_EXTERNAL_STORAGE 权限。
     * 如果权限未授予，fallback 到应用专属目录。
     */
    var downloadPath: String
        get() {
            val saved = prefs.getString(KEY_DOWNLOAD_PATH, null)
            val path = saved ?: DEFAULT_ROM_DIR
            // 确保目录存在
            val dir = File(path)
            if (!dir.exists()) dir.mkdirs()
            return path
        }
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_PATH, value).apply()

    // ===== Gitee 仓库配置 =====

    var giteeOwner: String
        get() = prefs.getString(KEY_GITEE_OWNER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITEE_OWNER, value).apply()

    var giteeRepo: String
        get() = prefs.getString(KEY_GITEE_REPO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITEE_REPO, value).apply()

    var giteeBranch: String
        get() = prefs.getString(KEY_GITEE_BRANCH, "master") ?: "master"
        set(value) = prefs.edit().putString(KEY_GITEE_BRANCH, value).apply()

    var giteeToken: String
        get() = prefs.getString(KEY_GITEE_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITEE_TOKEN, value).apply()

    // ===== 运行时设置 =====

    /** 主音量（0-100） */
    var volume: Int
        get() = prefs.getInt(KEY_VOLUME, 100)
        set(value) = prefs.edit().putInt(KEY_VOLUME, value.coerceIn(0, 100)).apply()

    /** 目标帧率 */
    var targetFps: Int
        get() = prefs.getInt(KEY_FPS, 60)
        set(value) = prefs.edit().putInt(KEY_FPS, value.coerceIn(30, 60)).apply()

    /** 是否保持屏幕常亮 */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** 是否显示 FPS */
    var showFps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FPS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_FPS, value).apply()

    // ===== 按键映射 =====

    /**
     * 保存指定平台的按键映射（按键名 -> 物理按键码）
     *
     * @param platform 平台标识（如 "NES"、"SNES"）
     * @param mapping  按键名到按键码的映射
     */
    fun setButtonMapping(platform: String, mapping: Map<String, Int>) {
        val json = gson.toJson(mapping.mapValues { it.value.toString() })
        prefs.edit().putString("${KEY_BUTTON_MAPPING}_$platform", json).apply()
    }

    /**
     * 获取指定平台的按键映射
     *
     * @param platform 平台标识
     * @param defaults 默认映射（未配置时使用）
     */
    fun getButtonMapping(platform: String, defaults: Map<String, Int> = emptyMap()): Map<String, Int> {
        val json = prefs.getString("${KEY_BUTTON_MAPPING}_$platform", null) ?: return defaults
        return try {
            val raw: Map<String, String> = gson.fromJson(json, mapType) ?: return defaults
            raw.mapValues { it.value.toIntOrNull() ?: -1 }.filterValues { it >= 0 }
        } catch (e: Exception) {
            defaults
        }
    }

    /** 清除指定平台的按键映射 */
    fun clearButtonMapping(platform: String) {
        prefs.edit().remove("${KEY_BUTTON_MAPPING}_$platform").apply()
    }

    // ===== 通用读写 =====

    fun putString(key: String, value: String) =
        prefs.edit().putString(key, value).apply()

    fun getString(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    fun putBoolean(key: String, value: Boolean) =
        prefs.edit().putBoolean(key, value).apply()

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        prefs.getBoolean(key, default)

    fun putInt(key: String, value: Int) =
        prefs.edit().putInt(key, value).apply()

    fun getInt(key: String, default: Int = 0): Int =
        prefs.getInt(key, default)

    companion object {
        private const val PREFS_NAME = "retrobox_prefs"

        /** 默认 ROM 目录（需要 MANAGE_EXTERNAL_STORAGE 权限） */
        const val DEFAULT_ROM_DIR = "/sdcard/RetroBox/ROMs"

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DOWNLOAD_PATH = "download_path"

        private const val KEY_GITEE_OWNER = "gitee_owner"
        private const val KEY_GITEE_REPO = "gitee_repo"
        private const val KEY_GITEE_BRANCH = "gitee_branch"
        private const val KEY_GITEE_TOKEN = "gitee_token"

        private const val KEY_VOLUME = "volume"
        private const val KEY_FPS = "target_fps"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_SHOW_FPS = "show_fps"

        private const val KEY_BUTTON_MAPPING = "button_mapping"
    }
}
