package com.nesstation.app.core.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import androidx.compose.ui.graphics.Color
import java.io.File
import java.util.jar.JarFile
import android.content.SharedPreferences

/**
 * Manages J2ME/Java ME game installation and listing.
 * Integrates with J2ME-Loader's Config and AppInstaller.
 */
object JavaGameStore {
    private const val TAG = "JavaGameStore"
    private val JAVA_ACCENT_COLORS = listOf(
        0xFF6A1B9A.toInt(), 0xFF283593.toInt(), 0xFF0277BD.toInt(),
        0xFF00838F.toInt(), 0xFF2E7D32.toInt(), 0xFFEF6C00.toInt()
    )

    /**
     * Get the J2ME converted apps directory.
     * Uses Config.getAppDir() which is initialized by J2ME-Loader.
     */
    private fun getConvertedDir(): File {
        return try {
            File(ru.playsoftware.j2meloader.config.Config.getAppDir())
        } catch (e: Exception) {
            // Fallback if Config hasn't been initialized
            File(System.getProperty("user.dir"), "J2ME-Loader/converted")
        }
    }

    /**
     * Scan the converted directory for installed Java games.
     * Returns a list of GameEntry objects for each installed game.
     */
    fun loadAll(ctx: Context): List<GameEntry> {
        val results = mutableListOf<GameEntry>()
        val convertedDir = getConvertedDir()
        if (!convertedDir.exists() || !convertedDir.isDirectory) {
            return results
        }
        convertedDir.listFiles()?.forEach { appDir ->
            if (!appDir.isDirectory) return@forEach
            val dexFile = File(appDir, "converted.dex")
            val manifestFile = File(appDir, "converted.dex.conf")
            if (!dexFile.exists() || !manifestFile.exists()) return@forEach

            try {
                val manifest = parseManifest(manifestFile)
                val title = manifest["MIDlet-Name"] ?: appDir.name
                val iconFile = File(appDir, "icon.png")
                val iconPath = if (iconFile.exists()) iconFile.absolutePath else null
                val appPath = appDir.absolutePath

                // Check if this game is already in RomStore (for custom icon, favorite, etc.)
                val existing = RomStore.loadAll(ctx).firstOrNull { it.romPath == appPath }

                val entry = if (existing != null) {
                    existing.copy(
                        title = title,
                        platform = GamePlatform.JAVA,
                        coverPath = iconPath
                    )
                } else {
                    val accent = JAVA_ACCENT_COLORS[results.size % JAVA_ACCENT_COLORS.size]
                    GameEntry(
                        id = "java_${appDir.name}",
                        title = title,
                        romPath = appPath,
                        accent = Color(accent),
                        platform = GamePlatform.JAVA,
                        coverPath = iconPath
                    )
                }
                results.add(entry)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Java game: ${appDir.name}", e)
            }
        }
        return results
    }

