package com.nesstation.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.ui.components.GameCard
import com.nesstation.app.ui.components.PixelBackdrop
import com.nesstation.app.ui.components.StatusBar

/**
 * TV home — large header, two horizontal sections, bottom dock pinned to
 * the bottom of the screen.
 *
 * Bottom dock (left to right): 游戏库 / 在线游戏 / SWF / 设置 / 关于 / 退出
 *
 * Layout uses a Box so the dock is always pinned to the bottom regardless
 * of the content area's height. The content area (header + sections) is
 * wrapped in a verticalScroll so it can never push the dock off-screen on
 * short TV viewports (a 1080p TV at xhdpi density has only ~540 dp of
 * height, while two fixed 220 dp LazyRows + header + dock would otherwise
 * need ~744 dp and overflow).
 *
 * When `featured` or `recents` is empty, the corresponding section is
 * hidden instead of showing an empty band.
 */
@Composable
fun TvHomeScreen(
    featured: List<GameEntry>,
    recents: List<GameEntry>,
    onOpenGame: (GameEntry) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenOnlineGames: () -> Unit,
    onOpenBattle: () -> Unit,
    onOpenSwf: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        PixelBackdrop()

        // ── Bottom dock — pinned to the bottom, above the system nav bar ──
        TvBottomDock(
            onSelect = { idx ->
                when (idx) {
                    0 -> onOpenLibrary()
                    1 -> onOpenBattle()
                    2 -> onOpenOnlineGames()
                    3 -> onOpenSwf()
                    4 -> onOpenSettings()
                    5 -> onOpenAbout()
                    6 -> onExit()
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // ── Scrollable content area — header + sections ──
        // Padding at the bottom reserves space for the dock (110 dp dock +
        // 24 dp gap + 16 dp system bar ≈ 150 dp).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 160.dp)
        ) {
            StatusBar()

            // Compact header — matches phone HomeScreen landscape layout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NesStation",
                        color = Color(0xFF1E2A3A),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "为客厅而生 · Android TV",
                        color = Color(0xFF4A5568),
                        fontSize = 14.sp
                    )
                }
            }

            // Featured section — hidden when empty
            if (featured.isNotEmpty()) {
                SectionLabel("精选")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    items(featured) { g ->
                        GameCard(
                            title = g.title,
                            accent = g.accent,
                            onClick = { onOpenGame(g) },
                            modifier = Modifier.size(width = 140.dp, height = 160.dp)
                        )
                    }
                }
            }

            // Recents section — hidden when empty, replaced with an empty-state hint
            SectionLabel("最近游玩")
            if (recents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "还没有导入游戏",
                            color = Color(0xFF1E2A3A),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "按下方「游戏库」按钮导入 ROM 文件",
                            color = Color(0xFF4A5568),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    items(recents) { g ->
                        GameCard(
                            title = g.title,
                            accent = g.accent,
                            onClick = { onOpenGame(g) },
                            modifier = Modifier.size(width = 140.dp, height = 160.dp)
                        )
                    }
                }
            }

            // Trailing spacer so the scrollable area has some breathing room
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFF1E2A3A),
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 4.dp)
    )
}

private data class TvDockItem(val label: String, val icon: ImageVector)

/**
 * TV-friendly bottom dock — large focusable pills with icon + label.
 * Each item scales up and gets a highlighted background when focused,
 * making D-pad navigation obvious. Pinned to the bottom of the screen.
 */
@Composable
private fun TvBottomDock(
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        TvDockItem("游戏库", Icons.Rounded.GridView),
        TvDockItem("对战平台", Icons.Rounded.SportsEsports),
        TvDockItem("在线游戏", Icons.Rounded.Public),
        TvDockItem("SWF", Icons.Rounded.PlayArrow),
        TvDockItem("设置", Icons.Rounded.Settings),
        TvDockItem("关于", Icons.AutoMirrored.Rounded.HelpOutline),
        TvDockItem("退出", Icons.AutoMirrored.Rounded.Logout)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.0f),
                        Color.White.copy(alpha = 0.85f)
                    )
                )
            )
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { idx, item ->
            TvDockItemView(
                item = item,
                onClick = { onSelect(idx) }
            )
        }
    }
}

@Composable
private fun TvDockItemView(item: TvDockItem, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = if (focused) 1.15f else 1.0f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (focused) Brush.verticalGradient(
                    listOf(Color(0xFF8A7BFF), Color(0xFF4F8AC4))
                ) else Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.35f))
                )
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF8A7BFF) else Color.White.copy(alpha = 0.6f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = if (focused) Color.White else Color(0xFF3A4A5C),
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = item.label,
            color = if (focused) Color.White else Color(0xFF1E2A3A),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
