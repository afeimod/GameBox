package com.retrobox.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/**
 * 手柄按钮的形状类型。
 *
 * @property CIRCLE        正圆。
 * @property SQUARE        正方形（直角）。
 * @property ROUNDED_SQUARE 圆角方形（适合肩键）。
 */
enum class GamepadButtonShape {
    CIRCLE,
    SQUARE,
    ROUNDED_SQUARE
}

/**
 * 可复用的虚拟手柄按钮组件。
 *
 * 该组件使用 [Canvas] 自绘霓虹发光效果，支持圆形/方形/圆角方形三种形状，
 * 按下时通过 [scale] 缩放动画与发光增强给予清晰的触觉与视觉反馈。
 * 触摸状态通过 [Modifier.pointerInput] + [detectTapGestures] 检测，
 * 并在按下时触发 [HapticFeedback]。
 *
 * 视觉层次（从底到顶）：
 * 1. 外发光光晕（多层径向渐变模拟辉光散射）
 * 2. 按钮主体（径向渐变填充，半透明）
 * 3. 顶部高光（模拟光源照射的立体感）
 * 4. 发光描边
 * 5. 文字标签
 *
 * @param modifier       外部修饰符。
 * @param size           按钮尺寸（宽=高，dp）。
 * @param shape          按钮形状。
 * @param color          按钮主色（发光与填充均基于此色）。
 * @param label          按钮上的文字标签，null 表示不显示。
 * @param labelColor     文字颜色。
 * @param glowIntensity  发光强度系数，0f~1f，叠加在主题之上。
 * @param hapticEnabled  是否启用震动反馈。
 * @param pressedOverride 外部强制按压状态（如父组件需要联动高亮时使用），null 表示仅由触摸控制。
 * @param onPress        按下回调。
 * @param onRelease      释放回调（手指抬起或取消均会触发）。
 */
@Composable
fun GamepadButton(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    shape: GamepadButtonShape = GamepadButtonShape.CIRCLE,
    color: Color = GamepadTheme.NeonPurple,
    label: String? = null,
    labelColor: Color = Color.White,
    glowIntensity: Float = 1f,
    hapticEnabled: Boolean = true,
    pressedOverride: Boolean? = null,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {}
) {
    var touchPressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val isPressed = pressedOverride ?: touchPressed

    // 按下时的缩放动画
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale"
    )

    // 发光透明度动画
    val animatedGlow by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0.45f * glowIntensity,
        animationSpec = tween(durationMillis = 160),
        label = "buttonGlow"
    )

    // 内部填充透明度动画
    val animatedFill by animateFloatAsState(
        targetValue = if (isPressed) 0.75f else 0.4f,
        animationSpec = tween(durationMillis = 160),
        label = "buttonFill"
    )

    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val glowSpread = if (isPressed) 0.35f else 0.18f

    Box(
        modifier = modifier
            .size(size)
            .scale(animatedScale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        touchPressed = true
                        if (hapticEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onPress()
                        // 挂起直到手指抬起或手势取消
                        tryAwaitRelease()
                        touchPressed = false
                        onRelease()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            drawNeonButton(
                sizePx = sizePx,
                shape = shape,
                color = color,
                glowAlpha = animatedGlow,
                fillAlpha = animatedFill,
                glowSpread = glowSpread
            )
        }

        if (label != null) {
            Text(
                text = label,
                color = labelColor,
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    shadow = Shadow(
                        color = color.copy(alpha = 0.9f),
                        blurRadius = with(density) { 8.dp.toPx() },
                        offset = Offset.Zero
                    )
                )
            )
        }
    }
}

/**
 * 在 [DrawScope] 中绘制霓虹风格按钮（发光 + 渐变主体 + 高光 + 描边）。
 * 供 [GamepadButton] 内部使用。
 */
