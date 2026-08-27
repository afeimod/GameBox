package com.nesstation.app.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.nesstation.app.core.storage.PadLayoutStore
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
import com.nesstation.app.ui.battle.BattleMatchArgs
import com.nesstation.app.ui.battle.BattleMatchScreen
import com.nesstation.app.ui.battle.BattleScreen
import com.nesstation.app.ui.online.OnlineGamesScreen
import com.nesstation.app.ui.online.WebGameScreen

object Routes {
    const val HOME = "home"
    // 带可选 platform 查询参数：主页平台磁贴可直接深链到对应平台的封面流
    const val LIBRARY = "library?platform={platform}"
    const val FAVORITES = "favorites"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val KEYMAP = "keymap"
    const val FILE_LIST = "file_list"
    const val SWF_LIST = "swf_list"
    const val ONLINE_GAMES = "online_games"
    const val BATTLE = "battle"
    const val BATTLE_MATCH = "battle_match/{roomId}/{gameId}/{isHost}/{tcpAddr}/{platform}/{fileName}"
    const val WEB_GAME = "web_game/{url}/{uaMode}"
    const val ABOUT = "about"
    const val EMULATOR = "emulator/{gameId}"
    const val SWF_PLAYER = "swf_player/{swfPath}"
    fun library(platform: GamePlatform? = null): String =
        if (platform == null) "library" else "library?platform=${platform.name}"
    fun emulator(id: String) = "emulator/$id"
    fun swfPlayer(path: String) = "swf_player/${java.net.URLEncoder.encode(path, "UTF-8")}"
    fun webGame(url: String, uaMode: String) =
        "web_game/${java.net.URLEncoder.encode(url, "UTF-8")}/$uaMode"
    fun battleMatch(roomId: String, gameId: String, isHost: Boolean, tcpAddr: String, platform: String = "arcade", fileName: String = "") =
        "battle_match/$roomId/$gameId/$isHost/${java.net.URLEncoder.encode(tcpAddr, "UTF-8")}/${java.net.URLEncoder.encode(platform, "UTF-8")}/${java.net.URLEncoder.encode(fileName, "UTF-8")}"
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

    // 全局背景配置：导航根部加载一次，ON_RESUME 自动重载，
    // 主页 / 游戏库等所有 FSD 深色页面通过 LocalFsdBg 消费同一张自定义壁纸。
    val bgConfig = com.nesstation.app.ui.fsd.rememberFsdBgConfig()

    androidx.compose.runtime.CompositionLocalProvider(
        com.nesstation.app.ui.fsd.LocalFsdBg provides bgConfig
    ) {
        if (isTv) {
            TvNavHost(nav = nav, games = games, reloadGames = reloadGames)
        } else {
            PhoneNavHost(nav = nav, games = games, reloadGames = reloadGames)
        }
    }
}

