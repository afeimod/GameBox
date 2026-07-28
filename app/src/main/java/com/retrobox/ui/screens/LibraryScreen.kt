package com.retrobox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrobox.data.GameInfo
import com.retrobox.data.Platform
import com.retrobox.ui.theme.NeonCyan
import com.retrobox.ui.theme.NeonPink
import com.retrobox.ui.theme.NeonPurple
import com.retrobox.ui.theme.CyberBackground
import com.retrobox.ui.theme.CyberSurface
import com.retrobox.ui.theme.CyberSurfaceVariant
import com.retrobox.ui.viewmodel.GameViewModel

/**
 * 游戏库主界面
 *
 * 展示本地游戏列表，支持平台筛选与搜索。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: GameViewModel,
    onGameClick: (GameInfo) -> Unit,
    onDownloadClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val games by viewModel.games.collectAsState()
    val selectedPlatform by viewModel.selectedPlatform.collectAsState()
    var searchText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBackground)
    ) {
        // 顶部栏
        TopAppBar(
            title = {
                Text(
                    text = "RetroBox",
                    color = NeonPurple,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            },
            actions = {
                IconButton(onClick = onDownloadClick) {
                    Icon(Icons.Default.Download, contentDescription = "下载", tint = NeonCyan)
                }
                IconButton(onClick = { viewModel.refreshGames() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = NeonCyan)
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = NeonCyan)
                }
            }
        )

        // 搜索框
        OutlinedTextField(
            value = searchText,
            onValueChange = {
                searchText = it
                viewModel.searchGames(it)
            },
            placeholder = { Text("搜索游戏…", color = Color.White.copy(alpha = 0.4f)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NeonCyan) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        // 平台筛选标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PlatformChip("全部", selectedPlatform == null) {
                viewModel.filterByPlatform(null)
            }
            Platform.values().forEach { platform ->
                PlatformChip(platform.displayName, selectedPlatform == platform) {
                    viewModel.filterByPlatform(platform)
                }
            }
        }

        // 游戏列表
        if (games.isEmpty()) {
            EmptyState(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(games) { game ->
                    GameCard(game = game, onClick = { onGameClick(game) })
                }
            }
        }
    }
}

@Composable
private fun PlatformChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = NeonPurple.copy(alpha = 0.3f),
            selectedLabelColor = NeonPurple
        )
    )
}

@Composable
private fun GameCard(game: GameInfo, onClick: () -> Unit) {
    val platformColor = when (game.platform) {
        Platform.NES -> NeonPink
        Platform.SNES -> NeonCyan
        Platform.GENESIS -> NeonPurple
        Platform.ARCADE -> Color(0xFFFFE600)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 平台图标
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(platformColor.copy(alpha = 0.15f))
                    .border(1.dp, platformColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = game.platform.displayName,
                    color = platformColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = game.name,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (game.hasPlayed) "游玩 ${game.playCount} 次" else "未游玩",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }

            // 文件大小
            if (game.fileSize > 0) {
                Text(
                    text = formatFileSize(game.fileSize),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "游戏库为空",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "点击右上角下载按钮获取游戏",
            color = Color.White.copy(alpha = 0.3f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
    }
}
