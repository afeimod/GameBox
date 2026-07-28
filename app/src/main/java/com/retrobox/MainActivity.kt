package com.retrobox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.retrobox.ui.theme.NeonCyan
import com.retrobox.ui.theme.NeonPurple
import com.retrobox.ui.theme.CyberBackground
import com.retrobox.ui.theme.RetroBoxTheme
import com.retrobox.ui.viewmodel.GameViewModel

/**
 * 主 Activity —— 应用入口，承载导航与全局主题。
 *
 * 启动时检查存储权限：
 * - Android 11+ (API 30+): 检查 MANAGE_EXTERNAL_STORAGE（所有文件访问权限）
 * - Android 10 及以下: 检查传统 READ/WRITE_EXTERNAL_STORAGE
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

    /**
     * 检查是否拥有存储权限
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 跳转到系统设置页面请求所有文件访问权限
     */
    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    @Composable
    private fun AppContent() {
        var hasPermission by remember { mutableStateOf(hasStoragePermission()) }

        // 传统权限请求 Launcher（Android 10 及以下）
        val legacyPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result.values.any { it }
            if (granted) {
                hasPermission = true
            }
        }

        // Activity 恢复时重新检查权限状态（从系统设置返回时触发）
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasPermission = hasStoragePermission()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        if (!hasPermission) {
            PermissionScreen(
                onRequestPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        requestManageStoragePermission()
                    } else {
                        legacyPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }
                },
                onSkip = {
                    hasPermission = true
                }
            )
            return
        }

        // 权限已授予，进入主界面
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

    /**
     * 权限请求引导界面
     */
    @Composable
    private fun PermissionScreen(
        onRequestPermission: () -> Unit,
        onSkip: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBackground)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = NeonPurple,
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "需要存储权限",
                color = NeonPurple,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "RetroBox 需要所有文件访问权限，以便导入和管理本地 ROM 游戏文件。\n\n" +
                        "授权后可以直接读取手机存储中的游戏文件，导入到游戏库。\n\n" +
                        "如果拒绝，仍可使用应用专属目录，但无法直接访问 /sdcard/ 下的文件。",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonPurple,
                    contentColor = Color.White
                )
            ) {
                Text("授予存储权限", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = NeonCyan
                )
            ) {
                Text("跳过（使用应用目录）", fontSize = 14.sp)
            }
        }
    }
}
