package com.nesstation.app.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.JavaGameStore
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.ui.components.GameCard
import com.nesstation.app.ui.components.PixelBackdrop
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/// ROM file extensions we support (NES, SNES/SFC, GB/GBC/GBA)
val ROM_EXTENSIONS = listOf(
    "nes", "fds", "unf", "unif", "nez", "unh",  // NES/Famicom
    "smc", "sfc", "swc", "fig", "bs",            // SNES/SFC
    "gb", "sgb", "gbc", "gba",                    // GB/GBC/GBA
    "zip", "7z", "gz"                             // compressed archives
)

@Composable
fun LibraryScreen(
    games: List<GameEntry>,
    onOpenGame: (GameEntry) -> Unit,
    onBack: () -> Unit = {},
    onHome: () -> Unit = onBack,
    onImport: () -> Unit,
    onSearch: () -> Unit,
    onLongClickGame: (GameEntry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 关键修复：之前的实现里 importedGames 和外部传入的 games 参数都从 RomStore.loadAll 加载，
    //          然后 allGames = importedGames + games 把同一批数据拼了两份，导致每个游戏显示两次。
    //          现在：把传入的 [games] 当作初次数据，导入/刷新时直接覆盖 importedGames，
    //          列表用 importedGames.distinctBy { it.id } 显示，确保不会重复。
    val importedGames = remember { mutableStateListOf<GameEntry>().apply { addAll(games) } }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var dialogMsg by remember { mutableStateOf<String?>(null) }
    // Built-in file browser dialog state — shown as a fallback when the
    // system SAF picker is unavailable (typical on Android TV boxes that
    // ship without DocumentsUI).
    var showFileBrowser by remember { mutableStateOf(false) }

    // 选中的平台分类标签（NES / Java）
    var selectedPlatform by remember { mutableStateOf(GamePlatform.NES) }

    // 搜索关键字 — 空字符串表示不搜索，显示当前平台所有游戏
    var searchQuery by remember { mutableStateOf("") }

    // 长按菜单相关状态
    var longPressGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingIconGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingDeleteGame by remember { mutableStateOf<GameEntry?>(null) }

    fun refreshList() {
        // 同时加载 NES 与 Java 游戏，按 id 去重
        val nes = RomStore.loadAll(context)
        val java = JavaGameStore.loadAll(context)

        // Remove games whose ROM files no longer exist on disk
        val validRomPaths = mutableSetOf<String>()
        // Scan all standard ROM directories for existing files
        val sd = Environment.getExternalStorageDirectory()
        val scanDirs = listOf(
            File(sd, "ROMs"), File(sd, "NesStation"), File(sd, "Download/NesStation"),
            context.getExternalFilesDir("roms") ?: File(context.filesDir, "roms")
        )
        for (dir in scanDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().forEach { f ->
                    if (f.isFile && f.name.substringAfterLast('.', "").lowercase() in ROM_EXTENSIONS) {
                        validRomPaths.add(f.absolutePath)
                    }
                }
            }
        }

        // Filter out games whose ROM file no longer exists
        val validNes = nes.filter { game ->
            val path = game.romPath ?: ""
            // Keep games with content:// URIs (SAF-imported) or files that still exist
            path.startsWith("content://") || path.startsWith("/") && File(path).exists()
        }
        // If any NES games were removed, persist the updated list
        if (validNes.size != nes.size) {
            RomStore.saveAll(context, validNes)
        }

        val merged = (validNes + java).distinctBy { it.id }
        importedGames.clear()
        importedGames.addAll(merged)
    }

    // 当外部传入的 games 列表变化时（父级 NavHost 在 ON_RESUME 时重新加载），
    // 同步到本地列表，保留本地可能的新增（避免和远端并发写入时丢数据）
    LaunchedEffect(games) {
        // 仅在外部列表比本地更新时合并：用 id 去重
        val localIds = importedGames.map { it.id }.toSet()
        val merged = (importedGames + games).distinctBy { it.id }
        if (merged.size != importedGames.size || games.any { it.id !in localIds }) {
            importedGames.clear()
            importedGames.addAll(merged)
        }
    }

    // SAF file picker for importing individual ROM files
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var count = 0
        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val name = queryDisplayName(uri) ?: "unknown.nes"
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in ROM_EXTENSIONS) {
                val platform = detectPlatformFromUri(context, uri, name)
                RomStore.add(context, name.substringBeforeLast('.'), uri.toString(), platform)
                count++
            }
        }
        if (count > 0) {
            refreshList()
            dialogMsg = "已导入 $count 个ROM文件"
        }
    }

    // SAF folder picker — recursively scan selected folder
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Wrap the whole callback in a try-catch: on TV (and some phone ROMs)
        // the persistable URI permission can fail silently and the subsequent
        // contentResolver queries may throw SecurityException. We must not
        // crash — show a friendly message instead.
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            catch (_: Exception) { }

            // Recursively scan the selected folder for ROM files
            val romFiles = scanUriForRomsRecursive(context, uri, uri, maxDepth = 5)
            if (romFiles.isEmpty()) {
                dialogMsg = "所选文件夹未找到ROM文件（支持 .nes .smc .sfc .gb .gbc .gba .fds .zip）"
            } else {
                var count = 0
                var failed = 0
                romFiles.forEach { (name, fileUri) ->
                    try {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val platform = detectPlatformFromUri(context, fileUri, name)
                        RomStore.add(context, name.substringBeforeLast('.'), fileUri.toString(), platform)
                        count++
                    } catch (_: Exception) {
                        failed++
                    }
                }
                refreshList()
                dialogMsg = if (failed > 0) "从文件夹导入 $count 个ROM文件（$failed 个失败）"
                else "从文件夹导入 $count 个ROM文件"
            }
        } catch (e: SecurityException) {
            dialogMsg = "没有权限访问所选文件夹，请重试或选择其他文件夹"
        } catch (e: Exception) {
            dialogMsg = "导入文件夹失败：${e.message}"
        }
    }

    // Storage permission launcher (Android <= 10)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            val entries = scanForRoms(context)
            if (entries.isNotEmpty()) {
                entries.forEach { (name, path) ->
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val platform = detectPlatformFromFile(File(path))
                    RomStore.add(context, name.substringBeforeLast('.'), path, platform)
                }
                refreshList()
                dialogMsg = "权限已授予，扫描到 ${entries.size} 个ROM文件"
            } else {
                dialogMsg = "权限已授予，但未在常见目录找到ROM文件"
            }
        } else {
            showPermissionDialog = true
        }
    }

    // SAF picker for installing J2ME .jar games (Java platform tab)
    val jarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var installed = 0
        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            if (JavaGameStore.installJar(context, uri) != null) installed++
        }
        refreshList()
        dialogMsg = if (installed > 0) "已安装 $installed 个 Java 游戏"
        else "安装失败，请检查 JAR 文件是否有效"
    }

    // SAF picker for choosing a custom cover icon for a game
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
            // 拷贝到应用内部目录，使 BitmapFactory.decodeFile 可用
            val iconsDir = File(context.filesDir, "icons").apply { mkdirs() }
            val iconFile = File(iconsDir, "icon_${game.id}_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                iconFile.outputStream().use { output -> input.copyTo(output) }
            }
            RomStore.setCustomIcon(context, game.id, iconFile.absolutePath)
            refreshList()
            dialogMsg = "已设置自定义图标"
        } catch (e: Exception) {
            dialogMsg = "图标设置失败：${e.message}"
        }
        pendingIconGame = null
    }

    fun importFiles() {
        // SAF file picker works without storage permission on all Android versions.
        // Wrap in try-catch: on some TV devices the DocumentsUI activity may
        // not be available — in that case, fall back to the built-in browser.
        try {
            filePickerLauncher.launch(arrayOf("*/*"))
        } catch (_: android.content.ActivityNotFoundException) {
            showFileBrowser = true
        } catch (e: Exception) {
            dialogMsg = "无法打开文件选择器：${e.message}"
        }
    }

    fun importFolder() {
        // Same defensive wrapping as importFiles() — TV devices may not have
        // a DocumentsUI that handles ACTION_OPEN_DOCUMENT_TREE. When SAF is
        // unavailable, fall back to the built-in FileBrowserDialog which can
        // walk the file system directly (requires READ_EXTERNAL_STORAGE on
        // Android <= 10, or MANAGE_EXTERNAL_STORAGE on Android 11+).
        try {
            folderPickerLauncher.launch(null)
        } catch (_: android.content.ActivityNotFoundException) {
            // No system folder picker — use the built-in browser instead.
            showFileBrowser = true
        } catch (e: Exception) {
            dialogMsg = "无法打开文件夹选择器：${e.message}"
        }
    }

    fun requestManageStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            } else {
                val entries = scanForRoms(context)
                if (entries.isNotEmpty()) {
                    entries.forEach { (name, path) ->
                        val platform = detectPlatformFromFile(File(path))
                        RomStore.add(context, name.substringBeforeLast('.'), path, platform)
                    }
                    refreshList()
                    dialogMsg = "已扫描到 ${entries.size} 个ROM文件"
                } else {
                    dialogMsg = "未在常见目录找到ROM文件"
                }
            }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val allGames = importedGames.distinctBy { it.id }
    val platformGames = allGames.filter { it.platform == selectedPlatform }
    // When searching, search across ALL platforms for better discoverability
    val displayGames = if (searchQuery.isBlank()) {
        platformGames
    } else {
        allGames.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PixelBackdrop()
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "游戏库",
                        color = Color(0xFF1E2A3A),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            "搜索「${searchQuery}」· 找到 ${displayGames.size} 款游戏"
                        } else {
                            "${platformGames.size} 款 ${selectedPlatform.displayName} 游戏 · 复古之旅"
                        },
                        color = Color(0xFF4A5568),
                        fontSize = 11.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 新增：返回主页按钮（首页风格：白底圆角 pill + 房子图标）
                    HomePill(
                        onClick = onHome,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selectedPlatform != GamePlatform.JAVA) {
                            ExtendedFloatingActionButton(
                                onClick = { importFolder() },
                                icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                                text = { Text("导入文件夹") },
                                containerColor = Color(0xFF4F8AC4),
                                contentColor = Color.White
                            )
                            ExtendedFloatingActionButton(
                                onClick = { importFiles() },
                                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                text = { Text("导入ROM") },
                                containerColor = Color(0xFFE74C3C),
                                contentColor = Color.White
                            )
                            // Built-in file browser — shown as a third button so it's
                            // always reachable on TV devices where the system SAF
                            // picker is unavailable. Also useful on phones when the
                            // user prefers the in-app browser.
                            ExtendedFloatingActionButton(
                                onClick = { showFileBrowser = true },
                                icon = { Icon(Icons.Rounded.Storage, contentDescription = null) },
                                text = { Text("本地浏览") },
                                containerColor = Color(0xFF2E7D32),
                                contentColor = Color.White
                            )
                        } else {
                            // Java 平台：加号按钮用于安装 .jar 文件
                            ExtendedFloatingActionButton(
                                onClick = {
                                    try {
                                        jarPickerLauncher.launch(
                                            arrayOf("application/java-archive", "application/java", "*/*")
                                        )
                                    } catch (_: android.content.ActivityNotFoundException) {
                                        dialogMsg = "系统文件选择器不可用"
                                    } catch (e: Exception) {
                                        dialogMsg = "无法打开文件选择器：${e.message}"
                                    }
                                },
                                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                text = { Text("安装 JAR") },
                                containerColor = Color(0xFF6A1B9A),
                                contentColor = Color.White
                            )
                        }
                    }
                }
            }

            // Action row — 横向滚动的平台分类标签（NES / Java）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    lazyItems(listOf(
                        GamePlatform.NES, GamePlatform.SFC,
                        GamePlatform.GB, GamePlatform.GBA,
                        GamePlatform.JAVA
                    )) { platform ->
                        FilterChip(
                            text = platform.displayName,
                            selected = selectedPlatform == platform,
                            onClick = { selectedPlatform = platform }
                        )
                    }
                }
                IconButton(onClick = { refreshList() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = Color(0xFF1E2A3A), modifier = Modifier.size(18.dp))
                }
            }

            // 搜索栏 — 实时过滤游戏列表
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                placeholder = {
                    Text(
                        "搜索游戏名称…",
                        fontSize = 13.sp,
                        color = Color(0xFF9CA3AF)
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = Color(0xFF4A5568),
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Rounded.Clear,
                                contentDescription = "清除",
                                tint = Color(0xFF4A5568),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4F8AC4),
                    unfocusedBorderColor = Color(0xFFD1D5DB),
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White.copy(alpha = 0.7f),
                    cursorColor = Color(0xFF4F8AC4)
                )
            )

            // Permission hint for Android 11+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFFF3E0))
                        .clickable { requestManageStorage() }
                        .padding(12.dp)
                ) {
                    Text(
                        text = "点击此处授予「所有文件访问权限」可自动扫描本地ROM。也可直接点击上方按钮通过系统文件选择器导入。",
                        color = Color(0xFFE65100),
                        fontSize = 11.sp
                    )
                }
            }

            LazyVerticalGrid(
                columns = if (isPortrait) GridCells.Fixed(2) else GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(displayGames) { g ->
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        onLongClick = { longPressGame = g },
                        coverPath = g.customIconPath ?: g.coverPath,
                        platform = g.platform,
                        modifier = Modifier.height(if (isPortrait) 140.dp else 130.dp)
                    )
                }
            }
        }
    }

    // Permission denied dialog
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("需要存储权限") },
            text = { Text("扫描本地ROM文件需要存储权限。\n\n您也可以直接点击「导入ROM」或「导入文件夹」按钮，通过系统文件选择器导入游戏文件，无需存储权限。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    requestManageStorage()
                }) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("取消") }
            }
        )
    }

    // Info dialog
    dialogMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { dialogMsg = null },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { dialogMsg = null }) { Text("确定") }
            }
        )
    }

    // Built-in file browser dialog — fallback when the system SAF picker is
    // unavailable (TV devices, custom ROMs without DocumentsUI).
    if (showFileBrowser) {
        FileBrowserDialog(
            onPicked = { folderPath ->
                showFileBrowser = false
                // Recursively scan the chosen folder for ROM files (same logic
                // as the SAF folder picker callback above).
                val folder = File(folderPath)
                val romFiles = scanLocalFolderForRoms(folder, maxDepth = 5)
                if (romFiles.isEmpty()) {
                    dialogMsg = "所选文件夹未找到ROM文件（支持 ${ROM_EXTENSIONS.joinToString()}）"
                } else {
                    var count = 0
                    var failed = 0
                    romFiles.forEach { file ->
                        try {
                            val platform = detectPlatformFromFile(file)
                            RomStore.add(
                                context,
                                file.nameWithoutExtension,
                                file.absolutePath,
                                platform
                            )
                            count++
                        } catch (_: Exception) {
                            failed++
                        }
                    }
                    refreshList()
                    dialogMsg = if (failed > 0)
                        "从文件夹导入 $count 个ROM文件（$failed 个失败）"
                    else "从文件夹导入 $count 个ROM文件"
                }
            },
            onDismiss = { showFileBrowser = false }
        )
    }

    // 长按游戏卡片弹出的操作菜单 — 使用自定义 Dialog 确保
    // 即使游戏名过长，所有选项（包括删除）也始终可见/可滚动
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
                            onOpenGame(game)
                        }
                        MenuOption("游戏设置") {
                            longPressGame = null
                            if (game.platform == GamePlatform.JAVA) {
                                JavaGameStore.openSettings(context, game)
                            } else {
                                onOpenGame(game)
                            }
                        }
                        MenuOption("自定义图标") {
                            longPressGame = null
                            pendingIconGame = game
                            iconPickerLauncher.launch(arrayOf("image/*"))
                        }
                        MenuOption(if (game.isFavorite) "取消收藏" else "收藏") {
                            longPressGame = null
                            RomStore.toggleFavorite(context, game.id)
                            refreshList()
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
                        JavaGameStore.deleteGame(context, game)
                    } else {
                        RomStore.remove(context, game.id)
                    }
                    pendingDeleteGame = null
                    refreshList()
                    dialogMsg = "已删除「${game.title}」"
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGame = null }) { Text("取消") }
            }
        )
    }
}

