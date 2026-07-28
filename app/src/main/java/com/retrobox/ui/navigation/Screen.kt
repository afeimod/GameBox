package com.retrobox.ui.navigation

/**
 * 导航路由定义
 */
sealed class Screen(val route: String) {

    /** 游戏库 */
    data object Library : Screen("library")

    /** 在线下载 */
    data object Download : Screen("download")

    /** 设置 */
    data object Settings : Screen("settings")

    /** 模拟器游戏界面 */
    data object Emulator : Screen("emulator/{gameId}") {
        const val ARG_GAME_ID = "gameId"
        fun createRoute(gameId: Long): String = "emulator/$gameId"
    }

    /** 按键设置 */
    data object GamepadSettings : Screen("gamepad_settings")
}
