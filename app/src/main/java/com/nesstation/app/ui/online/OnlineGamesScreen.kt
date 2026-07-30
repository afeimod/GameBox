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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Public
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---- Dark theme palette (per spec) ----
private val Bg = Color(0xFF0F1419)
private val CardBg = Color(0xFF1A2332)
private val Accent = Color(0xFF8A7BFF)
private val PrimaryText = Color(0xFFE2E8F0)
private val SecondaryText = Color(0xFF8899AA)
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

@Composable
fun OnlineGamesScreen(
    onBack: () -> Unit,
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

    Box(modifier = modifier.fillMaxSize().background(Bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- Top bar: back + title + count ----
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
                    Text("在线游戏", color = PrimaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${games.size} 个游戏网站", color = SecondaryText, fontSize = 10.sp)
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加游戏", tint = Accent)
                }
            }

            // ---- Grid ----
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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

        // ---- FAB: add custom game ----
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

        // ---- Snackbar feedback ----
        snackbarMsg?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp)
            ) {
                Text(msg)
            }
            LaunchedEffect(msg) {
                delay(2200)
                snackbarMsg = null
            }
        }
    }

    // ---- Add game dialog ----
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

    // ---- Delete confirm dialog (long-press on custom game) ----
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
            containerColor = CardBg,
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
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .then(clickModifier)
    ) {
        // Left colored accent strip
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(5.dp)
                .background(accent)
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxSize()
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: title + icon
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = game.title,
                    color = PrimaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(6.dp))
                Icon(
                    imageVector = Icons.Rounded.SportsEsports,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Middle: URL
            Text(
                text = game.url,
                color = SecondaryText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Bottom: UA badge (+ 自定义 tag for custom games)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // UA badge
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
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
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (!game.isBuiltin) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "自定义",
                        color = SecondaryText.copy(alpha = 0.7f),
                        fontSize = 9.sp
                    )
                }

                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    tint = SecondaryText.copy(alpha = 0.4f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
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

    val titleError = title.isBlank()
    val urlError = url.isBlank()
    val canSubmit = title.isNotBlank() && url.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加在线游戏", color = PrimaryText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Title input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("游戏名称") },
                    singleLine = true,
                    isError = titleError,
                    colors = darkFieldColors(),
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth()
                )

                // URL input
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网址 URL") },
                    singleLine = true,
                    isError = urlError,
                    placeholder = { Text("https://example.com", color = SecondaryText.copy(alpha = 0.5f)) },
                    colors = darkFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                // UA mode toggle (desktop / mobile)
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
        containerColor = CardBg,
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
            ) { Text("添加", color = if (canSubmit) Accent else SecondaryText) }
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

/** OutlinedTextField colors tuned for the dark dialog surface. */
@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PrimaryText,
    unfocusedTextColor = PrimaryText,
    cursorColor = Accent,
    focusedBorderColor = Accent,
    unfocusedBorderColor = SecondaryText.copy(alpha = 0.5f),
    focusedLabelColor = Accent,
    unfocusedLabelColor = SecondaryText,
    errorCursorColor = DeleteColor,
    errorBorderColor = DeleteColor,
    errorLabelColor = DeleteColor
)
