package com.retrobox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import com.retrobox.input.ButtonLayout
import com.retrobox.input.GamepadButtonId
import com.retrobox.input.GamepadConfig

/**
 * 虚拟手柄主容器，整合方向键、动作键、肩键与系统键。
 *
 * 布局结构（使用 Box + Alignment）：
 * ```
 * ┌────────────────────────────────────┐
 * │        [L2][L1]    [R1][R2]        │  顶部肩键
 * │                                    │
 * │   ┌─┐                    Y         │
 * │   │D│                  X   B        │  DPad(左) + 动作键(右)
 * │   └─┘                    A         │
 * │          [≡ Select] [▶ Start]      │  中部系统键
 * └────────────────────────────────────┘
 * │ [显示/隐藏]  [透明度 ━━●━━] [布局]  │  底部菜单栏
 * └────────────────────────────────────┘
 * ```
 *
 * 布局参数全部由 [GamepadConfig] 驱动，支持标准/紧凑/自定义三种模式切换。
 * 透明度可通过底部菜单栏的滑块实时调节。
 *
 * @param modifier        外部修饰符。
 * @param config          手柄配置（尺寸/间距/透明度/布局模式等）。
 * @param theme           主题配置（颜色/发光强度等）。
 * @param visible         手柄是否可见（由底部菜单栏切换）。
 * @param onVisibleChange 可见性变化回调。
 * @param onButtonPress   任意按钮按下回调（携带 [GamepadButtonId]）。
 * @param onButtonRelease 任意按钮释放回调。
 * @param onDirectionChange 方向键方向变化回调。
 * @param showMenuBar     是否显示底部菜单栏。
 */
@Composable
fun VirtualGamepad(
    modifier: Modifier = Modifier,
    config: GamepadConfig = GamepadConfig.default(),
    theme: GamepadTheme = GamepadTheme.Default,
    visible: Boolean = true,
    onVisibleChange: (Boolean) -> Unit = {},
    onButtonPress: (GamepadButtonId) -> Unit = {},
    onButtonRelease: (GamepadButtonId) -> Unit = {},
    onDirectionChange: (DPadDirection) -> Unit = {},
    showMenuBar: Boolean = true
) {
    var internalVisible by remember { mutableStateOf(visible) }
    var internalOpacity by remember { mutableStateOf(config.globalOpacity) }
    var internalLayout by remember { mutableStateOf(config.layout) }

    val dpadSize = config.dpadSizeDp.dp
    val actionButtonSize = config.buttonSizeDp.dp
    val spacing = config.resolvedSpacing().dp
    val shoulderWidth = config.shoulderWidthDp.dp
    val shoulderHeight = config.shoulderHeightDp.dp

    Column(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        theme.backgroundColor.copy(alpha = theme.backgroundAlpha),
                        theme.backgroundColor.copy(alpha = theme.backgroundAlpha * 0.6f)
                    )
                )
            )
    ) {
        // ---- 手柄主体区域 ----
        AnimatedVisibility(
            visible = internalVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(internalOpacity)
            ) {
                // 顶部肩键
                ShoulderButtons(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    buttonWidth = shoulderWidth,
                    buttonHeight = shoulderHeight,
                    spacing = spacing * 0.5f,
                    centerGap = 120.dp,
                    theme = theme,
                    hapticEnabled = config.hapticEnabled,
                    onButtonPress = onButtonPress,
                    onButtonRelease = onButtonRelease
                )

                // 左侧方向键
                DPad(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp, bottom = 24.dp),
                    size = dpadSize,
                    color = theme.primaryColor,
                    accentColor = theme.accentColor,
                    glowIntensity = theme.glowIntensity,
                    hapticEnabled = config.hapticEnabled,
                    onDirectionChange = onDirectionChange
                )

                // 右侧动作键
                ActionButtons(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 24.dp),
                    buttonSize = actionButtonSize,
                    spacing = spacing,
                    theme = theme,
                    hapticEnabled = config.hapticEnabled,
                    onButtonPress = onButtonPress,
                    onButtonRelease = onButtonRelease
                )

                // 中部系统键（Select / Start）
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SystemButton(
                        label = "≡",
                        color = theme.accentColor,
                        glowIntensity = theme.glowIntensity,
                        hapticEnabled = config.hapticEnabled,
                        onPress = { onButtonPress(GamepadButtonId.SELECT) },
                        onRelease = { onButtonRelease(GamepadButtonId.SELECT) }
                    )
                    SystemButton(
                        label = "▶",
                        color = theme.primaryColor,
                        glowIntensity = theme.glowIntensity,
                        hapticEnabled = config.hapticEnabled,
                        onPress = { onButtonPress(GamepadButtonId.START) },
                        onRelease = { onButtonRelease(GamepadButtonId.START) }
                    )
                }
            }
        }

        // ---- 底部菜单栏 ----
        if (showMenuBar) {
            GamepadMenuBar(
                visible = internalVisible,
                onVisibleChange = { internalVisible = it; onVisibleChange(it) },
                opacity = internalOpacity,
                onOpacityChange = { internalOpacity = it },
                layout = internalLayout,
                onLayoutChange = { internalLayout = it },
                theme = theme
            )
        }
    }
}

/**
 * 系统键（Start/Select）—— 小型圆角药丸按钮。
 *
 * 使用 [GamepadButton]（圆角方形）实现，标签使用符号以适应小尺寸。
 */
@Composable
private fun SystemButton(
    label: String,
    color: Color,
    glowIntensity: Float,
    hapticEnabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    GamepadButton(
        size = 52.dp,
        shape = GamepadButtonShape.ROUNDED_SQUARE,
        color = color,
        label = label,
        labelColor = Color.White,
        glowIntensity = glowIntensity,
        hapticEnabled = hapticEnabled,
        onPress = onPress,
        onRelease = onRelease
    )
}

/**
 * 底部菜单栏：显示/隐藏切换、透明度滑块、布局切换。
 */
@Composable
private fun GamepadMenuBar(
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    layout: ButtonLayout,
    onLayoutChange: (ButtonLayout) -> Unit,
    theme: GamepadTheme
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.backgroundColor.copy(alpha = 0.8f))
            .border(
                width = 1.dp,
                color = theme.primaryColor.copy(alpha = 0.3f),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 显示/隐藏切换
        MenuButton(
            text = if (visible) "隐藏手柄" else "显示手柄",
            color = theme.accentColor,
            onClick = { onVisibleChange(!visible) }
        )

        // 透明度滑块
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "透明度",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
            Slider(
                value = opacity,
                onValueChange = onOpacityChange,
                valueRange = 0.2f..1f,
                modifier = Modifier.width(100.dp)
            )
        }

        // 布局切换
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ButtonLayout.values().forEach { bl ->
                MenuButton(
                    text = when (bl) {
                        ButtonLayout.STANDARD -> "标准"
                        ButtonLayout.COMPACT -> "紧凑"
                        ButtonLayout.CUSTOM -> "自定义"
                    },
                    color = if (layout == bl) theme.primaryColor else Color.White.copy(alpha = 0.4f),
                    onClick = { onLayoutChange(bl) }
                )
            }
        }
    }
}

/**
 * 菜单栏中的小型文字按钮。
 */
@Composable
private fun MenuButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
