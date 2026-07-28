// SPDX-License-Identifier: MIT
// JNI bridge for FCEUmm core
#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>
#include <vector>
#include <atomic>
#include <thread>

namespace nescore {

// Frame callback type: (bgr_pixels, w, h, stride)
using VideoCallback = void (*)(const uint8_t* /*bgr*/, int /*w*/, int /*h*/, int /*stride*/, int64_t /*pts*/);

// Audio callback: interleaved 16-bit PCM
using AudioCallback = void (*)(const int16_t* /*pcm*/, int /*frames*/, int /*rate*/, int64_t /*pts*/);

// Input bits (NES standard pad)
struct NesPad {
    uint8_t a : 1;
    uint8_t b : 1;
    uint8_t select : 1;
    uint8_t start : 1;
    uint8_t up : 1;
    uint8_t down : 1;
    uint8_t left : 1;
    uint8_t right : 1;
};

class Engine {
public:
    static Engine& instance();
    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();
    void setPad1(const NesPad& pad);
    void setRegion(int region); // 0 = NTSC, 1 = PAL
    void setSampleRate(int hz);
    void setFastForward(bool on);
    void saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);
    int  width() const { return 256; }
    int  height() const { return 240; }

    void setVideoCallback(VideoCallback cb) { videoCb_ = cb; }
    void setAudioCallback(AudioCallback cb) { audioCb_ = cb; }

    std::string lastError() const { return lastError_; }

private:
    Engine() = default;
    ~Engine() = default;
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    void audioThread();
    void renderFrameToBuffer();
    void fillAudioBuffer(int16_t* out, int frames);

    std::atomic<bool> running_{false};
    std::thread audioThread_;
    std::string lastError_;
    VideoCallback videoCb_{nullptr};
    AudioCallback audioCb_{nullptr};
    NesPad pad1_{};
    int region_ = 0;
    int sampleRate_ = 44100;
    bool fastForward_ = false;
    uint8_t* frameBuffer_ = nullptr;
};

} // namespace nescore
