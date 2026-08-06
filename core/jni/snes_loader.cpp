// SPDX-License-Identifier: MIT
// libretro frontend that drives the SNES9x core.
//
// Features:
//   * Hardware-accelerated rendering via ANativeWindow (SurfaceView)
//   * Core options variable system (aspect, overclock, layers, audio, etc.)
//   * Dynamically-sized ARGB frame buffer (SNES resolution varies: 256x224
//     standard, up to 512x478 in high-resolution/interlaced modes)
//   * Stereo audio ring buffer (shared implementation from core_shared.h)
//   * Dual controller support (12-button SNES pad: A B X Y L R Select Start
//     Up Down Left Right)
//   * Save-state serialization
//
// All retro_* calls happen on a single emulation thread (see SnesEngine in
// Kotlin), so no extra internal locking is needed around the core itself.
// The frame and audio buffers are mutex-guarded because the UI / AudioTrack
// threads read them concurrently.
//
// Shared blit / XBR / HQX / audio ring buffer / surface management code is
// provided by core_shared.h — this file does NOT reimplement those utilities.

#include "snes_loader.h"
#include "shared/core_shared.h"

#include <libretro.h>
#include <android/log.h>
#include <android/native_window.h>

#include <atomic>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#define TAG "snescore-rom"
// core_shared.h already defines LOGI/LOGW/LOGE with tag "core-shared".
// Redefine them with our specific tag for better log filtering.
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace snescore::rom {

// SNES internal resolution.
//   Standard:  256x224 (NTSC) / 256x239 (PAL)
//   High-res:  512x224, 512x448 (interlaced)
//   Max:       512x478 (highest reported by SNES9x libretro core)
static constexpr int kSnesW    = 256;   // standard width
static constexpr int kSnesH    = 224;   // standard height
static constexpr int kSnesMaxW = 512;   // max width  (high-res mode)
static constexpr int kSnesMaxH = 478;   // max height (interlaced high-res)

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
static bool s_loaded = false;
static int  s_sampleRate = 0;
static int  s_region = 0;
static std::string s_systemDir;
static std::string s_saveDir;
static std::string s_coreMessage;  // last message from the core

// Frame buffer (ARGB, 0xAARRGGBB). Written by video_cb, read by
// copyFramebufferARGB. Also used as the source for ANativeWindow blitting.
// SNES resolution is variable, so we use a dynamically-sized vector rather
// than a fixed array. s_frameW / s_frameH track the current dimensions.
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static int s_frameW = 0;
static int s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

// Current video dimensions from the core (may change with high-res modes).
static unsigned s_videoW = kSnesW;
static unsigned s_videoH = kSnesH;

// Controller bits (12 buttons per pad — see setControllerInput layout).
// SNES supports dual controllers: port 0 (pad 1) and port 1 (pad 2).
static std::atomic<uint16_t> s_pad1{0};
static std::atomic<uint16_t> s_pad2{0};

// Video filter type:
//   0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x, 7=xbr+dot,
//   8=4xbr, 9=4xbr+dot, 10=hq4x+dot
static std::atomic<int> s_videoFilter{0};

// 2x upscale buffer for XBR/HQ2X (max 512x478 -> 1024x956)
static uint32_t s_xbrBuffer2x[kSnesMaxW * kSnesMaxH * 2 * 2];

// 4x upscale buffer for HQ4X/4xBR (max 512x478 -> 2048x1912)
static uint32_t s_xbrBuffer4x[kSnesMaxW * kSnesMaxH * 4 * 4];

// Intermediate buffer for 4xBR cascade pass 1 (max 512x478 -> 1024x956)
static uint32_t s_xbrMidBuffer[kSnesMaxW * kSnesMaxH * 2 * 2];

// Audio ring buffer: interleaved stereo int16 samples.
// Uses the shared implementation from core_shared.h.
static coreshared::AudioRingBuffer s_audio;

// ---------------------------------------------------------------------------
// ANativeWindow — hardware-accelerated direct surface rendering
// ---------------------------------------------------------------------------
static ANativeWindow* s_window = nullptr;
static std::mutex s_windowMtx;

// ---------------------------------------------------------------------------
// Core options — key/value map served to the core via GET_VARIABLE
// ---------------------------------------------------------------------------
static std::mutex s_optMtx;
static std::map<std::string, std::string> s_options;
static std::atomic<bool> s_optionsChanged{false};

// Initialize SNES9x core options with sensible defaults.
// Values MUST match SNES9x's libretro_core_options.h defaults exactly.
static void initDefaultOptions() {
    // --- Video / Aspect ---
    s_options["snes9x_aspect"]                = "4:3";

    // --- Overclock / Hacks ---
    s_options["snes9x_overclock_superfx"]     = "disabled";
    s_options["snes9x_blargg_filter"]         = "disabled";
    s_options["snes9x_audio_interpolation"]   = "disabled";
    s_options["snes9x_side_by_side"]          = "disabled";
    s_options["snes9x_reduce_sprite_flicker"] = "disabled";
    s_options["snes9x_reduce_slowdown"]       = "disabled";

    // --- Input ---
    s_options["snes9x_superscope"]            = "disabled";
    s_options["snes9x_up_down_allowed"]       = "disabled";
    s_options["snes9x_crosshair"]             = "0";

    // --- Layers ---
    s_options["snes9x_layer_1"]               = "enabled";
    s_options["snes9x_layer_2"]               = "enabled";
    s_options["snes9x_layer_3"]               = "enabled";
    s_options["snes9x_layer_4"]               = "enabled";
    s_options["snes9x_layer_5"]               = "enabled";

    // --- Graphics ---
    s_options["snes9x_gfx_clip"]              = "enabled";
    s_options["snes9x_gfx_hires"]             = "enabled";
    s_options["snes9x_gfx_transparency"]      = "enabled";

    // --- Audio ---
    s_options["snes9x_sound_output"]          = "enabled";
    s_options["snes9x_sound_channels"]        = "all";
}

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
    __android_log_vprint(prio, "snes9x", fmt, ap);
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
            LOGI("GET_SYSTEM_DIRECTORY -> %s", s_systemDir.c_str());
            return !s_systemDir.empty();

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_saveDir.c_str();
            return !s_saveDir.empty();

        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_systemDir.c_str();
            return !s_systemDir.empty();

        // The core registers these; we accept.
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
#ifdef RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
#endif
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return true;

        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            // Capture core messages for error reporting
            if (data) {
                auto* msg = static_cast<const retro_message*>(data);
                if (msg && msg->msg) {
                    s_coreMessage = msg->msg;
                    LOGE("Core message: %s", msg->msg);
                }
            }
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            if (!data) return false;
            auto* var = static_cast<retro_variable*>(data);
            if (!var->key) return false;
            std::lock_guard<std::mutex> lk(s_optMtx);
            auto it = s_options.find(var->key);
            if (it != s_options.end()) {
                var->value = it->second.c_str();
                return true;
            }
            return false;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (data) {
                *static_cast<bool*>(data) = s_optionsChanged.exchange(false,
                    std::memory_order_acq_rel);
            }
            return true;

        // SNES9x uses filestream_open() which falls back to the built-in VFS
        // implementation when no VFS interface is provided. This fallback
        // works correctly on Android for standard filesystem paths.
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

    s_videoW = width;
    s_videoH = height;

    const uint32_t* src = static_cast<const uint32_t*>(data);
    const size_t srcStride = pitch / sizeof(uint32_t);

    // Copy to internal frame buffer (for fallback Bitmap rendering + screenshots).
    // SNES resolution is variable, so we resize the vector on demand.
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        if ((int)width != s_frameW || (int)height != s_frameH) {
            s_frameW = (int)width;
            s_frameH = (int)height;
            s_frame.resize((size_t)width * height);
        }
        for (unsigned y = 0; y < height; ++y) {
            const uint32_t* srow = src + y * srcStride;
            uint32_t* drow = s_frame.data() + (size_t)y * width;
            for (unsigned x = 0; x < width; ++x) {
                // XRGB8888 (0xXXRRGGBB) -> ARGB (0xFFRRGGBB)
                drow[x] = 0xFF000000u | (srow[x] & 0x00FFFFFFu);
            }
        }
        s_newFrame.store(true, std::memory_order_release);
    }

    // Blit directly to ANativeWindow if a surface is attached (hardware accel).
    // Uses the shared filter+blit dispatcher from core_shared.h.
    const int filter = s_videoFilter.load(std::memory_order_relaxed);
    coreshared::applyFilterAndBlit(
        s_window, s_windowMtx,
        src, width, height, srcStride,
        filter,
        s_xbrBuffer2x, s_xbrBuffer4x, s_xbrMidBuffer,
        (unsigned)kSnesMaxW, (unsigned)kSnesMaxH);
}

