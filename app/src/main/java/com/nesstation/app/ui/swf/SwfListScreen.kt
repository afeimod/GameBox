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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@Composable
fun SwfListScreen(
    onBack: () -> Unit,
    onOpenSwf: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val startDir = remember {
        Environment.getExternalStorageDirectory() ?: File("/")
    }
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
        if (currentDir == startDir || currentDir.parentFile == null) {
            onBack()
        } else {
            currentDir = currentDir.parentFile ?: run { onBack(); return@BackHandler }
        }
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
                    Text(currentDir.absolutePath, color = SecondaryText, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Stats bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${subDirs.size} 个文件夹", color = SecondaryText, fontSize = 11.sp)
                Text("${swfFiles.size} 个 SWF", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            HorizontalDivider(color = Color(0xFF1E2A3A), thickness = 1.dp)

            // File list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Up button if not root
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
                            .clickable { onOpenSwf(file.absolutePath) }
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
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
