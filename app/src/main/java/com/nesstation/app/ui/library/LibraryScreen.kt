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
import com.nesstation.app.core.storage.ArcadeTitleMapper
import com.nesstation.app.core.storage.JavaGameStore
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.ui.components.GameCard
import com.nesstation.app.ui.components.PixelBackdrop
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/// ROM file extensions we support (NES, SNES/SFC, GB/GBC/GBA, DOSBox,
/// Arcade, SEGA MD/SMS/GG/SG, Mega-CD).
/// NOTE on .bin: ambiguous (could be SEGA MD cart, arcade ROM, or DOS
/// disk image). It IS in this list so the file picker accepts it; the
/// platform is then resolved by detectPlatformFromUri — bare .bin files
/// (not inside a zip) default to MD (most common usage in user libraries).
/// Inside a zip, .bin is treated as arcade content (see ARCADE_ROM_EXTENSIONS).
val ROM_EXTENSIONS = listOf(
    "nes", "fds", "unf", "unif", "nez", "unh",  // NES/Famicom
    "smc", "sfc", "swc", "fig", "bs",            // SNES/SFC
    "gb", "sgb", "gbc", "gba",                    // GB/GBC/GBA
    "dosz",                                          // DOSBox-Pure bundle
    // CD images — used by BOTH DOSBox (DOS CD games) and Mega-CD.
    // Platform disambiguation uses the user's selected tab as a hint.
    "iso", "cue", "img", "ccd", "sub",
    // SEGA Mega Drive / Genesis / Master System / Game Gear / SG-1000
    "md", "smd", "gen", "sms", "gg", "sg", "68k",
    "bin",                                           // MD cart dump (ambiguous; see note above)
    "chd",                                           // Mega-CD CHD images
    // Geargrafx — PC-Engine / TurboGrafx-16 / SuperGrafx / PCE-CD
    "pce", "sgx", "hes",
    // Arcade (FBNeo) — archives only; the filename IS the driver name
    "zip", "7z", "gz"
)

/// DOSBox launcher extensions (only these are imported as game entries when
/// a user picks a folder — data files like .DAT, .CFG, .PIC etc. are skipped).
val DOS_LAUNCHER_EXTENSIONS = setOf("bat", "exe", "com")