static void cb_audio_sample(int16_t left, int16_t right) {
    int16_t pair[2] = {left, right};
    s_audio.push(pair, 2);
}

static size_t cb_audio_batch(const int16_t* data, size_t frames) {
    s_audio.push(data, frames * 2);
    return frames;
}

static void cb_input_poll() { /* state is read on demand */ }

static int16_t cb_input_state(unsigned port, unsigned device,
                              unsigned /*index*/, unsigned id) {
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    // SNES supports dual controllers: port 0 (pad 1) and port 1 (pad 2).
    const uint16_t bits = (port == 0) ? s_pad1.load(std::memory_order_relaxed)
                                      : (port == 1) ? s_pad2.load(std::memory_order_relaxed)
                                                    : 0;
    // Controller bit layout (12 buttons):
    //   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
    //   bit6=Left, bit7=Right, bit8=X, bit9=Y, bit10=L, bit11=R
    switch (id) {
        case RETRO_DEVICE_ID_JOYPAD_A:      return (bits >> 0)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_B:      return (bits >> 1)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_SELECT: return (bits >> 2)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_START:  return (bits >> 3)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_UP:     return (bits >> 4)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_DOWN:   return (bits >> 5)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_LEFT:   return (bits >> 6)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_RIGHT:  return (bits >> 7)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_X:      return (bits >> 8)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_Y:      return (bits >> 9)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_L:      return (bits >> 10) & 1;
        case RETRO_DEVICE_ID_JOYPAD_R:      return (bits >> 11) & 1;
        default: return 0;
    }
}

