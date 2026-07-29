// SPDX-License-Identifier: MIT
// libretro frontend that drives the FCEUmm core.
//
// Features:
//   * Hardware-accelerated rendering via ANativeWindow (SurfaceView)
//   * Core options variable system (NTSC filter, aspect ratio, palette, etc.)
//   * 256x240 ARGB frame buffer for fallback Bitmap rendering
//   * Lock-free-ish stereo audio ring buffer
//   * Controller state + save-state serialization
//
// All retro_* calls happen on a single emulation thread (see NesEngine in
// Kotlin), so no extra internal locking is needed around the core itself.
// The frame and audio buffers are mutex-guarded because the UI / AudioTrack
// threads read them concurrently.

#include "rom_loader.h"

#include <libretro.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <atomic>
#include <algorithm>
#include <cctype>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
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
static std::string s_coreMessage;  // last message from the core (e.g. FDS BIOS missing)

// Frame buffer (ARGB, 0xAARRGGBB). Written by video_cb, read by
// copyFramebufferARGB. Also used as the source for ANativeWindow blitting.
static std::mutex s_frameMtx;
static uint32_t s_frame[kNesW * kNesH];
static std::atomic<bool> s_newFrame{false};

// Current video dimensions from the core (may change with NTSC filter etc.)
static unsigned s_videoW = kNesW;
static unsigned s_videoH = kNesH;

// Controller bits (see setControllerInput layout).
static std::atomic<uint16_t> s_pad1{0};
static std::atomic<uint16_t> s_pad2{0};

// FDS disk auto-insert: FCEUmm starts with the disk ejected (InDisk=255).
// The user must press the R button to insert it. We auto-press R on the
// first few frames after loading an FDS game so the game boots immediately.
static std::atomic<bool> s_fdsNeedInsert{false};
static std::atomic<bool> s_fdsRButton{false};
static std::atomic<bool> s_isFdsGame{false};
static int s_fdsInsertFrameCount = 0;

// Video filter type: 0=none, 1=scanline, 2=crt, 3=dot
static std::atomic<int> s_videoFilter{0};

// Audio ring buffer: interleaved stereo int16 samples.
static constexpr size_t kAudioCap = 1u << 15; // 32768 samples (~0.37s @44.1k stereo)
static int16_t s_audioRing[kAudioCap];
static size_t  s_audioWrite = 0;
static size_t  s_audioRead  = 0;
static size_t  s_audioCount = 0;
static std::mutex s_audioMtx;

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

