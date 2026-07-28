// SPDX-License-Identifier: MIT
// libretro frontend that drives the FCEUmm core.
//
// We link FCEUmm's libretro.c (which exports retro_init / retro_load_game /
// retro_run / ...) and drive it from here. This file owns:
//   * the libretro callbacks (environment / video / audio / input)
//   * a 256x240 ARGB frame buffer fed by retro_video_refresh_t
//   * a lock-free-ish stereo audio ring buffer fed by retro_audio_sample_batch_t
//   * controller state pushed in from JNI
//   * save-state serialization helpers
//
// All retro_* calls happen on a single emulation thread (see NesEngine in
// Kotlin), so no extra internal locking is needed around the core itself.
// The frame and audio buffers are mutex-guarded because the UI / AudioTrack
// threads read them concurrently.

#include "rom_loader.h"

#include <libretro.h>
#include <android/log.h>

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

#define TAG "nescore-rom"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace nescore::rom {

// NES internal resolution.
static constexpr int kNesW = 256;
static constexpr int kNesH = 240;

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
static bool s_loaded = false;
static int  s_sampleRate = 0;
static int  s_region = 0;
static std::string s_systemDir;
static std::string s_saveDir;

// Frame buffer (ARGB, 0xAARRGGBB). Written by video_cb, read by
// copyFramebufferARGB.
static std::mutex s_frameMtx;
static uint32_t s_frame[kNesW * kNesH];
static std::atomic<bool> s_newFrame{false};

// Controller bits (see setControllerInput layout).
static std::atomic<uint16_t> s_pad1{0};
static std::atomic<uint16_t> s_pad2{0};

// Audio ring buffer: interleaved stereo int16 samples.
static constexpr size_t kAudioCap = 1u << 15; // 32768 samples (~0.37s @44.1k stereo)
static int16_t s_audioRing[kAudioCap];
static size_t  s_audioWrite = 0;
static size_t  s_audioRead  = 0;
static size_t  s_audioCount = 0;
static std::mutex s_audioMtx;

// ---------------------------------------------------------------------------
// libretro callbacks
// ---------------------------------------------------------------------------
static void libretroLog(retro_log_level level, const char* fmt, ...) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case RETRO_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
        case RETRO_LOG_WARN:  prio = ANDROID_LOG_WARN;  break;
        case RETRO_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(prio, "fceumm", fmt, ap);
    va_end(ap);
}

static bool cb_environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (data) *static_cast<bool*>(data) = true;
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            // Accept the core's request (XRGB8888 with FRONTEND_SUPPORTS_RGB888).
            return true;

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (data) {
                auto* log = static_cast<retro_log_callback*>(data);
                log->log = libretroLog;
            }
            return true;

        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_systemDir.c_str();
            return !s_systemDir.empty();

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_saveDir.c_str();
            return !s_saveDir.empty();

        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_systemDir.c_str();
            return !s_systemDir.empty();

        // The core registers these; we accept and ignore.
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return true;

        case RETRO_ENVIRONMENT_GET_VARIABLE:
            return false; // no option values -> core uses built-in defaults

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (data) *static_cast<bool*>(data) = false;
            return false;

        // Use libretro-common's bundled local file_stream instead of a VFS
        // interface — it is already linked in and handles ROM/SRAM files fine.
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            return false;

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return false; // we answer per-button input_state queries

        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
            return false;

        default:
            return false;
    }
}

static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return; // duplicate frame / no data this frame

    std::lock_guard<std::mutex> lk(s_frameMtx);
    const uint32_t* src = static_cast<const uint32_t*>(data);
    const size_t srcStride = pitch / sizeof(uint32_t);

    const unsigned cw = (width < kNesW) ? width : (unsigned)kNesW;
    const unsigned ch = (height < kNesH) ? height : (unsigned)kNesH;

    // Clear first so cropped frames don't leave stale pixels at the edges.
    std::memset(s_frame, 0, sizeof(s_frame));
    for (unsigned y = 0; y < ch; ++y) {
        const uint32_t* srow = src + y * srcStride;
        uint32_t* drow = s_frame + y * kNesW;
        for (unsigned x = 0; x < cw; ++x) {
            // XRGB8888 (0xXXRRGGBB) -> ARGB (0xFFRRGGBB)
            drow[x] = 0xFF000000u | (srow[x] & 0x00FFFFFFu);
        }
    }
    s_newFrame.store(true, std::memory_order_release);
}

