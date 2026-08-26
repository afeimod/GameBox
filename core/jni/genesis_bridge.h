// SPDX-License-Identifier: MIT
// JNI bridge for Genesis-Plus-GX core (SEGA MD / SMS / GG / SG / Mega-CD).

#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace genesicore {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    // Standard libretro gamepad (port 0, RETRO_DEVICE_JOYPAD).
    //   bit0=A(SEGA A), bit1=B(SEGA B), bit2=Select(Mode), bit3=Start,
    //   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
    //   bit8=X(SEGA C), bit9=Y(SEGA X), bit10=L(SEGA Y), bit11=R(SEGA Z)
    void setPad1(int bits);
    void setPad2(int bits);          // second controller (port 1), same bit layout

    void setRegion(int region);
    void setSampleRate(int hz);
    void setFastForward(int speed);

    bool saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);

    // Flush the core's SAVE_RAM (battery save / .srm) to disk atomically.
    // Safe to call mid-emulation — the loader takes the state mutex so
    // retro_run() can't race. Returns false if no game is loaded, the
    // game has no SAVE_RAM, or the write fails.
    bool flushSaveRam();
    // Reload the per-game .srm file into the core's SAVE_RAM buffer,
    // discarding any unsaved in-core progress. Safe to call mid-emulation.
    // Returns false if no game is loaded or no .srm exists.
    bool reloadSaveRam();

    bool getFrameBuffer(uint32_t* out, int w, int h);
    int  readAudio(int16_t* out, int maxFrames);

    int  audioSampleRate();
    int  audioTargetSampleRate();

    void setPaths(const std::string& systemDir, const std::string& saveDir);
    void setSaveName(const std::string& name);

    void setSurface(jobject surface);
    void setCoreOption(const std::string& key, const std::string& value);

    int  videoWidth();
    int  videoHeight();

    void setVideoFilter(int filter);
    void setHighQualityScaling(bool enabled);

    std::string lastError() const { return lastError_; }

private:
    Engine() = default;
    std::string lastError_;
};

} // namespace genesicore
