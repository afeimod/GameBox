package com.nesstation.app.ui.fsd

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LocalPlay
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MobileFriendly
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.PadLayoutStore
import org.json.JSONObject
import java.io.File

/** 平台 → 专属图标：每个类别磁贴一个可辨识的图标，不再是千篇一律的手柄。 */
private fun platformIcon(p: GamePlatform) = when (p) {
    GamePlatform.NES    -> Icons.Rounded.Gamepad         // FC 十字手柄
    GamePlatform.SFC    -> Icons.Rounded.VideogameAsset  // SFC 手柄
    GamePlatform.GB     -> Icons.Rounded.Smartphone      // 竖向掌机
    GamePlatform.GBA    -> Icons.Rounded.MobileFriendly  // 横向掌机
    GamePlatform.MD     -> Icons.Rounded.Computer        // 16-bit 主机
    GamePlatform.PCE    -> Icons.Rounded.Radio           // PC-E 小白机
    GamePlatform.PSX    -> Icons.Rounded.Album           // CD 光盘
    GamePlatform.NDS    -> Icons.Rounded.MenuBook        // 翻盖双屏
    GamePlatform.ARCADE -> Icons.Rounded.LocalPlay       // 街机厅票券（legacy 集无 Arcade 图标）
    GamePlatform.DOS    -> Icons.Rounded.Terminal        // DOS 命令行
    GamePlatform.JAVA   -> Icons.Rounded.LocalCafe       // Java 咖啡
}

/** 解析磁贴自定义图标 JSON（{ tileKey → iconPath }），损坏时返回空 map。 */
private fun parseTileIconMap(json: String): Map<String, String> {
    if (json.isBlank()) return emptyMap()
    return try {
        val obj = JSONObject(json)
        val map = LinkedHashMap<String, String>()
        obj.keys().forEach { key -> map[key] = obj.optString(key) }
        map.filterValues { it.isNotBlank() }
    } catch (_: Exception) {
        emptyMap()
    }
}

/**
 * FSD 桌面主页 — 仿 Xbox 360 Freestyle Dash 的磁贴主菜单。
 *
 * 结构（对照用户提供的 FSD 截图）：
 *   - 顶部系统状态条（CPU/内存/存储/时钟，竖屏自动紧凑）
 *   - 左上面包屑「主菜单 ▪ 游戏库」
 *   - 蓝/黄对角磁贴封面流：全部游戏 → 各平台（专属图标+数量）→ 功能入口
 *   - 「N of M」计数 + 底部 A/B 按键提示
 *   - 底部状态条（IP / 状态 / 日期 时间）
 *
 * 个性化：
 *   - 主页背景可在设置里换成图片 / 循环视频（[FsdCustomBackground]）
 *   - 长按任意磁贴（或按 Y 键）可给该磁贴设置自定义图标
 *
 * 手机与 TV 共用：磁贴流内建 D-pad 焦点导航（左/右切换、OK 激活），
 * 触屏设备点击磁贴即可。平台磁贴仅在库内有游戏时显示。
 */
