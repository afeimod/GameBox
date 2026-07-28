package com.nesstation.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.ui.components.GameCard
import com.nesstation.app.ui.components.PixelBackdrop

@Composable
fun LibraryScreen(
    games: List<GameEntry>,
    onOpenGame: (GameEntry) -> Unit,
    onImport: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        PixelBackdrop()
        Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Column {
                    Text(
                        text = "游戏库",
                        color = Color(0xFF1E2A3A),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${games.size} 款游戏 · 复古之旅",
                        color = Color(0xFF4A5568),
                        fontSize = 13.sp
                    )
                }
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    ExtendedFloatingActionButton(
                        onClick = onImport,
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        text = { Text("导入 ROM") },
                        containerColor = Color(0xFFE74C3C),
                        contentColor = Color.White
                    )
                }
            }

            // Filter row
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            ) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip("全部", true)
                    FilterChip("最近", false)
                    FilterChip("收藏", false)
                    FilterChip("字母", false)
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    SearchPill(onClick = onSearch)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(games) { g ->
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        modifier = Modifier.height(170.dp)
                    )
                }
                if (games.isEmpty()) {
                    items(listOf(EmptyPlaceholder)) {
                        EmptyState()
                    }
                }
            }
        }
    }
}

private object EmptyPlaceholder

@Composable
private fun FilterChip(text: String, selected: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Color(0xFF1E2A3A)
                else Color.White.copy(alpha = 0.5f)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else Color(0xFF1E2A3A),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF1E2A3A), modifier = Modifier.size(16.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            Text("搜索", color = Color(0xFF1E2A3A), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.FilterList, contentDescription = null, tint = Color(0xFF1E2A3A), modifier = Modifier.size(40.dp))
            Text(
                "还没有游戏。点击右上角导入你的 .nes ROM",
                color = Color(0xFF1E2A3A),
                fontSize = 14.sp
            )
        }
    }
}
