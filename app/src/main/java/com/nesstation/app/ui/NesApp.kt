package com.nesstation.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

    // Load games from RomStore — refresh on ON_RESUME so the list updates
    // when the user returns from Library (after importing ROMs) or from
    // the emulator screen.
    var games by remember { mutableStateOf(RomStore.loadAll(ctx)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                games = RomStore.loadAll(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (isTv) {
        TvNavHost(nav = nav, games = games)
    } else {
        PhoneNavHost(nav = nav, games = games)
    }
}

@Composable
private fun PhoneNavHost(nav: androidx.navigation.NavHostController, games: List<GameEntry>) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenGame = { nav.navigate(Routes.emulator(it.id)) },
                onOpenLibrary = { nav.navigate(Routes.LIBRARY) },
                onOpenFileList = { nav.navigate(Routes.FILE_LIST) },
                onOpenOnlineGames = { nav.navigate(Routes.ONLINE_GAMES) },
                onOpenSwf = { nav.navigate(Routes.SWF_LIST) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onExit = { nav.context.let { (it as? android.app.Activity)?.finishAffinity() } }
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                games = games,
                onOpenGame = { nav.navigate(Routes.emulator(it.id)) },
                onBack = { nav.popBackStack() },
                onImport = { /* TODO: ACTION_OPEN_DOCUMENT */ },
                onSearch = { /* TODO */ }
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
                onOpenSwf = { path -> nav.navigate(Routes.swfPlayer(path)) }
            )
        }
        composable(Routes.ONLINE_GAMES) {
            OnlineGamesScreen(
                onBack = { nav.popBackStack() },
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
                onOpenGame = { nav.navigate(Routes.emulator(it.id)) },
                onBack = { nav.popBackStack() },
                onImport = { },
                onSearch = { }
            )
        }
        composable(Routes.HISTORY) {
            LibraryScreen(
                games = games,
                onOpenGame = { nav.navigate(Routes.emulator(it.id)) },
                onBack = { nav.popBackStack() },
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
