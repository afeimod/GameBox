package com.retrobox.download

import org.json.JSONArray
import org.json.JSONObject

/**
 * 游戏平台分类（下载列表使用）
 */
enum class GamePlatform(val display: String) {
    FC("FC"),
    SFC("SFC"),
    MD("MD"),
    ARCADE("ARCADE");

    companion object {
        /** 由字符串解析平台，兼容常见别名 */
        fun fromString(value: String?): GamePlatform {
            return when (value?.uppercase()) {
                "FC", "NES" -> FC
                "SFC", "SNES" -> SFC
                "MD", "GENESIS", "MEGADRIVE" -> MD
                "ARCADE", "MAME" -> ARCADE
                null, "" -> FC
                else -> FC
            }
        }
    }
}

/**
 * 下载游戏信息
 *
 * @property name        游戏名称
 * @property platform    所属平台
 * @property fileSize    文件大小（字节）
 * @property downloadUrl 下载地址
 * @property coverUrl    封面图地址
 * @property description 游戏描述
 * @property romUrl      仓库内 ROM 路径
 */
data class GameDownloadInfo(
    val name: String,
    val platform: GamePlatform,
    val fileSize: Long,
    val downloadUrl: String,
    val coverUrl: String,
    val description: String,
    val romUrl: String
)

/**
 * 游戏列表 JSON 解析器
 *
 * 支持的 JSON 数组结构示例：
 * ```json
 * [
 *   {
 *     "name": "超级马里奥",
 *     "platform": "FC",
 *     "fileSize": 40960,
 *     "downloadUrl": "https://gitee.com/xxx/raw/master/fc/mario.nes",
 *     "coverUrl": "https://gitee.com/xxx/raw/master/fc/mario.png",
 *     "description": "经典横版过关游戏",
 *     "romUrl": "fc/mario.nes"
 *   }
 * ]
 * ```
 */
object GameListParser {

    /**
     * 解析游戏列表 JSON 数组
     * @param json JSON 字符串
     * @return 游戏信息列表
     */
    fun parseList(json: String): List<GameDownloadInfo> {
        val list = mutableListOf<GameDownloadInfo>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseSingle(obj)?.let { list.add(it) }
        }
        return list
    }

    /**
     * 解析单个游戏信息 JSON 字符串
     */
    fun parseSingle(json: String): GameDownloadInfo? {
        return try {
            parseSingle(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析单个游戏信息 JSON 对象
     */
    fun parseSingle(obj: JSONObject): GameDownloadInfo? {
        return try {
            GameDownloadInfo(
                name = obj.optString("name"),
                platform = GamePlatform.fromString(obj.optString("platform")),
                fileSize = obj.optLong("fileSize"),
                downloadUrl = obj.optString("downloadUrl"),
                coverUrl = obj.optString("coverUrl"),
                description = obj.optString("description"),
                romUrl = obj.optString("romUrl")
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将游戏列表序列化为 JSON 数组字符串
     */
    fun toJsonList(list: List<GameDownloadInfo>): String {
        val arr = JSONArray()
        for (g in list) {
            arr.put(toJsonObject(g))
        }
        return arr.toString()
    }

    /** 将单个游戏信息序列化为 JSON 对象 */
    fun toJsonObject(g: GameDownloadInfo): JSONObject {
        return JSONObject().apply {
            put("name", g.name)
            put("platform", g.platform.display)
            put("fileSize", g.fileSize)
            put("downloadUrl", g.downloadUrl)
            put("coverUrl", g.coverUrl)
            put("description", g.description)
            put("romUrl", g.romUrl)
        }
    }

    /**
     * 按平台对游戏列表分组
     */
    fun groupByPlatform(list: List<GameDownloadInfo>): Map<GamePlatform, List<GameDownloadInfo>> {
        return list.groupBy { it.platform }
    }

    /**
     * 在列表中按名称关键字搜索
     */
    fun search(list: List<GameDownloadInfo>, keyword: String): List<GameDownloadInfo> {
        val kw = keyword.trim().lowercase()
        if (kw.isEmpty()) return list
        return list.filter { it.name.lowercase().contains(kw) }
    }
}
