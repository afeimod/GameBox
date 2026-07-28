package com.retrobox.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.retrobox.input.GamepadButtonId

/**
 * 肩键类型别名，对应 [GamepadButtonId] 中的 L1/R1/L2/R2。
 */
typealias ShoulderButton = GamepadButtonId

/**
 * 肩键的朝向：左侧（L1/L2）或右侧（R1/R2），决定 L 型切角方向。
 */
enum class ShoulderSide {
    LEFT,
    RIGHT
}

/**
 * 单个肩键组件，采用 L 型（带切角）设计，半透明渐变背景 + 发光描边。
 *
 * 按下时缩放 + 发光增强 + 震动反馈，与 [GamepadButton] 视觉风格一致。
 *
 * @param modifier       外部修饰符。
 * @param width          按钮宽度（dp）。
 * @param height         按钮高度（dp）。
 * @param side           朝向（左/右），决定切角方向。
 * @param color          按钮主色。
 * @param label          文字标签。
 * @param glowIntensity  发光强度系数。
 * @param hapticEnabled  是否启用震动。
 * @param onPress        按下回调。
 * @param onRelease      释放回调。
 */
@Composable
fun ShoulderButton(
    modifier: Modifier = Modifier,
    width: Dp = 72.dp,
    height: Dp = 40.dp,
    side: ShoulderSide = ShoulderSide.LEFT,
    color: Color = GamepadTheme.NeonCyan,
    label: String = "L1",
    glowIntensity: Float = 1f,
    hapticEnabled: Boolean = true,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {}
) {
    var isPressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "shoulderScale"
    )
    val animatedGlow by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0.4f * glowIntensity,
        animationSpec = tween(160),
        label = "shoulderGlow"
    )
    val animatedFill by animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 0.35f,
        animationSpec = tween(160),
        label = "shoulderFill"
    )

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .scale(animatedScale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        if (hapticEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onPress()
                        tryAwaitRelease()
                        isPressed = false
                        onRelease()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val widthPx = with(density) { width.toPx() }
        val heightPx = with(density) { height.toPx() }

        Canvas(modifier = Modifier.size(width = width, height = height)) {
            drawShoulderShape(
                widthPx = widthPx,
                heightPx = heightPx,
                side = side,
                color = color,
                glowAlpha = animatedGlow,
                fillAlpha = animatedFill
            )
        }

        Text(
            text = label,
            color = Color.White,
            fontSize = (height.value * 0.4f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = TextStyle(
                shadow = Shadow(
                    color = color.copy(alpha = 0.9f),
                    blurRadius = with(density) { 6.dp.toPx() },
                    offset = Offset.Zero
                )
            )
        )
    }
}

/**
 * 绘制 L 型肩键形状（发光 + 渐变主体 + 高光 + 描边）。
 *
 * 左侧肩键：右上角切角；右侧肩键：左上角切角。
 */
private fun DrawScope.drawShoulderShape(
    widthPx: Float,
    heightPx: Float,
    side: ShoulderSide,
    color: Color,
    glowAlpha: Float,
    fillAlpha: Float
) {
    val cornerCut = heightPx * 0.5f
    val center = Offset(widthPx / 2f, heightPx / 2f)

    val shapePath = when (side) {
        ShoulderSide.LEFT -> Path().apply {
            // 左上角开始，顺时针；右上角带切角
            moveTo(0f, 0f)
            lineTo(widthPx - cornerCut, 0f)
            lineTo(widthPx, cornerCut)
            lineTo(widthPx, heightPx)
            lineTo(0f, heightPx)
            close()
        }
        ShoulderSide.RIGHT -> Path().apply {
            // 右上角开始，顺时针；左上角带切角
            moveTo(widthPx, 0f)
            lineTo(cornerCut, 0f)
            lineTo(0f, cornerCut)
            lineTo(0f, heightPx)
            lineTo(widthPx, heightPx)
            close()
        }
    }

    // ---------- 1. 外发光 ----------
    val glowBrush = Brush.radialGradient(
        colors = listOf(
            color.copy(alpha = glowAlpha * 0.6f),
            color.copy(alpha = glowAlpha * 0.2f),
            Color.Transparent
        ),
        center = center,
        radius = widthPx * 0.75f
    )
    drawPath(shapePath, brush = glowBrush)

    // ---------- 2. 主体渐变填充 ----------
    val bodyBrush = Brush.linearGradient(
        colors = listOf(
            color.copy(alpha = fillAlpha * 0.8f),
            color.copy(alpha = fillAlpha * 0.4f),
            color.copy(alpha = fillAlpha * 0.15f)
        ),
        start = Offset(0f, 0f),
        end = Offset(widthPx, heightPx)
    )
    drawPath(shapePath, brush = bodyBrush)

    // ---------- 3. 顶部高光 ----------
    val highlightBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.3f),
            Color.White.copy(alpha = 0f)
        ),
        start = Offset(center.x, 0f),
        end = Offset(center.x, heightPx * 0.6f)
    )
    drawPath(shapePath, brush = highlightBrush)

    // ---------- 4. 发光描边 ----------
    val borderAlpha = (glowAlpha * 0.8f + 0.3f).coerceAtMost(1f)
    drawPath(
        path = shapePath,
        color = color.copy(alpha = borderAlpha),
        style = Stroke(width = 2f)
    )
}