static void pushAudio(const int16_t* samples, size_t count) {
    std::lock_guard<std::mutex> lk(s_audioMtx);
    for (size_t i = 0; i < count; ++i) {
        if (s_audioCount >= kAudioCap) {
            // Buffer full: drop the oldest sample (emulation is ahead).
            s_audioRead = (s_audioRead + 1) % kAudioCap;
            s_audioCount--;
        }
        s_audioRing[s_audioWrite] = samples[i];
        s_audioWrite = (s_audioWrite + 1) % kAudioCap;
        s_audioCount++;
    }
}

static void cb_audio_sample(int16_t left, int16_t right) {
    int16_t pair[2] = {left, right};
    pushAudio(pair, 2);
}

static size_t cb_audio_batch(const int16_t* data, size_t frames) {
    pushAudio(data, frames * 2);
    return frames;
}

static void cb_input_poll() { /* state is read on demand */ }

static int16_t cb_input_state(unsigned port, unsigned device,
                              unsigned /*index*/, unsigned id) {
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    const uint16_t bits = (port == 0) ? s_pad1.load(std::memory_order_relaxed)
                                      : (port == 1) ? s_pad2.load(std::memory_order_relaxed)
                                                    : 0;
    switch (id) {
        case RETRO_DEVICE_ID_JOYPAD_A:      return (bits >> 0) & 1;
        case RETRO_DEVICE_ID_JOYPAD_B:      return (bits >> 1) & 1;
        case RETRO_DEVICE_ID_JOYPAD_SELECT: return (bits >> 2) & 1;
        case RETRO_DEVICE_ID_JOYPAD_START:  return (bits >> 3) & 1;
        case RETRO_DEVICE_ID_JOYPAD_UP:     return (bits >> 4) & 1;
        case RETRO_DEVICE_ID_JOYPAD_DOWN:   return (bits >> 5) & 1;
        case RETRO_DEVICE_ID_JOYPAD_LEFT:   return (bits >> 6) & 1;
        case RETRO_DEVICE_ID_JOYPAD_RIGHT:  return (bits >> 7) & 1;
        default: return 0;
    }
}

static void resetAudioRing() {
    std::lock_guard<std::mutex> lk(s_audioMtx);
    s_audioRead = s_audioWrite = 0;
    s_audioCount = 0;
}

// ---------------------------------------------------------------------------
// Interface implementation
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    if (s_loaded) unload();

    retro_init();
    retro_set_environment(cb_environment);
    retro_set_video_refresh(cb_video);
    retro_set_audio_sample(cb_audio_sample);
    retro_set_audio_sample_batch(cb_audio_batch);
    retro_set_input_poll(cb_input_poll);
    retro_set_input_state(cb_input_state);

    // FCEUmm advertises need_fullpath, but support both modes for robustness.
    struct retro_system_info sysinfo;
    retro_get_system_info(&sysinfo);

    struct retro_game_info game;
    std::memset(&game, 0, sizeof(game));
    std::vector<uint8_t> romData;

    if (sysinfo.need_fullpath) {
        game.path = path.c_str();
    } else {
        FILE* f = std::fopen(path.c_str(), "rb");
        if (!f) { retro_deinit(); return "Cannot open ROM file: " + path; }
        std::fseek(f, 0, SEEK_END);
        long sz = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        if (sz <= 0) { std::fclose(f); retro_deinit(); return "Empty ROM file"; }
        romData.resize((size_t)sz);
        size_t rd = std::fread(romData.data(), 1, (size_t)sz, f);
        std::fclose(f);
        if (rd != (size_t)sz) { retro_deinit(); return "Cannot read ROM file"; }
        game.path = path.c_str();
        game.data = romData.data();
        game.size = romData.size();
    }

    if (!retro_load_game(&game)) {
        retro_unload_game();
        retro_deinit();
        return "FCEUmm rejected the ROM (unsupported mapper or corrupt file)";
    }
    s_loaded = true;

    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);

    struct retro_system_av_info av;
    retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    s_region = (av.timing.fps < 55.0) ? 1 : 0;
    regionOut = s_region;

    resetAudioRing();
    s_newFrame.store(false);

    LOGI("ROM loaded: %s  rate=%d  fps=%.2f  region=%d  geom=%ux%u",
         path.c_str(), s_sampleRate, av.timing.fps, s_region,
         av.geometry.base_width, av.geometry.base_height);
    return "";
}

