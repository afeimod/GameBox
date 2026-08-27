package com.nesstation.app.ui.fsd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val badge: String? = null   // 右上角小徽标（如游戏数量）
)

@Composable
fun FsdTile(
    item: FsdTileItem,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D2C55))
    ) {
        // 蓝/黄对角渐变表面
        Canvas(modifier = Modifier.fillMaxSize()) { drawFsdTileSurface() }

        // 图标 — 蓝色区域中央
        if (item.icon != null) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 34.dp)
                    .size(56.dp)
            )
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
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 12.dp, end = 12.dp)
        )
    }
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
 */
@Composable
fun FsdTileFlow(
    items: List<FsdTileItem>,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    onActivate: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FsdCoverFlow(
            count = items.size,
            selectedIndex = selectedIndex,
            onIndexChange = onIndexChange,
            onItemClick = onActivate,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            itemWidth = 296.dp,
            itemHeight = 196.dp,
            gap = 26.dp,
            tiltDegrees = 14f,
            fadePerStep = 0.26f,
            scalePerStep = 0.22f,
            showReflection = false,
            grabFocusOnLaunch = true
        ) { i ->
            FsdTile(items[i], modifier = Modifier.fillMaxSize())
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
