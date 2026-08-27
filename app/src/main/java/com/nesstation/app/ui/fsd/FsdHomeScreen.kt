package com.nesstation.app.ui.fsd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform

/**
 * FSD 桌面主页 — 仿 Xbox 360 Freestyle Dash 的磁贴主菜单。
 *
 * 结构（对照用户提供的 FSD 截图）：
 *   - 顶部系统状态条（CPU/内存/存储/时钟）
 *   - 左上面包屑「主菜单 ▪ 游戏库」
 *   - 蓝/黄对角磁贴封面流：全部游戏 → 各平台 → 功能入口
 *   - 「N of M」计数 + 底部 A/B 按键提示
 *   - 底部状态条（IP / 状态 / 日期 时间）
 *
 * 手机与 TV 共用：磁贴流内建 D-pad 焦点导航（左/右切换、OK 激活），
 * 触屏设备点击磁贴即可。平台磁贴仅在库内有游戏时显示。
 */
@Composable
fun FsdHomeScreen(
    games: List<GameEntry>,
    onOpenLibrary: () -> Unit,
    onOpenPlatform: (GamePlatform?) -> Unit,
    onOpenOnlineGames: () -> Unit,
    onOpenBattle: () -> Unit,
    onOpenSwf: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platformOrder = listOf(
        GamePlatform.NES, GamePlatform.SFC, GamePlatform.GB, GamePlatform.GBA,
        GamePlatform.MD, GamePlatform.PCE, GamePlatform.PSX, GamePlatform.NDS,
        GamePlatform.ARCADE, GamePlatform.DOS, GamePlatform.JAVA
    )
    val countByPlatform = remember(games) {
        games.groupingBy { it.platform }.eachCount()
    }

    val tiles = remember(games, countByPlatform) {
        buildList {
            add(FsdTileItem("all", "全部游戏", Icons.Rounded.GridView, badge = games.size.toString()))
            platformOrder.forEach { p ->
                val n = countByPlatform[p] ?: 0
                if (n > 0) {
                    add(FsdTileItem("platform:${p.name}", p.displayName, Icons.Rounded.VideogameAsset, badge = n.toString()))
                }
            }
            add(FsdTileItem("online", "在线游戏", Icons.Rounded.Public))
            add(FsdTileItem("battle", "对战平台", Icons.Rounded.SportsEsports))
            add(FsdTileItem("swf", "SWF/Flash", Icons.Rounded.PlayArrow))
            add(FsdTileItem("settings", "设置", Icons.Rounded.Settings))
            add(FsdTileItem("about", "关于", Icons.AutoMirrored.Rounded.HelpOutline))
            add(FsdTileItem("exit", "退出", Icons.AutoMirrored.Rounded.Logout))
        }
    }

    var selected by rememberSaveable { mutableIntStateOf(0) }
    // 列表变化（如新导入游戏）后安全收敛索引
    val safeSelected = if (tiles.isEmpty()) 0 else selected.coerceIn(0, tiles.size - 1)

    fun activate(idx: Int) {
        val tile = tiles.getOrNull(idx) ?: return
        when {
            tile.key == "all" -> onOpenLibrary()
            tile.key.startsWith("platform:") ->
                onOpenPlatform(GamePlatform.fromString(tile.key.removePrefix("platform:")))
            tile.key == "online" -> onOpenOnlineGames()
            tile.key == "battle" -> onOpenBattle()
            tile.key == "swf" -> onOpenSwf()
            tile.key == "settings" -> onOpenSettings()
            tile.key == "about" -> onOpenAbout()
            tile.key == "exit" -> onExit()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        FsdBackdrop()

        Column(modifier = Modifier.fillMaxSize()) {
            FsdTopBar()

            Spacer(Modifier.height(10.dp))
            FsdBreadcrumb(listOf("主菜单", "游戏库"))

            FsdTileFlow(
                items = tiles,
                selectedIndex = safeSelected,
                onIndexChange = { selected = it },
                onActivate = ::activate,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // 底部按键提示 + 状态条
            FsdButtonHints(
                hints = listOf(
                    FsdButtonHint("A", "选择", Fsd.BtnA),
                    FsdButtonHint("B", "返回", Fsd.BtnB)
                ),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 8.dp)
            )
            FsdBottomBar(status = "主菜单")
        }
    }
}