void unload() {
    if (s_loaded) {
        retro_unload_game();
        retro_deinit();
        s_loaded = false;
    }
    s_sampleRate = 0;
    resetAudioRing();
    s_newFrame.store(false);
}

void resetEmulation(bool /*hard*/) {
    if (!s_loaded) return;
    retro_reset();
}

void stepFrame() {
    if (!s_loaded) return;
    retro_run();
}

bool copyFramebufferARGB(uint32_t* out, int w, int h) {
    if (!out) return false;
    if (!s_loaded) {
        std::memset(out, 0, (size_t)w * h * sizeof(uint32_t));
        return false;
    }
    std::lock_guard<std::mutex> lk(s_frameMtx);
    const int cw = (w < kNesW) ? w : kNesW;
    const int ch = (h < kNesH) ? h : kNesH;
    for (int y = 0; y < ch; ++y) {
        std::memcpy(out + (size_t)y * w, s_frame + (size_t)y * kNesW,
                    (size_t)cw * sizeof(uint32_t));
    }
    return s_newFrame.exchange(false, std::memory_order_acq_rel);
}

int readAudio(int16_t* out, int maxFrames) {
    if (!out || maxFrames <= 0) return 0;
    const size_t want = (size_t)maxFrames * 2;
    std::lock_guard<std::mutex> lk(s_audioMtx);
    size_t n = (s_audioCount < want) ? s_audioCount : want;
    for (size_t i = 0; i < n; ++i) {
        out[i] = s_audioRing[s_audioRead];
        s_audioRead = (s_audioRead + 1) % kAudioCap;
    }
    s_audioCount -= n;
    for (size_t i = n; i < want; ++i) out[i] = 0; // underrun -> silence
    return (int)(n / 2);
}

int audioSampleRate() { return s_sampleRate; }

void setControllerInput(int port, uint8_t bits) {
    if (port == 0)      s_pad1.store(bits, std::memory_order_relaxed);
    else if (port == 1) s_pad2.store(bits, std::memory_order_relaxed);
}

void setPaths(const std::string& systemDir, const std::string& saveDir) {
    s_systemDir = systemDir;
    s_saveDir = saveDir;
}

void applyRegion(int /*region*/) { /* region is auto-detected at load */ }
void applySampleRate(int /*hz*/) { /* fixed by the core */ }
void applySpeed(float /*multiplier*/) { /* fast-forward is handled by the Kotlin loop */ }

void saveStateToPath(int /*slot*/, const std::string& path) {
    if (!s_loaded) return;
    size_t sz = retro_serialize_size();
    if (sz == 0) return;
    std::vector<uint8_t> buf(sz);
    if (!retro_serialize(buf.data(), sz)) { LOGE("retro_serialize failed"); return; }
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) { LOGE("Cannot open save state for write: %s", path.c_str()); return; }
    std::fwrite(buf.data(), 1, sz, f);
    std::fclose(f);
}

bool loadStateFromPath(int /*slot*/, const std::string& path) {
    if (!s_loaded) return false;
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (sz <= 0) { std::fclose(f); return false; }
    std::vector<uint8_t> buf((size_t)sz);
    size_t rd = std::fread(buf.data(), 1, (size_t)sz, f);
    std::fclose(f);
    if (rd != (size_t)sz) return false;
    if (!retro_unserialize(buf.data(), sz)) { LOGE("retro_unserialize failed"); return false; }
    return true;
}

} // namespace nescore::rom