private fun DrawScope.drawNeonButton(
    sizePx: Float,
    shape: GamepadButtonShape,
    color: Color,
    glowAlpha: Float,
    fillAlpha: Float,
    glowSpread: Float
) {
    val center = Offset(sizePx / 2f, sizePx / 2f)
    val baseRadius = sizePx / 2f
    val cornerRadius = baseRadius * 0.25f

    // ---------- 1. 外发光光晕 ----------
    val glowRadius = baseRadius * (1f + glowSpread)
    val glowBrush = Brush.radialGradient(
        colors = listOf(
            color.copy(alpha = glowAlpha * 0.7f),
            color.copy(alpha = glowAlpha * 0.3f),
            color.copy(alpha = 0f)
        ),
        center = center,
        radius = glowRadius
    )
    drawNeonShape(
        shape = shape,
        center = center,
        size = sizePx,
        cornerRadius = cornerRadius,
        brush = glowBrush,
        radius = glowRadius
    )

    // ---------- 2. 按钮主体（径向渐变填充） ----------
    val bodyBrush = Brush.radialGradient(
        colors = listOf(
            color.copy(alpha = fillAlpha * 0.9f),
            color.copy(alpha = fillAlpha * 0.5f),
            color.copy(alpha = fillAlpha * 0.2f)
        ),
        center = Offset(center.x, center.y - baseRadius * 0.15f),
        radius = baseRadius
    )
    drawNeonShape(
        shape = shape,
        center = center,
        size = sizePx,
        cornerRadius = cornerRadius,
        brush = bodyBrush,
        radius = baseRadius
    )

    // ---------- 3. 顶部高光（立体感） ----------
    val highlightBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.35f),
            Color.White.copy(alpha = 0f)
        ),
        start = Offset(center.x, center.y - baseRadius),
        end = Offset(center.x, center.y + baseRadius * 0.1f)
    )
    drawNeonShape(
        shape = shape,
        center = center,
        size = sizePx * 0.86f,
        cornerRadius = cornerRadius,
        brush = highlightBrush,
        radius = baseRadius * 0.86f
    )

    // ---------- 4. 发光描边 ----------
    val borderAlpha = (glowAlpha * 0.85f + 0.3f).coerceAtMost(1f)
    drawNeonBorder(
        shape = shape,
        center = center,
        size = sizePx,
        cornerRadius = cornerRadius,
        color = color.copy(alpha = borderAlpha),
        strokeWidth = 2.5f
    )
}

/**
 * 根据形状类型绘制填充区域。
 */
private fun DrawScope.drawNeonShape(
    shape: GamepadButtonShape,
    center: Offset,
    size: Float,
    cornerRadius: Float,
    brush: Brush,
    radius: Float
) {
    when (shape) {
        GamepadButtonShape.CIRCLE -> {
            drawCircle(brush = brush, radius = radius, center = center)
        }

        GamepadButtonShape.SQUARE -> {
            val left = center.x - size / 2f
            val top = center.y - size / 2f
            drawRect(
                brush = brush,
                topLeft = Offset(left, top),
                size = Size(size, size)
            )
        }

        GamepadButtonShape.ROUNDED_SQUARE -> {
            val left = center.x - size / 2f
            val top = center.y - size / 2f
            drawRoundRect(
                brush = brush,
                topLeft = Offset(left, top),
                size = Size(size, size),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
            )
        }
    }
}

/**
 * 根据形状类型绘制发光描边。
 */
private fun DrawScope.drawNeonBorder(
    shape: GamepadButtonShape,
    center: Offset,
    size: Float,
    cornerRadius: Float,
    color: Color,
    strokeWidth: Float
) {
    when (shape) {
        GamepadButtonShape.CIRCLE -> {
            drawCircle(
                color = color,
                radius = size / 2f,
                center = center,
                style = Stroke(width = strokeWidth)
            )
        }

        GamepadButtonShape.SQUARE -> {
            val left = center.x - size / 2f
            val top = center.y - size / 2f
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(size, size),
                style = Stroke(width = strokeWidth)
            )
        }

        GamepadButtonShape.ROUNDED_SQUARE -> {
            val left = center.x - size / 2f
            val top = center.y - size / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(size, size),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
                style = Stroke(width = strokeWidth)
            )
        }
    }
}
