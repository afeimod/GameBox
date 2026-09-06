/* FCEUmm - NES/Famicom Emulator
 *
 * Copyright notice for this file:
 *  Copyright (C) 2022
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
#include "mapinc.h"
#include "mmc3.h"

static uint8_t *CHRRAM;
static uint32_t CHRRAMSIZE;

static void Mapper195_PWrap(uint32_t A, uint8_t V) {
	// Waixing FS303 (mapper 195) pirate dumps can carry up to 2MB PRG.
	// The generic MMC3 GENPWRAP masks V with 0x7F (1MB max), which breaks
	// dumps whose banks are >= 128 (e.g. the 1.25MB Chinese translation of
	// Captain Tsubasa Vol.2 / 天使之翼2 中文版) — the reset vector then ends
	// up in the wrong half of the image and the screen stays gray.
	// setprg8() already masks V with PRGmask8, which Mapper195_Init shrinks
	// to the actual bank count, so passing the full 8-bit value is correct
	// for both power-of-two and odd-sized dumps.
	setprg8(A, V);
}

static void Mapper195_CHRWrap(uint32_t A, uint8_t V) {
	// Hacked Captain Tsubasa Vol.2 (Ch) and Crystalis (Ch) boards wire the
	// first 4KB of CHR (banks 0-3) to CHR-RAM and the rest to CHR-ROM.
	// This is the behavior used by the reference NostalgiaLite/FCEUX
	// implementation (M195CW, V <= 3 with 4KB CHRRAM). The 2022 fceumm
	// GAL-based auto-detection only matches the unhacked Japanese ROM and
	// greys out the Chinese translation hacks. Note: routing only banks 0-1
	// (2KB, the mapper-196 layout) is NOT enough — bank 1 (CHR addr $0400-
	// $0BFF region) is used as RAM by the hack, so the threshold must be 3.
	if (V <= 3)
		setchr1r(0x10, A, V);
	else
		setchr1r(0, A, V);
}

static void Mapper195_Power(void) {
	GenMMC3Power();
	setprg4r(0x10, 0x5000, 2);
	SetWriteHandler(0x5000, 0x5FFF, CartBW);
	SetReadHandler(0x5000, 0x5FFF, CartBR);
}

static void Mapper195_Close(void) {
	if (CHRRAM)
		FCEU_gfree(CHRRAM);
	CHRRAM = NULL;
}

void Mapper195_Init(CartInfo *info) {
	GenMMC3_Init(info, 512, 256, 16, info->battery);
	pwrap = Mapper195_PWrap;
	cwrap = Mapper195_CHRWrap;
	info->Power = Mapper195_Power;
	info->Reset = MMC3RegReset;
	info->Close = Mapper195_Close;

	// Non-power-of-two PRG dumps (e.g. the 1.25MB 天使之翼2 Chinese hack)
	// are loaded into a power-of-two padded buffer, so PRGmask8[0] comes
	// out as 0xFF and the MMC3's initial $E000-$FFFF map ("~0") points at
	// 0xFF padding instead of the last real bank -> reset vector $FFFF ->
	// grey screen before any game code runs. Shrink the mask to the actual
	// 8KB-bank count so "~0" selects the last real bank.
	if (info->PRGRomSize)
		PRGmask8[0] = (info->PRGRomSize >> 13) - 1;

	CHRRAMSIZE = 4096;
	CHRRAM =(uint8_t*)FCEU_gmalloc(CHRRAMSIZE);
	SetupCartCHRMapping(0x10, CHRRAM, CHRRAMSIZE, 1);
	AddExState(CHRRAM, CHRRAMSIZE, 0, "CHRR");
}
