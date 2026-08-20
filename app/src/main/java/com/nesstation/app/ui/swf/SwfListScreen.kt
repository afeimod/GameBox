package com.nesstation.app.ui.swf

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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
import com.nesstation.app.ui.components.PixelBackdrop
import kotlinx.coroutines.delay
import java.io.File

// ---- Home-style palette (matches HomeScreen / LibraryScreen) ----
private val PrimaryText = Color(0xFF1E2A3A)
private val SecondaryText = Color(0xFF4A5568)
private val SecondaryTextLight = Color(0xFF8899AA)
private val Accent = Color(0xFF8A7BFF)
private val Gold = Color(0xFFFFD66B)
private val DeleteColor = Color(0xFFE74C3C)
private val CardBgWhite = Color.White.copy(alpha = 0.78f)

/** Accent color palette for SWF cards (cycled by index). */
private val AccentPalette = listOf(
    Color(0xFF8A7BFF), Color(0xFFE74C3C), Color(0xFF27AE60), Color(0xFF3498DB),
    Color(0xFFE67E22), Color(0xFF1ABC9C), Color(0xFF9B59B6), Color(0xFFF1C40F)
)

/**
 * SWF 游戏库界面（重写为首页风格）。
 *
 * 关键改动：
 * 1. 新增「返回主页」按钮（同时保留左上角箭头），跳转到 HOME 路由。
 * 2. 模仿首页：使用 PixelBackdrop 像素风背景 + 圆角白色 GameCard 网格。
 * 3. 保留双 Tab：「我的游戏」（已添加） / 「浏览文件」（系统文件浏览器）。
 * 4. 已添加游戏用 GameCard 网格展示（修复之前列表不好看的问题）。
 *
 * @param onBack  返回上一级（保留旧入口）
 * @param onHome  返回主页（新增）
 * @param onOpenSwf 打开指定 SWF 文件
 */
@Composable
fun SwfListScreen(
    onBack: () -> Unit,
    onHome: () -> Unit = onBack,
    onOpenSwf: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(0) } // 0=我的游戏, 1=浏览文件
    var swfList by remember { mutableStateOf(SwfStore.list(context).distinctBy { it.path }) }
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
        // 关键修复：去重逻辑
        // 之前 swfList 直接赋值为 SwfStore.list(context)，如果 SwfStore.add 因为某种
        // 边界情况没去重（比如在并发调用下），这里再加一道 distinctBy 兜底。
        swfList = SwfStore.list(context).distinctBy { it.path }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // PixelBackdrop 像素风背景（与首页一致）
        PixelBackdrop()

        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 顶部栏：返回箭头 + 返回主页 + 标题 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = PrimaryText
                    )
                }
                // 新增：返回主页按钮（与首页风格一致，使用半透明白色 pill）
                HomePill(
                    onClick = onHome,
                    modifier = Modifier.padding(start = 2.dp)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                ) {
                    Text(
                        "SWF 游戏",
                        color = PrimaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        if (currentTab == 1) currentDir.absolutePath
                        else "${swfList.size} 个已添加的游戏",
                        color = SecondaryText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ---- Tab 切换器（首页风格） ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabPill(
                    text = "我的游戏",
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    modifier = Modifier.weight(1f)
                )
                TabPill(
                    text = "浏览文件",
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    modifier = Modifier.weight(1f)
                )
            }

            // ---- 文件浏览模式下的扫描栏 ----
            if (currentTab == 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${subDirs.size} 文件夹 · ${swfFiles.size} SWF",
                        color = SecondaryText,
                        fontSize = 11.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.7f))
                            .clickable {
                                val count = SwfStore.scanFolder(context, currentDir.absolutePath)
                                if (count > 0) {
                                    refreshList()
                                    snackbarMsg = "已扫描添加 $count 个 SWF 文件"
                                } else {
                                    snackbarMsg = "此文件夹没有 SWF 文件"
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                tint = Gold,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "扫描此文件夹",
                                color = PrimaryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // ---- 内容区 ----
            if (currentTab == 0) {
                // 我的游戏：首页 GameCard 网格风格
                if (swfList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = SecondaryText,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.size(16.dp))
                            Text(
                                "还没有添加 SWF 游戏",
                                color = SecondaryText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "切换到「浏览文件」添加本地 SWF 文件",
                                color = SecondaryTextLight,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(120.dp),
                        contentPadding = PaddingValues(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(swfList, key = { it.path }) { entry ->
                            SwfGameCard(
                                title = entry.title,
                                size = entry.size,
                                accent = AccentPalette[swfList.indexOf(entry) % AccentPalette.size],
                                onClick = { onOpenSwf(entry.path) },
                                onLongClick = {
                                    SwfStore.remove(context, entry.path)
                                    refreshList()
                                    snackbarMsg = "已移除: ${entry.title}"
                                }
                            )
                        }
                    }
                }
            } else {
                // 浏览文件
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Up
                    if (currentDir.parentFile != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.6f))
                                    .clickable { currentDir = currentDir.parentFile!! }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = SecondaryText,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.size(10.dp))
                                Text("..", color = PrimaryText, fontSize = 14.sp)
                            }
                        }
                    }
                    // 子目录
                    items(subDirs) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.7f))
                                .clickable { currentDir = dir }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = Gold,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                dir.name,
                                color = PrimaryText,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // SWF 文件
                    items(swfFiles) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.85f))
                                .clickable {
                                    SwfStore.add(context, file.absolutePath, file.nameWithoutExtension)
                                    refreshList()
                                    onOpenSwf(file.absolutePath)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.size(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    color = PrimaryText,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    formatSize(file.length()),
                                    color = SecondaryText,
                                    fontSize = 10.sp
                                )
                            }
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "播放",
                                tint = Accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (subDirs.isEmpty() && swfFiles.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "此目录没有 SWF 文件",
                                    color = SecondaryText,
                                    fontSize = 13.sp
                                )
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
                    .navigationBarsPadding()
                    .padding(16.dp),
                containerColor = Color(0xFF1E2A3A),
                contentColor = Color.White,
                action = {
                    TextButton(onClick = { snackbarMsg = null }) {
                        Text("确定", color = Accent)
                    }
                }
            ) {
                Text(msg)
            }
            LaunchedEffect(msg) {
                delay(2200)
                snackbarMsg = null
            }
        }
    }
}

/** 首页风格的「返回主页」按钮（半透明白色 pill + 房子图标）。 */
@Composable
private fun HomePill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Icon(
            Icons.Rounded.Home,
            contentDescription = "返回主页",
            tint = PrimaryText,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "主页",
            color = PrimaryText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Tab 切换 pill（首页风格：白底 + 选中变深）。 */
@Composable
private fun TabPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Color(0xFF1E2A3A) else Color.White.copy(alpha = 0.6f)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Color.White else PrimaryText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

/** SWF 卡片：复用首页 GameCard 视觉，但加上长按删除。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SwfGameCard(
    title: String,
    size: Long,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var showRemoveConfirm by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.85f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showRemoveConfirm = true }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部色块（与首页 GameCard 一致）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.55f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    color = PrimaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatSize(size),
                    color = SecondaryTextLight,
                    fontSize = 10.sp
                )
            }
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
                    onLongClick()
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
