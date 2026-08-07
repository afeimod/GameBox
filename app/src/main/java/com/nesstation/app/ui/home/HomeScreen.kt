package com.nesstation.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.JavaGameStore
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.ui.components.BottomDock
import com.nesstation.app.ui.components.GameCard
import com.nesstation.app.ui.components.PixelBackdrop
import kotlinx.coroutines.delay

/**
 * Main home screen — only shows "最近游玩" (recent games) section.
 * Compact header, content moved up, small centered dock at bottom.
 *
 * Dock buttons (left to right): 游戏库 / 文件 / SWF / 设置 / 关于 / 退出
 */
@Composable
fun HomeScreen(
    onOpenGame: (GameEntry) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenFileList: () -> Unit,
    onOpenOnlineGames: () -> Unit,
    onOpenSwf: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit,
    onLongClickGame: (GameEntry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { time = System.currentTimeMillis(); delay(33) }
    }

    // Load recent games (NES + Java) — refresh on every ON_RESUME so the list
    // updates when the user returns from Library (after importing ROMs) or
    // from the emulator (after playing a game).
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun loadRecents(): List<GameEntry> {
        val nes = RomStore.loadAll(context)
        val java = JavaGameStore.loadAll(context)
        val all = (nes + java).distinctBy { it.id }
        // 有最近游玩时间的按时间倒序排前，其余保持原顺序在后
        val (played, unplayed) = all.partition { it.lastPlayedAt > 0 }
        return played.sortedByDescending { it.lastPlayedAt } + unplayed
    }

    var recents by remember { mutableStateOf(loadRecents()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                recents = loadRecents()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PixelBackdrop(timeMs = time)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
        ) {
            // Compact header — title + quick actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "NesStation",
                        color = Color(0xFF1E2A3A),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "为复古而生",
                        color = Color(0xFF4A5568),
                        fontSize = 11.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeaderPill(Icons.Rounded.Search, "搜索", onClick = onOpenLibrary)
                    HeaderPill(Icons.Rounded.Save, "存档", onClick = onOpenLibrary)
                }
            }

            // Section: 最近游玩 (only section on home) — 标题旁附加平台标识
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "最近游玩",
                    color = Color(0xFF1E2A3A),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                recents.map { it.platform }.distinct().forEach { p ->
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (p == GamePlatform.JAVA)
                                    Color(0xFF6A1B9A).copy(alpha = 0.85f)
                                else Color(0xFF1E2A3A).copy(alpha = 0.85f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = p.displayName,
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

            if (recents.isEmpty()) {
                // Empty state — prompt user to import games
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "还没有导入游戏",
                            color = Color(0xFF8899AA),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = "点击下方「游戏库」导入 ROM 文件",
                            color = Color(0xFF4A5568),
                            fontSize = 11.sp
                        )
                    }
                }
            } else if (isPortrait) {
                // Portrait mode: two-column vertical grid instead of horizontal LazyRow
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    gridItems(recents) { g ->
                        GameCard(
                            title = g.title,
                            accent = g.accent,
                            onClick = { onOpenGame(g) },
                            onLongClick = { onLongClickGame(g) },
                            coverPath = g.customIconPath ?: g.coverPath,
                            platform = g.platform,
                            modifier = Modifier.height(100.dp)
                        )
                    }
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.height(150.dp).fillMaxWidth()
                ) {
                    items(recents) { g ->
                        GameCard(
                            title = g.title,
                            accent = g.accent,
                            onClick = { onOpenGame(g) },
                            onLongClick = { onLongClickGame(g) },
                            coverPath = g.customIconPath ?: g.coverPath,
                            platform = g.platform,
                            modifier = Modifier.size(width = 120.dp, height = 145.dp)
                        )
                    }
                }
            }

            // Spacer to push dock to bottom
            Spacer(modifier = Modifier.weight(1f))
        }

        // Bottom dock — in portrait mode, placed on the left side; otherwise at bottom center
        val dockAlignment = if (isPortrait) Alignment.CenterStart else Alignment.BottomCenter
        Box(
            modifier = Modifier
                .align(dockAlignment)
                .padding(bottom = if (isPortrait) 0.dp else 6.dp, start = if (isPortrait) 4.dp else 0.dp)
        ) {
            BottomDock(
                selectedIndex = 0,
                isPortrait = isPortrait,
                onSelect = { idx ->
                    when (idx) {
                        0 -> onOpenLibrary()         // 游戏库
                        1 -> onOpenOnlineGames()     // 在线游戏
                        2 -> onOpenSwf()             // SWF
                        3 -> onOpenSettings()        // 设置
                        4 -> onOpenAbout()           // 关于
                        5 -> onExit()                // 退出
                    }
                }
            )
        }
    }
}

@Composable
private fun HeaderPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                color = Color.White.copy(alpha = 0.5f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF1E2A3A), modifier = Modifier.size(13.dp))
        Spacer(Modifier.size(3.dp))
        Text(label, color = Color(0xFF1E2A3A), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
