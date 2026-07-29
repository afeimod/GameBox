package com.nesstation.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.ui.emulator.EmulatorScreen
import com.nesstation.app.ui.home.HomeScreen
import com.nesstation.app.ui.home.HomeSamples
import com.nesstation.app.ui.library.LibraryScreen
import com.nesstation.app.ui.settings.KeyMapScreen
import com.nesstation.app.ui.settings.SettingsScreen
import com.nesstation.app.ui.tv.TvHomeScreen

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val FAVORITES = "favorites"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val KEYMAP = "keymap"
    const val EMULATOR = "emulator/{gameId}"
    fun emulator(id: String) = "emulator/$id"
}

@Composable
fun NesApp() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val isTv = remember {
        ctx.packageManager.hasSystemFeature("android.hardware.touchscreen").not()
    }
    val games = remember { HomeSamples.recents + HomeSamples.featured }

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
                onOpenFavorites = { nav.navigate(Routes.FAVORITES) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenSearch = { nav.navigate(Routes.LIBRARY) }
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                games = games,
                onOpenGame = { nav.navigate(Routes.emulator(it.id)) },
                onImport = { /* TODO: ACTION_OPEN_DOCUMENT */ },
                onSearch = { /* TODO */ }
            )
        }
        composable(Routes.FAVORITES) {
            LibraryScreen(
                games = games.filter { it.id.startsWith("f") },
                onOpenGame = { nav.navigate(Routes.emulator(it.id)) },
                onImport = { },
                onSearch = { }
            )
        }
        composable(Routes.HISTORY) {
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

@Composable
private fun TvNavHost(nav: androidx.navigation.NavHostController, games: List<GameEntry>) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.HOME) {
            TvHomeScreen(
                featured = games.filter { it.id.startsWith("f") },
                recents = games.filter { !it.id.startsWith("f") },
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
