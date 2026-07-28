package com.retrobox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.retrobox.input.GamepadButtonId

/**
 * 动作按钮的类型别名，对应 [GamepadButtonId] 中的 A/B/X/Y。
 */
typealias ActionButton = GamepadButtonId

/**
 * 四个动作按钮（A/B/X/Y）的菱形排列组合。
 *
 * 布局如下（标准 Xbox/Switch 风格）：
 * ```
 *       Y
 *     X   B
 *       A
 * ```
 * 每个按钮使用 [GamepadButton]（圆形），颜色来自 [GamepadTheme]。
 * 按下时由 [GamepadButton] 自身处理缩放动画与发光增强。
 *
 * @param modifier       外部修饰符。
 * @param buttonSize     单个按钮尺寸（dp）。
 * @param spacing        按钮之间的间距（dp），影响菱形整体大小。
 * @param theme          主题配置，提供各按钮颜色与发光强度。
 * @param hapticEnabled  是否启用震动反馈。
 * @param onButtonPress  按钮按下回调。
 * @param onButtonRelease 按钮释放回调。
 */
@Composable
fun ActionButtons(
    modifier: Modifier = Modifier,
    buttonSize: Dp = 64.dp,
    spacing: Dp = 12.dp,
    theme: GamepadTheme = GamepadTheme.Default,
    hapticEnabled: Boolean = true,
    onButtonPress: (ActionButton) -> Unit = {},
    onButtonRelease: (ActionButton) -> Unit = {}
) {
    // 菱形容器尺寸：对角线上两个按钮中心距 = buttonSize + spacing
    // 容器需容纳按钮半径 + 间距 + 按钮半径
    val containerSize = buttonSize * 2 + spacing

    Box(
        modifier = modifier.size(containerSize),
        contentAlignment = Alignment.Center
    ) {
        // Y — 顶部（紫色）
        GamepadButton(
            modifier = Modifier.align(Alignment.TopCenter),
            size = buttonSize,
            shape = GamepadButtonShape.CIRCLE,
            color = theme.buttonYColor,
            label = "Y",
            labelColor = theme.labelColor,
            glowIntensity = theme.glowIntensity,
            hapticEnabled = hapticEnabled,
            onPress = { onButtonPress(ActionButton.Y) },
            onRelease = { onButtonRelease(ActionButton.Y) }
        )

        // A — 底部（粉红）
        GamepadButton(
            modifier = Modifier.align(Alignment.BottomCenter),
            size = buttonSize,
            shape = GamepadButtonShape.CIRCLE,
            color = theme.buttonAColor,
            label = "A",
            labelColor = theme.labelColor,
            glowIntensity = theme.glowIntensity,
            hapticEnabled = hapticEnabled,
            onPress = { onButtonPress(ActionButton.A) },
            onRelease = { onButtonRelease(ActionButton.A) }
        )

        // X — 左侧（蓝色）
        GamepadButton(
            modifier = Modifier.align(Alignment.CenterStart),
            size = buttonSize,
            shape = GamepadButtonShape.CIRCLE,
            color = theme.buttonXColor,
            label = "X",
            labelColor = theme.labelColor,
            glowIntensity = theme.glowIntensity,
            hapticEnabled = hapticEnabled,
            onPress = { onButtonPress(ActionButton.X) },
            onRelease = { onButtonRelease(ActionButton.X) }
        )

        // B — 右侧（青色）
        GamepadButton(
            modifier = Modifier.align(Alignment.CenterEnd),
            size = buttonSize,
            shape = GamepadButtonShape.CIRCLE,
            color = theme.buttonBColor,
            label = "B",
            labelColor = theme.labelColor,
            glowIntensity = theme.glowIntensity,
            hapticEnabled = hapticEnabled,
            onPress = { onButtonPress(ActionButton.B) },
            onRelease = { onButtonRelease(ActionButton.B) }
        )
    }
}

/**
 * 动作按钮的默认颜色映射，便于外部单独引用。
 */
val ActionButton.defaultColor: Color
    get() = when (this) {
        ActionButton.A -> GamepadTheme.NeonPink
        ActionButton.B -> GamepadTheme.NeonCyan
        ActionButton.X -> GamepadTheme.NeonBlue
        ActionButton.Y -> GamepadTheme.NeonPurple
        else -> GamepadTheme.NeonPurple
    }
