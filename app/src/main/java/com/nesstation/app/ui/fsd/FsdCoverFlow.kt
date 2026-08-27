package com.nesstation.app.ui.fsd

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * FSD 风格 3D 封面流（Cover Flow）轮播。
 *
 * 视觉特征（对照 Xbox 360 Freestyle Dash）：
 *   - 选中封面居中放大、正对用户
 *   - 两侧封面按距离递减缩放 / 淡出，并向中心方向旋转（3D 透视）
 *   - 每个封面下方带垂直翻转的倒影（可用 [showReflection] 关闭）
 *   - 左右拖拽翻页；点击侧边封面将其居中；点击中间封面触发 [onItemClick]
 *   - 支持 D-pad 左右移动 + OK 激活（TV 模式）
 *
 * [content] 以索引被调用，绘制第 i 个封面（宽 [itemWidth]、高 [itemHeight]）。
 * 仅为 |i - selectedIndex| <= [visibleHalfWindow] 的条目做组合，列表很长时性能可控。
 */
@Composable
fun FsdCoverFlow(
    count: Int,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    onItemClick: (Int) -> Unit,
    onItemLongClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    itemWidth: Dp = 186.dp,
    itemHeight: Dp = 252.dp,
    gap: Dp = 30.dp,
    tiltDegrees: Float = 18f,
    fadePerStep: Float = 0.32f,
    scalePerStep: Float = 0.26f,
    showReflection: Boolean = true,
    visibleHalfWindow: Int = 4,
    grabFocusOnLaunch: Boolean = false,
    content: @Composable (Int) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        if (count <= 0) return@BoxWithConstraints

        val sel = selectedIndex.coerceIn(0, count - 1)
        val animated by animateFloatAsState(
            targetValue = sel.toFloat(),
            animationSpec = tween(durationMillis = 240),
            label = "fsd-flow-pos"
        )

        val stepPx = with(LocalDensity.current) { (itemWidth + gap).toPx() }
        var dragAccum by remember { mutableFloatStateOf(0f) }

        // 实时拖拽跟手：拖动中封面直接随手指平移，松手后残余偏移量平滑归零，
        // 与翻页动画叠加 —— 旧版只在松手时整页翻转，手感生硬。
        var dragPx by remember { mutableFloatStateOf(0f) }
        var settling by remember { mutableStateOf(false) }
        LaunchedEffect(settling) {
            if (settling) {
                animate(
                    initialValue = dragPx,
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                ) { v, _ -> dragPx = v }   // animate 尾 lambda 签名为 (value, velocity) 两参数
                settling = false
            }
        }

        // TV 遥控器：进入界面后自动抓焦，否则 D-pad 首次按键无响应
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(grabFocusOnLaunch) {
            if (grabFocusOnLaunch) {
                kotlinx.coroutines.delay(150)
                runCatching { focusRequester.requestFocus() }
            }
        }

        fun move(delta: Int) {
            onIndexChange((sel + delta).coerceIn(0, count - 1))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                        false
                    } else when (e.key) {
                        Key.DirectionLeft -> { move(-1); true }
                        Key.DirectionRight -> { move(1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            onItemClick(sel); true
                        }
                        Key.Y, Key.ButtonY -> { onItemLongClick(sel); true }  // Y = 选项（长按等效）
                        else -> false
                    }
                }
                .pointerInput(count) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragAccum = 0f
                            settling = false
                            dragPx = 0f
                        },
                        onHorizontalDrag = { change, amount ->
                            dragAccum += amount
                            dragPx += amount
                            change.consume()
                        },
                        onDragEnd = {
                            // 半步即翻页 — 手感与 FSD 一致；翻页与回弹动画并行，落位顺滑
                            val steps = (-dragAccum / (stepPx * 0.45f)).roundToInt()
                            if (steps != 0) move(steps.coerceIn(-visibleHalfWindow, visibleHalfWindow))
                            settling = true
                        },
                        onDragCancel = { settling = true }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val from = (sel - visibleHalfWindow).coerceAtLeast(0)
            val to = (sel + visibleHalfWindow).coerceAtMost(count - 1)

            for (i in from..to) {
                val pos = i - animated          // 动画中的相对位置
                val dist = abs(i - sel)          // 静态距离（决定可点击性判断）

                // 缩放 / 淡出 / 倾斜都由动画位置驱动，过渡平滑
                val scale = (1f - scalePerStep * abs(pos)).coerceAtLeast(0.5f)
                val alpha = (1f - fadePerStep * abs(pos)).coerceIn(0.25f, 1f)
                val tilt = (pos * tiltDegrees).coerceIn(-tiltDegrees * 2, tiltDegrees * 2).toFloat()

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            translationX = pos * stepPx + dragPx
                            scaleX = scale
                            scaleY = scale
                            rotationY = tilt
                            this.alpha = alpha
                            cameraDistance = 10f * density // 更柔和的透视
                        }
                        .combinedClickable(
                            onClick = {
                                if (dist == 0) onItemClick(i) else onIndexChange(i)
                            },
                            onLongClick = { onItemLongClick(i) }
                        )
                ) {
                    // 封面主体
                    Box(
                        modifier = Modifier
                            .width(itemWidth)
                            .height(itemHeight)
                    ) {
                        content(i)
                    }

                    if (showReflection) {
                        // 倒影：垂直翻转 + 渐隐遮罩，FSD 桌面标志性效果
                        Box(
                            modifier = Modifier
                                .width(itemWidth)
                                .height(itemHeight * 0.36f)
                                .graphicsLayer {
                                    scaleX = 1f
                                    scaleY = -1f
                                    this.alpha = 0.22f // 外层局部 val alpha 遮蔽了 GraphicsLayerScope.alpha，必须用 this.
                                }
                        ) {
                            content(i)
                            // 渐隐遮罩：上透明 → 下渐变为背景色
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.75f to Fsd.BgMid.copy(alpha = 0.7f),
                                            1f to Fsd.BgMid
                                        )
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * FSD 标题横幅 — 金属灰半透明圆角条，显示当前选中项标题。
 * 例：截图中的「角色扮演類 最終幻想 13-2 中文」。
 */
@Composable
fun FsdTitleBanner(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .widthIn(max = 380.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFB9C2CC).copy(alpha = 0.85f),
                        Color(0xFF6E7A88).copy(alpha = 0.85f)
                    )
                )
            )
            .padding(horizontal = 22.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = Color(0xFF121A24),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** FSD 右侧计数「149 of 150」 */
@Composable
fun FsdCounter(current: Int, total: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = "$current of $total",
            color = Color(0xFFDCE8F5),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** FSD 面包屑标题（例：设置 ▪ 工具 ▪ 游戏库） */
@Composable
fun FsdBreadcrumb(
    path: List<String>,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.padding(horizontal = 34.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        path.forEachIndexed { idx, seg ->
            if (idx > 0) {
                Text(
                    text = "  ▪  ",
                    color = Fsd.BarTextDim.copy(alpha = 0.7f),
                    fontSize = 16.sp
                )
            }
            Text(
                text = seg,
                color = if (idx == path.lastIndex) Color.White else Fsd.BarTextDim,
                fontSize = if (idx == path.lastIndex) 22.sp else 17.sp,
                fontWeight = if (idx == path.lastIndex) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
