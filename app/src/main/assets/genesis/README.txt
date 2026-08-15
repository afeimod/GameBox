Genesis-Plus-GX BIOS Files
==========================

Place the following BIOS zip files in THIS directory
(`app/src/main/assets/genesis/`) before building the APK. They will be
auto-extracted to `<filesDir>/genesis/` on first launch by
`NesApp.ensureGenesisBios()`.

⚠️  COPYRIGHT NOTICE
--------------------
These BIOS files contain copyrighted code (SEGA's Mega-CD / SEGA-CD
firmware). You may NOT redistribute them publicly (e.g. on GitHub, in a
public APK release, or via any file-sharing service). You may only bundle
them in private APK builds for your own personal use on your own devices.

For public/open-source distribution, leave this directory empty — users
will be prompted to import the BIOS files manually via the Settings →
MD/SEGA → BIOS Management UI.

Required BIOS files
-------------------
File           | Region   | Required for
----------------|----------|---------------------------------------------
bios_CD_E.zip  | Europe   | European Mega-CD games (.cue/.chd/.iso)
bios_CD_J.zip  | Japan    | Japanese Mega-CD games (.cue/.chd/.iso)
bios_CD_U.zip  | USA      | US SEGA-CD games (.cue/.chd/.iso)

Each zip file must contain a single .bin file:
   bios_CD_E.zip  →  bios_CD_E.bin
   bios_CD_J.zip  →  bios_CD_J.bin
   bios_CD_U.zip  →  bios_CD_U.bin

Cartridge games do NOT require BIOS
-----------------------------------
MD (Mega Drive / Genesis), SMS (Master System), GG (Game Gear), and
SG-1000 cartridge games boot directly from ROM — no BIOS needed.
Only Mega-CD / SEGA-CD disc images require these BIOS files.

Where to get them
-----------------
These BIOS files are part of the standard libretro / RetroArch BIOS pack.
The official Genesis-Plus-GX project does not host them due to copyright.
Common filenames you may find them under:
   - "Mega-CD BIOS" / "SEGA-CD BIOS"
   - "bios_CD_E.bin" (MD5: e66fa1dc5820d254611fdcdba0662372)
   - "bios_CD_J.bin" (MD5: 278a9397d192149e84e820ac621a8edd)
   - "bios_CD_U.bin" (MD5: 2efd74e3232ff260e371b99f84024f7f)
