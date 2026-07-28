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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrobox.input.ButtonLayout
import com.retrobox.input.EmulatorPlatform
import com.retrobox.input.GamepadButtonId
import com.retrobox.ui.components.GamepadPreset
import com.retrobox.ui.components.GamepadTheme
import com.retrobox.ui.components.VirtualGamepad
import com.retrobox.ui.theme.CyberBackground
import com.retrobox.ui.theme.CyberSurface
import com.retrobox.ui.theme.NeonCyan
import com.retrobox.ui.theme.NeonPink
import com.retrobox.ui.theme.NeonPurple
import com.retrobox.ui.viewmodel.GameViewModel

/**
 * 手柄设置界面
 *
 * 支持配置：
 * - 手柄主题（4 套预设）
 * - 布局模式（标准/紧凑/自定义）
 * - 按钮尺寸、间距、透明度
 * - 触觉反馈开关
 * - 各平台按键映射
 * - 实时预览
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamepadSettingsScreen(
    viewModel: GameViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config by viewModel.gamepadConfig.collectAsState()
    val preset by viewModel.gamepadPreset.collectAsState()

    var buttonSize by remember(config) { mutableStateOf(config.buttonSizeDp) }
    var dpadSize by remember(config) { mutableStateOf(config.dpadSizeDp) }
    var spacing by remember(config) { mutableStateOf(config.buttonSpacingDp) }
    var opacity by remember(config) { mutableStateOf(config.globalOpacity) }
    var haptic by remember(config) { mutableStateOf(config.hapticEnabled) }
    var selectedPlatform by remember { mutableStateOf(config.currentPlatform) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBackground)
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("手柄设置", color = NeonPurple, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
            },
            actions = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(NeonCyan.copy(alpha = 0.15f))
                        .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .clickable {
                            val newConfig = config.copy(
                                buttonSizeDp = buttonSize,
                                dpadSizeDp = dpadSize,
                                buttonSpacingDp = spacing,
                                globalOpacity = opacity,
                                hapticEnabled = haptic,
                                currentPlatform = selectedPlatform
                            )
                            viewModel.updateGamepadConfig(newConfig)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("保存", color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        )

        // ===== 实时预览 =====
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSurface)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    "实时预览",
                    color = NeonCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberBackground)
                ) {
                    VirtualGamepad(
                        modifier = Modifier.fillMaxSize(),
                        config = config.copy(
                            buttonSizeDp = buttonSize,
                            dpadSizeDp = dpadSize,
                            buttonSpacingDp = spacing,
                            globalOpacity = opacity,
                            hapticEnabled = haptic
                        ),
                        theme = GamepadTheme.fromPreset(preset),
                        visible = true,
                        showMenuBar = false,
                        onButtonPress = {},
                        onButtonRelease = {},
                        onDirectionChange = {}
                    )
                }
            }
        }

        // ===== 主题选择 =====
        SettingsSection("手柄主题") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GamepadPreset.values().forEach { p ->
                    val theme = GamepadTheme.fromPreset(p)
                    val label = when (p) {
                        GamepadPreset.NEON_CYBER -> "霓虹赛博"
                        GamepadPreset.RETRO_GAMING -> "复古游戏"
                        GamepadPreset.MINIMAL_DARK -> "极简暗黑"
                        GamepadPreset.GLOW_PURPLE -> "发光紫色"
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(theme.backgroundColor)
                            .border(
                                width = if (preset == p) 2.dp else 1.dp,
                                color = if (preset == p) theme.primaryColor else Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { viewModel.updateGamepadPreset(p) }
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(theme.buttonAColor))
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(theme.buttonBColor))
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(theme.buttonXColor))
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(theme.buttonYColor))
                            }
                            Text(label, color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }

        // ===== 布局模式 =====
        SettingsSection("布局模式") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ButtonLayout.values().forEach { layout ->
                    val label = when (layout) {
                        ButtonLayout.STANDARD -> "标准"
                        ButtonLayout.COMPACT -> "紧凑"
                        ButtonLayout.CUSTOM -> "自定义"
                    }
                    val isSelected = config.layout == layout
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) NeonPurple.copy(alpha = 0.2f) else CyberBackground)
                            .border(
                                1.dp,
                                if (isSelected) NeonPurple else Color.White.copy(alpha = 0.2f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                val newConfig = config.copy(layout = layout)
                                viewModel.updateGamepadConfig(newConfig)
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (isSelected) NeonPurple else Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // ===== 尺寸调节 =====
        SettingsSection("按钮尺寸") {
            SliderItem(
                label = "动作按钮大小",
                value = buttonSize,
                range = 40f..100f,
                suffix = "dp",
                onValueChange = { buttonSize = it }
            )
            SliderItem(
                label = "方向键大小",
                value = dpadSize,
                range = 120f..280f,
                suffix = "dp",
                onValueChange = { dpadSize = it }
            )
            SliderItem(
                label = "按钮间距",
                value = spacing,
                range = 4f..40f,
                suffix = "dp",
                onValueChange = { spacing = it }
            )
        }

        // ===== 透明度与触觉 =====
        SettingsSection("显示与反馈") {
            SliderItem(
                label = "全局透明度",
                value = opacity,
                range = 0.2f..1f,
                suffix = "",
                onValueChange = { opacity = it }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("触觉反馈（震动）", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                Switch(
                    checked = haptic,
                    onCheckedChange = { haptic = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = NeonPurple,
                        checkedTrackColor = NeonPurple.copy(alpha = 0.3f)
                    )
                )
            }
        }

        // ===== 按键映射 =====
        SettingsSection("按键映射（当前平台）") {
            // 平台选择
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EmulatorPlatform.values().forEach { platform ->
                    val label = platform.displayName.substringBefore(" (")
                    val isSelected = selectedPlatform == platform
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) NeonCyan.copy(alpha = 0.15f) else CyberBackground)
                            .border(
                                1.dp,
                                if (isSelected) NeonCyan else Color.White.copy(alpha = 0.15f),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { selectedPlatform = platform }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (isSelected) NeonCyan else Color.White.copy(alpha = 0.4f),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.1f))

            // 按键映射表
            val mapping = config.keyMappingFor(selectedPlatform)
            val buttonsToShow = listOf(
                GamepadButtonId.DPAD_UP to "上",
                GamepadButtonId.DPAD_DOWN to "下",
                GamepadButtonId.DPAD_LEFT to "左",
                GamepadButtonId.DPAD_RIGHT to "右",
                GamepadButtonId.A to "A",
                GamepadButtonId.B to "B",
                GamepadButtonId.X to "X",
                GamepadButtonId.Y to "Y",
                GamepadButtonId.START to "Start",
                GamepadButtonId.SELECT to "Select"
            ).filter { mapping.keyCodeFor(it.first) > 0 }

            buttonsToShow.forEach { (button, label) ->
                val code = mapping.keyCodeFor(button)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Text(
                        "KeyCode: $code",
                        color = NeonPurple.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(NeonPurple.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                "提示：按键映射遵循 Android KeyEvent 码值，可在自定义核心中重新配置。",
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Box(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = NeonCyan, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Divider(modifier = Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.1f))
            content()
        }
    }
}

@Composable
private fun SliderItem(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Text(
                "${value.toInt()}$suffix",
                color = NeonPurple,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = NeonPurple,
                activeTrackColor = NeonPurple
            )
        )
    }
}
