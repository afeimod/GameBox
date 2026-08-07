package com.nesstation.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.JavaGameStore
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.ui.emulator.EmulatorScreen
import com.nesstation.app.ui.home.HomeScreen
import com.nesstation.app.ui.library.LibraryScreen
import com.nesstation.app.ui.settings.KeyMapScreen
import com.nesstation.app.ui.settings.SettingsScreen
import com.nesstation.app.ui.tv.TvHomeScreen
import com.nesstation.app.ui.files.FileListScreen
import com.nesstation.app.ui.swf.SwfListScreen
import com.nesstation.app.ui.swf.SwfPlayerScreen
import com.nesstation.app.ui.about.AboutScreen
import com.nesstation.app.ui.online.OnlineGamesScreen
import com.nesstation.app.ui.online.WebGameScreen
import java.io.File

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val FAVORITES = "favorites"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val KEYMAP = "keymap"
    const val FILE_LIST = "file_list"
    const val SWF_LIST = "swf_list"
    const val ONLINE_GAMES = "online_games"
    const val WEB_GAME = "web_game/{url}/{uaMode}"
    const val ABOUT = "about"
    const val EMULATOR = "emulator/{gameId}"
    const val SWF_PLAYER = "swf_player/{swfPath}"
    fun emulator(id: String) = "emulator/$id"
    fun swfPlayer(path: String) = "swf_player/${java.net.URLEncoder.encode(path, "UTF-8")}"
    fun webGame(url: String, uaMode: String) =
        "web_game/${java.net.URLEncoder.encode(url, "UTF-8")}/$uaMode"
}

