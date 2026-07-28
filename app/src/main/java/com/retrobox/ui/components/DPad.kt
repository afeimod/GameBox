package com.retrobox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 方向键支持的方向。
 *
 * 除了基本的四方向外，还包含四个对角线方向，支持斜向移动。
 * [NONE] 表示未触摸或处于中心死区。
 */
enum class DPadDirection {
    NONE,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    UP_LEFT,
    UP_RIGHT,
    DOWN_LEFT,
    DOWN_RIGHT;

    /** 是否包含「上」分量。 */
    val hasUp: Boolean get() = this == UP || this == UP_LEFT || this == UP_RIGHT

    /** 是否包含「下」分量。 */
    val hasDown: Boolean get() = this == DOWN || this == DOWN_LEFT || this == DOWN_RIGHT

    /** 是否包含「左」分量。 */
    val hasLeft: Boolean get() = this == LEFT || this == UP_LEFT || this == DOWN_LEFT

    /** 是否包含「右」分量。 */
    val hasRight: Boolean get() = this == RIGHT || this == UP_RIGHT || this == DOWN_RIGHT
}

/**
 * 赛博朋克霓虹风格的十字方向键。
 *
 * 使用 [Canvas] 绘制十字形主体，中央有圆形装饰，外圈有发光光晕。
 * 触摸时根据手指位置实时计算方向（含对角线），对应区域高亮发光。
 * 支持拖动切换方向，手指抬起后回到 [DPadDirection.NONE]。
 *
 * @param modifier       外部修饰符。
 * @param size           方向键整体尺寸（正方形，dp）。
 * @param color          方向键主色（发光与填充基于此色）。
 * @param accentColor    中心装饰与高亮的强调色。
 * @param glowIntensity  发光强度系数，0f~1f。
 * @param hapticEnabled  是否启用震动反馈（方向切换时触发）。
 * @param onDirectionChange 方向变化回调，手指抬起时会回调 [DPadDirection.NONE]。
 */
@Composable
fun DPad(
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    color: Color = GamepadTheme.NeonPurple,
    accentColor: Color = GamepadTheme.NeonCyan,
    glowIntensity: Float = 1f,
    hapticEnabled: Boolean = true,
    onDirectionChange: (DPadDirection) -> Unit = {}
) {
    var currentDirection by remember { mutableStateOf(DPadDirection.NONE) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }

    // 各方向臂的高亮动画
    val upGlow by animateFloatAsState(
        targetValue = if (currentDirection.hasUp) 1f else 0f,
        animationSpec = tween(120), label = "upGlow"
    )
    val downGlow by animateFloatAsState(
        targetValue = if (currentDirection.hasDown) 1f else 0f,
        animationSpec = tween(120), label = "downGlow"
    )
    val leftGlow by animateFloatAsState(
        targetValue = if (currentDirection.hasLeft) 1f else 0f,
        animationSpec = tween(120), label = "leftGlow"
    )
    val rightGlow by animateFloatAsState(
        targetValue = if (currentDirection.hasRight) 1f else 0f,
        animationSpec = tween(120), label = "rightGlow"
    )

    Box(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Wait for first pointer down (replacement for awaitFirstDown)
                        var downChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
                        while (downChange == null) {
                            val ev = awaitPointerEvent(pass = PointerEventPass.Main)
                            downChange = ev.changes.firstOrNull { it.changedToDown() }
                        }
                        val newDir = calculateDirection(downChange.position, sizePx)
                        if (newDir != currentDirection) {
                            currentDirection = newDir
                            if (newDir != DPadDirection.NONE && hapticEnabled) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            onDirectionChange(newDir)
                        }

                        // 持续跟踪手指移动
                        var pointerUp = false
                        while (!pointerUp) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val change = event.changes.firstOrNull()
                            if (change == null || !change.pressed) {
                                pointerUp = true
                            } else {
                                val movedDir = calculateDirection(change.position, sizePx)
                                if (movedDir != currentDirection) {
                                    currentDirection = movedDir
                                    if (movedDir != DPadDirection.NONE && hapticEnabled) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    onDirectionChange(movedDir)
                                }
                            }
                        }

                        // 手指抬起
                        if (currentDirection != DPadDirection.NONE) {
                            currentDirection = DPadDirection.NONE
                            onDirectionChange(DPadDirection.NONE)
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val s = sizePx
            val center = Offset(s / 2f, s / 2f)
            val armWidth = s * 0.34f
            val halfArm = armWidth / 2f
            val cornerR = armWidth * 0.28f

            // ---------- 1. 外圈发光光晕 ----------
            val haloBrush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.25f * glowIntensity),
                    color.copy(alpha = 0.08f * glowIntensity),
                    Color.Transparent
                ),
                center = center,
                radius = s * 0.62f
            )
            drawCircle(haloBrush, radius = s * 0.62f, center = center)

            // ---------- 2. 十字主体路径 ----------
            val crossPath = buildCrossPath(s, halfArm, cornerR)
            val bodyBrush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.35f),
                    color.copy(alpha = 0.18f),
                    color.copy(alpha = 0.08f)
                ),
                center = center,
                radius = s * 0.55f
            )
            drawPath(crossPath, brush = bodyBrush)

            // ---------- 3. 各方向臂高亮 ----------
            drawArmGlow(
                sizePx = s, halfArm = halfArm, cornerR = cornerR,
                direction = DPadDirection.UP, glow = upGlow, color = color, center = center
            )
            drawArmGlow(
                sizePx = s, halfArm = halfArm, cornerR = cornerR,
                direction = DPadDirection.DOWN, glow = downGlow, color = color, center = center
            )
            drawArmGlow(
                sizePx = s, halfArm = halfArm, cornerR = cornerR,
                direction = DPadDirection.LEFT, glow = leftGlow, color = color, center = center
            )
            drawArmGlow(
                sizePx = s, halfArm = halfArm, cornerR = cornerR,
                direction = DPadDirection.RIGHT, glow = rightGlow, color = color, center = center
            )

            // ---------- 4. 十字描边 ----------
            drawPath(
                crossPath,
                color = color.copy(alpha = 0.7f),
                style = Stroke(width = 2f)
            )

            // ---------- 5. 中心圆形装饰 ----------
            val centerRadius = halfArm * 0.65f
            // 中心外发光
            if (currentDirection != DPadDirection.NONE) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.6f), Color.Transparent),
                        center = center,
                        radius = centerRadius * 1.8f
                    ),
                    radius = centerRadius * 1.8f,
                    center = center
                )
            }
            // 中心圆填充
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.5f),
                        accentColor.copy(alpha = 0.2f)
                    ),
                    center = center,
                    radius = centerRadius
                ),
                radius = centerRadius,
                center = center
            )
            // 中心圆描边
            drawCircle(
                color = accentColor.copy(alpha = 0.8f),
                radius = centerRadius,
                center = center,
                style = Stroke(width = 1.5f)
            )
            // 中心小圆点
            drawCircle(
                color = accentColor.copy(alpha = if (currentDirection != DPadDirection.NONE) 0.95f else 0.5f),
                radius = centerRadius * 0.3f,
                center = center
            )

            // ---------- 6. 方向箭头 ----------
            drawDirectionArrows(s, halfArm, color, currentDirection)
        }
    }
}