/** Query the display name of a URI from the ContentResolver */
private fun queryDisplayName(uri: Uri): String? {
    return try {
        val context = com.nesstation.app.NesApp.get() ?: return null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    } catch (_: Exception) {
        uri.lastPathSegment
    }
}

/**
 * Recursively scan a SAF folder URI for ROM files using DocumentsContract.
 * Traverses subdirectories up to [maxDepth] levels deep.
 */
private fun scanUriForRomsRecursive(
    context: android.content.Context,
    treeUri: Uri,
    folderUri: Uri,
    maxDepth: Int
): List<Pair<String, Uri>> {
    val results = mutableListOf<Pair<String, Uri>>()
    if (maxDepth <= 0) return results
    try {
        val folderDocId = if (folderUri == treeUri) {
            DocumentsContract.getTreeDocumentId(treeUri)
        } else {
            DocumentsContract.getDocumentId(folderUri)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
        context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    // Recurse into subdirectory
                    val subUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    results.addAll(scanUriForRomsRecursive(context, treeUri, subUri, maxDepth - 1))
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in ROM_EXTENSIONS) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        results.add(name to fileUri)
                    }
                }
            }
        }
    } catch (_: Exception) { }
    return results
}

/**
 * Recursively scan [folder] for ROM files (matching [ROM_EXTENSIONS]) up to
 * [maxDepth] levels deep. Returns a flat list of ROM File objects.
 *
 * Used by the built-in FileBrowserDialog when the system SAF picker is
 * unavailable (TV devices without DocumentsUI).
 */
