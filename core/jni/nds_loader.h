// SPDX-License-Identifier: MIT
// libretro frontend surface for the melonDS (Nintendo DS) core.
//
// nds_loader wraps the standard libretro API (retro_init /
// retro_load_game / retro_run ...) behind a small, stable C++ interface
// that the JNI bridge in nds_bridge.cpp calls. The prebuilt
// libmelonds_libretro_android.so is dlopen()'d at runtime and the
// retro_* symbols resolved via dlsym() — same pattern as fbneo_loader.cpp.
//
// Features:
//   * Runtime loading of prebuilt libmelonds_libretro_android.so
//   * 12-button DS gamepad (RETRO_DEVICE_JOYPAD) on ports 0-3 with the
//     standard DS layout: D-pad + A/B/X/Y + L/R + Start/Select. DS has
//     the same 12-button layout as SNES. Touchscreen input is NOT exposed
//     via this 12-bit field — it requires RETRO_DEVICE_POINTER which we'll
//     add in a future revision. For now the touch mode defaults to "mouse"
//     in core options so a hardware mouse would still work via pointer.
//   * Fixed 256x192 per-screen frame buffer; default layout stacks both
//     screens vertically (256x384). melonDS's libretro port renders the
//     two-screen layout into a single 256x384 (or 512x192 horizontal)
//     framebuffer, so the frontend sees one combined buffer.
//   * Stereo audio ring buffer + resampler (shared implementation)
//   * Core options variable system (melonds_* — keys MUST match melonDS's
//     libretro_core_options.h exactly).
//   * Pixel format conversion (XRGB8888 / RGB565 / 0RGB1555 -> ARGB)
//   * Hardware-accelerated rendering via ANativeWindow (SurfaceView)
//   * BIOS file lookup in the system directory — melonDS requires
//     bios7.bin / bios9.bin / firmware.bin for NDS mode, plus additional
//     dsi_* files for DSi mode. The core reports BIOS-load errors itself;
//     the frontend does NOT pre-check for these files.
//   * Save RAM persistence via RETRO_MEMORY_SAVE_RAM (SRAM .srm alongside
//     the save state directory).
//
// All retro_* calls happen on a single emulation thread (see NdsEngine
// in Kotlin), so no extra internal locking is needed around the core
// itself. The frame and audio buffers are mutex-guarded because the UI /
// AudioTrack threads read them concurrently.

#pragma once
#include <string>
#include <cstdint>

namespace ndscore::rom {

// Load a DS game (path to .nds / .ids / .app / .srl / .dsi ROM file).
// DS ROMs are small (max 512 MB for the largest commercial carts), so they
// are pre-loaded into memory and passed to retro_load_game via data + size
// (standard libretro behavior for non-CD content).
// Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (DS is region-free; this is
// always 0 — DS has no region concept — but the field is kept for API
// symmetry with the other cores).
std::string loadFromFile(const std::string& path, int& regionOut);

void unload();
void resetEmulation(bool hard);
void stepFrame();

bool copyFramebufferARGB(uint32_t* out, int w, int h);
int  readAudio(int16_t* out, int maxFrames);

int audioSampleRate();
int audioTargetSampleRate();

// Push controller state. `bits` layout (12 buttons, same as SNES):
//   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
//   bit6=Left, bit7=Right, bit8=X, bit9=Y, bit10=L, bit11=R
// melonDS's libretro port maps these 1:1 to the DS hardware buttons:
//   A=A, B=B, X=X, Y=Y, L=L, R=R, Select=Select, Start=Start.
// Touchscreen / microphone / lid-close are NOT exposed via this 12-bit
// field (would require RETRO_DEVICE_POINTER + RETRO_DEVICE_MIC).
// Supports up to 4 controllers (DS Download Play can support up to 16,
// but only pads 1-4 are exposed here).
void setControllerInput(int port, uint16_t bits);

// Set system (BIOS files) and save (SRAM .srm) directories.
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit .srm basename for the next ROM load.
void setSaveName(const std::string& name);

// Set the absolute path to libmelonds_libretro_android.so for dlopen.
// Pass an empty string to revert to bare-name dlopen.
void setCoreLibPath(const std::string& path);

void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);

void saveStateToPath(int slot, const std::string& path);
bool loadStateFromPath(int slot, const std::string& path);

// --- Hardware-accelerated rendering ---
void setSurface(void* nativeWindow);

// --- Core options ---
void setCoreOption(const std::string& key, const std::string& value);

// --- Video geometry ---
int  videoWidth();
int  videoHeight();
void videoAspectRatio(int& num, int& den);

// --- Video filter (frontend post-processing) ---
void setVideoFilter(int filter);
void setHighQualityScaling(bool enabled);

// Check whether libmelonds_libretro_android.so was successfully dlopen()'d.
bool isCoreLoaded();

} // namespace ndscore::rom
