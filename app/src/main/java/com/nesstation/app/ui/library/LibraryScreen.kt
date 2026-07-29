package com.nesstation.app.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.ui.components.GameCard
import com.nesstation.app.ui.components.PixelBackdrop
import java.io.File

/// ROM file extensions we support
val ROM_EXTENSIONS = listOf("nes", "fds", "unf", "unif", "zip", "7z", "gz")

@Composable
fun LibraryScreen(
    games: List<GameEntry>,
    onOpenGame: (GameEntry) -> Unit,
    onImport: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Load persisted ROMs on first composition
    val importedGames = remember { mutableStateListOf<GameEntry>().apply { addAll(RomStore.loadAll(context)) } }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var dialogMsg by remember { mutableStateOf<String?>(null) }

    fun refreshList() {
        importedGames.clear()
        importedGames.addAll(RomStore.loadAll(context))
    }

    // SAF file picker for importing individual ROM files
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val entries = mutableListOf<Pair<String, String>>()
        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val name = queryDisplayName(uri) ?: "unknown.nes"
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in ROM_EXTENSIONS) {
                entries.add(name.substringBeforeLast('.') to uri.toString())
            }
        }
        if (entries.isNotEmpty()) {
            RomStore.addAll(context, entries)
            refreshList()
            dialogMsg = "已导入 ${entries.size} 个ROM文件"
        }
    }

    // SAF folder picker — recursively scan selected folder
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }

        // Recursively scan the selected folder for ROM files
        val romFiles = scanUriForRomsRecursive(context, uri, uri, maxDepth = 5)
        if (romFiles.isEmpty()) {
            dialogMsg = "所选文件夹未找到ROM文件（支持 .nes .fds .zip .7z .gz）"
        } else {
            val entries = romFiles.map { it.first to it.second.toString() }
            RomStore.addAll(context, entries)
            refreshList()
            dialogMsg = "从文件夹导入 ${entries.size} 个ROM文件"
        }
    }

    // Storage permission launcher (Android <= 10)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            val entries = scanForRoms(context)
            if (entries.isNotEmpty()) {
                RomStore.addAll(context, entries)
                refreshList()
                dialogMsg = "权限已授予，扫描到 ${entries.size} 个ROM文件"
            } else {
                dialogMsg = "权限已授予，但未在常见目录找到ROM文件"
            }
        } else {
            showPermissionDialog = true
        }
    }

    fun importFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                val entries = scanForRoms(context)
                if (entries.isNotEmpty()) {
                    RomStore.addAll(context, entries)
                    refreshList()
                }
            }
            filePickerLauncher.launch(arrayOf("*/*"))
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                val entries = scanForRoms(context)
                if (entries.isNotEmpty()) {
                    RomStore.addAll(context, entries)
                    refreshList()
                }
                filePickerLauncher.launch(arrayOf("*/*"))
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }
    }

    fun importFolder() {
        folderPickerLauncher.launch(null)
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
                    RomStore.addAll(context, entries)
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

    val allGames = importedGames + games

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
                        text = "${allGames.size} 款游戏 · 复古之旅",
                        color = Color(0xFF4A5568),
                        fontSize = 11.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
            }

            // Action row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip("全部", true)
                FilterChip("最近", false)
                FilterChip("收藏", false)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { refreshList() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = Color(0xFF1E2A3A), modifier = Modifier.size(18.dp))
                }
                SearchPill(onClick = onSearch)
            }

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
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(allGames) { g ->
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        modifier = Modifier.height(130.dp)
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
                        results.add(name.substringBeforeLast('.') to fileUri)
                    }
                }
            }
        }
    } catch (_: Exception) { }
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
                    results.add(file.nameWithoutExtension to file.absolutePath)
                }
            }
        }
    }
    return results
}

@Composable
private fun FilterChip(text: String, selected: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Color(0xFF1E2A3A)
                else Color.White.copy(alpha = 0.5f)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else Color(0xFF1E2A3A),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
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