@Composable
fun FsdHomeScreen(
    games: List<GameEntry>,
    onOpenLibrary: () -> Unit,
    onOpenPlatform: (GamePlatform?) -> Unit,
    onOpenOnlineGames: () -> Unit,
    onOpenBattle: () -> Unit,
    onOpenSwf: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // === 个性化状态（背景 + 磁贴图标），ON_RESUME 时从存储重载 ===
    var bgUri by rememberSaveable { mutableStateOf("") }
    var bgIsVideo by rememberSaveable { mutableStateOf(false) }
    var tileIconsJson by rememberSaveable { mutableStateOf("") }

    fun reloadPersonalization() {
        val pl = PadLayoutStore.load(context)
        bgUri = pl.homeBackgroundUri
        bgIsVideo = pl.homeBackgroundIsVideo
        tileIconsJson = pl.homeTileIcons
    }

    LaunchedEffect(Unit) { reloadPersonalization() }
    // 从设置页改完背景/图标返回主页时立即生效
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadPersonalization()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val tileIcons = remember(tileIconsJson) { parseTileIconMap(tileIconsJson) }

    val platformOrder = listOf(
        GamePlatform.NES, GamePlatform.SFC, GamePlatform.GB, GamePlatform.GBA,
        GamePlatform.MD, GamePlatform.PCE, GamePlatform.PSX, GamePlatform.NDS,
        GamePlatform.ARCADE, GamePlatform.DOS, GamePlatform.JAVA
    )
    val countByPlatform = remember(games) {
        games.groupingBy { it.platform }.eachCount()
    }

    val tiles = remember(games, countByPlatform, tileIcons) {
        buildList {
            add(FsdTileItem("all", "全部游戏", Icons.Rounded.GridView,
                badge = games.size.toString(), iconPath = tileIcons["all"]))
            platformOrder.forEach { p ->
                val n = countByPlatform[p] ?: 0
                if (n > 0) {
                    add(FsdTileItem("platform:${p.name}", p.displayName, platformIcon(p),
                        badge = n.toString(), iconPath = tileIcons["platform:${p.name}"]))
                }
            }
            add(FsdTileItem("online", "在线游戏", Icons.Rounded.Public,
                iconPath = tileIcons["online"]))
            add(FsdTileItem("battle", "对战平台", Icons.Rounded.SportsEsports,
                iconPath = tileIcons["battle"]))
            add(FsdTileItem("swf", "SWF/Flash", Icons.Rounded.PlayArrow,
                iconPath = tileIcons["swf"]))
            add(FsdTileItem("settings", "设置", Icons.Rounded.Settings,
                iconPath = tileIcons["settings"]))
            add(FsdTileItem("about", "关于", Icons.AutoMirrored.Rounded.HelpOutline,
                iconPath = tileIcons["about"]))
            add(FsdTileItem("exit", "退出", Icons.AutoMirrored.Rounded.Logout,
                iconPath = tileIcons["exit"]))
        }
    }

    var selected by rememberSaveable { mutableIntStateOf(0) }
    // 列表变化（如新导入游戏）后安全收敛索引
    val safeSelected = if (tiles.isEmpty()) 0 else selected.coerceIn(0, tiles.size - 1)

    // 长按磁贴弹出的「磁贴选项」对话框索引
    var tileMenuIdx by remember { mutableStateOf<Int?>(null) }
    var pendingTileIconIdx by remember { mutableStateOf<Int?>(null) }

    fun activate(idx: Int) {
        val tile = tiles.getOrNull(idx) ?: return
        when {
            tile.key == "all" -> onOpenLibrary()
            tile.key.startsWith("platform:") ->
                onOpenPlatform(GamePlatform.fromString(tile.key.removePrefix("platform:")))
            tile.key == "online" -> onOpenOnlineGames()
            tile.key == "battle" -> onOpenBattle()
            tile.key == "swf" -> onOpenSwf()
            tile.key == "settings" -> onOpenSettings()
            tile.key == "about" -> onOpenAbout()
            tile.key == "exit" -> onExit()
        }
    }

    // 长按磁贴 → 挑选自定义图标（拷贝到 filesDir/icons，立即写回存储）
    val tileIconPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val idx = pendingTileIconIdx
        val tile = idx?.let { tiles.getOrNull(it) }
        pendingTileIconIdx = null
        val uri = uris.firstOrNull()
        if (uri == null || tile == null) return@rememberLauncherForActivityResult
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val iconsDir = File(context.filesDir, "icons").apply { mkdirs() }
            val dest = File(iconsDir, "tile_${tile.key.replace(':', '_')}_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取所选图片")
            val newMap = LinkedHashMap<String, String>(tileIcons)
            newMap[tile.key] = dest.absolutePath
            val json = JSONObject().apply { newMap.forEach { (k, v) -> put(k, v) } }.toString()
            val layout = PadLayoutStore.load(context)
            PadLayoutStore.save(context, layout.copy { homeTileIcons = json })
            tileIconsJson = json   // 立即刷新
        } catch (_: Exception) {
            // 拷贝失败保持现状，不打断主页
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 背景：用户自定义（图片/视频）优先，否则默认 FSD 深蓝壁纸
        if (bgUri.isNotBlank()) {
            FsdCustomBackground(uriString = bgUri, isVideo = bgIsVideo)
        } else {
            FsdBackdrop()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            FsdTopBar()

            Spacer(Modifier.height(10.dp))
            FsdBreadcrumb(listOf("主菜单", "游戏库"))

            FsdTileFlow(
                items = tiles,
                selectedIndex = safeSelected,
                onIndexChange = { selected = it },
                onActivate = ::activate,
                onItemLongClick = { tileMenuIdx = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // 底部按键提示 + 状态条
            FsdButtonHints(
                hints = listOf(
                    FsdButtonHint("A", "选择", Fsd.BtnA),
                    FsdButtonHint("B", "返回", Fsd.BtnB)
                ),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 8.dp)
            )
            FsdBottomBar(status = "主菜单")
        }
    }

    // === 磁贴选项对话框：自定义图标 / 恢复默认 ===
    tileMenuIdx?.let { idx ->
        val tile = tiles.getOrNull(idx)
        if (tile == null) {
            tileMenuIdx = null
        } else {
            AlertDialog(
                onDismissRequest = { tileMenuIdx = null },
                title = {
                    Text(
                        "磁贴选项 · ${tile.label}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "可为本磁贴设置自定义图标（支持图片）。图标立即生效并持久保存。",
                        fontSize = 13.sp,
                        color = Color(0xFF4A5568)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingTileIconIdx = idx
                        tileMenuIdx = null
                        runCatching { tileIconPicker.launch(arrayOf("image/*")) }
                    }) { Text("自定义图标") }
                },
                dismissButton = {
                    if (tile.iconPath != null) {
                        TextButton(onClick = {
                            val newMap = LinkedHashMap<String, String>(tileIcons)
                            newMap.remove(tile.key)
                            val json = JSONObject().apply { newMap.forEach { (k, v) -> put(k, v) } }.toString()
                            val layout = PadLayoutStore.load(context)
                            PadLayoutStore.save(context, layout.copy { homeTileIcons = json })
                            tileIconsJson = json
                            tileMenuIdx = null
                        }) { Text("恢复默认") }
                    } else {
                        TextButton(onClick = { tileMenuIdx = null }) { Text("取消") }
                    }
                }
            )
        }
    }
}
