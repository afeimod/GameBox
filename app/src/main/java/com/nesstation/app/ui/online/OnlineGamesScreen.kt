package com.nesstation.app.ui.online

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.ui.components.PixelBackdrop
import kotlinx.coroutines.delay

// ---- Home-style palette (matches HomeScreen / LibraryScreen) ----
private val PrimaryText = Color(0xFF1E2A3A)
private val SecondaryText = Color(0xFF4A5568)
private val SecondaryTextLight = Color(0xFF8899AA)
private val Accent = Color(0xFF8A7BFF)
private val DeleteColor = Color(0xFFE74C3C)

/** Accent color palette cycled across the grid cards. */
private val AccentPalette = listOf(
    Color(0xFF8A7BFF), // 紫
    Color(0xFFE74C3C), // 红
    Color(0xFF27AE60), // 绿
    Color(0xFF3498DB), // 蓝
    Color(0xFFE67E22), // 橙
    Color(0xFF1ABC9C), // 青绿
    Color(0xFF9B59B6), // 紫2
    Color(0xFFF1C40F), // 黄
    Color(0xFFE84393), // 粉
    Color(0xFF00CEC9), // 青
    Color(0xFF6C5CE7), // 靛
    Color(0xFFFDCB6E)  // 浅橙
)

/**
 * 在线网页游戏列表（重写为首页风格）。
 *
 * 关键改动：
 * 1. 新增「返回主页」按钮（与 SWF 列表一致）。
 * 2. 使用 PixelBackdrop 像素风背景 + 圆角白色卡片（参考首页 GameCard 风格）。
 * 3. 保留：长按删除自定义游戏、添加自定义游戏对话框、UA 模式标识。
 */
@Composable
fun OnlineGamesScreen(
    onBack: () -> Unit,
    onHome: () -> Unit = onBack,
    onOpenGame: (WebGameEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var games by remember { mutableStateOf(WebGameStore.loadAll(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WebGameEntry?>(null) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        games = WebGameStore.loadAll(context)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // PixelBackdrop 像素风背景（与首页 / 库界面一致）
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
                // 新增：返回主页按钮
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
                        "在线游戏",
                        color = PrimaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        "${games.size} 个游戏网站",
                        color = SecondaryText,
                        fontSize = 10.sp
                    )
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = "添加游戏",
                        tint = Accent
                    )
                }
            }

            // ---- 卡片网格（首页风格：白底 + 圆角 + 色条） ----
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(games, key = { _, g -> g.url }) { index, game ->
                    WebGameCard(
                        game = game,
                        accent = AccentPalette[index % AccentPalette.size],
                        onClick = { onOpenGame(game) },
                        onLongClick = if (!game.isBuiltin) {
                            { pendingDelete = game }
                        } else null
                    )
                }
            }
        }

        // ---- FAB：快速添加 ----
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp),
            containerColor = Accent,
            contentColor = Color.White
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "添加在线游戏")
        }

        // ---- Snackbar ----
        snackbarMsg?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
                containerColor = Color(0xFF1E2A3A),
                contentColor = Color.White
            ) {
                Text(msg)
            }
            LaunchedEffect(msg) {
                delay(2200)
                snackbarMsg = null
            }
        }
    }

    // ---- Add dialog ----
    if (showAddDialog) {
        AddGameDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { entry ->
                WebGameStore.save(context, entry)
                showAddDialog = false
                refresh()
                snackbarMsg = "已添加：${entry.title}"
            }
        )
    }

    // ---- Delete confirm dialog ----
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除游戏", color = PrimaryText) },
            text = {
                Text(
                    "确定要删除「${target.title}」吗？\n（仅从自定义列表移除，内置游戏不受影响）",
                    color = SecondaryText
                )
            },
            containerColor = Color.White,
            titleContentColor = PrimaryText,
            textContentColor = SecondaryText,
            confirmButton = {
                TextButton(onClick = {
                    WebGameStore.delete(context, target.url)
                    pendingDelete = null
                    refresh()
                    snackbarMsg = "已删除：${target.title}"
                }) { Text("删除", color = DeleteColor) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = SecondaryText)
                }
            }
        )
    }
}

/**
 * 单个游戏卡片：模仿首页 GameCard 视觉（白底圆角 + 顶部色块渐变），
 * 但保留「长按删除」「UA 标识」「内置/自定义标签」等信息。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WebGameCard(
    game: WebGameEntry,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.85f))
            .then(clickModifier)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部色块（渐变 + 图标）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.55f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.SportsEsports,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
                // 右上角 UA 标识
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (game.uaMode == "mobile") Icons.Rounded.Smartphone
                        else Icons.Rounded.Computer,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(Modifier.size(3.dp))
                    Text(
                        text = if (game.uaMode == "mobile") "手机" else "PC",
                        color = accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // 底部信息
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = game.title,
                    color = PrimaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = game.url,
                    color = SecondaryTextLight,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!game.isBuiltin) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "长按删除",
                        color = SecondaryTextLight,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

/** 首页风格的「返回主页」按钮（与 SWF 列表一致）。 */
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

@Composable
private fun AddGameDialog(
    onDismiss: () -> Unit,
    onConfirm: (WebGameEntry) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var isMobile by remember { mutableStateOf(false) }

    val canSubmit = title.isNotBlank() && url.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加在线游戏", color = PrimaryText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("游戏名称") },
                    singleLine = true,
                    isError = title.isBlank(),
                    colors = lightFieldColors(),
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网址 URL") },
                    singleLine = true,
                    isError = url.isBlank(),
                    placeholder = {
                        Text(
                            "https://example.com",
                            color = SecondaryTextLight.copy(alpha = 0.6f)
                        )
                    },
                    colors = lightFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("UA 模式", color = SecondaryText, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UaToggleOption(
                        label = "PC（桌面端）",
                        selected = !isMobile,
                        accent = Accent,
                        onClick = { isMobile = false },
                        modifier = Modifier.weight(1f)
                    )
                    UaToggleOption(
                        label = "手机端",
                        selected = isMobile,
                        accent = Accent,
                        onClick = { isMobile = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        containerColor = Color.White,
        titleContentColor = PrimaryText,
        textContentColor = PrimaryText,
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    val raw = url.trim()
                    val finalUrl = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                        raw
                    } else {
                        "https://$raw"
                    }
                    onConfirm(
                        WebGameEntry(
                            title = title.trim(),
                            url = finalUrl,
                            isBuiltin = false,
                            uaMode = if (isMobile) "mobile" else "desktop"
                        )
                    )
                }
            ) { Text("添加", color = if (canSubmit) Accent else SecondaryTextLight) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = SecondaryText) }
        }
    )
}

@Composable
private fun UaToggleOption(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) accent else SecondaryText,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun lightFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PrimaryText,
    unfocusedTextColor = PrimaryText,
    cursorColor = Accent,
    focusedBorderColor = Accent,
    unfocusedBorderColor = SecondaryTextLight.copy(alpha = 0.5f),
    focusedLabelColor = Accent,
    unfocusedLabelColor = SecondaryText,
    errorCursorColor = DeleteColor,
    errorBorderColor = DeleteColor,
    errorLabelColor = DeleteColor
)
