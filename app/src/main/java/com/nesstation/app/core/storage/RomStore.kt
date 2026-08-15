package com.nesstation.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import androidx.compose.ui.graphics.Color

/**
 * Persistent ROM library store using SharedPreferences.
 * Stores imported ROM paths so they survive activity recreation and app restarts.
 * Supports multiple platforms (NES, JAVA) and custom icons.
 */
object RomStore {
    private const val PREFS_NAME = "rom_library"
    private const val KEY_COUNT = "rom_count"
    private const val KEY_PREFIX_ID = "rom_id_"
    private const val KEY_PREFIX_TITLE = "rom_title_"
    private const val KEY_PREFIX_PATH = "rom_path_"
    private const val KEY_PREFIX_ACCENT = "rom_accent_"
    private const val KEY_PREFIX_PLATFORM = "rom_platform_"
    private const val KEY_PREFIX_ICON = "rom_icon_"
    private const val KEY_PREFIX_LASTPLAYED = "rom_lastplayed_"
    private const val KEY_PREFIX_FAVORITE = "rom_favorite_"

    private val ACCENT_COLORS = listOf(
        0xFFE74C3C.toInt(), 0xFF27AE60.toInt(), 0xFF3498DB.toInt(),
        0xFF8E44AD.toInt(), 0xFFE67E22.toInt(), 0xFF1ABC9C.toInt(),
        0xFF2ECC71.toInt(), 0xFF9B59B6.toInt(), 0xFFF1C40F.toInt(),
        0xFF1E2A3A.toInt()
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Load all persisted ROM entries */
    fun loadAll(ctx: Context): MutableList<GameEntry> {
        val p = prefs(ctx)
        val count = p.getInt(KEY_COUNT, 0)
        val list = mutableListOf<GameEntry>()
        for (i in 0 until count) {
            val id = p.getString("${KEY_PREFIX_ID}$i", null) ?: continue
            val title = p.getString("${KEY_PREFIX_TITLE}$i", "Unknown") ?: "Unknown"
            val path = p.getString("${KEY_PREFIX_PATH}$i", null) ?: continue
            val accentInt = p.getInt("${KEY_PREFIX_ACCENT}$i", 0xFFE74C3C.toInt())
            val platform = GamePlatform.fromString(p.getString("${KEY_PREFIX_PLATFORM}$i", "NES"))
            val iconPath = p.getString("${KEY_PREFIX_ICON}$i", null)
            val lastPlayed = p.getLong("${KEY_PREFIX_LASTPLAYED}$i", 0L)
            val favorite = p.getBoolean("${KEY_PREFIX_FAVORITE}$i", false)
            list.add(GameEntry(
                id = id, title = title, romPath = path,
                accent = Color(accentInt.toLong() and 0xFFFFFFFF),
                platform = platform,
                customIconPath = iconPath,
                lastPlayedAt = lastPlayed,
                isFavorite = favorite
            ))
        }
        return list
    }

    /** Load games filtered by platform */
    fun loadByPlatform(ctx: Context, platform: GamePlatform): List<GameEntry> {
        return loadAll(ctx).filter { it.platform == platform }
    }

    /** Save the entire list, replacing any previous data */
    fun saveAll(ctx: Context, list: List<GameEntry>) {
        val p = prefs(ctx)
        p.edit().clear().apply()
        p.edit().apply {
            putInt(KEY_COUNT, list.size)
            list.forEachIndexed { i, entry ->
                putString("${KEY_PREFIX_ID}$i", entry.id)
                putString("${KEY_PREFIX_TITLE}$i", entry.title)
                putString("${KEY_PREFIX_PATH}$i", entry.romPath ?: "")
                putInt("${KEY_PREFIX_ACCENT}$i", entry.accent.value.toInt())
                putString("${KEY_PREFIX_PLATFORM}$i", entry.platform.name)
                putString("${KEY_PREFIX_ICON}$i", entry.customIconPath)
                putLong("${KEY_PREFIX_LASTPLAYED}$i", entry.lastPlayedAt)
                putBoolean("${KEY_PREFIX_FAVORITE}$i", entry.isFavorite)
            }
        }.apply()
    }

    /** Add a single ROM entry and persist */
    fun add(ctx: Context, title: String, romPath: String, platform: GamePlatform = GamePlatform.NES): GameEntry {
        val list = loadAll(ctx)
        val existingIdx = list.indexOfFirst { it.romPath == romPath }
        if (existingIdx >= 0) {
            // Update platform and title if they differ — fixes wrong platform
            // from older imports that defaulted everything to NES.
            val existing = list[existingIdx]
            if (existing.platform != platform || existing.title != title) {
                list[existingIdx] = existing.copy(platform = platform, title = title)
                saveAll(ctx, list)
            }
            return list[existingIdx]
        }
        val accent = ACCENT_COLORS[list.size % ACCENT_COLORS.size]
        val entry = GameEntry(
            id = "rom_${System.currentTimeMillis()}_${list.size}",
            title = title,
            romPath = romPath,
            accent = Color(accent),
            platform = platform
        )
        list.add(entry)
        saveAll(ctx, list)
        return entry
    }

    /** Add multiple ROM entries and persist */
    fun addAll(ctx: Context, entries: List<Pair<String, String>>, platform: GamePlatform = GamePlatform.NES): List<GameEntry> {
        val list = loadAll(ctx)
        val existingPaths = list.map { it.romPath }.toMutableSet()
        val added = mutableListOf<GameEntry>()
        entries.forEach { (title, path) ->
            if (path !in existingPaths) {
                val accent = ACCENT_COLORS[list.size % ACCENT_COLORS.size]
                val entry = GameEntry(
                    id = "rom_${System.currentTimeMillis()}_${list.size}",
                    title = title,
                    romPath = path,
                    accent = Color(accent),
                    platform = platform
                )
                list.add(entry)
                existingPaths.add(path)
                added.add(entry)
            }
        }
        saveAll(ctx, list)
        return added
    }

    /** Remove a ROM entry by id */
    fun remove(ctx: Context, id: String) {
        val list = loadAll(ctx)
        list.removeAll { it.id == id }
        saveAll(ctx, list)
    }

    /** Update a game entry (e.g., custom icon, last played, favorite) */
    fun update(ctx: Context, entry: GameEntry) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            list[idx] = entry
            saveAll(ctx, list)
        }
    }

    /** Set custom icon path for a game */
    fun setCustomIcon(ctx: Context, gameId: String, iconPath: String?) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == gameId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(customIconPath = iconPath)
            saveAll(ctx, list)
        }
    }

    /** Update last played timestamp */
    fun updateLastPlayed(ctx: Context, gameId: String) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == gameId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(lastPlayedAt = System.currentTimeMillis())
            saveAll(ctx, list)
        }
    }

    /** Toggle favorite status */
    fun toggleFavorite(ctx: Context, gameId: String) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == gameId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(isFavorite = !list[idx].isFavorite)
            saveAll(ctx, list)
        }
    }

    /**
     * 街机游戏中文标题迁移。
     *
     * 对于已经是 ARCADE 平台但标题仍是英文驱动名（如 "kof98h"、"mvc"）
     * 的游戏，尝试用 ArcadeTitleMapper 查找中文名并更新。
     *
     * 已是中文标题或查找不到中文名的游戏不会被修改。
     * 返回更新了标题的游戏数量。
     */
    fun migrateArcadeTitles(ctx: Context): Int {
        val list = loadAll(ctx)
        var updatedCount = 0
        var changed = false
        for (i in list.indices) {
            val entry = list[i]
            if (entry.platform != GamePlatform.ARCADE) continue
            // 跳过已包含中文字符的标题（认为已经汉化过）
            if (entry.title.any { it.code in 0x4E00..0x9FFF }) continue
            // 尝试用文件名（带后缀）查表，覆盖度更高
            val fileName = entry.romPath?.substringAfterLast('/') ?: entry.title
            val cnName = ArcadeTitleMapper.lookupByFileName(fileName)
                ?: ArcadeTitleMapper.lookup(entry.title)
            if (cnName != null && cnName != entry.title) {
                list[i] = entry.copy(title = cnName)
                updatedCount++
                changed = true
            }
        }
        if (changed) saveAll(ctx, list)
        return updatedCount
    }
}
