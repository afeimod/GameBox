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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import com.nesstation.app.ui.components.GameCard
import com.nesstation.app.ui.components.PixelBackdrop
import java.io.File

@Composable
fun LibraryScreen(
    games: List<GameEntry>,
    onOpenGame: (GameEntry) -> Unit,
    onImport: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val importedGames = remember { mutableStateListOf<GameEntry>() }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var dialogMsg by remember { mutableStateOf<String?>(null) }

    // SAF file picker for importing individual ROM files
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            try {
                // Take persistable URI permission so we can read it later
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val name = queryDisplayName(uri) ?: "unknown.nes"
            importedGames.add(
                GameEntry(
                    id = "import_${System.currentTimeMillis()}_${name}",
                    title = name.substringBeforeLast('.'),
                    romPath = uri.toString()
                )
            )
        }
        dialogMsg = "已导入 ${uris.size} 个ROM文件"
    }

    // SAF folder picker for importing an entire folder of ROMs
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }

        // Scan the selected folder for ROM files
        val romFiles = scanUriForRoms(context, uri)
        if (romFiles.isEmpty()) {
            dialogMsg = "所选文件夹未找到ROM文件（支持 .nes .fds .unf .unif）"
        } else {
            romFiles.forEach { (name, fileUri) ->
                importedGames.add(
                    GameEntry(
                        id = "folder_${fileUri.hashCode()}",
                        title = name,
                        romPath = fileUri.toString()
                    )
                )
            }
            dialogMsg = "从文件夹导入 ${romFiles.size} 个ROM文件"
        }
    }

    // Storage permission launcher (for Android <= 10)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            scanForRoms(context, importedGames)
            dialogMsg = "权限已授予，已扫描本地ROM文件"
        } else {
            showPermissionDialog = true
        }
    }

    fun importFiles() {
        // On Android 11+, SAF doesn't need storage permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Also scan common directories if we have MANAGE_EXTERNAL_STORAGE
            if (Environment.isExternalStorageManager()) {
                scanForRoms(context, importedGames)
            }
            filePickerLauncher.launch(arrayOf("*/*"))
        } else {
            val perm = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                scanForRoms(context, importedGames)
                filePickerLauncher.launch(arrayOf("*/*"))
            } else {
                permissionLauncher.launch(perm)
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
                scanForRoms(context, importedGames)
                dialogMsg = "已有所有文件访问权限，已扫描本地ROM"
            }
        } else {
            val perm = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionLauncher.launch(perm)
        }
    }

    val allGames = importedGames + games

    Box(modifier = modifier.fillMaxSize()) {
        PixelBackdrop()
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
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
                        text = "点击此处授予「所有文件访问权限」，可自动扫描本地ROM。也可直接点击上方按钮通过系统文件选择器导入。",
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
                        modifier = Modifier.height(145.dp)
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

/** Scan a SAF folder URI for ROM files using DocumentsContract */
private fun scanUriForRoms(
    context: android.content.Context,
    folderUri: Uri
): List<Pair<String, Uri>> {
    val results = mutableListOf<Pair<String, Uri>>()
    val extensions = listOf("nes", "fds", "unf", "unif")
    try {
        val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)
        context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                // Only look at files (not subdirectories)
                if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in extensions) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                        results.add(name.substringBeforeLast('.') to fileUri)
                    }
                }
            }
        }
    } catch (_: Exception) { }
    return results
}

/** Scan common directories for .nes files (requires storage permission) */
private fun scanForRoms(
    context: android.content.Context,
    list: androidx.compose.runtime.snapshots.SnapshotStateList<GameEntry>
) {
    val dirs = mutableListOf<File>()
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
        dirs.add(Environment.getExternalStorageDirectory())
        dirs.add(File(Environment.getExternalStorageDirectory(), "Download"))
        dirs.add(File(Environment.getExternalStorageDirectory(), "ROMs"))
        dirs.add(File(Environment.getExternalStorageDirectory(), "NES"))
    }
    dirs.add(context.getExternalFilesDir(null) ?: context.filesDir)
    dirs.add(File(context.filesDir, "roms"))

    dirs.forEach { dir ->
        if (dir.exists() && dir.isDirectory) {
            dir.walkTopDown().take(100).forEach { file ->
                if (file.isFile && file.extension.lowercase() in listOf("nes", "fds", "unf", "unif")) {
                    val entry = GameEntry(
                        id = "scan_${file.absolutePath.hashCode()}",
                        title = file.nameWithoutExtension,
                        romPath = file.absolutePath
                    )
                    if (list.none { it.romPath == entry.romPath }) {
                        list.add(entry)
                    }
                }
            }
        }
    }
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
