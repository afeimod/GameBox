// SPDX-License-Identifier: MIT
// libretro frontend surface for the FCEUmm core.
//
// rom_loader wraps the standard libretro API (retro_init / retro_load_game /
// retro_run ...) behind a small, stable C++ interface that the JNI bridge in
// bridge.cpp calls. Video frames are delivered as 0xAARRGGBB ints (ready for
// Android Bitmap.ARGB_8888), audio as interleaved stereo int16 pulled on
// demand by Kotlin's AudioTrack.
#pragma once
#include <string>
#include <cstdint>

namespace nescore::rom {

// Load a ROM. Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (auto-detected from the core).
std::string loadFromFile(const std::string& path, int& regionOut);

// Tear down the current game + core.
void unload();

// Soft/hard reset (libretro only exposes retro_reset()).
void resetEmulation(bool hard);

// Emulate exactly one frame. Fills the internal ARGB frame buffer and pushes
// audio into the ring buffer. Must be called on a single dedicated thread.
void stepFrame();

// Copy the latest produced frame into `out` (w*h uint32 entries, 0xAARRGGBB).
// Returns true if a fresh frame was produced since the previous call.
bool copyFramebufferARGB(uint32_t* out, int w, int h);

// Pull up to `maxFrames` stereo frames (2 int16 each) into `out`, zero-filling
// on underrun. Returns the number of *real* stereo frames written.
int readAudio(int16_t* out, int maxFrames);

// Sample rate reported by the core after load (e.g. 44100). 0 before load.
int audioSampleRate();

// Push controller state. `bits` layout:
//   bit0 A, bit1 B, bit2 Select, bit3 Start, bit4 Up, bit5 Down, bit6 Left, bit7 Right
void setControllerInput(int port, uint8_t bits);

// Filesystem directories the core uses for FDS BIOS (system) and SRAM (save).
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Region / sample-rate / speed hints. Region is auto-detected at load; the
// core fixes the audio sample rate, so these are best-effort and mostly no-ops
// kept for ABI compatibility with the bridge.
void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);

// Save / load a state to an absolute filesystem path.
void saveStateToPath(int slot, const std::string& path);
bool loadStateFromPath(int slot, const std::string& path);

} // namespace nescore::rom
