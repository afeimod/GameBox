package com.nesstation.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.nesstation.app.core.model.GameEntry
import androidx.compose.ui.graphics.Color

/**
 * Persistent ROM library store using SharedPreferences.
 * Stores imported ROM paths so they survive activity recreation and app restarts.
 */
object RomStore {
    private const val PREFS_NAME = "rom_library"
    private const val KEY_COUNT = "rom_count"
    private const val KEY_PREFIX_ID = "rom_id_"
    private const val KEY_PREFIX_TITLE = "rom_title_"
    private const val KEY_PREFIX_PATH = "rom_path_"
    private const val KEY_PREFIX_ACCENT = "rom_accent_"

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
            val accent = p.getInt("${KEY_PREFIX_ACCENT}$i", 0xFFE74C3C.toInt())
            list.add(GameEntry(id = id, title = title, romPath = path, accent = Color(accent)))
        }
        return list
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
            }
        }.apply()
    }

    /** Add a single ROM entry and persist */
    fun add(ctx: Context, title: String, romPath: String): GameEntry {
        val list = loadAll(ctx)
        // Avoid duplicates
        if (list.any { it.romPath == romPath }) {
            return list.first { it.romPath == romPath }
        }
        val accent = ACCENT_COLORS[list.size % ACCENT_COLORS.size]
        val entry = GameEntry(
            id = "rom_${System.currentTimeMillis()}_${list.size}",
            title = title,
            romPath = romPath,
            accent = Color(accent)
        )
        list.add(entry)
        saveAll(ctx, list)
        return entry
    }

    /** Add multiple ROM entries and persist */
    fun addAll(ctx: Context, entries: List<Pair<String, String>>): List<GameEntry> {
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
                    accent = Color(accent)
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
}