private fun scanLocalFolderForRoms(folder: File, maxDepth: Int): List<File> {
    val results = mutableListOf<File>()
    if (maxDepth <= 0) return results
    val children = try {
        folder.listFiles() ?: return results
    } catch (_: Exception) {
        return results
    }
    for (f in children) {
        if (f.name.startsWith(".")) continue
        if (f.isFile) {
            val ext = f.extension.lowercase()
            if (ext in ROM_EXTENSIONS) results.add(f)
        } else if (f.isDirectory) {
            results.addAll(scanLocalFolderForRoms(f, maxDepth - 1))
        }
    }
    return results
}

/** Scan common directories for ROM files (requires storage permission) */
private fun scanForRoms(context: android.content.Context): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    val dirs = mutableListOf<File>()
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q || Environment.isExternalStorageManager()) {
        dirs.add(Environment.getExternalStorageDirectory())
        dirs.add(File(Environment.getExternalStorageDirectory(), "Download"))
        dirs.add(File(Environment.getExternalStorageDirectory(), "ROMs"))
        dirs.add(File(Environment.getExternalStorageDirectory(), "NES"))
        dirs.add(File(Environment.getExternalStorageDirectory(), "Games"))
    }
    dirs.add(context.getExternalFilesDir(null) ?: context.filesDir)
    dirs.add(File(context.filesDir, "roms"))

    dirs.forEach { dir ->
        if (dir.exists() && dir.isDirectory) {
            dir.walkTopDown().take(500).forEach { file ->
                if (file.isFile && file.extension.lowercase() in ROM_EXTENSIONS) {
                    results.add(file.name to file.absolutePath)
                }
            }
        }
    }
    return results
}

