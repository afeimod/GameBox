package com.nesstation.app.ui.swf

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val Bg = Color(0xFF0D1117)
private val CardBg = Color(0xFF1E2A3A)
private val PrimaryText = Color(0xFFE2E8F0)
private val SecondaryText = Color(0xFF8899AA)
private val Accent = Color(0xFF8A7BFF)
private val Gold = Color(0xFFFFD66B)
private val DeleteColor = Color(0xFFE74C3C)

@Composable
fun SwfListScreen(
    onBack: () -> Unit,
    onOpenSwf: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(0) } // 0=列表, 1=浏览
    var swfList by remember { mutableStateOf(SwfStore.list(context)) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }

    // 文件浏览状态
    val startDir = remember { Environment.getExternalStorageDirectory() ?: File("/") }
    var currentDir by remember { mutableStateOf(startDir) }

    val swfFiles = remember(currentDir) {
        try {
            currentDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("swf", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    val subDirs = remember(currentDir) {
        try {
            currentDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    BackHandler {
        if (currentTab == 1 && currentDir != startDir && currentDir.parentFile != null) {
            currentDir = currentDir.parentFile!!
        } else {
            onBack()
        }
    }

    fun refreshList() {
        swfList = SwfStore.list(context)
    }

    Box(modifier = modifier.fillMaxSize().background(Bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PrimaryText)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("SWF 游戏", color = PrimaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (currentTab == 1) {
                        Text(currentDir.absolutePath, color = SecondaryText, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text("${swfList.size} 个已添加的游戏", color = SecondaryText, fontSize = 10.sp)
                    }
                }
            }

            // Tab selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabButton(
                    text = "SWF列表",
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    text = "浏览文件夹",
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = Color(0xFF1E2A3A), thickness = 1.dp)

            // Stats bar (browse mode)
            if (currentTab == 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${subDirs.size} 文件夹 · ${swfFiles.size} SWF", color = SecondaryText, fontSize = 11.sp)
                    TextButton(onClick = {
                        val count = SwfStore.scanFolder(context, currentDir.absolutePath)
                        if (count > 0) {
                            refreshList()
                            snackbarMsg = "已扫描添加 $count 个 SWF 文件"
                        } else {
                            snackbarMsg = "此文件夹没有 SWF 文件"
                        }
                    }) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = Gold, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("扫描此文件夹", color = Gold, fontSize = 11.sp)
                    }
                }
            }

            // Content
            if (currentTab == 0) {
                // ---- SWF List tab ----
                if (swfList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Movie, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.size(16.dp))
                            Text("还没有添加 SWF 游戏", color = SecondaryText, fontSize = 14.sp)
                            Spacer(Modifier.size(4.dp))
                            Text("切换到「浏览文件夹」添加 SWF 文件", color = SecondaryText, fontSize = 11.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(swfList, key = { it.path }) { entry ->
                            SwfListItem(
                                title = entry.title,
                                path = entry.path,
                                size = entry.size,
                                onPlay = { onOpenSwf(entry.path) },
                                onRemove = {
                                    SwfStore.remove(context, entry.path)
                                    refreshList()
                                    snackbarMsg = "已移除: ${entry.title}"
                                }
                            )
                        }
                    }
                }
            } else {
                // ---- Browse folder tab ----
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Up button
                    if (currentDir.parentFile != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { currentDir = currentDir.parentFile!! }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Folder, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.size(10.dp))
                                Text("..", color = SecondaryText, fontSize = 14.sp)
                            }
                        }
                    }

                    // Subdirectories
                    items(subDirs) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { currentDir = dir }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, tint = Gold, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(10.dp))
                            Text(dir.name, color = PrimaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    // SWF files
                    items(swfFiles) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CardBg.copy(alpha = 0.5f))
                                .clickable {
                                    SwfStore.add(context, file.absolutePath, file.nameWithoutExtension)
                                    refreshList()
                                    onOpenSwf(file.absolutePath)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Movie, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, color = PrimaryText, fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(formatSize(file.length()), color = SecondaryText, fontSize = 10.sp)
                            }
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "播放", tint = Accent, modifier = Modifier.size(16.dp))
                        }
                    }

                    // Empty state
                    if (subDirs.isEmpty() && swfFiles.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("此目录没有 SWF 文件", color = SecondaryText, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // Snackbar
        snackbarMsg?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { snackbarMsg = null }) {
                        Text("确定", color = Accent)
                    }
                }
            ) {
                Text(msg)
            }
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                snackbarMsg = null
            }
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Accent else SecondaryText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun SwfListItem(
    title: String,
    path: String,
    size: Long,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    var showRemoveConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg.copy(alpha = 0.5f))
            .clickable { onPlay() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Movie, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = PrimaryText, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "${formatSize(size)} · $path",
                color = SecondaryText, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = { showRemoveConfirm = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "移除", tint = DeleteColor, modifier = Modifier.size(16.dp))
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("移除游戏") },
            text = { Text("确定要从列表中移除「$title」吗？\n（不会删除原文件）") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirm = false
                    onRemove()
                }) { Text("移除", color = DeleteColor) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("取消") }
            }
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