// Initialize FCEUmm core options with sensible defaults.
// These mirror the option keys registered by FCEUmm's retro_set_variables().
static void initDefaultOptions() {
    s_options["fceumm_region"]            = "Auto";
    s_options["fceumm_ntsc_filter"]       = "disabled";
    s_options["fceumm_palette"]           = "default";
    s_options["fceumm_aspect"]            = "8:7 PAR";   // MUST match FCEUmm's option values exactly
    s_options["fceumm_sndquality"]        = "Low";
    s_options["fceumm_sndlowpass"]        = "enabled";
    s_options["fceumm_sndvolume"]         = "150";
    s_options["fceumm_turbo_enable"]      = "None";
    s_options["fceumm_turbo_delay"]       = "3";
    s_options["fceumm_show_adv_graphic"]  = "enabled";
    s_options["fceumm_overclocking"]      = "disabled";
    s_options["fceumm_nospritelimit"]     = "disabled";
    s_options["fceumm_show_crosshair"]    = "enabled";
    s_options["fceumm_aspect_orient"]     = "horizontal";
    s_options["fceumm_su_swap"]           = "disabled";
    s_options["fceumm_disable_swap"]      = "disabled";
    // Non-PSP overscan: 4 individual crop options (0/4/8/12/16)
    s_options["fceumm_overscan_h_left"]   = "0";
    s_options["fceumm_overscan_h_right"]  = "0";
    s_options["fceumm_overscan_v_top"]    = "0";
    s_options["fceumm_overscan_v_bottom"] = "0";
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

        // The core registers these; we accept.
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return true;

        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            // Capture core messages (e.g. "FDS BIOS image missing") for error reporting
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

        // FDS BIOS loading uses filestream_open(), which falls back to
        // retro_vfs_file_open_impl() (from vfs_implementation.c, compiled in)
        // when no VFS interface is provided. This fallback works correctly on
        // Android for standard filesystem paths, so we don't need to provide
        // a VFS interface — returning false lets the core use the fallback.
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

// Blit a row of XRGB8888 pixels to the ANativeWindow buffer (ARGB_8888).
// On Android, ANativeWindow buffers with format WINDOW_FORMAT_RGBA_8888 or
// WINDOW_FORMAT_RGBX_8888 store pixels as RGBA in memory. We need to convert
// XRGB (0xXXRRGGBB) to RGBA (0xRRGGBBAA or 0xRRGGBBXX).
static inline uint32_t xrgbToRgba(uint32_t px) {
    // XRGB8888: 0xXXRRGGBB -> RGBA8888: 0xRRGGBBAA (alpha = 0xFF)
    return ((px & 0x00FFFFFF) << 8) | 0x000000FFu;
}

static void blitToSurface(const uint32_t* src, unsigned w, unsigned h, size_t srcStride) {
    std::lock_guard<std::mutex> lk(s_windowMtx);
    if (!s_window) return;

    ANativeWindow_Buffer buf;
    memset(&buf, 0, sizeof(buf));
    int rc = ANativeWindow_lock(s_window, &buf, nullptr);
    if (rc != 0) {
        LOGE("ANativeWindow_lock failed: %d", rc);
        return;
    }

    // Determine destination parameters
    const uint32_t dstW = (uint32_t)buf.width;
    const uint32_t dstH = (uint32_t)buf.height;
    if (dstW == 0 || dstH == 0) {
        ANativeWindow_unlockAndPost(s_window);
        return;
    }

    // Scale source to destination (nearest-neighbor, preserve aspect ratio)
    float sx = (float)w / (float)dstW;
    float sy = (float)h / (float)dstH;
    float scale = sx > sy ? sx : sy; // use larger scale = fit inside
    // Actually we want to fill the surface; let the Kotlin layer set the
    // SurfaceView layout to match the aspect ratio. Here we just stretch.
    sx = (float)w / (float)dstW;
    sy = (float)h / (float)dstH;

    // The buffer format is typically WINDOW_FORMAT_RGBA_8888 (4 bytes/pixel)
    // or WINDOW_FORMAT_RGB_565. Handle RGBA_8888 (most common on modern devices).
    int filterType = s_videoFilter.load(std::memory_order_relaxed);

    if (buf.format == WINDOW_FORMAT_RGBA_8888 ||
        buf.format == WINDOW_FORMAT_RGBX_8888) {
        uint8_t* dst = static_cast<uint8_t*>(buf.bits);
        const uint32_t dstStride = buf.stride * 4; // bytes per row
        for (uint32_t y = 0; y < dstH; ++y) {
            uint32_t srcY = (uint32_t)(y * sy);
            if (srcY >= h) srcY = h - 1;
            uint8_t* drow = dst + y * dstStride;
            const uint32_t* srow = src + srcY * srcStride;
            for (uint32_t x = 0; x < dstW; ++x) {
                uint32_t srcX = (uint32_t)(x * sx);
                if (srcX >= w) srcX = w - 1;
                uint32_t px = srow[srcX];
                // XRGB8888 -> RGBA8888
                uint8_t r = (px >> 16) & 0xFF;
                uint8_t g = (px >> 8) & 0xFF;
                uint8_t b = px & 0xFF;

                // Apply video filter post-processing
                if (filterType == 1) {
                    // Scanline: darken every other row by 30%
                    if (y & 1) { r = r * 7 / 10; g = g * 7 / 10; b = b * 7 / 10; }
                } else if (filterType == 2) {
                    // CRT: scanline + slight horizontal blur + vignette
                    if (y & 1) { r = r * 6 / 10; g = g * 6 / 10; b = b * 6 / 10; }
                    // Subtle vignette darkening at edges
                    float edgeX = (float)x / dstW;
                    float edgeY = (float)y / dstH;
                    float vignette = 1.0f;
                    float dx = edgeX - 0.5f, dy = edgeY - 0.5f;
                    float dist = dx*dx + dy*dy;
                    if (dist > 0.15f) vignette = 1.0f - (dist - 0.15f) * 0.5f;
                    if (vignette < 0.7f) vignette = 0.7f;
                    r = (uint8_t)(r * vignette);
                    g = (uint8_t)(g * vignette);
                    b = (uint8_t)(b * vignette);
                } else if (filterType == 3) {
                    // Dot grid: darken pixels at regular intervals to simulate LCD dot mask
                    if ((x % 3 == 2) || (y % 3 == 2)) {
                        r = r * 7 / 10; g = g * 7 / 10; b = b * 7 / 10;
                    }
                } else if (filterType == 4) {
                    // XBR: edge-preserving smooth scaling
                    // Compute exact source position for bilinear + edge detection
                    float fx = (float)x * sx;
                    float fy = (float)y * sy;
                    int ix = (int)fx;
                    int iy = (int)fy;
                    float dx = fx - ix;
                    float dy = fy - iy;
                    if (ix < 0) ix = 0;
                    if (iy < 0) iy = 0;
                    if (ix >= (int)w) ix = (int)w - 1;
                    if (iy >= (int)h) iy = (int)h - 1;
                    int ix1 = (ix + 1 < (int)w) ? ix + 1 : ix;
                    int iy1 = (iy + 1 < (int)h) ? iy + 1 : iy;

                    uint32_t p00 = src[iy  * srcStride + ix];
                    uint32_t p01 = src[iy  * srcStride + ix1];
                    uint32_t p10 = src[iy1 * srcStride + ix];
                    uint32_t p11 = src[iy1 * srcStride + ix1];

                    // Bilinear weights
                    float w00 = (1.0f - dx) * (1.0f - dy);
                    float w01 = dx * (1.0f - dy);
                    float w10 = (1.0f - dx) * dy;
                    float w11 = dx * dy;

                    // Edge detection via squared color distance
                    auto sqDist = [](uint32_t a, uint32_t b) -> int {
                        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
                        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
                        int db = (a & 0xFF) - (b & 0xFF);
                        return dr * dr + dg * dg + db * db;
                    };
                    int dvert = sqDist(p00, p10) + sqDist(p01, p11);
                    int dhorz = sqDist(p00, p01) + sqDist(p10, p11);

                    // Reduce weight across detected edges
                    if (dvert > 9000) {
                        if (dy < 0.5f) { w10 *= 0.15f; w11 *= 0.15f; }
                        else           { w00 *= 0.15f; w01 *= 0.15f; }
                    }
                    if (dhorz > 9000) {
                        if (dx < 0.5f) { w01 *= 0.15f; w11 *= 0.15f; }
                        else           { w00 *= 0.15f; w10 *= 0.15f; }
                    }

                    float ws = w00 + w01 + w10 + w11;
                    if (ws > 0.0f) {
                        r = (uint8_t)((((p00 >> 16) & 0xFF) * w00 + ((p01 >> 16) & 0xFF) * w01 +
                                       ((p10 >> 16) & 0xFF) * w10 + ((p11 >> 16) & 0xFF) * w11) / ws + 0.5f);
                        g = (uint8_t)((((p00 >> 8) & 0xFF) * w00 + ((p01 >> 8) & 0xFF) * w01 +
                                       ((p10 >> 8) & 0xFF) * w10 + ((p11 >> 8) & 0xFF) * w11) / ws + 0.5f);
                        b = (uint8_t)(((p00 & 0xFF) * w00 + (p01 & 0xFF) * w01 +
                                       (p10 & 0xFF) * w10 + (p11 & 0xFF) * w11) / ws + 0.5f);
                    }
                }

                drow[x * 4 + 0] = r;   // R
                drow[x * 4 + 1] = g;   // G
                drow[x * 4 + 2] = b;   // B
                drow[x * 4 + 3] = 0xFF; // A
            }
        }
    } else if (buf.format == WINDOW_FORMAT_RGB_565) {
        uint16_t* dst = static_cast<uint16_t*>(buf.bits);
        const uint32_t dstStride = buf.stride; // pixels per row
        for (uint32_t y = 0; y < dstH; ++y) {
            uint32_t srcY = (uint32_t)(y * sy);
            if (srcY >= h) srcY = h - 1;
            uint16_t* drow = dst + y * dstStride;
            const uint32_t* srow = src + srcY * srcStride;
            for (uint32_t x = 0; x < dstW; ++x) {
                uint32_t srcX = (uint32_t)(x * sx);
                if (srcX >= w) srcX = w - 1;
                uint32_t px = srow[srcX];
                uint16_t r = ((px >> 16) & 0xF8) >> 3; // 5 bits
                uint16_t g = ((px >> 8) & 0xFC) >> 2;  // 6 bits
                uint16_t b = (px & 0xF8) >> 3;          // 5 bits
                drow[x] = (r << 11) | (g << 5) | b;
            }
        }
    }

    ANativeWindow_unlockAndPost(s_window);
}

static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return; // duplicate frame / no data this frame

    s_videoW = width;
    s_videoH = height;

    const uint32_t* src = static_cast<const uint32_t*>(data);
    const size_t srcStride = pitch / sizeof(uint32_t);

    // Copy to internal frame buffer (for fallback Bitmap rendering + screenshots)
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        const unsigned cw = (width < kNesW) ? width : (unsigned)kNesW;
        const unsigned ch = (height < kNesH) ? height : (unsigned)kNesH;
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

    // Blit directly to ANativeWindow if a surface is attached (hardware accel)
    blitToSurface(src, width, height, srcStride);
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
        case RETRO_DEVICE_ID_JOYPAD_R:
            // R button is used for FDS disk insert/eject.
            // Driven by s_fdsRButton, not the regular pad bits.
            return (port == 0) ? s_fdsRButton.load(std::memory_order_relaxed) : 0;
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
    // FCEUmm's retro_init() calls the environment callback (e.g. to get the
    // log interface). If environ_cb is still NULL, it dereferences pc 0x0.
    retro_set_environment(cb_environment);
    retro_init();
    retro_set_video_refresh(cb_video);
    retro_set_audio_sample(cb_audio_sample);
    retro_set_audio_sample_batch(cb_audio_batch);
    retro_set_input_poll(cb_input_poll);
    retro_set_input_state(cb_input_state);

    // FCEUmm advertises need_fullpath=true, but also registers a content
    // info override with need_fullpath=false. Always provide BOTH path and
    // data so the core can use whichever mode it prefers — this is
    // especially important for FDS games where the core may need the data
    // buffer directly.
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

    // Reset FDS auto-insert state
    s_fdsNeedInsert.store(false, std::memory_order_relaxed);
    s_fdsRButton.store(false, std::memory_order_relaxed);
    s_isFdsGame.store(false, std::memory_order_relaxed);
    s_fdsInsertFrameCount = 0;

    if (!retro_load_game(&game)) {
        retro_unload_game();
        retro_deinit();
        // Return the core's own error message if available (e.g. FDS BIOS missing)
        if (!s_coreMessage.empty()) {
            std::string err = s_coreMessage;
            s_coreMessage.clear();
            return err;
        }
        return "FCEUmm rejected the ROM (unsupported mapper or corrupt file)";
    }
    s_loaded = true;

    // Detect FDS games by file extension and schedule auto disk-insert.
    // FCEUmm starts with the disk ejected (InDisk=255); the R button must
    // be pressed once to insert it. Without this, FDS games show a blank
    // white/gray BIOS screen forever.
    {
        std::string ext;
        size_t dot = path.find_last_of('.');
        if (dot != std::string::npos) {
            ext = path.substr(dot);
            std::transform(ext.begin(), ext.end(), ext.begin(),
                           [](unsigned char c) { return std::tolower(c); });
        }
        if (ext == ".fds") {
            s_fdsNeedInsert.store(true, std::memory_order_relaxed);
            s_isFdsGame.store(true, std::memory_order_relaxed);
            s_fdsInsertFrameCount = 0;
            LOGI("FDS game detected — will auto-insert disk on first frames");
        }
    }

    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);

    struct retro_system_av_info av;
    retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    s_region = (av.timing.fps < 55.0) ? 1 : 0;
    regionOut = s_region;
    s_videoW = av.geometry.base_width;
    s_videoH = av.geometry.base_height;

    resetAudioRing();
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
    resetAudioRing();
    s_newFrame.store(false);
    // Reset FDS auto-insert state
    s_fdsNeedInsert.store(false, std::memory_order_relaxed);
    s_fdsRButton.store(false, std::memory_order_relaxed);
    s_isFdsGame.store(false, std::memory_order_relaxed);
    s_fdsInsertFrameCount = 0;
}