@Composable
private fun PhoneNavHost(
    nav: androidx.navigation.NavHostController,
    games: List<GameEntry>,
    reloadGames: () -> Unit
) {
    val ctx = LocalContext.current

    // Apply saved orientation setting on startup
    LaunchedEffect(Unit) {
        val padLayout = PadLayoutStore.load(ctx)
        val activity = ctx as? android.app.Activity ?: return@LaunchedEffect
        activity.requestedOrientation = when (padLayout.screenOrientation) {
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    // 平台感知的打开游戏：Java 直接启动 J2ME 引擎，NES 进入模拟器
    val openGame: (GameEntry) -> Unit = { game ->
        if (game.platform == GamePlatform.JAVA) {
            JavaGameStore.launchGame(ctx, game)
        } else {
            nav.navigate(Routes.emulator(game.id))
        }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                games = games,
                onOpenLibrary = { nav.navigate(Routes.library()) },
                onOpenPlatform = { p -> nav.navigate(Routes.library(p)) },
                onOpenOnlineGames = { nav.navigate(Routes.ONLINE_GAMES) },
                onOpenBattle = { nav.navigate(Routes.BATTLE) },
                onOpenSwf = { nav.navigate(Routes.SWF_LIST) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onExit = { nav.context.let { (it as? android.app.Activity)?.finishAffinity() } }
            )
        }
        composable(Routes.BATTLE) {
            BattleScreen(
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onOpenMatch = { args ->
                    nav.navigate(Routes.battleMatch(args.roomId, args.gameId, args.isHost, args.tcpAddr, args.platform.name, args.fileName))
                }
            )
        }
        composable(
            Routes.BATTLE_MATCH,
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("gameId") { type = NavType.StringType },
                navArgument("isHost") { type = NavType.BoolType },
                navArgument("tcpAddr") { type = NavType.StringType },
                navArgument("platform") { type = NavType.StringType },
                navArgument("fileName") { type = NavType.StringType }
            )
        ) { entry ->
            val roomId = entry.arguments?.getString("roomId") ?: ""
            val gameId = entry.arguments?.getString("gameId") ?: ""
            val isHost = entry.arguments?.getBoolean("isHost") ?: false
            val tcpAddr = java.net.URLDecoder.decode(
                entry.arguments?.getString("tcpAddr") ?: "",
                "UTF-8"
            )
            val platformStr = java.net.URLDecoder.decode(
                entry.arguments?.getString("platform") ?: "arcade",
                "UTF-8"
            )
            val fileName = java.net.URLDecoder.decode(
                entry.arguments?.getString("fileName") ?: "",
                "UTF-8"
            )
            BattleMatchScreen(
                args = BattleMatchArgs(
                    roomId = roomId,
                    gameId = gameId,
                    isHost = isHost,
                    tcpAddr = tcpAddr,
                    platform = com.nesstation.app.core.model.GamePlatform.fromString(platformStr),
                    fileName = fileName
                ),
                onExit = {
                    nav.popBackStack(Routes.BATTLE, inclusive = false)
                }
            )
        }
        composable(
            Routes.LIBRARY,
            arguments = listOf(
                navArgument("platform") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val platformStr = entry.arguments?.getString("platform")
            LibraryScreen(
                games = games,
                onOpenGame = openGame,
                onBack = { nav.popBackStack() },
                onHome = {
                    // 返回主页：弹出到 HOME 路由
                    nav.popBackStack(Routes.HOME, inclusive = false)
                },
                onGamesChanged = reloadGames,
                initialPlatform = platformStr?.takeIf { it.isNotBlank() }
                    ?.let { GamePlatform.fromString(it) }
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
                onGamesChanged = reloadGames
            )
        }
        composable(Routes.HISTORY) {
            LibraryScreen(
                games = games,
                onOpenGame = openGame,
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onGamesChanged = reloadGames
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
}

@Composable
private fun TvNavHost(
    nav: androidx.navigation.NavHostController,
    games: List<GameEntry>,
    reloadGames: () -> Unit
) {
    val ctx = LocalContext.current

    // Platform-aware openGame: Java games launch via J2ME engine, NES via emulator
    val openGame: (GameEntry) -> Unit = { game ->
        if (game.platform == GamePlatform.JAVA) {
            JavaGameStore.launchGame(ctx, game)
        } else {
            nav.navigate(Routes.emulator(game.id))
        }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.HOME) {
            TvHomeScreen(
                games = games,
                onOpenLibrary = { nav.navigate(Routes.library()) },
                onOpenPlatform = { p -> nav.navigate(Routes.library(p)) },
                onOpenOnlineGames = { nav.navigate(Routes.ONLINE_GAMES) },
                onOpenBattle = { nav.navigate(Routes.BATTLE) },
                onOpenSwf = { nav.navigate(Routes.SWF_LIST) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onExit = { nav.context.let { (it as? android.app.Activity)?.finishAffinity() } }
            )
        }
        composable(Routes.BATTLE) {
            BattleScreen(
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onOpenMatch = { args ->
                    nav.navigate(Routes.battleMatch(args.roomId, args.gameId, args.isHost, args.tcpAddr, args.platform.name, args.fileName))
                }
            )
        }
        composable(
            Routes.BATTLE_MATCH,
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("gameId") { type = NavType.StringType },
                navArgument("isHost") { type = NavType.BoolType },
                navArgument("tcpAddr") { type = NavType.StringType },
                navArgument("platform") { type = NavType.StringType },
                navArgument("fileName") { type = NavType.StringType }
            )
        ) { entry ->
            val roomId = entry.arguments?.getString("roomId") ?: ""
            val gameId = entry.arguments?.getString("gameId") ?: ""
            val isHost = entry.arguments?.getBoolean("isHost") ?: false
            val tcpAddr = java.net.URLDecoder.decode(
                entry.arguments?.getString("tcpAddr") ?: "",
                "UTF-8"
            )
            val platformStr = java.net.URLDecoder.decode(
                entry.arguments?.getString("platform") ?: "arcade",
                "UTF-8"
            )
            val fileName = java.net.URLDecoder.decode(
                entry.arguments?.getString("fileName") ?: "",
                "UTF-8"
            )
            BattleMatchScreen(
                args = BattleMatchArgs(
                    roomId = roomId,
                    gameId = gameId,
                    isHost = isHost,
                    tcpAddr = tcpAddr,
                    platform = com.nesstation.app.core.model.GamePlatform.fromString(platformStr),
                    fileName = fileName
                ),
                onExit = {
                    nav.popBackStack(Routes.BATTLE, inclusive = false)
                }
            )
        }
        composable(
            Routes.LIBRARY,
            arguments = listOf(
                navArgument("platform") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val platformStr = entry.arguments?.getString("platform")
            LibraryScreen(
                games = games,
                onOpenGame = openGame,
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onGamesChanged = reloadGames,
                initialPlatform = platformStr?.takeIf { it.isNotBlank() }
                    ?.let { GamePlatform.fromString(it) }
            )
        }
        composable(Routes.SWF_LIST) {
            SwfListScreen(
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onOpenSwf = { path -> nav.navigate(Routes.swfPlayer(path)) }
            )
        }
        composable(Routes.ONLINE_GAMES) {
            OnlineGamesScreen(
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
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
}
