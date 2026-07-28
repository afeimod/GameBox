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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrobox.data.Platform
import com.retrobox.download.DownloadStatus
import com.retrobox.download.DownloadTask
import com.retrobox.download.GameDownloadInfo
import com.retrobox.download.GamePlatform
import com.retrobox.ui.theme.NeonCyan
import com.retrobox.ui.theme.NeonPink
import com.retrobox.ui.theme.NeonPurple
import com.retrobox.ui.theme.NeonGreen
import com.retrobox.ui.theme.CyberBackground
import com.retrobox.ui.theme.CyberSurface
import com.retrobox.ui.viewmodel.GameViewModel

/**
 * 在线下载界面
 *
 * 从 Gitee 仓库拉取游戏列表并下载 ROM 文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    viewModel: GameViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val downloadList by viewModel.downloadList.collectAsState()
    val downloadTasks by viewModel.downloadTasks.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val message by viewModel.downloadMessage.collectAsState()
    var searchText by remember { mutableStateOf("") }
    var selectedPlatform by remember { mutableStateOf<GamePlatform?>(null) }

    // 首次进入自动拉取
    LaunchedEffect(Unit) {
        if (downloadList.isEmpty()) {
            viewModel.fetchGameList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBackground)
    ) {
        TopAppBar(
            title = {
                Text("在线下载", color = NeonPurple, fontWeight = FontWeight.Bold)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
            },
            actions = {
                IconButton(onClick = { viewModel.fetchGameList(selectedPlatform) }) {
                    Icon(Icons.Default.CloudDownload, contentDescription = "刷新列表", tint = NeonCyan)
                }
            }
        )

        // 搜索框
        OutlinedTextField(
            value = searchText,
            onValueChange = {
                searchText = it
                viewModel.searchOnlineGames(it)
            },
            placeholder = { Text("搜索游戏…", color = Color.White.copy(alpha = 0.4f)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NeonCyan) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        // 平台筛选
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedPlatform == null,
                onClick = {
                    selectedPlatform = null
                    viewModel.fetchGameList(null)
                },
                label = { Text("全部", fontSize = 13.sp) }
            )
            GamePlatform.values().forEach { platform ->
                FilterChip(
                    selected = selectedPlatform == platform,
                    onClick = {
                        selectedPlatform = platform
                        viewModel.fetchGameList(platform)
                    },
                    label = { Text(platform.display, fontSize = 13.sp) }
                )
            }
        }

        // 消息提示
        message?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NeonCyan.copy(alpha = 0.1f))
                    .padding(12.dp)
            ) {
                Text(text = msg, color = NeonCyan, fontSize = 13.sp)
            }
        }

        // 下载中进度
        if (isDownloading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = NeonPurple
            )
        }

        // 下载任务列表
        val activeTasks = downloadTasks.filter {
            it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PENDING
        }
        if (activeTasks.isNotEmpty()) {
            Text(
                text = "下载任务",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            )
            activeTasks.forEach { task ->
                DownloadTaskItem(
                    task = task,
                    onPause = { viewModel.pauseDownload(task.id) },
                    onResume = { viewModel.resumeDownload(task.id) },
                    onCancel = { viewModel.cancelDownload(task.id) }
                )
            }
        }

        // 游戏列表
        if (downloadList.isEmpty() && !isDownloading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "暂无游戏数据",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 16.sp
                )
                Text(
                    "请检查 Gitee 仓库配置",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(downloadList) { game ->
                    DownloadGameCard(
                        game = game,
                        isDownloading = downloadTasks.any {
                            it.fileName.startsWith(game.name) && it.status == DownloadStatus.RUNNING
                        },
                        isCompleted = downloadTasks.any {
                            it.fileName.startsWith(game.name) && it.status == DownloadStatus.COMPLETED
                        },
                        onDownload = { viewModel.downloadGame(game) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadGameCard(
    game: GameDownloadInfo,
    isDownloading: Boolean,
    isCompleted: Boolean,
    onDownload: () -> Unit
) {
    val platformColor = when (game.platform) {
        GamePlatform.FC -> NeonPink
        GamePlatform.SFC -> NeonCyan
        GamePlatform.MD -> NeonPurple
        GamePlatform.ARCADE -> Color(0xFFFFE600)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 平台标签
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(platformColor.copy(alpha = 0.15f))
                    .border(1.dp, platformColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = game.platform.display,
                    color = platformColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = game.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (game.fileSize > 0) {
                    Text(
                        text = formatFileSize(game.fileSize),
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 11.sp
                    )
                }
            }

            // 下载按钮
            when {
                isCompleted -> {
                    Text(
                        "已下载",
                        color = NeonGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                isDownloading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = NeonCyan
                    )
                }
                else -> {
                    IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "下载",
                            tint = NeonCyan
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskItem(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CyberSurface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.fileName,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (task.progress >= 0) {
                LinearProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    color = NeonPurple
                )
                Text(
                    text = "${task.progress}%",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
        }

        if (task.status == DownloadStatus.RUNNING) {
            IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Pause, contentDescription = "暂停", tint = NeonCyan, modifier = Modifier.size(18.dp))
            }
        } else if (task.status == DownloadStatus.PAUSED) {
            IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "继续", tint = NeonGreen, modifier = Modifier.size(18.dp))
            }
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "取消", tint = NeonPink, modifier = Modifier.size(18.dp))
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
    }
}
