// SPDX-License-Identifier: MIT
// JNI bridge for FCEUmm core — simplified pull-model interface.
//
// Kotlin owns the emulation loop: it calls runFrame() to step the core,
// getFrameBuffer() to pull the latest ARGB frame, and readAudio() to
// pull stereo PCM for AudioTrack. No native-side threads or callbacks.
#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace nescore {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    void setPad1(int bits);          // bit layout: A B SEL STA U D L R
    void setRegion(int region);      // 0=NTSC 1=PAL
    void setSampleRate(int hz);
    void setFastForward(bool on);

    void saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);

    // Pull the latest frame into `out` (w*h uint32, 0xAARRGGBB).
    // Returns true if a new frame was produced since the last call.
    bool getFrameBuffer(uint32_t* out, int w, int h);

    // Pull up to maxFrames stereo frames (2 int16 each) into `out`.
    // Returns the number of real frames written (zero-filled on underrun).
    int  readAudio(int16_t* out, int maxFrames);

    // Core-reported sample rate (0 before load).
    int  audioSampleRate();

    // Set system (FDS BIOS) and save (SRAM) directories.
    void setPaths(const std::string& systemDir, const std::string& saveDir);

    std::string lastError() const { return lastError_; }

private:
    Engine() = default;
    std::string lastError_;
};

} // namespace nescore
