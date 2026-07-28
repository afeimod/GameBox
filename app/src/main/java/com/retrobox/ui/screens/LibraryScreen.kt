package com.retrobox.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
 * 展示本地游戏列表，支持平台筛选、搜索，以及导入本地 ROM 文件。
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
    val downloadMessage by viewModel.downloadMessage.collectAsState()
    var searchText by remember { mutableStateOf("") }
    var showImportMenu by remember { mutableStateOf(false) }
    var showSnackbar by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // ===== 文件选择器：选择单个 ROM 文件 =====
    val singleFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 从 Uri 获取文件名
            val fileName = queryFileName(context, uri) ?: "imported_rom"
            viewModel.importLocalRom(uri, fileName) { _ -> }
        }
    }

    // ===== 目录选择器：选择整个文件夹批量扫描 =====
    val dirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.scanCustomDirectory(uri) { _ -> }
        }
    }

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
                // 导入本地游戏（下拉菜单）
                Box {
                    IconButton(onClick = { showImportMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = "导入游戏", tint = NeonCyan)
                    }
                    DropdownMenu(
                        expanded = showImportMenu,
                        onDismissRequest = { showImportMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("选择 ROM 文件") },
                            onClick = {
                                showImportMenu = false
                                // 支持的 ROM 扩展名作为 MIME 过滤
                                singleFilePicker.launch(arrayOf(
                                    "application/octet-stream",
                                    "application/zip",
                                    "application/x-7z-compressed",
                                    "*/*"
                                ))
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Add, contentDescription = null, tint = NeonCyan)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("扫描文件夹") },
                            onClick = {
                                showImportMenu = false
                                dirPicker.launch(null)
                            },
                            leadingIcon = {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = NeonCyan)
                            }
                        )
                    }
                }
                // 在线下载
                IconButton(onClick = onDownloadClick) {
                    Icon(Icons.Default.Download, contentDescription = "下载", tint = NeonCyan)
                }
                // 刷新
                IconButton(onClick = { viewModel.refreshGames() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = NeonCyan)
                }
                // 设置
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

        // 操作提示条
        if (downloadMessage != null) {
            Text(
                text = downloadMessage!!,
                color = NeonCyan,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

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

/**
 * 从 Uri 查询文件名
 */
private fun queryFileName(context: android.content.Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                result = cursor.getString(nameIndex)
            }
        }
    }
    if (result == null) {
        result = uri.lastPathSegment
    }
    return result
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
            text = "点击右上角 + 导入本地游戏，或下载按钮在线获取",
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
