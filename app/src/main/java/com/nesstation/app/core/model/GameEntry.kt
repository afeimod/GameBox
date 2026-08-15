package com.nesstation.app.core.model

import androidx.compose.ui.graphics.Color

/**
 * Game platform type.
 * NES    = NES/Famicom games (FCEUmm core)
 * SFC    = SNES/Super Famicom games (snes9x core)
 * GB     = Game Boy / Game Boy Color games (mGBA core)
 * GBA    = Game Boy Advance games (mGBA core)
 * DOS    = DOS/PC games (DOSBox-Pure core)
 * ARCADE = Arcade machines (FBNeo core — CPS1/2/3, NeoGeo, PGM, etc.)
 * MD     = SEGA Mega Drive / Genesis / Master System / Game Gear /
 *           Mega-CD / SG-1000 (Genesis-Plus-GX core)
 *           NOTE: SS (Saturn) is NOT supported by Genesis-Plus-GX —
 *           it requires a separate Saturn core (Yabause/Mednafen).
 * JAVA   = J2ME/Java ME games (J2ME-Loader engine)
 */
enum class GamePlatform(val displayName: String) {
    NES("NES"),
    SFC("SFC"),
    GB("GB/GBC"),
    GBA("GBA"),
    DOS("DOS"),
    ARCADE("Arcade"),
    MD("MD/SEGA"),
    JAVA("Java");

    companion object {
        fun fromString(value: String?): GamePlatform = when (value) {
            "GBC" -> GB  // Migration: GBC merged into GB
            else -> entries.firstOrNull { it.name == value } ?: NES
        }

        /**
         * Determine platform from a ROM file extension.
         * GB and GBC are merged into a single GB category.
         *
         * DOSBox accepts: .bat (batch launcher), .exe (DOS executable),
         * .com (small DOS executable), .dosz (dosbox-pure zip bundle),
         * .conf (dosbox config), .iso/.cue/.img (CD images),
         * .ima/.vhd/.hd (hard disk images).
         *
         * FBNeo (Arcade) accepts: .zip / .7z archives (the archive itself
         * IS the ROM — arcade ROMs are stored as zip files named after
         * their MAME-style driver, e.g. "mvc.zip", "kof97.zip").
         *
         * Genesis-Plus-GX (MD) accepts MD/Genesis ROMs (.md/.smd/.gen),
         * Master System (.sms), Game Gear (.gg), SG-1000 (.sg),
         * and Mega-CD images (.cue/.chd/.iso).
         *
         * NOTE on .zip: arcade ROMs are .zip files, but users may also
         * store other ROM types in .zip archives. The fromExtension()
         * function returns null for .zip so the caller (detectPlatformFromUri)
         * can peek inside the zip to find the actual ROM extension. If
         * the zip contains no recognized ROM extension, the caller should
         * default to ARCADE (since arcade zips contain raw .bin ROM files
         * which are not part of any other platform's standard).
         */
        fun fromExtension(ext: String): GamePlatform? {
            return when (ext.lowercase()) {
                "nes", "unf", "unif", "fds", "nez", "unh" -> NES
                "smc", "sfc", "swc", "fig", "bs" -> SFC
                "gb", "sgb", "gbc" -> GB
                "gba" -> GBA
                // DOSBox-Pure — executable launchers and bundle formats.
                "bat", "exe", "com", "dosz", "conf", "iso", "cue", "img", "ima", "vhd", "hd" -> DOS
                // FBNeo (Arcade) — .7z is unambiguously arcade (no other
                // platform uses .7z in this app). .zip is handled by the
                // caller (detectPlatformFromUri) because users may store
                // other ROM types in zip archives.
                "7z" -> ARCADE
                // Genesis-Plus-GX — MD/SMS/GG/SG cartridge + Mega-CD images.
                // NOTE: .bin is intentionally NOT mapped here — it is too
                // ambiguous (also used by arcade ROMs and DOS disk images).
                // .bin files inside .zip archives are inspected by
                // detectPlatformFromUri, which checks for arcade-style
                // extensions first. Bare .bin files default to MD via
                // the platform-tab selection in the import flow.
                "md", "smd", "gen", "sms", "gg", "sg", "68k" -> MD
                "chd" -> MD   // SEGA CD / Mega-CD CHD images
                "jar", "jad" -> JAVA
                // .zip is intentionally NOT mapped — see detectPlatformFromUri
                // for the disambiguation logic.
                // .bin is intentionally NOT mapped — too ambiguous.
                else -> null
            }
        }

        /**
         * DOSBox launcher file extensions (used by the folder-import flow).
         * When a user picks a folder, we look for files with these extensions
         * and pick the best launch candidate (play.bat > run.bat > START.BAT >
         * autoexec.bat > setup.exe > any .exe > any .com).
         */
        val DOS_LAUNCHER_EXTENSIONS = setOf("bat", "exe", "com")

        /**
         * Files preferred as folder-import launch targets, in priority order.
         * The first matching file (case-insensitive) becomes the game's entry.
         */
        val DOS_LAUNCHER_PRIORITY = listOf(
            "play.bat", "run.bat", "start.bat", "autoexec.bat",
            "go.bat", "launch.bat", "main.bat",
            "play.exe", "run.exe", "start.exe", "setup.exe",
            "game.exe", "main.exe", "launch.exe"
        )
    }
}

data class GameEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val accent: Color = Color(0xFFE74C3C),
    val romPath: String? = null,
    val coverPath: String? = null,
    val lastPlayedAt: Long = 0L,
    val playTimeMs: Long = 0L,
    val isFavorite: Boolean = false,
    val platform: GamePlatform = GamePlatform.NES,
    val customIconPath: String? = null
)