@Composable
fun NesApp() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isTv = remember {
        ctx.packageManager.hasSystemFeature("android.hardware.touchscreen").not()
    }

    // 同时加载 NES 与 Java 游戏库（按 id 去重）
    fun loadAllGames(ctx: Context): List<GameEntry> {
        val nesGames = RomStore.loadAll(ctx)
        val javaGames = JavaGameStore.loadAll(ctx)
        return (nesGames + javaGames).distinctBy { it.id }
    }

    var games by remember { mutableStateOf(loadAllGames(ctx)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                games = loadAllGames(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val reloadGames: () -> Unit = { games = loadAllGames(ctx) }

    if (isTv) {
        TvNavHost(nav = nav, games = games)
    } else {
        PhoneNavHost(nav = nav, games = games, reloadGames = reloadGames)
    }
}

@Composable
private fun PhoneNavHost(
    nav: androidx.navigation.NavHostController,
    games: List<GameEntry>,
    reloadGames: () -> Unit
) {
    val ctx = LocalContext.current

    // 平台感知的打开游戏：Java 直接启动 J2ME 引擎，NES 进入模拟器
    val openGame: (GameEntry) -> Unit = { game ->
        if (game.platform == GamePlatform.JAVA) {
            JavaGameStore.launchGame(ctx, game)
        } else {
            nav.navigate(Routes.emulator(game.id))
        }
    }

    // 长按菜单状态（由 HomeScreen 的 onLongClickGame 触发；LibraryScreen 自身处理长按菜单）
    var longPressGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingIconGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingDeleteGame by remember { mutableStateOf<GameEntry?>(null) }

    // 自定义图标选择器（HomeScreen 长按菜单使用）
    val iconPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val game = pendingIconGame
        if (uris.isEmpty() || game == null) {
            pendingIconGame = null
            return@rememberLauncherForActivityResult
        }
        val uri = uris.first()
        try {
            val iconsDir = File(ctx.filesDir, "icons").apply { mkdirs() }
            val iconFile = File(iconsDir, "icon_${game.id}_${System.currentTimeMillis()}.png")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                iconFile.outputStream().use { output -> input.copyTo(output) }
            }
            RomStore.setCustomIcon(ctx, game.id, iconFile.absolutePath)
            reloadGames()
        } catch (_: Exception) { }
        pendingIconGame = null
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenGame = openGame,
                onOpenLibrary = { nav.navigate(Routes.LIBRARY) },
                onOpenFileList = { nav.navigate(Routes.FILE_LIST) },
                onOpenOnlineGames = { nav.navigate(Routes.ONLINE_GAMES) },
                onOpenSwf = { nav.navigate(Routes.SWF_LIST) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onExit = { nav.context.let { (it as? android.app.Activity)?.finishAffinity() } },
                onLongClickGame = { longPressGame = it }
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                games = games,
                onOpenGame = openGame,
                onBack = { nav.popBackStack() },
                onHome = {
                    // 返回主页：弹出到 HOME 路由
                    nav.popBackStack(Routes.HOME, inclusive = false)
                },
                onImport = { /* TODO: ACTION_OPEN_DOCUMENT */ },
                onSearch = { /* TODO */ },
                onLongClickGame = { }   // LibraryScreen 自身处理长按菜单
            )
        }
        composable(Routes.FILE_LIST) {
            FileListScreen(
                onBack = { nav.popBackStack() },
                onOpenSwf = { path -> nav.navigate(Routes.swfPlayer(path)) }
            )
        }
        composable(Routes.SWF_LIST) {
            SwfListScreen(
                onBack = { nav.popBackStack() },
                onHome = {
                    // 返回主页：弹出到 HOME 路由为止，确保按返回键不会回到 SWF 列表
                    nav.popBackStack(Routes.HOME, inclusive = false)
                },
                onOpenSwf = { path -> nav.navigate(Routes.swfPlayer(path)) }
            )
        }
        composable(Routes.ONLINE_GAMES) {
            OnlineGamesScreen(
                onBack = { nav.popBackStack() },
                onHome = {
                    // 返回主页：弹出到 HOME 路由
                    nav.popBackStack(Routes.HOME, inclusive = false)
                },
                onOpenGame = { game ->
                    nav.navigate(Routes.webGame(game.url, game.uaMode))
                }
            )
        }
        composable(
            Routes.WEB_GAME,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("uaMode") { type = NavType.StringType }
            )
        ) { entry ->
            val encodedUrl = entry.arguments?.getString("url") ?: ""
            val url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            val uaMode = entry.arguments?.getString("uaMode") ?: "desktop"
            WebGameScreen(
                url = url,
                uaMode = uaMode,
                onExit = { nav.popBackStack() }
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.FAVORITES) {
            LibraryScreen(
                games = games.filter { it.isFavorite },
                onOpenGame = openGame,
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onImport = { },
                onSearch = { },
                onLongClickGame = { }
            )
        }
        composable(Routes.HISTORY) {
            LibraryScreen(
                games = games,
                onOpenGame = openGame,
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onImport = { },
                onSearch = { },
                onLongClickGame = { }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenKeyMap = { nav.navigate(Routes.KEYMAP) }
            )
        }
        composable(Routes.KEYMAP) {
            KeyMapScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.EMULATOR,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("gameId") ?: ""
            val game = games.firstOrNull { it.id == id }
                ?: RomStore.loadAll(ctx).firstOrNull { it.id == id }
                ?: GameEntry(id, "未知游戏")
            EmulatorScreen(game = game, onExit = { nav.popBackStack() })
        }
        composable(
            Routes.SWF_PLAYER,
            arguments = listOf(navArgument("swfPath") { type = NavType.StringType })
        ) { entry ->
            val encodedPath = entry.arguments?.getString("swfPath") ?: ""
            val swfPath = java.net.URLDecoder.decode(encodedPath, "UTF-8")
            SwfPlayerScreen(swfPath = swfPath, onExit = { nav.popBackStack() })
        }
    }

    // 长按游戏菜单（由 HomeScreen 的 onLongClickGame 触发）
    // 使用自定义 Dialog 确保，即使游戏名过长，所有选项（包括删除）也始终可见/可滚动
    longPressGame?.let { game ->
        Dialog(onDismissRequest = { longPressGame = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.88f)
            ) {
                Column(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .heightIn(max = 440.dp)
                ) {
                    // 标题 — 限制1行+省略号，避免占用过多空间
                    Text(
                        text = game.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color(0xFF1E2A3A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )

                    // 可滚动的菜单选项列表
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .weight(1f, fill = false)
                    ) {
                        MenuOption("开始游戏") {
                            longPressGame = null
                            openGame(game)
                        }
                        MenuOption("游戏设置") {
                            longPressGame = null
                            if (game.platform == GamePlatform.JAVA) {
                                JavaGameStore.openSettings(ctx, game)
                            } else {
                                openGame(game)
                            }
                        }
                        MenuOption("自定义图标") {
                            longPressGame = null
                            pendingIconGame = game
                            iconPickerLauncher.launch(arrayOf("image/*"))
                        }
                        MenuOption(if (game.isFavorite) "取消收藏" else "收藏") {
                            longPressGame = null
                            RomStore.toggleFavorite(ctx, game.id)
                            reloadGames()
                        }
                        MenuOption("删除游戏", danger = true) {
                            longPressGame = null
                            pendingDeleteGame = game
                        }
                    }

                    // 关闭按钮 — 始终固定在底部
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        TextButton(onClick = { longPressGame = null }) { Text("关闭") }
                    }
                }
            }
        }
    }

    // 删除游戏确认弹窗
    pendingDeleteGame?.let { game ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGame = null },
            title = { Text("删除游戏") },
            text = { Text("确定要删除「${game.title}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    if (game.platform == GamePlatform.JAVA) {
                        JavaGameStore.deleteGame(ctx, game)
                    } else {
                        RomStore.remove(ctx, game.id)
                    }
                    pendingDeleteGame = null
                    reloadGames()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGame = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun MenuOption(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(
            text = text,
            color = if (danger) Color(0xFFE74C3C) else Color(0xFF1E2A3A),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TvNavHost(nav: androidx.navigation.NavHostController, games: List<GameEntry>) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.HOME) {
            TvHomeScreen(
                featured = games.filter { it.isFavorite },
                recents = games,
                onOpenGame = { nav.navigate(Routes.emulator(it.id)) },
                onOpenLibrary = { nav.navigate(Routes.LIBRARY) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                games = games,
                onOpenGame = { nav.navigate(Routes.emulator(it.id)) },
                onImport = { },
                onSearch = { }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenKeyMap = { nav.navigate(Routes.KEYMAP) }
            )
        }
        composable(Routes.KEYMAP) {
            KeyMapScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.EMULATOR,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("gameId") ?: ""
            val ctx = LocalContext.current
            val game = games.firstOrNull { it.id == id }
                ?: RomStore.loadAll(ctx).firstOrNull { it.id == id }
                ?: GameEntry(id, "未知游戏")
            EmulatorScreen(game = game, onExit = { nav.popBackStack() })
        }
    }
}
