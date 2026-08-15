FBNeo BIOS Files
================

Place the following BIOS zip files in THIS directory (`app/src/main/assets/fbneo/`)
before building the APK. They will be auto-extracted to `<filesDir>/fbneo/` on
first launch by `NesApp.ensureFbNeoBios()`.

⚠️  COPYRIGHT NOTICE
--------------------
These BIOS files contain copyrighted code. You may NOT redistribute them
publicly (e.g. on GitHub, in a public APK release, or via any file-sharing
service). You may only bundle them in private APK builds for your own
personal use on your own devices.

For public/open-source distribution, leave this directory empty — users
will be prompted to import the BIOS files manually via the Settings →
Arcade → BIOS Management UI.

Required BIOS files
-------------------
File            | Hardware           | Required for
----------------|--------------------|---------------------------------------------
neogeo.zip      | NeoGeo MVS/AES     | ALL NeoGeo games (KOF, Metal Slug, SamSho)
pgm.zip         | PolyGame Master    | ALL PGM games (Knights of Valour / 三国战纪,
                |                    |   Demon Front / 魔窟, Espgaluda, DoDonPachi)
neocdz.zip      | NeoGeo CDZ         | NeoGeo CD-based games (rare)
cvs2.zip        | CPS2 decrypt key   | Capcom VS SNK 2 (cvs2)
cps1.zip        | CPS1 BIOS          | Some CPS1 games (varies by set)
cps2.zip        | CPS2 BIOS          | Some CPS2 games (varies by set)
stvbios.zip     | ST-V BIOS          | ST-V arcade games (varies)
tickgal.zip     | decryption key     | Some Galaxian-based bootlegs

Where to get them
-----------------
These BIOS files are part of the FBNeo ROM set. The official FBNeo project
does not host them due to copyright, but they are commonly available in
"FBNeo full ROM set" distributions or "MAME BIOS pack" distributions.

The zip files must contain the raw .bin BIOS files with the correct
filenames expected by FBNeo (e.g. neogeo.zip should contain
"000-lo.lo", "sm1.sm1", "sfix.sfix", "uni-bios.rom", etc.).
