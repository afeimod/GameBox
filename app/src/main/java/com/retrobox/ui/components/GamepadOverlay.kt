package com.retrobox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.retrobox.input.GamepadButtonId
import com.retrobox.input.GamepadConfig
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 手柄覆盖层状态，封装位置、缩放与可见性。
 *
 * 可通过 [rememberGamepadOverlayState] 在 Composable 中创建并记住。
 */
class GamepadOverlayState(
    initialVisible: Boolean = true,
    initialScale: Float = 1f,
    initialOffsetX: Float = 0f,
    initialOffsetY: Float = 0f
) {
    /** 手柄是否可见。 */
    var visible: Boolean by mutableStateOf(initialVisible)

    /** 手柄缩放比例。 */
    var scale: Float by mutableFloatStateOf(initialScale)

    /** X 方向偏移（px）。 */
    var offsetX: Float by mutableFloatStateOf(initialOffsetX)

    /** Y 方向偏移（px）。 */
    var offsetY: Float by mutableFloatStateOf(initialOffsetY)

    /** 重置到默认状态。 */
    fun reset() {
        visible = true
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    /** 切换可见性。 */
    fun toggle() {
        visible = !visible
    }
}

/**
 * 创建并记住一个 [GamepadOverlayState]。
 */
@Composable
fun rememberGamepadOverlayState(
    initialVisible: Boolean = true,
    initialScale: Float = 1f,
    initialOffsetX: Float = 0f,
    initialOffsetY: Float = 0f
): GamepadOverlayState = remember {
    GamepadOverlayState(initialVisible, initialScale, initialOffsetX, initialOffsetY)
}

/**
 * 手柄覆盖层，浮在游戏画面之上。
 *
 * 支持以下手势：
 * - **单指拖拽**空白区域：移动手柄位置。
 * - **双指捏合**：缩放手柄大小（0.5x ~ 2.5x）。
 * - **双指点击**（快速双指触摸无移动）：切换手柄显示/隐藏。
 *
 * 手势处理策略：
 * - 双指手势在 [PointerEventPass.Initial] 阶段拦截并消费，避免影响内部按钮。
 * - 单指拖拽在 Main 阶段处理，仅当触摸未被子组件（按钮）消费时触发，
 *   因此拖拽只作用于按钮之间的空白区域，不会干扰按钮操作。
 *
 * @param modifier       外部修饰符，通常应填满整个游戏画面区域。
 * @param config         手柄配置。
 * @param theme          主题配置。
 * @param state          覆盖层状态（位置/缩放/可见性），可通过 [rememberGamepadOverlayState] 创建。
 * @param onButtonPress  按钮按下回调。
 * @param onButtonRelease 按钮释放回调。
 * @param onDirectionChange 方向变化回调。
 */
@Composable
fun GamepadOverlay(
    modifier: Modifier = Modifier,
    config: GamepadConfig = GamepadConfig.default(),
    theme: GamepadTheme = GamepadTheme.Default,
    state: GamepadOverlayState = rememberGamepadOverlayState(),
    onButtonPress: (GamepadButtonId) -> Unit = {},
    onButtonRelease: (GamepadButtonId) -> Unit = {},
    onDirectionChange: (DPadDirection) -> Unit = {}
) {
    Box(
        modifier = modifier
            // ---- 双指手势检测（Initial 阶段，先于子组件） ----
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var gestureStartTime = 0L
                    var startDistance = 0f
                    var prevDistance = 0f
                    var inGesture = false
                    var totalMovement = 0f

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val activeChanges = event.changes.filter { it.pressed }
                        val activeCount = activeChanges.size

                        if (activeCount >= 2) {
                            // 双指（或多指）—— 消费所有变更，阻止子组件响应
                            event.changes.forEach { it.consume() }

                            val p1 = activeChanges[0].position
                            val p2 = activeChanges[1].position
                            val currentDist = hypot(p1.x - p2.x, p1.y - p2.y)

                            if (!inGesture) {
                                // 手势开始
                                inGesture = true
                                gestureStartTime = System.currentTimeMillis()
                                startDistance = currentDist
                                prevDistance = currentDist
                                totalMovement = 0f
                            } else {
                                // 持续捏合缩放
                                if (prevDistance > 1f) {
                                    val zoomFactor = currentDist / prevDistance
                                    state.scale = (state.scale * zoomFactor)
                                        .coerceIn(0.5f, 2.5f)
                                }
                                totalMovement += abs(currentDist - prevDistance)
                                prevDistance = currentDist
                            }
                        } else if (inGesture && activeCount < 2) {
                            // 双指手势结束（手指抬起）
                            val duration = System.currentTimeMillis() - gestureStartTime
                            // 判定双指点击：持续时间短且移动距离小
                            if (duration < 300 && totalMovement < 40f) {
                                state.toggle()
                            }
                            inGesture = false
                            totalMovement = 0f
                        }
                    }
                }
            }
            // ---- 单指拖拽（Main 阶段，仅处理未被按钮消费的触摸） ----
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    state.offsetX += dragAmount.x
                    state.offsetY += dragAmount.y
                }
            }
    ) {
        // ---- 手柄主体 ----
        AnimatedVisibility(
            visible = state.visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            VirtualGamepad(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(state.scale)
                    .offset {
                        IntOffset(
                            state.offsetX.roundToInt(),
                            state.offsetY.roundToInt()
                        )
                    },
                config = config,
                theme = theme,
                showMenuBar = false,
                onButtonPress = onButtonPress,
                onButtonRelease = onButtonRelease,
                onDirectionChange = onDirectionChange
            )
        }

        // ---- 隐藏时的提示 ----
        AnimatedVisibility(
            visible = !state.visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                theme.primaryColor.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = theme.primaryColor.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "双指点击显示手柄",
                    color = theme.accentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