/**
 * 构建十字形 [Path]。
 */
private fun buildCrossPath(size: Float, halfArm: Float, cornerR: Float): Path {
    val c = size / 2f
    val left = c - halfArm
    val right = c + halfArm
    val top = c - halfArm
    val bottom = c + halfArm

    return Path().apply {
        // 从上臂左上角开始，顺时针绘制
        moveTo(left, 0f)
        lineTo(right, 0f)
        lineTo(right, top)
        lineTo(size, top)
        lineTo(size, bottom)
        lineTo(right, bottom)
        lineTo(right, size)
        lineTo(left, size)
        lineTo(left, bottom)
        lineTo(0f, bottom)
        lineTo(0f, top)
        lineTo(left, top)
        close()
    }
}

/**
 * 绘制单个方向臂的高亮发光（在按下时叠加）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArmGlow(
    sizePx: Float,
    halfArm: Float,
    cornerR: Float,
    direction: DPadDirection,
    glow: Float,
    color: Color,
    center: Offset
) {
    if (glow <= 0.01f) return
    val c = sizePx / 2f
    val armPath = when (direction) {
        DPadDirection.UP -> Path().apply {
            moveTo(c - halfArm, 0f)
            lineTo(c + halfArm, 0f)
            lineTo(c + halfArm, c - halfArm)
            lineTo(c - halfArm, c - halfArm)
            close()
        }
        DPadDirection.DOWN -> Path().apply {
            moveTo(c - halfArm, c + halfArm)
            lineTo(c + halfArm, c + halfArm)
            lineTo(c + halfArm, sizePx)
            lineTo(c - halfArm, sizePx)
            close()
        }
        DPadDirection.LEFT -> Path().apply {
            moveTo(0f, c - halfArm)
            lineTo(c - halfArm, c - halfArm)
            lineTo(c - halfArm, c + halfArm)
            lineTo(0f, c + halfArm)
            close()
        }
        DPadDirection.RIGHT -> Path().apply {
            moveTo(c + halfArm, c - halfArm)
            lineTo(sizePx, c - halfArm)
            lineTo(sizePx, c + halfArm)
            lineTo(c + halfArm, c + halfArm)
            close()
        }
        else -> return
    }
    val glowBrush = Brush.radialGradient(
        colors = listOf(
            color.copy(alpha = 0.7f * glow),
            color.copy(alpha = 0.2f * glow),
            Color.Transparent
        ),
        center = when (direction) {
            DPadDirection.UP -> Offset(c, c * 0.3f)
            DPadDirection.DOWN -> Offset(c, sizePx - c * 0.3f)
            DPadDirection.LEFT -> Offset(c * 0.3f, c)
            DPadDirection.RIGHT -> Offset(sizePx - c * 0.3f, c)
            else -> center
        },
        radius = halfArm * 2f
    )
    drawPath(armPath, brush = glowBrush)
}

/**
 * 绘制四方向箭头（▲▼◀▶），按下方向时箭头更亮。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDirectionArrows(
    size: Float,
    halfArm: Float,
    color: Color,
    direction: DPadDirection
) {
    val c = size / 2f
    val arrowSize = halfArm * 0.45f
    val arrowOffset = halfArm * 1.5f

    // 上箭头 ▲
    val upAlpha = if (direction.hasUp) 1f else 0.5f
    drawTriangle(
        center = Offset(c, c - arrowOffset),
        size = arrowSize,
        rotation = 0f, // pointing up
        color = color.copy(alpha = upAlpha)
    )
    // 下箭头 ▼
    val downAlpha = if (direction.hasDown) 1f else 0.5f
    drawTriangle(
        center = Offset(c, c + arrowOffset),
        size = arrowSize,
        rotation = 180f, // pointing down
        color = color.copy(alpha = downAlpha)
    )
    // 左箭头 ◀
    val leftAlpha = if (direction.hasLeft) 1f else 0.5f
    drawTriangle(
        center = Offset(c - arrowOffset, c),
        size = arrowSize,
        rotation = 270f, // pointing left
        color = color.copy(alpha = leftAlpha)
    )
    // 右箭头 ▶
    val rightAlpha = if (direction.hasRight) 1f else 0.5f
    drawTriangle(
        center = Offset(c + arrowOffset, c),
        size = arrowSize,
        rotation = 90f, // pointing right
        color = color.copy(alpha = rightAlpha)
    )
}

/**
 * 绘制一个等边三角形箭头。
 *
 * @param rotation 旋转角度（0=朝上，90=朝右，180=朝下，270=朝左）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTriangle(
    center: Offset,
    size: Float,
    rotation: Float,
    color: Color
) {
    val angleRad = Math.toRadians(rotation.toDouble()).toFloat()
    val cos = kotlin.math.cos(angleRad)
    val sin = kotlin.math.sin(angleRad)

    // 三角形三个顶点（默认朝上）
    val p1 = Offset(0f, -size) // top
    val p2 = Offset(-size * 0.866f, size * 0.5f) // bottom-left
    val p3 = Offset(size * 0.866f, size * 0.5f) // bottom-right

    // 旋转
    fun rotate(p: Offset): Offset = Offset(
        p.x * cos - p.y * sin + center.x,
        p.x * sin + p.y * cos + center.y
    )

    val path = Path().apply {
        moveTo(rotate(p1).x, rotate(p1).y)
        lineTo(rotate(p2).x, rotate(p2).y)
        lineTo(rotate(p3).x, rotate(p3).y)
        close()
    }
    drawPath(path, color = color)
}

/**
 * 根据触摸位置计算方向（8 方向 + 死区）。
 *
 * 使用极坐标方式：以中心为原点计算角度，分为 8 个扇区。
 * 中心一定半径内为死区（返回 [DPadDirection.NONE]）。
 */
