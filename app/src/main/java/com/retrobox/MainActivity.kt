package com.retrobox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.retrobox.ui.navigation.Screen
import com.retrobox.ui.screens.DownloadScreen
import com.retrobox.ui.screens.EmulatorScreen
import com.retrobox.ui.screens.GamepadSettingsScreen
import com.retrobox.ui.screens.LibraryScreen
import com.retrobox.ui.screens.SettingsScreen
import com.retrobox.ui.theme.RetroBoxTheme
import com.retrobox.ui.viewmodel.GameViewModel

/**
 * 主 Activity —— 应用入口，承载导航与全局主题。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RetroBoxTheme {
                AppContent()
            }
        }
    }
}

@Composable
private fun AppContent() {
    val navController = rememberNavController()
    val viewModel: GameViewModel = viewModel()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier.fillMaxSize()
        ) {
            // 游戏库
            composable(Screen.Library.route) {
                LibraryScreen(
                    viewModel = viewModel,
                    onGameClick = { game ->
                        viewModel.startGame(game) {
                            navController.navigate(Screen.Emulator.createRoute(game.id))
                        }
                    },
                    onDownloadClick = { navController.navigate(Screen.Download.route) },
                    onSettingsClick = { navController.navigate(Screen.Settings.route) },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            // 在线下载
            composable(Screen.Download.route) {
                DownloadScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            // 设置
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onGamepadSettings = { navController.navigate(Screen.GamepadSettings.route) },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            // 按键设置
            composable(Screen.GamepadSettings.route) {
                GamepadSettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            // 模拟器游戏界面
            composable(
                route = Screen.Emulator.route,
                arguments = listOf(navArgument(Screen.Emulator.ARG_GAME_ID) { type = NavType.LongType })
            ) {
                EmulatorScreen(
                    viewModel = viewModel,
                    onExit = {
                        viewModel.stopEmulator()
                        navController.popBackStack()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
