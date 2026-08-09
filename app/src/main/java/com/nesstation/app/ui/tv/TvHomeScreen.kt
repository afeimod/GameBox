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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
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
 * TV home — large header, two horizontal sections, big pill shortcuts.
 *
 * Bottom dock (left to right): 游戏库 / 在线游戏 / SWF / 设置 / 关于 / 退出
 *
 * Uses the standard Compose Foundation LazyRow (TV focus traversal is
 * automatically wired up in Compose 1.6+). All dock buttons are focusable
 * and scale up when focused, so D-pad navigation works correctly on TV.
 */
@Composable
fun TvHomeScreen(
    featured: List<GameEntry>,
    recents: List<GameEntry>,
    onOpenGame: (GameEntry) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenOnlineGames: () -> Unit,
    onOpenSwf: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        PixelBackdrop()
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar()

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("NesStation", color = Color(0xFF1E2A3A), fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                    Text("为客厅而生 · Android TV", color = Color(0xFF4A5568), fontSize = 18.sp)
                }
            }

            SectionLabel("精选")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth().height(220.dp)
            ) {
                items(featured) { g ->
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        modifier = Modifier.size(width = 180.dp, height = 200.dp)
                    )
                }
            }

            SectionLabel("最近游玩")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth().height(220.dp)
            ) {
                items(recents) { g ->
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        modifier = Modifier.size(width = 180.dp, height = 200.dp)
                    )
                }
            }

            // Spacer pushes the dock to the bottom
            Spacer(modifier = Modifier.weight(1f))

            // Bottom dock — TV-friendly: focusable, larger, scales on focus.
            TvBottomDock(
                onSelect = { idx ->
                    when (idx) {
                        0 -> onOpenLibrary()
                        1 -> onOpenOnlineGames()
                        2 -> onOpenSwf()
                        3 -> onOpenSettings()
                        4 -> onOpenAbout()
                        5 -> onExit()
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFF1E2A3A),
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 48.dp, top = 12.dp, bottom = 4.dp)
    )
}

private data class TvDockItem(val label: String, val icon: ImageVector)

/**
 * TV-friendly bottom dock — large focusable pills with icon + label.
 * Each item scales up and gets a highlighted background when focused,
 * making D-pad navigation obvious.
 */
@Composable
private fun TvBottomDock(onSelect: (Int) -> Unit) {
    val items = listOf(
        TvDockItem("游戏库", Icons.Rounded.GridView),
        TvDockItem("在线游戏", Icons.Rounded.Public),
        TvDockItem("SWF", Icons.Rounded.PlayArrow),
        TvDockItem("设置", Icons.Rounded.Settings),
        TvDockItem("关于", Icons.Rounded.HelpOutline),
        TvDockItem("退出", Icons.Rounded.Logout)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
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
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (focused) Brush.verticalGradient(
                    listOf(Color(0xFF8A7BFF), Color(0xFF4F8AC4))
                ) else Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.30f))
                )
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF8A7BFF) else Color.White.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = if (focused) Color.White else Color(0xFF3A4A5C),
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = item.label,
            color = if (focused) Color.White else Color(0xFF1E2A3A),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
