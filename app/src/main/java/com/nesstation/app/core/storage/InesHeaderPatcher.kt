package com.nesstation.app.core.storage

import java.io.File

/**
 * Patches iNES headers for pirate multicart ROMs (500-in-1, 1000000-in-1,
 * COOLBOY, etc.) whose PRG/CHR size byte is wrong — typically reporting 1MB
 * PRG when the actual file is 16MB+.
 *
 * WHY: FCEUmm trusts the iNES header's PRG/CHR size fields to allocate memory
 * and load ROM data. If the header says 1MB PRG but the file is 16MB, FCEUmm
 * loads only the first 1MB, leaving the upper banks inaccessible. The
 * multicart menu then can't switch banks and the screen stays gray.
 *
 * This patcher fixes the header in the actual temp file (not just in memory)
 * so FCEUmm sees the correct size whether it loads from `game.data` or
 * re-reads from `game.path`. The native rom_loader.cpp also patches in
 * memory, but if FCEUmm uses need_fullpath=true mode and reads the file
 * directly, the in-memory patch is wasted. Writing to the file ensures the
 * patch always takes effect.
 *
 * Header layout (16 bytes, iNES / NES 2.0):
 *   [0..3]   "NES\x1a" magic
 *   [4]      PRG ROM size, low 8 bits (in 16KB units; 0 = 256 units legacy)
 *   [5]      CHR ROM size, low 8 bits (in 8KB units)
 *   [6]      flags 6 (mapper low 4 bits + mirroring + trainer + 4-screen)
 *   [7]      flags 7 (mapper high 4 bits + NES2 marker bits 2-3)
 *   [8]      NES 2.0: submapper + mapper bits 8-11
 *   [9]      NES 2.0: PRG size bits 8-11 (low) + CHR size bits 8-11 (high)
 *   [10..15] NES 2.0: PRG RAM, CHR RAM, region, VS, misc, exp device
 *
 * NES 2.0 identifier: byte 7 bits 2-3 == 0b10 → (byte 7 & 0x0C) == 0x08
 */
object InesHeaderPatcher {

    /**
     * Patch the iNES header of [file] in place if the file is significantly
     * larger than the header claims. Returns a human-readable description
     * of what was patched (or "no patch needed" / error message).
     *
     * Safe to call on non-iNES files — checks magic bytes first and returns
     * early if not an iNES header.
     */
    fun patchIfNeeded(file: File): String {
        if (!file.exists()) return "file not found"
        val size = file.length()
        if (size < 16) return "file too small"

        // Read the 16-byte header
        val header = ByteArray(16)
        try {
            file.inputStream().use { input ->
                val read = input.read(header)
                if (read != 16) return "header read failed"
            }
        } catch (e: Exception) {
            return "header read error: ${e.message}"
        }

        // Verify iNES magic ("NES\x1a")
        if (header[0] != 0x4E.toByte() ||  // 'N'
            header[1] != 0x45.toByte() ||  // 'E'
            header[2] != 0x53.toByte() ||  // 'S'
            header[3] != 0x1A.toByte()) {  // \x1a
            return "not iNES format (no patch needed)"
        }

        // Decode legacy PRG/CHR sizes
        val prgSizeByte = header[4].toInt() and 0xFF
        val chrSizeByte = header[5].toInt() and 0xFF
        val hdrPrgBytes = (if (prgSizeByte == 0) 256 else prgSizeByte) * 16 * 1024
        val hdrChrBytes = chrSizeByte * 8 * 1024
        val hasTrainer = (header[6].toInt() and 0x04) != 0
        val headerClaimedSize = 16L + (if (hasTrainer) 512L else 0L) + hdrPrgBytes + hdrChrBytes

        // Only patch if file is significantly larger than header claims
        // (more than 16KB extra = one PRG bank)
        if (size <= headerClaimedSize + 16 * 1024) {
            return "no patch needed (size=${size}, claimed=${headerClaimedSize})"
        }

        // Compute new PRG size
        val extraBytes = size - headerClaimedSize
        val newPrgBytes = hdrPrgBytes + extraBytes
        // Round up to a multiple of 16KB
        var prgUnits = (newPrgBytes + 16 * 1024 - 1) / (16 * 1024)
        // Cap at 0xEFF (~60MB) — beyond this requires broken exponent mode
        if (prgUnits > 0xEFF) prgUnits = 0xEFF

        // Decode current mapper (for diagnostic logging)
        val mapperLow = header[6].toInt() and 0x0F
        val mapperHigh = (header[7].toInt() and 0xF0) shr 4
        val mapper = mapperLow or (mapperHigh shl 4)

        // Build patched header
        header[4] = (prgUnits and 0xFF).toByte()
        val highNibble = ((prgUnits shr 8) and 0x0F)

        if (highNibble > 0) {
            // Need NES 2.0 marker so byte 9's low nibble is read.
            // Set byte 7 bits 2-3 = 0b10 (preserve other bits including mapper).
            header[7] = ((header[7].toInt() and 0xF3) or 0x08).toByte()
            // Set byte 9 low nibble = highNibble (preserve high nibble = CHR size bits)
            header[9] = ((header[9].toInt() and 0xF0) or highNibble).toByte()
        }
        // If highNibble == 0, byte 4 alone holds the full PRG size (legacy mode).

        // Write the patched header back to the file (only the first 16 bytes)
        try {
            file.outputStream().use { out ->
                out.write(header, 0, 16)
            }
        } catch (e: Exception) {
            return "header write failed: ${e.message}"
        }

        return "PATCHED: file=${size}B, header claimed=${headerClaimedSize}B " +
               "(PRG=${hdrPrgBytes}, CHR=${hdrChrBytes}, trainer=$hasTrainer, mapper=$mapper). " +
               "New PRG=${prgUnits} units (${prgUnits * 16 * 1024}B), highNibble=$highNibble"
    }
}
