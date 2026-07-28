package com.retrobox.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 游戏仓库
 *
 * 管理本地游戏库：
 * - 扫描本地 ROM 目录并生成游戏信息
 * - 保存 / 读取游戏信息（基于 SharedPreferences + Gson 序列化）
 * - 更新游玩记录（上次游玩时间、游玩次数）
 */
class GameRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()

    // 游戏表：id -> GameInfo
    private val gamesMapType = object : TypeToken<MutableMap<Long, GameInfo>>() {}.type

    /** 当前内存中的游戏表（与 SharedPreferences 同步） */
    private val games: MutableMap<Long, GameInfo> by lazy { loadGames() }

    /**
     * 扫描本地 ROM 目录，将识别到的 ROM 加入游戏库
     *
     * @param directory ROM 所在目录
     * @return 本次新增的游戏列表
     */
    fun scanRoms(directory: File): List<GameInfo> {
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        val added = mutableListOf<GameInfo>()
        directory.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val platform = Platform.fromPath(file.absolutePath) ?: return@forEach
                val id = generateId(file.absolutePath)
                // 已存在则跳过
                if (games.containsKey(id)) return@forEach
                val info = GameInfo(
                    id = id,
                    name = file.nameWithoutExtension,
                    platform = platform,
                    romPath = file.absolutePath,
                    fileSize = file.length()
                )
                games[id] = info
                added.add(info)
            }
        if (added.isNotEmpty()) persistGames()
        return added
    }

    /** 保存或更新单个游戏信息 */
    fun saveGame(info: GameInfo) {
        games[info.id] = info
        persistGames()
    }

    /** 批量保存游戏信息 */
    fun saveGames(list: List<GameInfo>) {
        list.forEach { games[it.id] = it }
        persistGames()
    }

    /** 根据 ID 获取游戏信息 */
    fun getGame(id: Long): GameInfo? = games[id]

    /** 获取全部游戏 */
    fun getAllGames(): List<GameInfo> = games.values.toList()

    /** 按平台获取游戏 */
    fun getGamesByPlatform(platform: Platform): List<GameInfo> =
        games.values.filter { it.platform == platform }

    /** 删除游戏记录（不删除 ROM 文件） */
    fun deleteGame(id: Long) {
        games.remove(id)
        persistGames()
    }

    /** 删除游戏记录及其本地 ROM 文件 */
    fun deleteGameWithFile(id: Long) {
        val info = games.remove(id)
        info?.let {
            try {
                File(it.romPath).delete()
            } catch (_: Exception) {
            }
            it.coverPath?.let { path -> runCatching { File(path).delete() } }
        }
        persistGames()
    }

    /** 更新上次游玩时间 */
    fun updateLastPlayed(id: Long) {
        val info = games[id] ?: return
        games[id] = info.copy(lastPlayed = System.currentTimeMillis())
        persistGames()
    }

    /** 游玩次数 +1，并刷新上次游玩时间 */
    fun incrementPlayCount(id: Long) {
        val info = games[id] ?: return
        games[id] = info.copy(
            playCount = info.playCount + 1,
            lastPlayed = System.currentTimeMillis()
        )
        persistGames()
    }

    /** 设置存档路径 */
    fun setSaveStatePath(id: Long, path: String) {
        val info = games[id] ?: return
        games[id] = info.copy(saveStatePath = path)
        persistGames()
    }

    /** 按名称搜索 */
    fun searchByName(keyword: String): List<GameInfo> {
        val kw = keyword.trim().lowercase()
        if (kw.isEmpty()) return getAllGames()
        return games.values.filter { it.name.lowercase().contains(kw) }
    }

    /** 获取最近游玩的游戏（按时间倒序） */
    fun getRecentlyPlayed(limit: Int = 10): List<GameInfo> =
        games.values.filter { it.lastPlayed > 0 }
            .sortedByDescending { it.lastPlayed }
            .take(limit)

    // ===== 持久化 =====

    private fun loadGames(): MutableMap<Long, GameInfo> {
        val json = prefs.getString(KEY_GAMES, null) ?: return mutableMapOf()
        return try {
            gson.fromJson<MutableMap<Long, GameInfo>>(json, gamesMapType) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun persistGames() {
        prefs.edit().putString(KEY_GAMES, gson.toJson(games)).apply()
    }

    /** 由路径生成稳定的 ID（取绝对路径的 hashCode 的无符号值） */
    private fun generateId(path: String): Long {
        return path.hashCode().toLong() and 0xFFFFFFFFL
    }

    companion object {
        private const val PREFS_NAME = "game_library"
        private const val KEY_GAMES = "games"
    }
}
