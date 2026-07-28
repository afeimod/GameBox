package com.retrobox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrobox.ui.theme.CyberBackground
import com.retrobox.ui.theme.CyberSurface
import com.retrobox.ui.theme.NeonCyan
import com.retrobox.ui.theme.NeonPink
import com.retrobox.ui.theme.NeonPurple
import com.retrobox.ui.viewmodel.GameViewModel

/**
 * 设置界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: GameViewModel,
    onBack: () -> Unit,
    onGamepadSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var volume by remember { mutableStateOf(viewModel.volume) }
    var fps by remember { mutableStateOf(viewModel.targetFps.toFloat()) }
    var keepScreenOn by remember { mutableStateOf(viewModel.keepScreenOn) }
    var showFps by remember { mutableStateOf(viewModel.showFps) }
    var downloadPath by remember { mutableStateOf(viewModel.downloadPath) }

    val giteeConfig = remember { viewModel.getGiteeConfig() }
    var giteeOwner by remember { mutableStateOf(giteeConfig.owner) }
    var giteeRepo by remember { mutableStateOf(giteeConfig.repo) }
    var giteeBranch by remember { mutableStateOf(giteeConfig.branch) }
    var giteeToken by remember { mutableStateOf(giteeConfig.token) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("设置", color = NeonPurple, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
            }
        )

        // ===== 手柄设置入口 =====
        SettingsCard(
            icon = Icons.Default.Gamepad,
            title = "虚拟手柄设置",
            subtitle = "布局、主题、按键映射、透明度"
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(NeonPurple.copy(alpha = 0.15f))
                    .border(1.dp, NeonPurple.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onGamepadSettings)
                    .padding(12.dp)
            ) {
                Text("进入手柄设置 →", color = NeonPurple, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        // ===== Gitee 仓库配置 =====
        SettingsCard(
            icon = Icons.Default.Folder,
            title = "Gitee 仓库配置",
            subtitle = "配置在线下载的游戏仓库"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingTextField(
                    label = "仓库所有者 (Owner)",
                    value = giteeOwner,
                    onValueChange = { giteeOwner = it }
                )
                SettingTextField(
                    label = "仓库名 (Repo)",
                    value = giteeRepo,
                    onValueChange = { giteeRepo = it }
                )
                SettingTextField(
                    label = "分支名 (Branch)",
                    value = giteeBranch,
                    onValueChange = { giteeBranch = it }
                )
                SettingTextField(
                    label = "访问令牌 (Token, 可选)",
                    value = giteeToken,
                    onValueChange = { giteeToken = it }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NeonCyan.copy(alpha = 0.15f))
                        .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .clickable {
                            viewModel.saveGiteeConfig(giteeOwner, giteeRepo, giteeBranch, giteeToken)
                        }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("保存配置", color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // ===== 下载路径 =====
        SettingsCard(
            icon = Icons.Default.Folder,
            title = "ROM 下载路径",
            subtitle = "游戏文件保存位置"
        ) {
            SettingTextField(
                label = "下载路径",
                value = downloadPath,
                onValueChange = {
                    downloadPath = it
                    viewModel.downloadPath = it
                }
            )
        }

        // ===== 运行时设置 =====
        SettingsCard(
            icon = Icons.Default.VolumeUp,
            title = "音量",
            subtitle = "${volume}%"
        ) {
            Slider(
                value = volume.toFloat(),
                onValueChange = {
                    volume = it.toInt()
                    viewModel.volume = volume
                },
                valueRange = 0f..100f,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = NeonPurple,
                    activeTrackColor = NeonPurple
                )
            )
        }

        SettingsCard(
            icon = Icons.Default.Speed,
            title = "目标帧率",
            subtitle = "${fps.toInt()} FPS"
        ) {
            Slider(
                value = fps,
                onValueChange = {
                    fps = it
                    viewModel.targetFps = it.toInt()
                },
                valueRange = 30f..60f,
                steps = 5,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = NeonCyan,
                    activeTrackColor = NeonCyan
                )
            )
        }

        SettingsCard(
            icon = Icons.Default.Lightbulb,
            title = "屏幕常亮",
            subtitle = "游戏运行时保持屏幕开启"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("保持屏幕常亮", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                Switch(
                    checked = keepScreenOn,
                    onCheckedChange = {
                        keepScreenOn = it
                        viewModel.keepScreenOn = it
                    }
                )
            }
        }

        SettingsCard(
            icon = Icons.Default.Speed,
            title = "显示 FPS",
            subtitle = "在游戏画面上显示帧率"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("显示帧率", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                Switch(
                    checked = showFps,
                    onCheckedChange = {
                        showFps = it
                        viewModel.showFps = it
                    }
                )
            }
        }

        // 底部留白
        Box(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(24.dp))
                Column {
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                }
            }
            Divider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = Color.White.copy(alpha = 0.1f)
            )
            content()
        }
    }
}

@Composable
private fun SettingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = CyberBackground,
            unfocusedContainerColor = CyberBackground,
            focusedIndicatorColor = NeonPurple,
            unfocusedIndicatorColor = Color.White.copy(alpha = 0.2f),
            focusedLabelColor = NeonPurple,
            unfocusedLabelColor = Color.White.copy(alpha = 0.4f),
            cursorColor = NeonPurple
        )
    )
}
