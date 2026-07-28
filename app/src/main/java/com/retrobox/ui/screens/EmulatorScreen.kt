package com.retrobox.ui.screens

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.retrobox.emulator.EmulatorThread
import com.retrobox.input.GamepadButtonId
import com.retrobox.ui.components.DPadDirection
import com.retrobox.ui.components.GamepadOverlay
import com.retrobox.ui.components.rememberGamepadOverlayState
import com.retrobox.ui.theme.CyberBackground
import com.retrobox.ui.theme.NeonCyan
import com.retrobox.ui.theme.NeonPink
import com.retrobox.ui.theme.NeonPurple
import com.retrobox.ui.viewmodel.GameViewModel

/**
 * 模拟器游戏界面
 *
 * 上半部分为游戏画面（SurfaceView），下半部分为虚拟手柄覆盖层。
 * 支持暂停/恢复、存档/读档、退出。
 */
@Composable
fun EmulatorScreen(
    viewModel: GameViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gamepadConfig by viewModel.gamepadConfig.collectAsState()
    val emulatorStatus by viewModel.emulatorStatus.collectAsState()
    val overlayState = rememberGamepadOverlayState()
    var showMenu by remember { mutableStateOf(false) }
    var emulatorThread by remember { mutableStateOf<EmulatorThread?>(null) }
    val core = remember { viewModel.getActiveCore() }

    // 核心 -> 按键码映射辅助函数
    val keyCodeFor: (GamepadButtonId) -> Int = remember(gamepadConfig) {
        { buttonId ->
            gamepadConfig.currentKeyMapping().keyCodeFor(buttonId)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopEmulator()
        }
    }

    Box(
        modifier = modifier
            .background(CyberBackground)
    ) {
        // 游戏画面 —— SurfaceView
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (core != null) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            val thread = EmulatorThread(core)
                            thread.setSurface(holder.surface)
                            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                    thread.setSurface(holder.surface)
                                    thread.startEmulator()
                                }

                                override fun surfaceChanged(
                                    holder: android.view.SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    thread.setSurface(holder.surface)
                                }

                                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                    thread.pauseEmulator()
                                }
                            })
                            emulatorThread = thread
                            viewModel.setEmulatorThread(thread)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 没有活跃核心时显示提示
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "无法加载游戏核心",
                        color = NeonPink,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = emulatorStatus,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // 虚拟手柄覆盖层
            if (core != null) {
                GamepadOverlay(
                    modifier = Modifier.fillMaxSize(),
                    config = gamepadConfig,
                    theme = viewModel.gamepadTheme,
                    state = overlayState,
                    onButtonPress = { buttonId ->
                        val code = keyCodeFor(buttonId)
                        if (code > 0) core.setButtonState(code, true)
                    },
                    onButtonRelease = { buttonId ->
                        val code = keyCodeFor(buttonId)
                        if (code > 0) core.setButtonState(code, false)
                    },
                    onDirectionChange = { direction ->
                        // 释放所有方向键
                        val upCode = keyCodeFor(GamepadButtonId.DPAD_UP)
                        val downCode = keyCodeFor(GamepadButtonId.DPAD_DOWN)
                        val leftCode = keyCodeFor(GamepadButtonId.DPAD_LEFT)
                        val rightCode = keyCodeFor(GamepadButtonId.DPAD_RIGHT)
                        if (upCode > 0) core.setButtonState(upCode, false)
                        if (downCode > 0) core.setButtonState(downCode, false)
                        if (leftCode > 0) core.setButtonState(leftCode, false)
                        if (rightCode > 0) core.setButtonState(rightCode, false)

                        // 按下当前方向
                        when (direction) {
                            DPadDirection.UP -> if (upCode > 0) core.setButtonState(upCode, true)
                            DPadDirection.DOWN -> if (downCode > 0) core.setButtonState(downCode, true)
                            DPadDirection.LEFT -> if (leftCode > 0) core.setButtonState(leftCode, true)
                            DPadDirection.RIGHT -> if (rightCode > 0) core.setButtonState(rightCode, true)
                            DPadDirection.UP_LEFT -> {
                                if (upCode > 0) core.setButtonState(upCode, true)
                                if (leftCode > 0) core.setButtonState(leftCode, true)
                            }
                            DPadDirection.UP_RIGHT -> {
                                if (upCode > 0) core.setButtonState(upCode, true)
                                if (rightCode > 0) core.setButtonState(rightCode, true)
                            }
                            DPadDirection.DOWN_LEFT -> {
                                if (downCode > 0) core.setButtonState(downCode, true)
                                if (leftCode > 0) core.setButtonState(leftCode, true)
                            }
                            DPadDirection.DOWN_RIGHT -> {
                                if (downCode > 0) core.setButtonState(downCode, true)
                                if (rightCode > 0) core.setButtonState(rightCode, true)
                            }
                            DPadDirection.NONE -> { /* 全部已释放 */ }
                        }
                    }
                )
            }

            // 顶部菜单栏（点击屏幕上半部分空白区域切换显示）
            if (showMenu) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberBackground.copy(alpha = 0.9f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.Close, contentDescription = "退出", tint = NeonPink)
                    }

                    Text(
                        text = emulatorStatus,
                        color = NeonCyan,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Row {
                        // 暂停/恢复
                        IconButton(onClick = {
                            if (emulatorStatus == "运行中") {
                                viewModel.pauseEmulator()
                            } else if (emulatorStatus == "已暂停") {
                                viewModel.resumeEmulator()
                            }
                        }) {
                            Icon(
                                if (emulatorStatus == "运行中") Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "暂停/恢复",
                                tint = NeonCyan
                            )
                        }

                        // 存档
                        IconButton(onClick = { viewModel.saveState(1) }) {
                            Icon(Icons.Default.Save, contentDescription = "存档", tint = NeonPurple)
                        }

                        // 读档
                        IconButton(onClick = { viewModel.loadState(1) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "读档", tint = NeonPurple)
                        }
                    }
                }
            }
        }

        // 底部提示：点击切换菜单
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CyberBackground.copy(alpha = 0.6f))
                .border(1.dp, NeonPurple.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .clickable { showMenu = !showMenu }
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (showMenu) "收起菜单" else "点击显示菜单",
                color = NeonPurple.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
    }
}
