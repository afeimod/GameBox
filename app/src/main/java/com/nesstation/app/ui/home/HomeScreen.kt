package com.nesstation.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.ui.fsd.FsdHomeScreen

/**
 * 主页 — Xbox 360 FSD（Freestyle Dash）桌面风格磁贴主菜单。
 *
 * 完整的视觉实现位于 [FsdHomeScreen]（ui/fsd 包），手机与 TV 共用：
 *   - 顶部系统状态条（CPU/内存/存储/时钟，真实数据）
 *   - 蓝/黄对角磁贴封面流：全部游戏 → 各游戏平台（带数量徽标）→
 *     在线游戏 / 对战平台 / SWF / 设置 / 关于 / 退出
 *   - 「N of M」计数、A/B 按键提示、底部 IP/日期/时间状态条
 *   - D-pad 左右切换磁贴、OK 激活（TV 遥控器友好）
 *
 * 选择平台磁贴直接进入按该平台过滤的游戏库封面流（[onOpenPlatform]）。
 */
@Composable
fun HomeScreen(
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
    FsdHomeScreen(
        games = games,
        onOpenLibrary = onOpenLibrary,
        onOpenPlatform = onOpenPlatform,
        onOpenOnlineGames = onOpenOnlineGames,
        onOpenBattle = onOpenBattle,
        onOpenSwf = onOpenSwf,
        onOpenSettings = onOpenSettings,
        onOpenAbout = onOpenAbout,
        onExit = onExit,
        modifier = modifier
    )
}
