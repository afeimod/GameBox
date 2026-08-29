package com.nesstation.app.ui.fsd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * FSD 主菜单磁贴 — 仿 Xbox 360 Freestyle Dash 的蓝/黄对角大磁贴。
 *
 * 视觉拆解（对照截图）：
 *   - 圆角矩形，上部为蓝色渐变，下部为黄色渐变，交界是一条左低右高的对角线
 *   - 白色图标悬浮在蓝色区域中央偏上
 *   - 左下角白色粗体标签
 *   - 表面有一条淡淡的斜向高光，营造玻璃质感
 *
 * 直接作为 [FsdCoverFlow]（showReflection=false）的 item content 使用，
 * 获得 FSD 截图里「中间大、两侧渐退」的主菜单排布。
 */
data class FsdTileItem(
    val key: String,
    val label: String,
    val icon: ImageVector?,
    val badge: String? = null,   // 右上角小徽标（如游戏数量）
    val iconPath: String? = null, // 自定义图标（用户挑选的图片，绝对路径）；非空时优先于 [icon]
    val iconAlpha: Float = 1f    // 自定义图标透明度 0.05..1.0（磁贴选项里调节）；对矢量图标不生效
)

@Composable
fun FsdTile(
    item: FsdTileItem,
    modifier: Modifier = Modifier,
    compactTile: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D2C55))
    ) {
        // 蓝/黄对角渐变表面
        Canvas(modifier = Modifier.fillMaxSize()) { drawFsdTileSurface() }

        // 图标 — 自定义图片优先，否则用平台专属矢量图标；蓝色区域中央偏上
        if (item.iconPath != null) {
            val bmp = remember(item.iconPath) {
                FsdImaging.decodeFile(item.iconPath!!, 220, 220)
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = item.label,
                    contentScale = ContentScale.Fit,
                    alpha = item.iconAlpha.coerceIn(0.05f, 1f),   // 用户可调透明度，让图标与磁贴底色融合
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (compactTile) 16.dp else 30.dp)
                        .size(if (compactTile) 46.dp else 68.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else if (item.icon != null) {
                TileVectorIcon(item, compactTile)
            }
        } else if (item.icon != null) {
            TileVectorIcon(item, compactTile)
        }

        // 徽标（游戏数量）
        if (item.badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = item.badge,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 标签 — 左下角
        Text(
            text = item.label,
            color = Color(0xFF1A1206),
            fontSize = if (compactTile) 15.sp else 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = if (compactTile) 12.dp else 16.dp,
                    bottom = if (compactTile) 8.dp else 12.dp,
                    end = 12.dp
                )
        )
    }
}

/** 平台专属矢量图标（自定义图标缺失/解码失败时的回退）。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TileVectorIcon(
    item: FsdTileItem,
    compactTile: Boolean
) {
    Icon(
        imageVector = item.icon!!, // 调用方已判空
        contentDescription = item.label,
        tint = Color.White,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = if (compactTile) 18.dp else 34.dp)
            .size(if (compactTile) 44.dp else 56.dp)
    )
}

private fun DrawScope.drawFsdTileSurface() {
    val w = size.width
    val h = size.height

    // 1. 蓝色基底
    drawRect(
        brush = Brush.verticalGradient(listOf(Fsd.TileBlueTop, Fsd.TileBlueBottom))
    )

    // 2. 黄色对角块 — 左低右高的斜线切分（FSD 标志性配色）
    val splitLeftY = h * 0.62f
    val splitRightY = h * 0.38f
    val p = Path().apply {
        moveTo(0f, splitLeftY)
        lineTo(w, splitRightY)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(
        p,
        brush = Brush.verticalGradient(
            listOf(Fsd.TileYellowTop, Fsd.TileYellowBottom),
            startY = splitRightY, endY = h
        )
    )

    // 3. 对角分界高光细线
    drawLine(
        color = Color.White.copy(alpha = 0.35f),
        start = Offset(0f, splitLeftY),
        end = Offset(w, splitRightY),
        strokeWidth = 2f
    )

    // 4. 表面斜向高光（玻璃质感）
    val gloss = Path().apply {
        moveTo(0f, 0f)
        lineTo(w * 0.45f, 0f)
        lineTo(w * 0.10f, h)
        lineTo(0f, h)
        close()
    }
    drawPath(gloss, color = Color.White.copy(alpha = 0.06f))
}

/**
 * FSD 主菜单 — 由 [FsdTileItem] 列表驱动的磁贴封面流。
 *
 * 布局对照截图 2：
 *   - 中央磁贴最大，右侧磁贴依次渐退（cover-flow 排布）
 *   - 下方居中显示「N of M」计数
 *   - 磁贴下方标题即 [FsdTileItem.label]（在磁贴内部）
 *
 * 竖屏（<600dp 宽）自动切换紧凑尺寸：磁贴缩小到 ~190dp，左右邻贴各露一角，
 * 不再“整屏只看到一个磁贴”；横屏保持 296dp 大磁贴。
 */