// ---------------------------------------------------------------------------
// Interface implementation
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    if (s_loaded) unload();

    // Initialize core options before the core starts.
    // Always call initDefaultOptions() to ensure ALL keys exist, but don't
    // overwrite values already set by the user via setCoreOption().
    {
        std::lock_guard<std::mutex> lk(s_optMtx);
        // Save current user-set values
        auto saved = s_options;
        initDefaultOptions();
        // Restore user-set values on top of defaults
        for (auto& [k, v] : saved) {
            s_options[k] = v;
        }
    }
    s_optionsChanged.store(true, std::memory_order_release);

    // CRITICAL: retro_set_environment MUST be called before retro_init().
    // SNES9x's retro_init() calls the environment callback (e.g. to get the
    // log interface). If environ_cb is still NULL, it dereferences pc 0x0.
    retro_set_environment(cb_environment);
    retro_init();
    retro_set_video_refresh(cb_video);
    retro_set_audio_sample(cb_audio_sample);
    retro_set_audio_sample_batch(cb_audio_batch);
    retro_set_input_poll(cb_input_poll);
    retro_set_input_state(cb_input_state);

    // Provide BOTH path and data so the core can use whichever mode it prefers.
    struct retro_game_info game;
    std::memset(&game, 0, sizeof(game));
    std::vector<uint8_t> romData;

    {
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

    LOGI("About to call retro_load_game for: %s (systemDir=%s)",
         path.c_str(), s_systemDir.c_str());

    if (!retro_load_game(&game)) {
        retro_unload_game();
        retro_deinit();
        LOGE("retro_load_game FAILED for: %s", path.c_str());
        LOGE("Core message: %s", s_coreMessage.c_str());
        // Return the core's own error message if available
        if (!s_coreMessage.empty()) {
            std::string err = s_coreMessage;
            s_coreMessage.clear();
            return err;
        }
        return "SNES9x rejected the ROM (unsupported or corrupt file)";
    }
    s_loaded = true;
    LOGI("retro_load_game SUCCEEDED for: %s", path.c_str());

    // Set up dual controller ports
    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);

    struct retro_system_av_info av;
    retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    s_region = (av.timing.fps < 55.0) ? 1 : 0;
    regionOut = s_region;
    s_videoW = av.geometry.base_width;
    s_videoH = av.geometry.base_height;

    // Initialize frame buffer to the core's base geometry
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frameW = (int)av.geometry.base_width;
        s_frameH = (int)av.geometry.base_height;
        if (s_frameW <= 0) s_frameW = kSnesW;
        if (s_frameH <= 0) s_frameH = kSnesH;
        s_frame.assign((size_t)s_frameW * s_frameH, 0xFF000000u);
    }

    s_audio.reset();
    s_newFrame.store(false);

    LOGI("ROM loaded: %s  rate=%d  fps=%.2f  region=%d  geom=%ux%u  max=%ux%u",
         path.c_str(), s_sampleRate, av.timing.fps, s_region,
         av.geometry.base_width, av.geometry.base_height,
         av.geometry.max_width, av.geometry.max_height);
    return "";
}