/**
 * Detect the game platform from a file URI.
 * For ZIP/7z/gz archives, looks inside to find the actual ROM extension.
 */
private fun detectPlatformFromUri(
    context: android.content.Context,
    uri: Uri,
    fileName: String
): GamePlatform {
    val ext = fileName.substringAfterLast('.', "").lowercase()

    // Direct ROM extension — use it immediately
    GamePlatform.fromExtension(ext)?.let { return it }

    // For compressed archives, inspect contents to determine platform
    if (ext == "zip") {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val entryExt = entry.name.substringAfterLast('.', "").lowercase()
                            GamePlatform.fromExtension(entryExt)?.let { return it }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (_: Exception) { }
    }

    return GamePlatform.NES // fallback
}

/**
 * Detect the game platform from a local File.
 * For ZIP archives, looks inside to find the actual ROM extension.
 */
private fun detectPlatformFromFile(file: File): GamePlatform {
    val ext = file.extension.lowercase()

    GamePlatform.fromExtension(ext)?.let { return it }

    if (ext == "zip") {
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        val entryExt = entry.name.substringAfterLast('.', "").lowercase()
                        GamePlatform.fromExtension(entryExt)?.let { return it }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    return GamePlatform.NES
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Color(0xFF1E2A3A)
                else Color.White.copy(alpha = 0.5f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else Color(0xFF1E2A3A),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 长按菜单中的单个可点击选项 */
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
private fun SearchPill(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF1E2A3A), modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
            Text("搜索", color = Color(0xFF1E2A3A), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 首页风格的「返回主页」按钮（与 SwfListScreen / OnlineGamesScreen 一致）。 */
@Composable
private fun HomePill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            Icons.Rounded.Home,
            contentDescription = "返回主页",
            tint = Color(0xFF1E2A3A),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "主页",
            color = Color(0xFF1E2A3A),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
