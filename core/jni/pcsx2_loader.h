// SPDX-License-Identifier: MIT
// libretro frontend surface for the PCEE2 (PCSX2, PlayStation 2) core.
//
// pcsx2_loader wraps the standard libretro API behind a small, stable C++
// interface that the JNI bridge in pcsx2_bridge.cpp calls. The prebuilt
// libpcee2_libretro_android.so (PCEE2 — the official libretro buildbot's
// Android arm64-v8a core built from upstream PCSX2 v2.7.523) is dlopen()'d
// at runtime and the retro_* symbols resolved via dlsym() — same pattern
// as psx_loader.cpp.
//
// Differences from the PS1 (psx_loader) frontend:
//   * 16-button PS2 gamepad: DualShock layout + L2/R2/L3/R3 (bit12..15).
//   * Dual analog sticks: RETRO_DEVICE_ANALOG axes fed from the on-screen
//     twin-stick UI (setAnalogInput) — left stick = LX/LY, right = RX/RY.
//   * Dynamic ARGB frame buffer up to 2560x2048 (covers every offered
//     pcsx2_upscale_multiplier: 1x–4x incl. PAL worst case; oversize frames
//     are box-downsampled as a safety net).
//   * Core options: pcsx2_renderer (vulkan|software),
//     pcsx2_upscale_multiplier ("1".."4" — the "分辨率倍数" setting),
//     pcsx2_texture_filtering (bilinear_ps2|nearest). Keys MUST match the
//     core's option table exactly (verified against PCEE2 source).
//   * BIOS: PCEE2 looks for <systemDir>/pcsx2/bios/scphXXXXX.bin (e.g.
//     scph10000.bin); a legacy <systemDir>/bios/ folder is auto-migrated on
//     first load. Without a BIOS most games cannot boot — surface a clear
//     error telling the user where to put it.

#pragma once
#include <string>
#include <cstdint>

namespace ps2core::rom {

// Load a PS2 game (path to .iso / .chd / .cso / .zso / .cue+bin / .gz /
// .mdf / .nrg / .elf image).
// The core opens the image itself from `path` — never pre-load into memory.
// Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (auto-detected from av_info fps).
std::string loadFromFile(const std::string& path, int& regionOut);

void unload();
void resetEmulation(bool hard);
void stepFrame();

// Queue a libretro controller-port device switch. Applied on the emulation
// thread at the next stepFrame() — never directly from the caller thread.
void setPortDevice(int port, int device);
double videoRefreshRate();

bool copyFramebufferARGB(uint32_t* out, int w, int h);
int  readAudio(int16_t* out, int maxFrames);

int audioSampleRate();
int audioTargetSampleRate();

// Push digital button state. 16-button PS2 layout (libretro standard):
//   bit0=Cross(×), bit1=Square(□), bit2=Select, bit3=Start,
//   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
//   bit8=Circle(○), bit9=Triangle(△), bit10=L1, bit11=R1,
//   bit12=L2, bit13=R2, bit14=L3, bit15=R3.
// Supports up to 4 controllers via the standard 4 ports.
void setControllerInput(int port, uint32_t bits);

// Push analog stick axes for a port. All values are int16:
// -32768..32767 (libretro RETRO_DEVICE_ID_ANALOG range).
//   lx/ly = left stick X/Y, rx/ry = right stick X/Y.
void setAnalogInput(int port, int16_t lx, int16_t ly, int16_t rx, int16_t ry);

// Set system (BIOS: <systemDir>/pcsx2/bios/scphXXXXX.bin) and save directories.
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit .srm basename for the next ROM load.
void setSaveName(const std::string& name);

// Set the absolute path to libpcee2_libretro_android.so for dlopen.
// Pass an empty string to revert to bare-name dlopen.
void setCoreLibPath(const std::string& path);

void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);

void saveStateToPath(int slot, const std::string& path);
bool loadStateFromPath(int slot, const std::string& path);

// --- Core options (pcsx2_renderer / pcsx2_upscale_multiplier etc.) ---
void setCoreOption(const std::string& key, const std::string& value);

// --- Video geometry ---
int  videoWidth();
int  videoHeight();
void videoAspectRatio(int& num, int& den);

// --- Video filter (frontend post-processing, shared with PS1) ---
void setVideoFilter(int filter);
void setHighQualityScaling(bool enabled);

// --- Hardware-accelerated rendering ---
void setSurface(void* nativeWindow);

// Check whether libpcee2_libretro_android.so was successfully dlopen()'d.
bool isCoreLoaded();

} // namespace ps2core::rom