void unload() {
    if (s_loaded) {
        retro_unload_game();
        retro_deinit();
        s_loaded = false;
    }
    s_sampleRate = 0;
    s_audio.reset();
    s_newFrame.store(false);
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frame.clear();
        s_frameW = 0;
        s_frameH = 0;
    }
    s_videoW = kSnesW;
    s_videoH = kSnesH;
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
    if (!s_loaded || s_frame.empty()) {
        std::memset(out, 0, (size_t)w * h * sizeof(uint32_t));
        return false;
    }
    std::lock_guard<std::mutex> lk(s_frameMtx);
    // w/h are the destination dimensions. Copy the intersection of (w,h)
    // and the actual frame size (s_frameW x s_frameH) from s_frame.
    const int cw = (w < s_frameW) ? w : s_frameW;
    const int ch = (h < s_frameH) ? h : s_frameH;
    for (int y = 0; y < ch; ++y) {
        std::memcpy(out + (size_t)y * w,
                    s_frame.data() + (size_t)y * s_frameW,
                    (size_t)cw * sizeof(uint32_t));
    }
    return s_newFrame.exchange(false, std::memory_order_acq_rel);
}

int readAudio(int16_t* out, int maxFrames) {
    return s_audio.read(out, maxFrames);
}

int audioSampleRate() { return s_sampleRate; }

void setControllerInput(int port, uint16_t bits) {
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

// --- Hardware-accelerated rendering ---------------------------------------

void setSurface(void* nativeWindow) {
    // Use the shared surface management from core_shared.h.
    coreshared::setSurface(s_window, s_windowMtx, nativeWindow);
    if (nativeWindow) {
        LOGI("Surface attached (buffer geometry = window default)");
    } else {
        LOGI("Surface detached");
    }
}

// --- Core options ----------------------------------------------------------

void setCoreOption(const std::string& key, const std::string& value) {
    {
        std::lock_guard<std::mutex> lk(s_optMtx);
        s_options[key] = value;
    }
    s_optionsChanged.store(true, std::memory_order_release);
    LOGI("Core option set: %s = %s", key.c_str(), value.c_str());
}

// --- Video geometry --------------------------------------------------------

int videoWidth()  { return (int)s_videoW; }
int videoHeight() { return (int)s_videoH; }

void videoAspectRatio(int& num, int& den) {
    // SNES9x default aspect is 4:3 (configured via "snes9x_aspect" core option).
    // The Kotlin layer uses this to size the SurfaceView.
    num = 4;
    den = 3;
}

// --- Video filter ----------------------------------------------------------

void setVideoFilter(int filter) {
    s_videoFilter.store(filter, std::memory_order_relaxed);
    LOGI("Video filter set: %d (0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x, 7=xbr+dot)", filter);
    // No buffer geometry changes needed. The buffer is always at 0x0 (window
    // default). For XBR (filter 4/7), the 2xBR upscale happens in cb_video
    // via coreshared::applyFilterAndBlit before blitting. For HQ2X (5) and
    // HQ4X (6), the HQX scaler runs similarly. Scanline/CRT/dot effects are
    // GPU-accelerated Compose overlays drawn on top of the SurfaceView.
}

} // namespace snescore::rom