void resetEmulation(bool /*hard*/) {
    if (!s_loaded) return;
    retro_reset();
    // After reset, FDS disk is ejected again — schedule re-insert
    if (s_isFdsGame.load(std::memory_order_relaxed)) {
        s_fdsNeedInsert.store(true, std::memory_order_relaxed);
        s_fdsRButton.store(false, std::memory_order_relaxed);
        s_fdsInsertFrameCount = 0;
    }
}

void stepFrame() {
    if (!s_loaded) return;

    // FDS auto disk-insert sequence: FCEUmm starts with the disk ejected
    // (InDisk=255). The user must press R to insert it. We wait ~90 frames
    // (1.5s) for the BIOS screen to initialize, then press R for exactly
    // one frame. FCEUmm uses edge detection: if (curR && !prevR) insert.
    // So we need R=false for several frames, then R=true for 1 frame,
    // then R=false again.
    if (s_fdsNeedInsert.load(std::memory_order_relaxed)) {
        s_fdsInsertFrameCount++;
        if (s_fdsInsertFrameCount == 90) {
            // Press R for exactly 1 frame to trigger disk insertion
            s_fdsRButton.store(true, std::memory_order_relaxed);
            LOGI("FDS: pressing R to insert disk (frame %d)", s_fdsInsertFrameCount);
        } else if (s_fdsInsertFrameCount == 91) {
            // Release R immediately — the edge has been detected
            s_fdsRButton.store(false, std::memory_order_relaxed);
            s_fdsNeedInsert.store(false, std::memory_order_relaxed);
            LOGI("FDS disk auto-inserted");
        }
        // For all other frames, R stays false (let BIOS init)
    }

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

// --- Hardware-accelerated rendering ---------------------------------------

void setSurface(void* nativeWindow) {
    std::lock_guard<std::mutex> lk(s_windowMtx);

    // Release the previous window
    if (s_window) {
        ANativeWindow_release(s_window);
        s_window = nullptr;
    }

    if (nativeWindow) {
        s_window = static_cast<ANativeWindow*>(nativeWindow);
        ANativeWindow_acquire(s_window);

        // Set buffer format to RGBA_8888. Use 0x0 for dimensions so the
        // buffer matches the SurfaceView's actual display size — this lets
        // the blit function scale from NES resolution (256x240) to the
        // display resolution, which is essential for XBR and gives better
        // scanline/CRT/dot filter quality.
        ANativeWindow_setBuffersGeometry(s_window, 0, 0,
                                         WINDOW_FORMAT_RGBA_8888);
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
    // FCEUmm reports aspect_ratio in the AV info struct, but we compute
    // it from the current video dimensions.
    if (s_videoH == 0) {
        num = 4; den = 3;
        return;
    }
    // Common NES aspect ratios:
    // 8:7  = 256:224 (native PAR)
    // 4:3  = standard TV
    // NTSC = ~10:11 PAR -> 256*10/11 : 224 ≈ 4:3
    // We simplify: return the raw pixel ratio
    num = (int)s_videoW;
    den = (int)s_videoH;
    // Reduce by GCD
    int a = num, b = den;
    while (b) { int t = b; b = a % b; a = t; }
    if (a > 0) { num /= a; den /= a; }
}

// --- Video filter ----------------------------------------------------------

void setVideoFilter(int filter) {
    s_videoFilter.store(filter, std::memory_order_relaxed);
    LOGI("Video filter set: %d", filter);
}

} // namespace nescore::rom
