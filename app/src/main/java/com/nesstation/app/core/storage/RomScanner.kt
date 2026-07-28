package com.nesstation.app.core.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans common ROM directories and any user-granted SAF tree for `.nes` files.
 */
class RomScanner(private val context: Context) {

    suspend fun scanDefaults(): List<File> = withContext(Dispatchers.IO) {
        val candidates = buildList {
            // /sdcard/ROMs, /sdcard/NesStation, /sdcard/Download/NesStation
            val sd = Environment.getExternalStorageDirectory()
            add(File(sd, "ROMs"))
            add(File(sd, "NesStation"))
            add(File(sd, "Download/NesStation"))
            // app-private dir, useful for sideloading
            add(context.getExternalFilesDir("roms") ?: File(context.filesDir, "roms"))
        }
        candidates.filter { it.exists() && it.isDirectory }
            .flatMap { it.walkTopDown().filter(File::isFile).toList() }
            .filter { it.extension.equals("nes", ignoreCase = true) }
    }

    suspend fun scanSafTree(treeUri: Uri): List<File> = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val out = mutableListOf<File>()
        doc.traverse { name ->
            if (name.endsWith(".nes", ignoreCase = true)) out.add(File(name))
        }
        out
    }

    private fun DocumentFile.traverse(visit: (String) -> Unit) {
        val children = listFiles() ?: return
        children.forEach { c ->
            if (c.isDirectory) c.traverse(visit)
            else if (c.isFile) c.uri.lastPathSegment?.let(visit)
        }
    }
}