private fun calculateDirection(position: Offset, canvasSize: Float): DPadDirection {
    val center = Offset(canvasSize / 2f, canvasSize / 2f)
    val dx = position.x - center.x
    val dy = position.y - center.y
    val distance = hypot(dx, dy)

    // 中心死区
    val deadZone = canvasSize * 0.14f
    if (distance < deadZone) return DPadDirection.NONE

    // 超出最大半径视为无效
    val maxRadius = canvasSize / 2f
    if (distance > maxRadius * 1.05f) return DPadDirection.NONE

    // 计算角度（屏幕坐标 y 向下，翻转 y 使 0°=右，90°=上）
    val angleRad = atan2(-dy.toDouble(), dx.toDouble())
    var degrees = Math.toDegrees(angleRad).toFloat()
    if (degrees < 0) degrees += 360f

    // 8 扇区，每个 45°，中心对齐正方向
    return when {
        degrees >= 337.5f || degrees < 22.5f -> DPadDirection.RIGHT
        degrees < 67.5f -> DPadDirection.UP_RIGHT
        degrees < 112.5f -> DPadDirection.UP
        degrees < 157.5f -> DPadDirection.UP_LEFT
        degrees < 202.5f -> DPadDirection.LEFT
        degrees < 247.5f -> DPadDirection.DOWN_LEFT
        degrees < 292.5f -> DPadDirection.DOWN
        else -> DPadDirection.DOWN_RIGHT
    }
}
