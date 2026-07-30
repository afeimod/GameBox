package com.nesstation.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Compact bottom dock — small centered pill, matches reference design.
 * Selected item gets a gradient circle highlight. Minimal footprint
 * so it doesn't block game content above.
 */
@Composable
fun BottomDock(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        DockItem("游戏库", Icons.Rounded.GridView),
        DockItem("文件", Icons.Rounded.Folder),
        DockItem("SWF", Icons.Rounded.PlayArrow),
        DockItem("设置", Icons.Rounded.Settings),
        DockItem("关于", Icons.Rounded.HelpOutline),
        DockItem("退出", Icons.Rounded.Logout)
    )

    var selected by rememberSaveable { mutableIntStateOf(selectedIndex) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.30f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { idx, item ->
            DockItemView(
                item = item,
                selected = idx == selected,
                onClick = {
                    selected = idx
                    onSelect(idx)
                }
            )
        }
    }
}

private data class DockItem(val label: String, val icon: ImageVector)

@Composable
private fun DockItemView(item: DockItem, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = selected || pressed
    val scale by animateFloatAsState(if (active) 1.1f else 1f, label = "dock-scale")

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (active) Brush.radialGradient(
                        listOf(Color(0xFF8A7BFF), Color(0xFF4F8AC4))
                    ) else Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
                .border(
                    width = if (active) 1.5.dp else 0.dp,
                    color = if (active) Color(0xFF8A7BFF).copy(alpha = 0.7f) else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = if (active) Color.White else Color(0xFF3A4A5C),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
