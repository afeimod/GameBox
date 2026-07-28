package com.retrobox.data

/**
 * 游戏平台枚举
 *
 * @property displayName 平台显示名
 * @property extensions 该平台支持的 ROM 扩展名（不含点号，小写）
 */
enum class Platform(val displayName: String, val extensions: List<String>) {
    NES("NES", listOf("nes", "fds", "unf", "nez")),
    SNES("SNES", listOf("smc", "sfc", "fig", "bs")),
    GENESIS("Genesis", listOf("md", "gen", "smd", "bin")),
    ARCADE("Arcade", listOf("zip", "7z"));

    companion object {
        /** 由文件扩展名推断平台 */
        fun fromExtension(ext: String): Platform? {
            val key = ext.lowercase().trim()
            return values().firstOrNull { it.extensions.contains(key) }
        }

        /** 由文件路径推断平台 */
        fun fromPath(path: String): Platform? {
            val ext = path.substringAfterLast('.', "")
            return fromExtension(ext)
        }
    }
}

/**
 * 游戏信息数据类
 *
 * @property id            游戏唯一 ID
 * @property name          游戏名称
 * @property platform      所属平台
 * @property romPath       本地 ROM 文件路径
 * @property coverPath     本地封面图路径
 * @property lastPlayed    上次游玩时间戳（毫秒）
 * @property playCount     游玩次数
 * @property saveStatePath 存档文件路径
 * @property fileSize      ROM 文件大小（字节）
 * @property downloadUrl   下载地址
 * @property coverUrl      封面图远程地址
 */
data class GameInfo(
    val id: Long,
    val name: String,
    val platform: Platform,
    val romPath: String,
    val coverPath: String? = null,
    val lastPlayed: Long = 0L,
    val playCount: Int = 0,
    val saveStatePath: String? = null,
    val fileSize: Long = 0L,
    val downloadUrl: String? = null,
    val coverUrl: String? = null
) {
    /** 是否曾游玩过 */
    val hasPlayed: Boolean get() = playCount > 0
}