/**
 * 四个肩键（L1/R1/L2/R2）的水平排列组合。
 *
 * 布局如下，左右两组分别靠两端，中间留空：
 * ```
 * [L2] [L1]        [R1] [R2]
 * ```
 * L2/R2 在外侧（模拟后扳机），L1/R1 在内侧（模拟前肩键）。
 *
 * @param modifier       外部修饰符。
 * @param buttonWidth    单个肩键宽度（dp）。
 * @param buttonHeight   单个肩键高度（dp）。
 * @param spacing        同侧两键间距（dp）。
 * @param centerGap      左右两组之间的间距（dp）。
 * @param theme          主题配置。
 * @param hapticEnabled  是否启用震动。
 * @param onButtonPress  按下回调。
 * @param onButtonRelease 释放回调。
 */
@Composable
fun ShoulderButtons(
    modifier: Modifier = Modifier,
    buttonWidth: Dp = 72.dp,
    buttonHeight: Dp = 40.dp,
    spacing: Dp = 8.dp,
    centerGap: Dp = 80.dp,
    theme: GamepadTheme = GamepadTheme.Default,
    hapticEnabled: Boolean = true,
    onButtonPress: (ShoulderButton) -> Unit = {},
    onButtonRelease: (ShoulderButton) -> Unit = {}
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：L2（外）+ L1（内）
        ShoulderButton(
            width = buttonWidth,
            height = buttonHeight,
            side = ShoulderSide.LEFT,
            color = theme.shoulderColor,
            label = "L2",
            glowIntensity = theme.glowIntensity,
            hapticEnabled = hapticEnabled,
            onPress = { onButtonPress(ShoulderButton.L2) },
            onRelease = { onButtonRelease(ShoulderButton.L2) }
        )
        Spacer(modifier = Modifier.width(spacing))
        ShoulderButton(
            width = buttonWidth,
            height = buttonHeight,
            side = ShoulderSide.LEFT,
            color = theme.shoulderColor,
            label = "L1",
            glowIntensity = theme.glowIntensity,
            hapticEnabled = hapticEnabled,
            onPress = { onButtonPress(ShoulderButton.L1) },
            onRelease = { onButtonRelease(ShoulderButton.L1) }
        )

        // 中间间距
        Spacer(modifier = Modifier.width(centerGap))

        // 右侧：R1（内）+ R2（外）
        ShoulderButton(
            width = buttonWidth,
            height = buttonHeight,
            side = ShoulderSide.RIGHT,
            color = theme.shoulderColor,
            label = "R1",
            glowIntensity = theme.glowIntensity,
            hapticEnabled = hapticEnabled,
            onPress = { onButtonPress(ShoulderButton.R1) },
            onRelease = { onButtonRelease(ShoulderButton.R1) }
        )
        Spacer(modifier = Modifier.width(spacing))
        ShoulderButton(
            width = buttonWidth,
            height = buttonHeight,
            side = ShoulderSide.RIGHT,
            color = theme.shoulderColor,
            label = "R2",
            glowIntensity = theme.glowIntensity,
            hapticEnabled = hapticEnabled,
            onPress = { onButtonPress(ShoulderButton.R2) },
            onRelease = { onButtonRelease(ShoulderButton.R2) }
        )
    }
}