/// Preferred DOS launcher filenames in priority order. When a folder contains
/// multiple launchers, the first matching file (case-insensitive) is imported.
val DOS_LAUNCHER_PRIORITY = listOf(
    "play.bat", "run.bat", "start.bat", "autoexec.bat",
    "go.bat", "launch.bat", "main.bat",
    "play.exe", "run.exe", "start.exe", "setup.exe",
    "game.exe", "main.exe", "launch.exe"
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
    onGamesChanged: (() -> Unit)? = null,
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
        // 1) Re-scan every folder the user has imported games from, to pick up
        //    newly added ROMs and remove ROMs that have been deleted from disk.
        //    Previously only the LAST imported folder was re-scanned, and only
        //    when the scan returned non-empty results, so:
        //      - ROMs added/deleted in folders imported earlier were never seen
        //      - deleting ALL ROMs in a folder left stale entries in the list
        //    Now every imported folder is re-scanned; SAF entries are removed
        //    when the folder no longer lists them (an empty result from a
        //    successful scan means the folder is genuinely empty), and local
        //    entries are removed when their file no longer exists or the
        //    folder itself is gone.
        var stepAdded = 0
        var stepRemoved = 0
        var lostFolderAccess = false
        val folders = RomStore.getImportedFolders(context)
        folders.forEach { (folderUriStr, hintPlatform) ->
            if (folderUriStr.startsWith("content://")) {
                try {
                    val folderUri = Uri.parse(folderUriStr)
                    // If this throws SecurityException the persistable URI
                    // permission was revoked (e.g. user cleared app data);
                    // we skip this folder instead of deleting its games.
                    val romFiles = scanUriForRomsRecursive(context, folderUri, folderUri, maxDepth = 5)

                    // Scan succeeded — even an empty list means the folder has
                    // no ROM files right now, so entries no longer listed here
                    // are safe to remove.
                    val foundUris = romFiles.map { it.second.toString() }.toMutableSet()

                    val existing = RomStore.loadAll(context)
                    val folderTreePrefix = run {
                        // content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fsms/document/primary%3AROMs%2Fsms%2F...
                        // Match anything that starts with the tree prefix:
                        // "content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fsms"
                        val s = folderUri.toString()
                        // Strip trailing "/document/..." if present
                        val docIdx = s.indexOf("/document/")
                        if (docIdx > 0) s.substring(0, docIdx) else s
                    }
                    // Boundary-safe prefix match: only games under THIS folder
                    // tree (e.g. ".../tree/primary%3AROMs/document/...") count.
                    // A sibling folder ".../tree/primary%3AROMs2/document/..."
                    // must NOT match — otherwise we'd delete the wrong games.
                    fun isUnderFolder(uriStr: String): Boolean =
                        uriStr == folderTreePrefix || uriStr.startsWith("$folderTreePrefix/document/")

                    val toRemove = existing.filter { game ->
                        val p = game.romPath ?: return@filter false
                        if (!p.startsWith("content://")) return@filter false
                        if (!isUnderFolder(p)) return@filter false
                        p !in foundUris
                    }
                    if (toRemove.isNotEmpty()) {
                        toRemove.forEach { RomStore.remove(context, it.id) }
                        stepRemoved += toRemove.size
                    }

                    // Add new ROMs found in the folder that aren't yet in the library
                    val existingPaths = RomStore.loadAll(context).mapNotNull { it.romPath }.toSet()
                    var added = 0
                    romFiles.forEach { (name, fileUri) ->
                        val uriStr = fileUri.toString()
                        if (uriStr !in existingPaths) {
                            try {
                                val platform = detectPlatformFromUri(context, fileUri, name, hintPlatform = hintPlatform)
                                val title = if (platform == GamePlatform.ARCADE) {
                                    ArcadeTitleMapper.resolveDisplayTitle(name)
                                } else {
                                    name.substringBeforeLast('.')
                                }
                                RomStore.add(context, title, uriStr, platform)
                                added++
                            } catch (_: Exception) { }
                        }
                    }
                    stepAdded += added
                } catch (_: SecurityException) {
                    // Persistable URI permission lost — we can't verify the
                    // folder's contents, so skip it without deleting anything.
                    lostFolderAccess = true
                } catch (_: Exception) {
                    // Any other scan failure — skip rather than deleting games
                    // based on an incomplete scan.
                }
            } else {
                // === FIX: local filesystem folder re-scan ===
                // RomStore.setLastImportFolder() was called with a plain path
                // (e.g. "/sdcard/ROMs/sms") by FileBrowserDialog. Re-walk the
                // folder, remove games whose files are gone, and add new ones.
                try {
                    val folder = java.io.File(folderUriStr)
                    if (!folder.exists() || !folder.isDirectory) {
                        // The imported folder itself is gone — remove every
                        // game that lived under it.
                        val folderAbs = folder.absolutePath
                        val existing = RomStore.loadAll(context)
                        val toRemove = existing.filter { game ->
                            val p = game.romPath ?: return@filter false
                            p.startsWith("/") && (p == folderAbs || p.startsWith("$folderAbs/"))
                        }
                        if (toRemove.isNotEmpty()) {
                            toRemove.forEach { RomStore.remove(context, it.id) }
                            stepRemoved += toRemove.size
                        }
                    } else {
                        val romFiles = scanLocalFolderForRoms(folder, maxDepth = 5)
                        val folderAbs = folder.absolutePath
                        val existing = RomStore.loadAll(context)

                        // Remove games under this folder whose file no longer
                        // exists on disk. File.exists() is exact, so this works
                        // even when ALL ROMs were deleted (an empty scan result
                        // used to block removal entirely).
                        val toRemove = existing.filter { game ->
                            val p = game.romPath ?: return@filter false
                            if (!p.startsWith("/")) return@filter false
                            if (p != folderAbs && !p.startsWith("$folderAbs/")) return@filter false
                            !java.io.File(p).exists()
                        }
                        if (toRemove.isNotEmpty()) {
                            toRemove.forEach { RomStore.remove(context, it.id) }
                            stepRemoved += toRemove.size
                        }

                        // Add new ROMs
                        val existingPaths = RomStore.loadAll(context).mapNotNull { it.romPath }.toSet()
                        var added = 0
                        romFiles.forEach { file ->
                            val path = file.absolutePath
                            if (path !in existingPaths) {
                                try {
                                    val platform = detectPlatformFromFile(file, hintPlatform = hintPlatform)
                                    val title = if (platform == GamePlatform.ARCADE) {
                                        ArcadeTitleMapper.resolveDisplayTitle(file.name)
                                    } else {
                                        file.nameWithoutExtension
                                    }
                                    RomStore.add(context, title, path, platform)
                                    added++
                                } catch (_: Exception) { }
                            }
                        }
                        stepAdded += added
                    }
                } catch (_: Exception) {
                    // Skip failed folders rather than removing entries.
                }
            }
        }

        // 2) Load everything back from RomStore (this picks up the changes above
        //    plus filters out any games whose local files no longer exist).
        //
        // === FIX: also scan standard ROM directories for NEW files ===
        // Even if there's no saved "last import folder" (e.g. user imported
        // individual files, or the folder was imported before the fix), we
        // still scan the standard ROM directories and add any new files that
        // aren't yet in RomStore. This makes the refresh button actually
        // useful for the common case of "I dropped a new ROM into /sdcard/ROMs".
        val nes = RomStore.loadAll(context)
        val java = JavaGameStore.loadAll(context)

        // Build a set of all existing ROM paths (for fast lookup)
        val existingPaths = nes.mapNotNull { it.romPath }.toMutableSet()

        // Scan all standard ROM directories for existing files AND new files
        val validRomPaths = mutableSetOf<String>()
        val sd = Environment.getExternalStorageDirectory()
        val scanDirs = listOf(
            File(sd, "ROMs"), File(sd, "NesStation"), File(sd, "Download/NesStation"),
            File(sd, "Games"), File(sd, "Games/NES"), File(sd, "Games/MD"),
            File(sd, "Games/SEGA"), File(sd, "Games/PCE"), File(sd, "Games/Arcade"),
            context.getExternalFilesDir("roms") ?: File(context.filesDir, "roms")
        )
        var newFound = 0
        for (dir in scanDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            try {
                dir.walkTopDown().forEach { f ->
                    if (f.isFile && f.name.substringAfterLast('.', "").lowercase() in ROM_EXTENSIONS) {
                        val absPath = f.absolutePath
                        validRomPaths.add(absPath)
                        // If this file isn't in RomStore yet, add it
                        if (absPath !in existingPaths) {
                            try {
                                val platform = detectPlatformFromFile(f, hintPlatform = selectedPlatform)
                                val title = if (platform == GamePlatform.ARCADE) {
                                    ArcadeTitleMapper.resolveDisplayTitle(f.name)
                                } else {
                                    f.nameWithoutExtension
                                }
                                RomStore.add(context, title, absPath, platform)
                                existingPaths.add(absPath)
                                newFound++
                            } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Android 11+ 作用域存储：未授予"所有文件访问权限"时遍历
                // /sdcard 下的标准目录会抛 SecurityException。这里只跳过该
                // 目录，绝不让它中断整个刷新流程——否则已导入文件夹里新增/
                // 删除的游戏结果也不会显示在列表里（刷新按钮"点了没反应"）。
            } catch (_: Exception) {
                // 遍历中遇到其他不可读子目录/文件时同样跳过，保证刷新继续。
            }
        }

        // Reload from RomStore to pick up any additions from step 1 or step 2
        val finalNes = if (newFound > 0) RomStore.loadAll(context) else nes

        // Filter out games whose ROM file no longer exists
        val validNes = finalNes.filter { game ->
            val path = game.romPath ?: ""
            // Keep games with content:// URIs (SAF-imported) or files that still exist
            path.startsWith("content://") || path.startsWith("/") && File(path).exists()
        }
        // If any games were removed, persist the updated list
        if (validNes.size != finalNes.size) {
            RomStore.saveAll(context, validNes)
        }

        val removedCount = finalNes.size - validNes.size

        // Combined summary from step 1 (imported folders) and step 2 (standard
        // directories). The "已是最新" message is only shown when every
        // imported folder was scanned successfully and nothing changed.
        val totalAdded = stepAdded + newFound
        val totalRemoved = stepRemoved + removedCount
        if (totalAdded > 0 || totalRemoved > 0) {
            dialogMsg = "刷新完成：新增 $totalAdded 个，移除 $totalRemoved 个"
        } else if (lostFolderAccess) {
            dialogMsg = "需要重新选择文件夹（之前的访问权限已失效）"
        } else {
            dialogMsg = "已是最新（无新增/移除）"
        }

        val merged = (validNes + java).distinctBy { it.id }
        importedGames.clear()
        importedGames.addAll(merged)

        // Notify the parent (NesApp) so the Home screen / other Library
        // instances reload the latest list — otherwise the refreshed list
        // is replaced by stale data when navigating back.
        onGamesChanged?.invoke()
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

        // === Deduplicate multi-file CD images ===
        // When the user selects multiple files from the same game folder
        // (e.g. game.cue + game.bin + game.iso), only import the launch
        // file (.cue if present, otherwise .ccd, otherwise keep .iso/.chd
        // as single-file formats). Skip companion files (.bin/.img/.sub).
        // This matches the folder-scan behavior in scanUriForRomsRecursive
        // and prevents the user's reported bug of "3 entries per MD CD game".
        data class PickedFile(val uri: android.net.Uri, val name: String, val ext: String)
        val picked = mutableListOf<PickedFile>()
        uris.forEach { u ->
            val n = queryDisplayName(u) ?: return@forEach
            val e = n.substringAfterLast('.', "").lowercase()
            if (e in ROM_EXTENSIONS) picked.add(PickedFile(u, n, e))
        }
        val pickedExts = picked.map { it.ext }.toSet()
        val hasCue = "cue" in pickedExts
        val hasCcd = "ccd" in pickedExts
        // .bin is only skipped if we have a .cue (it's a CD data track).
        // Without .cue, a .bin is likely a SEGA MD cart dump — keep it.
        val skipIfCue = setOf("img", "bin", "ccd", "sub", "iso")
        val skipIfCcd = setOf("img", "sub")
        val filtered = picked.filter { c ->
            if (c.ext == "sub") return@filter false  // .sub is always a companion
            if (hasCue && c.ext in skipIfCue) return@filter false
            if (!hasCue && hasCcd && c.ext in skipIfCcd) return@filter false
            true
        }

        var count = 0
        var skipped = picked.size - filtered.size
        filtered.forEach { pf ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    pf.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            // Pass the user's selected platform tab as a hint so that
            // ambiguous CD-image extensions (.cue/.img/.iso/.ccd/.sub)
            // are resolved in favor of the user's intent. Without a hint,
            // SEGA-CD games would default to DOS.
            val platform = detectPlatformFromUri(context, pf.uri, pf.name, hintPlatform = selectedPlatform)
            // 街机游戏使用中文名映射（kof98h → 拳皇98 - ...）
            val title = if (platform == GamePlatform.ARCADE) {
                ArcadeTitleMapper.resolveDisplayTitle(pf.name)
            } else {
                pf.name.substringBeforeLast('.')
            }
            RomStore.add(context, title, pf.uri.toString(), platform)
            count++
        }
        if (count > 0) {
            refreshList()
            dialogMsg = if (skipped > 0) {
                "已导入 $count 个ROM文件（自动跳过 $skipped 个CD附属文件）"
            } else {
                "已导入 $count 个ROM文件"
            }
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

            // === DOSBox folder import ===
            // When the user is on the DOS platform tab and picks a folder, we
            // only import the executable launcher file (play.bat / run.bat /
            // START.BAT / setup.exe / ...). Data files in the same folder are
            // left untouched — dosbox_pure reads them via its own VFS at run
            // time using the launcher's parent directory as the working dir.
            if (selectedPlatform == GamePlatform.DOS) {
                val launcherUri = findDosLauncherInSafTree(context, uri)
                if (launcherUri == null) {
                    dialogMsg = "所选文件夹未找到 DOS 启动文件（支持 .bat / .exe / .com）\n" +
                                "建议命名：play.bat / run.bat / START.BAT"
                } else {
                    val launcherName = queryDisplayName(launcherUri) ?: "dos_game.bat"
                    val execName = launcherName.substringBeforeLast('.')
                    // Build title as "folderName(execName)" — e.g. folder "pal"
                    // + launcher "play.bat" → "pal(play)". This makes it easy to
                    // distinguish multiple games that share the same launcher name
                    // (e.g. several games each with their own play.bat).
                    val folderName = extractFolderNameFromTreeUri(uri)
                    val title = if (folderName.isNotEmpty()) {
                        "$folderName($execName)"
                    } else {
                        execName
                    }
                    RomStore.add(context, title, launcherUri.toString(), GamePlatform.DOS)
                    refreshList()
                    dialogMsg = "已导入 DOS 游戏：$title"
                }
                return@rememberLauncherForActivityResult
            }

            // Recursively scan the selected folder for ROM files
            val romFiles = scanUriForRomsRecursive(context, uri, uri, maxDepth = 5)
            if (romFiles.isEmpty()) {
                dialogMsg = "所选文件夹未找到ROM文件（支持 .nes .smc .sfc .gb .gbc .gba .fds .md .smd .gen .sms .gg .sg .zip .7z .dosz .cue .chd）"
            } else {
                // Save the folder URI so the Refresh button can re-scan it
                // later (without re-asking the user to pick the folder again).
                RomStore.setLastImportFolder(context, uri.toString(), selectedPlatform)
                var count = 0
                var failed = 0
                romFiles.forEach { (name, fileUri) ->
                    try {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val platform = detectPlatformFromUri(context, fileUri, name, hintPlatform = selectedPlatform)
                        // 街机游戏使用中文名映射
                        val title = if (platform == GamePlatform.ARCADE) {
                            ArcadeTitleMapper.resolveDisplayTitle(name)
                        } else {
                            name.substringBeforeLast('.')
                        }
                        RomStore.add(context, title, fileUri.toString(), platform)
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
                    val platform = detectPlatformFromFile(File(path), hintPlatform = selectedPlatform)
                    val title = if (platform == GamePlatform.ARCADE) {
                        ArcadeTitleMapper.resolveDisplayTitle(name)
                    } else {
                        name.substringBeforeLast('.')
                    }
                    RomStore.add(context, title, path, platform)
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
                        val platform = detectPlatformFromFile(File(path), hintPlatform = selectedPlatform)
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
                        GamePlatform.DOS,
                        GamePlatform.ARCADE,
                        GamePlatform.MD,
                        GamePlatform.PCE,
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
                    // Resolve icon path: custom icon > built-in icon (Java/arcade)
                    // > cover path. GameIconExtractor handles Java MIDlet-Icon
                    // extraction and arcade zip preview png extraction.
                    val resolvedIcon = remember(g.id, g.customIconPath, g.coverPath) {
                        com.nesstation.app.core.storage.GameIconExtractor
                            .resolveIconPath(context, g)
                    }
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        onLongClick = { longPressGame = g },
                        coverPath = resolvedIcon,
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
                val folder = File(folderPath)

                // === DOSBox folder import — local file system path ===
                if (selectedPlatform == GamePlatform.DOS) {
                    val launcher = findDosLauncherInLocalFolder(folder)
                    if (launcher == null) {
                        dialogMsg = "所选文件夹未找到 DOS 启动文件（支持 .bat / .exe / .com）\n" +
                                    "建议命名：play.bat / run.bat / START.BAT"
                    } else {
                        // Build title as "folderName(execName)" — e.g. folder "pal"
                        // + launcher "play.bat" → "pal(play)".
                        val folderName = folder.name
                        val execName = launcher.nameWithoutExtension
                        val title = "$folderName($execName)"
                        RomStore.add(
                            context,
                            title,
                            launcher.absolutePath,
                            GamePlatform.DOS
                        )
                        refreshList()
                        dialogMsg = "已导入 DOS 游戏：$title"
                    }
                    return@FileBrowserDialog
                }

                // Recursively scan the chosen folder for ROM files (same logic
                // as the SAF folder picker callback above).
                val romFiles = scanLocalFolderForRoms(folder, maxDepth = 5)
                if (romFiles.isEmpty()) {
                    dialogMsg = "所选文件夹未找到ROM文件（支持 ${ROM_EXTENSIONS.joinToString()}）"
                } else {
                    // === FIX: save the local folder path so the Refresh button
                    // can re-scan it later (previously this branch only added
                    // games to RomStore but never called setLastImportFolder,
                    // so refreshList() had no folder to re-scan and the button
                    // did nothing).
                    RomStore.setLastImportFolder(context, folder.absolutePath, selectedPlatform)
                    var count = 0
                    var failed = 0
                    romFiles.forEach { file ->
                        try {
                            val platform = detectPlatformFromFile(file, hintPlatform = selectedPlatform)
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
    // === FIX: do NOT swallow SecurityException here ===
    // Previously the whole body was wrapped in `try { ... } catch (_: Exception) { }`,
    // which silently ate SecurityException when the persistable URI permission
    // had been revoked. As a result refreshList()'s outer catch never saw the
    // SecurityException and the user got no "需要重新选择文件夹" message — the
    // refresh button just did nothing.
    //
    // We now let SecurityException propagate to refreshList(). Other
    // exceptions (e.g. a single malformed cursor row) are still swallowed
    // per-row so a partial scan still returns something useful.
    val folderDocId = if (folderUri == treeUri) {
        DocumentsContract.getTreeDocumentId(treeUri)
    } else {
        DocumentsContract.getDocumentId(folderUri)
    }
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)

    // === Two-pass scan to deduplicate multi-file CD images ===
    // A typical Mega-CD / SEGA-CD dump consists of:
    //   game.cue + game.img + game.ccd + game.sub   (4 files!)
    //   OR  game.cue + game.bin + game.sub
    //   OR  game.chd                                 (single file)
    //   OR  game.iso                                 (single file)
    // Previously we imported .cue + .img + .ccd as 3 separate library
    // entries, which cluttered the UI. Now we do a pre-pass to collect
    // all file extensions in the folder, then:
    //   - If the folder has .cue → import only .cue (skip .img/.bin/.ccd/.sub/.iso)
    //   - If the folder has .ccd (no .cue) → import only .ccd (skip .img/.sub)
    //   - .chd and standalone .iso are always imported (single-file formats)
    // This ensures each game appears as ONE entry in the library.
    data class FileEntry(val name: String, val uri: Uri, val ext: String)
    val candidates = mutableListOf<FileEntry>()

    // NOTE: the .query() call itself can throw SecurityException on revoked
    // permissions — that's exactly what we WANT to propagate up so the
    // caller can show the "re-select folder" message.
    context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            try {
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
                        candidates.add(FileEntry(name, fileUri, ext))
                    }
                }
            } catch (_: Exception) {
                // Skip a single bad row but keep scanning the rest
            }
        }
    }

    // Pass 2: decide which candidates to keep based on folder contents.
    // CD companion files that should be skipped if a primary exists.
    val folderExts = candidates.map { it.ext }.toSet()
    val hasCue = "cue" in folderExts
    val hasCcd = "ccd" in folderExts
    // .bin is tricky — it could be a Mega-CD data track OR a SEGA MD
    // cartridge dump. Only skip .bin if we have a .cue (which references
    // it as a CD data track); otherwise keep it (it's likely a cart).
    val skipIfCue = setOf("img", "bin", "ccd", "sub", "iso")
    val skipIfCcd = setOf("img", "sub")

    for (c in candidates) {
        if (hasCue && c.ext in skipIfCue) continue
        if (hasCue.not() && hasCcd && c.ext in skipIfCcd) continue
        // .sub is always a companion file — never import standalone.
        if (c.ext == "sub") continue
        results.add(c.name to c.uri)
    }
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

    // === Two-pass deduplication (same logic as scanUriForRomsRecursive) ===
    // If folder contains .cue → skip .img/.bin/.ccd/.sub/.iso (CD companions)
    // If folder contains .ccd (no .cue) → skip .img/.sub
    // .sub is always skipped (always a companion file).
    val fileChildren = children.filter { it.isFile && !it.name.startsWith(".") }
    val dirChildren = children.filter { it.isDirectory && !it.name.startsWith(".") }

    val folderExts = fileChildren.map { it.extension.lowercase() }.toSet()
    val hasCue = "cue" in folderExts
    val hasCcd = "ccd" in folderExts
    val skipIfCue = setOf("img", "bin", "ccd", "sub", "iso")
    val skipIfCcd = setOf("img", "sub")

    for (f in fileChildren) {
        val ext = f.extension.lowercase()
        if (ext !in ROM_EXTENSIONS) continue
        if (hasCue && ext in skipIfCue) continue
        if (hasCue.not() && hasCcd && ext in skipIfCcd) continue
        if (ext == "sub") continue
        results.add(f)
    }
    for (d in dirChildren) {
        results.addAll(scanLocalFolderForRoms(d, maxDepth - 1))
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
 * Extensions that are unambiguously FBNeo arcade ROM content, found inside
 * .zip archives. NeoGeo uses .p1/.p2/.sp1 (program), .s1 (fix layer),
 * .m1 (Z80 program), .v1/.v2 (ADPCM audio), .c1..c4 (sprites), .lo (lookup).
 * CPS1/CPS2 use .prg/.gfx/.snd etc. These extensions never appear in MD/SMS
 * ROM dumps, so their presence is a strong signal the zip is an arcade ROM.
 */
private val ARCADE_ROM_EXTENSIONS = setOf(
    // NeoGeo
    "p1", "p2", "sp1", "sp2", "p3", "p4", "s1", "s2", "m1", "m2",
    "v1", "v2", "v3", "v4", "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8",
    "lo", "sm1", "sfix",
    // CPS1 / CPS2 / CPS3
    "prg", "gfx", "snd", "qsf", "q1", "q2", "q3", "q4", "q5",
    // PGM
    "b1", "b2", "t1", "t2", "u1", "u2", "v10", "v20", "v30", "v40",
    // Generic arcade dumps
    "rom", "bin"   // .bin inside a zip is most likely arcade (CPS/MD carts are usually bare .md/.bin)
)

/**
 * Detect the game platform from a file URI.
 * For ZIP/7z/gz archives, looks inside to find the actual ROM extension.
 *
 * Disambiguation priority for zips:
 *   1. If any entry has a known ARCADE extension (.p1, .s1, .c1, .rom, etc.)
 *      → ARCADE (arcade zips often contain .bin files that would otherwise
 *      match MD detection).
 *   2. If any entry has a known MD extension (.md, .smd, .gen, .sms, .gg,
 *      .sg, .68k) → MD.
 *   3. If any entry has another platform's extension → that platform.
 *   4. Otherwise → ARCADE (default for unrecognized zips, since arcade
 *      ROMs use arbitrary driver-name extensions).
 *
 * @param hintPlatform When the user has explicitly chosen a platform tab
 *   (e.g. MD), pass it here so ambiguous CD-image extensions (.cue/.img/.iso/.ccd/.sub)
 *   are resolved in favor of the user's choice. Without a hint, these extensions
 *   default to DOS (DOSBox-Pure), which would misclassify SEGA-CD/Mega-CD games
 *   stored as .cue/.img/.ccd as DOS games.
 */
private fun detectPlatformFromUri(
    context: android.content.Context,
    uri: Uri,
    fileName: String,
    hintPlatform: GamePlatform? = null
): GamePlatform {
    val ext = fileName.substringAfterLast('.', "").lowercase()

    // === CD-image extension ambiguity ===
    // .cue/.img/.iso/.ccd/.sub are used by DOSBox-Pure (DOS CD games),
    // Genesis-Plus-GX (SEGA-CD / Mega-CD) AND Geargrafx (PCE-CD).
    // We disambiguate using the user's selected platform tab. If no hint,
    // default to MD (more common for retro console users than DOS).
    if (ext in CD_IMAGE_EXTENSIONS) {
        return when (hintPlatform) {
            GamePlatform.DOS -> GamePlatform.DOS
            GamePlatform.PCE -> GamePlatform.PCE
            else -> GamePlatform.MD
        }
    }

    // Direct ROM extension — use it immediately
    GamePlatform.fromExtension(ext)?.let { return it }

    // For compressed archives, inspect contents to determine platform
    if (ext == "zip") {
        // Collect all entry extensions for two-pass detection
        val entryExts = mutableListOf<String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val entryExt = entry.name.substringAfterLast('.', "").lowercase()
                            if (entryExt.isNotEmpty()) entryExts.add(entryExt)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (_: Exception) { }

        // Pass 1: arcade-specific extensions (highest priority for zips)
        if (entryExts.any { it in ARCADE_ROM_EXTENSIONS }) return GamePlatform.ARCADE

        // Pass 2: any other recognized platform extension (NES/SFC/GB/GBA/MD/DOS)
        for (entryExt in entryExts) {
            GamePlatform.fromExtension(entryExt)?.let { return it }
        }

        // Pass 3: if the zip contains CD-image extensions (.cue/.img) and the
        // user picked the MD or PCE tab, treat as that platform (Mega-CD /
        // PCE-CD). Otherwise arcade.
        if (entryExts.any { it in CD_IMAGE_EXTENSIONS } && hintPlatform == GamePlatform.MD) {
            return GamePlatform.MD
        }
        if (entryExts.any { it in CD_IMAGE_EXTENSIONS } && hintPlatform == GamePlatform.PCE) {
            return GamePlatform.PCE
        }

        // Pass 4: no recognized extension → default to arcade
        return GamePlatform.ARCADE
    }

    // .7z is always arcade (FBNeo).
    if (ext == "7z") return GamePlatform.ARCADE

    // .gz — decompress and inspect (rare; treated as arcade default)
    if (ext == "gz") return GamePlatform.ARCADE

    // Bare .bin file (not in a zip) — most commonly a SEGA Mega Drive
    // cart dump. The user can manually re-tag via the platform filter if
    // it's actually a DOS image or arcade ROM.
    if (ext == "bin") return GamePlatform.MD

    return GamePlatform.NES // fallback
}

/**
 * CD-image extensions shared by DOSBox (DOS CD games) and Genesis-Plus-GX
 * (SEGA-CD / Mega-CD). Disambiguation requires either the user's platform
 * tab or folder context (e.g. presence of a .bat/.exe suggests DOS).
 */
private val CD_IMAGE_EXTENSIONS = setOf(
    "cue", "img", "iso", "ccd", "sub", "bin", "chd"
)

/**
 * Detect the game platform from a local File.
 * For ZIP archives, looks inside to find the actual ROM extension.
 *
 * @param hintPlatform When the user has chosen a platform tab, CD-image
 *   extensions (.cue/.img/.iso/.ccd/.sub) are resolved to that platform.
 */
private fun detectPlatformFromFile(file: File, hintPlatform: GamePlatform? = null): GamePlatform {
    val ext = file.extension.lowercase()

    // CD-image extensions: ambiguous between DOS (DOSBox), MD (Mega-CD),
    // and PCE (PCE-CD). Use the user's selected platform tab as hint;
    // default to MD.
    if (ext in CD_IMAGE_EXTENSIONS) {
        return when (hintPlatform) {
            GamePlatform.DOS -> GamePlatform.DOS
            GamePlatform.PCE -> GamePlatform.PCE
            else -> GamePlatform.MD
        }
    }

    GamePlatform.fromExtension(ext)?.let { return it }

    if (ext == "zip") {
        val entryExts = mutableListOf<String>()
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        val entryExt = entry.name.substringAfterLast('.', "").lowercase()
                        if (entryExt.isNotEmpty()) entryExts.add(entryExt)
                    }
                }
            }
        } catch (_: Exception) { }

        // Pass 1: arcade-specific extensions (highest priority for zips)
        if (entryExts.any { it in ARCADE_ROM_EXTENSIONS }) return GamePlatform.ARCADE

        // Pass 2: any other recognized platform extension
        for (entryExt in entryExts) {
            GamePlatform.fromExtension(entryExt)?.let { return it }
        }

        // Pass 3: if zip contains CD-image extensions and user picked MD or PCE
        if (entryExts.any { it in CD_IMAGE_EXTENSIONS } && hintPlatform == GamePlatform.MD) {
            return GamePlatform.MD
        }
        if (entryExts.any { it in CD_IMAGE_EXTENSIONS } && hintPlatform == GamePlatform.PCE) {
            return GamePlatform.PCE
        }

        return GamePlatform.ARCADE
    }

    // .7z is always arcade (FBNeo).
    if (ext == "7z") return GamePlatform.ARCADE

    // Bare .bin file (not in a zip) — most commonly a SEGA Mega Drive
    // cart dump.
    if (ext == "bin") return GamePlatform.MD

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

// ---------------------------------------------------------------------------
// DOSBox folder-import helpers
// ---------------------------------------------------------------------------

/**
 * Extract the folder display name from a SAF tree URI.
 *
 * SAF tree URIs look like:
 *   content://com.android.externalstorage.documents/tree/primary:Games%2Fpal
 *   content://com.android.externalstorage.documents/tree/msf%3A1234%3BGames%2Fpal
 *
 * The last path segment (after "tree/") is the document ID, URL-encoded.
 * After decoding, it looks like "primary:Games/pal" or "msf:1234;Games/pal".
 * The folder name is the part after the last "/" (or after ":" if no "/").
 *
 * Returns "" if the name cannot be extracted.
 *
 * Chinese folder names are handled transparently — URL decoding produces the
 * original Unicode string.
 */
private fun extractFolderNameFromTreeUri(treeUri: Uri): String {
    return try {
        val paths = treeUri.pathSegments
        val treeIdx = paths.indexOf("tree")
        if (treeIdx < 0 || treeIdx + 1 >= paths.size) return ""
        val treeDocId = android.net.Uri.decode(paths[treeIdx + 1])
        // treeDocId looks like "primary:Games/pal" or "primary:Games%2Fpal" (already decoded)
        // The folder name is the last segment after "/" or ":".
        val afterColon = treeDocId.substringAfter(':')
        val afterSlash = afterColon.substringAfterLast('/')
        afterSlash.ifBlank { treeDocId.substringAfterLast(':').ifBlank { treeDocId } }
    } catch (_: Exception) {
        ""
    }
}

/**
 * Recursively scan a SAF tree folder for DOS launcher files (.bat / .exe / .com)
 * and return the URI of the best launch candidate by priority:
 *   play.bat > run.bat > START.BAT > autoexec.bat > go.bat > launch.bat >
 *   main.bat > play.exe > ... > setup.exe > any .exe > any .com
 *
 * Returns null if no launcher is found.
 *
 * Works with content:// URIs that contain UTF-8 percent-encoded Chinese
 * characters — DocumentsContract handles the encoding transparently.
 */
private fun findDosLauncherInSafTree(
    context: android.content.Context,
    treeUri: Uri
): Uri? {
    data class Candidate(val uri: Uri, val name: String, val isBat: Boolean, val isExe: Boolean)

    val candidates = mutableListOf<Candidate>()

    fun walk(folderUri: Uri) {
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
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: continue
                    val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val subUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        walk(subUri)
                    } else {
                        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                        if (ext in DOS_LAUNCHER_EXTENSIONS) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            candidates.add(Candidate(fileUri, name.lowercase(),
                                isBat = ext == "bat", isExe = ext == "exe"))
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }
    walk(treeUri)

    if (candidates.isEmpty()) return null

    // Match by priority list (case-insensitive)
    for (preferred in DOS_LAUNCHER_PRIORITY) {
        val match = candidates.firstOrNull { it.name == preferred }
        if (match != null) return match.uri
    }
    // Fallback: prefer .bat, then .exe, then .com
    return candidates.firstOrNull { it.isBat }?.uri
        ?: candidates.firstOrNull { it.isExe }?.uri
        ?: candidates.firstOrNull()?.uri
}

/**
 * Scan a local file system folder for DOS launcher files and return the best
 * launch candidate by priority. Used by the built-in FileBrowserDialog when
 * the system SAF picker is unavailable (TV devices).
 *
 * Chinese folder/file names work transparently — Java's File class is
 * Unicode-native and Android's filesystem is UTF-8.
 */
private fun findDosLauncherInLocalFolder(folder: File): File? {
    if (!folder.exists() || !folder.isDirectory) return null

    val candidates = folder.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in DOS_LAUNCHER_EXTENSIONS }
        .toList()

    if (candidates.isEmpty()) return null

    for (preferred in DOS_LAUNCHER_PRIORITY) {
        val match = candidates.firstOrNull { it.name.equals(preferred, ignoreCase = true) }
        if (match != null) return match
    }
    return candidates.firstOrNull { it.name.endsWith(".bat", ignoreCase = true) }
        ?: candidates.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
        ?: candidates.firstOrNull { it.name.endsWith(".com", ignoreCase = true) }
}