@Composable
fun FsdTileFlow(
    items: List<FsdTileItem>,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    onActivate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onItemLongClick: (Int) -> Unit = {}
) {
    val compact = LocalConfiguration.current.screenWidthDp < 600
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FsdCoverFlow(
            count = items.size,
            selectedIndex = selectedIndex,
            onIndexChange = onIndexChange,
            onItemClick = onActivate,
            onItemLongClick = onItemLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            itemWidth = if (compact) 190.dp else 296.dp,
            itemHeight = if (compact) 122.dp else 196.dp,
            gap = if (compact) 16.dp else 26.dp,
            tiltDegrees = if (compact) 10f else 14f,
            fadePerStep = 0.26f,
            scalePerStep = 0.22f,
            showReflection = false,
            grabFocusOnLaunch = true
        ) { i ->
            FsdTile(items[i], modifier = Modifier.fillMaxSize(), compactTile = compact)
        }

        // N of M 计数
        if (items.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FsdCounter(
                current = (selectedIndex + 1).coerceIn(1, items.size),
                total = items.size
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * 主页分区：一个标题 + 一组磁贴。
 *
 * 主页为「3 个横向滑动的分区行」：游戏库 / 在线·对战·SWF / 设置·关于·退出。
 * 每个分区一个 [FsdMenuSection] —— 标题写在分区标题条上，磁贴保留 FSD 蓝/黄
 * 封面样式，按一行横向排列；页面整体上下滚动即可秒达任意分区，行内磁贴
 * 左右滑动浏览（与游戏库封面流一致）。
 */
data class FsdMenuSection(
    val key: String,          // 分区唯一标识（如 "library"）
    val title: String,        // 分区标题（如 "游戏库"）
    val items: List<FsdTileItem>
)

/** FSD 分区标题条：黄色圆点 + 白色粗体大标题，替代原来顶部单一面包屑。 */
@Composable
fun FsdSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 黄色圆点（FSD 标志色）
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Fsd.TileYellowTop)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = subtitle,
                color = Fsd.BarTextDim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 主页分区行 — 一个横向滑动的磁贴行（配合 [FsdMenuSection]）。
 *
 * 主页有 3 个分区（游戏库 / 在线·对战·SWF / 设置·关于·退出），每个分区
 * 一行磁贴，左右滑动浏览（与游戏库封面流一致）。分区之间有明确的选中态：
 *   - 选中分区（[isFocused]）：正常亮度，选中磁贴带黄色高亮边框
 *   - 未选中分区：整行变暗（graphicsLayer alpha），提示可用 D-pad 上下切换
 *
 * TV/遥控器：左右切换行内磁贴、上下切换分区、OK 激活、Y 呼出磁贴选项。
 * 触屏：直接横向滑动浏览、点按激活、长按呼出磁贴选项。
 */
@Composable
fun FsdSectionRow(
    section: FsdMenuSection,
    selectedIndex: Int,
    isFocused: Boolean,
    onIndexChange: (Int) -> Unit,
    onActivate: (FsdTileItem) -> Unit,
    onItemLongClick: (FsdTileItem) -> Unit,
    onFocusUp: () -> Unit,
    onFocusDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val tileW = if (compact) 156.dp else 196.dp
    val tileH = if (compact) 100.dp else 126.dp
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // 分区被选中时：抓焦点 + 把选中磁贴滚到可见
    LaunchedEffect(isFocused) {
        if (isFocused) {
            delay(80)
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(isFocused, selectedIndex) {
        if (isFocused && selectedIndex > 0) {
            runCatching { listState.animateScrollToItem(selectedIndex) }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FsdSectionHeader(
            title = section.title,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                // 未选中的分区整行变暗；选中的正常亮度
                .graphicsLayer { this.alpha = if (isFocused) 1f else 0.45f }
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                        false
                    } else when (e.key) {
                        Key.DirectionLeft -> {
                            if (selectedIndex > 0) onIndexChange(selectedIndex - 1)
                            true
                        }
                        Key.DirectionRight -> {
                            if (selectedIndex < section.items.size - 1) onIndexChange(selectedIndex + 1)
                            true
                        }
                        Key.DirectionUp -> { onFocusUp(); true }
                        Key.DirectionDown -> { onFocusDown(); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            section.items.getOrNull(selectedIndex)?.let(onActivate)
                            true
                        }
                        Key.Y, Key.ButtonY -> {
                            section.items.getOrNull(selectedIndex)?.let(onItemLongClick)
                            true
                        }
                        else -> false
                    }
                }
        ) {
            itemsIndexed(section.items) { i, item ->
                FsdTile(
                    item = item,
                    compactTile = true,
                    modifier = Modifier
                        .width(tileW)
                        .height(tileH)
                        .then(
                            // 选中分区中，当前磁贴加黄色高亮边框
                            if (isFocused && i == selectedIndex) {
                                Modifier.border(
                                    width = 3.dp,
                                    color = Fsd.TileYellowTop,
                                    shape = RoundedCornerShape(10.dp)
                                )
                            } else Modifier
                        )
                        .combinedClickable(
                            onClick = { onActivate(item) },
                            onLongClick = { onItemLongClick(item) }
                        )
                )
            }
        }
    }
}