    /**
     * Install a JAR file as a J2ME game.
     * Uses J2ME-Loader's AppInstaller for DEX conversion.
     * Returns the installed game path on success, null on failure.
     */
    fun installJar(ctx: Context, jarUri: Uri): String? {
        return try {
            // Copy JAR to cache directory first
            val cacheDir = File(ctx.cacheDir, "installer")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val jarFile = File(cacheDir, "temp_${System.currentTimeMillis()}.jar")

            ctx.contentResolver.openInputStream(jarUri)?.use { input ->
                jarFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            // Parse manifest from JAR
            val jar = JarFile(jarFile)
            val manifest = jar.manifest
            val attrs = manifest.mainAttributes
            val title = attrs.getValue("MIDlet-Name") ?: jarFile.nameWithoutExtension
            val vendor = attrs.getValue("MIDlet-Vendor") ?: "Unknown"
            val version = attrs.getValue("MIDlet-Version") ?: "1.0"
            val iconPath = attrs.getValue("MIDlet-Icon")

            // Generate app directory name
            val convertedDir = getConvertedDir()
            if (!convertedDir.exists()) convertedDir.mkdirs()
            val appDirName = title.replace(Regex("[^\\w]"), "_").take(30)
            var targetDir = File(convertedDir, appDirName)
            var counter = 1
            while (targetDir.exists()) {
                targetDir = File(convertedDir, "${appDirName}_$counter")
                counter++
            }
            targetDir.mkdirs()

            // Convert JAR to DEX using dx
            try {
                com.android.dx.command.dexer.Main.main(arrayOf(
                    "--no-optimize", "--core-library",
                    "--output=${File(targetDir, "converted.dex").absolutePath}",
                    jarFile.absolutePath
                ))
            } catch (e: Exception) {
                Log.e(TAG, "DEX conversion failed", e)
                targetDir.deleteRecursively()
                return null
            }

            // Copy JAR as res.jar
            val resJar = File(targetDir, "res.jar")
            jarFile.copyTo(resJar, overwrite = true)

            // Extract icon if available
            if (iconPath != null) {
                try {
                    val iconEntry = jar.getJarEntry(iconPath)
                    if (iconEntry != null) {
                        val iconFile = File(targetDir, "icon.png")
                        jar.getInputStream(iconEntry).use { input ->
                            iconFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract icon", e)
                }
            }

            // Write manifest file
            val manifestOut = File(targetDir, "converted.dex.conf")
            manifestOut.writeText(buildManifestString(title, vendor, version, attrs))

            // Clean up cache
            jarFile.delete()

            // Add to RomStore
            RomStore.add(ctx, title, targetDir.absolutePath, GamePlatform.JAVA)

            Log.i(TAG, "Java game installed: $title at ${targetDir.absolutePath}")
            targetDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install JAR", e)
            null
        }
    }

    /**
     * Launch a Java game using J2ME-Loader's Config.startApp().
     */
    fun launchGame(ctx: Context, game: GameEntry) {
        try {
            val path = game.romPath ?: return
            val title = game.title

            // Sync video filter setting to J2ME SharedPreferences so MicroActivity picks it up
            val padLayout = PadLayoutStore.load(ctx)
            val j2meMode = when (padLayout.videoFilter) {
                "scanline" -> 1; "crt" -> 2; "dot" -> 3
                "xbr" -> 4; "4xbr" -> 5; "xbr_dot" -> 6; "4xbr_dot" -> 7
                "hq4x" -> 8; "hq4x_dot" -> 9
                else -> 0
            }
            ctx.getSharedPreferences("j2me_prefs", Context.MODE_PRIVATE)
                .edit().putInt("j2me_video_filter", j2meMode).apply()

            ru.playsoftware.j2meloader.config.Config.startApp(ctx, title, path, false)
            // Update last played
            RomStore.updateLastPlayed(ctx, game.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Java game: ${game.title}", e)
        }
    }

    /**
     * Open J2ME config/settings for a game.
     */
    fun openSettings(ctx: Context, game: GameEntry) {
        try {
            val path = game.romPath ?: return
            val title = game.title
            ru.playsoftware.j2meloader.config.Config.startApp(ctx, title, path, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Java game settings: ${game.title}", e)
        }
    }

    /**
     * Delete a Java game from the converted directory.
     */
    fun deleteGame(ctx: Context, game: GameEntry) {
        try {
            val path = game.romPath ?: return
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                dir.deleteRecursively()
            }
            RomStore.remove(ctx, game.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Java game: ${game.title}", e)
        }
    }

    /**
     * Get the icon path for a Java game.
     */
    fun getIconPath(game: GameEntry): String? {
        val path = game.romPath ?: return null
        val iconFile = File(path, "icon.png")
        return if (iconFile.exists()) iconFile.absolutePath else null
    }

    private fun parseManifest(file: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        file.readLines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    private fun buildManifestString(
        title: String, vendor: String, version: String,
        attrs: java.util.jar.Attributes
    ): String {
        val sb = StringBuilder()
        sb.append("MIDlet-Name: $title\n")
        sb.append("MIDlet-Vendor: $vendor\n")
        sb.append("MIDlet-Version: $version\n")

        // Copy MIDlet-N entries
        attrs.forEach { (key, value) ->
            val keyStr = key.toString()
            if (keyStr.startsWith("MIDlet-")) {
                sb.append("$keyStr: $value\n")
            }
        }

        // Add default MIDlet-1 if not present
        if (attrs.getValue("MIDlet-1") == null) {
            val mainClass = attrs.getValue("MIDlet-main-class") ?: "MIDlet"
            sb.append("MIDlet-1: $title, , $mainClass\n")
        }

        return sb.toString()
    }
}
