// SPDX-License-Identifier: MIT
// JNI bridge for the PCEE2 (PCSX2, PlayStation 2) core.
//
// Thin wrapper around the libretro frontend in pcsx2_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the PS1 (psx_bridge) and other bridges.
//
// PS2 uses the 16-button DualShock layout plus DUAL ANALOG STICKS:
//   libretro bits: bit0=Cross(×), bit1=Square(□), bit2=Select, bit3=Start,
//   bit4..7=D-pad, bit8=Circle(○), bit9=Triangle(△), bit10=L1, bit11=R1,
//   bit12=L2, bit13=R2, bit14=L3, bit15=R3.
//   Analog axes are int16 (-32768..32767): LX/LY/RX/RY per port.
// All controller ports default to RETRO_DEVICE_ANALOG (DualShock).

#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace ps2core {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    // 16-button DualShock state (see layout above), ports 0..3.
    void setPad1(int bits);
    void setPad2(int bits);
    void setPad3(int bits);
    void setPad4(int bits);

    // Dual analog sticks, int16 libretro range, ports 0..3.
    void setAnalog1(int lx, int ly, int rx, int ry);
    void setAnalog2(int lx, int ly, int rx, int ry);

    void setRegion(int region);
    void setSampleRate(int hz);
    void setFastForward(int speed);

    // Switch a controller port device (JOYPAD=1 / ANALOG=5). Queued; applied
    // on the emu thread before the next frame.
    void setPortDevice(int port, int device);
    // Core-reported refresh rate (59.94 NTSC / 50 PAL).
    double videoRefreshRate();

    void saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);

    // Pull the latest frame into `out` (w*h uint32, 0xAARRGGBB).
    bool getFrameBuffer(uint32_t* out, int w, int h);

    // Pull up to maxFrames stereo frames (2 int16 each) into `out`.
    int  readAudio(int16_t* out, int maxFrames);

    int  audioSampleRate();
    int  audioTargetSampleRate();

    void setPaths(const std::string& systemDir, const std::string& saveDir);
    void setSaveName(const std::string& name);

    // --- Core options (pcsx2_renderer / pcsx2_upscale_multiplier etc.) ---
    void setCoreOption(const std::string& key, const std::string& value);

    // --- Video geometry ---
    int  videoWidth();
    int  videoHeight();

    // --- Video filter (frontend post-processing) ---
    void setVideoFilter(int filter);
    void setHighQualityScaling(bool enabled);

    std::string lastError() const { return lastError_; }

private:
    Engine() = default;
    std::string lastError_;
};

} // namespace ps2core
