// SPDX-License-Identifier: MIT
// libretro frontend that drives the prebuilt PCEE2 (PCSX2) PlayStation 2 core.
//
// This loader follows the same dlopen() pattern as psx_loader.cpp:
// we dlopen() the prebuilt libpcee2_libretro_android.so at runtime and
// resolve the retro_* symbols via dlsym(). The .so ships in
// app/src/main/jniLibs/<abi>/.
//
// PCEE2 is the libretro core build of the CURRENT upstream PCSX2 codebase
// (v2.7.523), shipped by the official libretro buildbot for Android
// arm64-v8a. The user-requested swap: Play! had too many game compatibility
// issues, PCEE2 tracks modern PCSX2 instead.
//
// Video: pixel format XRGB8888. Renderer core options:
//   - "vulkan"  — hardware GS via an offscreen Vulkan device, frames read
//                 back and delivered through retro_video_refresh when the
//                 frontend offers no HW-render context (our case).
//   - "software"— pure CPU GS rasterizer, direct video_refresh delivery.
// Both paths land in our cb_video() soft-copy pipeline unchanged.
// Internal resolution scales via pcsx2_upscale_multiplier ("1".."4":
// 640x448 .. 2560x1792 NTSC / up to 2560x2048 PAL). Our frame buffer caps
// at 2560x2048 so every offered multiplier fits natively; oversize frames
// are still box-downsampled as a safety net.
//
// Audio: SPU2 outputs at a fixed 48000 Hz; passthrough (no resampling).
//
// Input: 16-button PS2 DualShock gamepad (bit12..15 = L2/R2/L3/R3) plus
// DUAL ANALOG STICKS via RETRO_DEVICE_ANALOG — left stick LX/LY, right
// stick RX/RY, all int16 -32768..32767. All 4 controller ports are set to
// RETRO_DEVICE_ANALOG after load (PS2 games probe analog sticks natively).
//
// Core options used by this frontend (verified against the PCEE2 source,
// WizzardSK/pcee2-libretro Libretro.cpp definitions[]):
//   pcsx2_renderer            — "vulkan" | "software"
//   pcsx2_upscale_multiplier  — "1"|"2"|"3"|"4" (分辨率倍数设置)
//   pcsx2_texture_filtering   — "nearest"|"bilinear_ps2"|... (双线性开关映射)
//
// BIOS: PCEE2 looks in <systemDir>/pcsx2/bios/scphXXXXX.bin (e.g.
// scph10000.bin, scph39001.bin). Without a real BIOS most games will not
// boot — the load error explains exactly where to put the file. A legacy
// <systemDir>/bios/ location from the previous Play! era layout is
// migrated automatically on first load.

#include "pcsx2_loader.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <sys/stat.h>

// libretro API — same shared header used by every loader (core/jni/shared/).
#include <libretro.h>

#include "shared/core_shared.h"

#define TAG "ps2core"
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace ps2core::rom {

// ---------------------------------------------------------------------------
// Maximum video resolution supported by the frontend frame buffer.
// PS2 GS video modes (1x): 640x448 NTSC / 640x512 PAL (interlaced).
// pcsx2_upscale_multiplier multiplies the frame: 2x = 1280x896,
// 3x = 1920x1344, 4x = 2560x1792 NTSC (2560x2048 worst-case PAL).
// We cap at 2560x2048 so every offered multiplier fits at full quality;
// oversized frames are still box-downsampled as a safety net.
// ---------------------------------------------------------------------------
static constexpr int kMaxW = 2560;
static constexpr int kMaxH = 2048;

static constexpr int TARGET_SAMPLE_RATE = coreshared::TARGET_SAMPLE_RATE;

// ---------------------------------------------------------------------------
// libretro function pointer types
// ---------------------------------------------------------------------------
typedef void   (*retro_init_t)(void);
typedef void   (*retro_deinit_t)(void);
typedef unsigned (*retro_api_version_t)(void);
typedef void   (*retro_get_system_info_t)(struct retro_system_info* info);
typedef void   (*retro_get_system_av_info_t)(struct retro_system_av_info* info);
typedef void   (*retro_set_controller_port_device_t)(unsigned port, unsigned device);
typedef void   (*retro_reset_t)(void);
typedef void   (*retro_run_t)(void);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool   (*retro_serialize_t)(void* data, size_t size);
typedef bool   (*retro_unserialize_t)(const void* data, size_t size);
typedef void*  (*retro_get_memory_data_t)(unsigned id);
typedef size_t (*retro_get_memory_size_t)(unsigned id);
typedef bool   (*retro_load_game_t)(const struct retro_game_info* game);
typedef void   (*retro_unload_game_t)(void);
typedef void   (*retro_set_environment_t)(retro_environment_t);
typedef void   (*retro_set_video_refresh_t)(retro_video_refresh_t);
typedef void   (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void   (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t);
typedef void   (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void   (*retro_set_input_state_t)(retro_input_state_t);

// ---------------------------------------------------------------------------
// State — dlopen handle and resolved symbols
// ---------------------------------------------------------------------------
static void* s_coreLib = nullptr;

static retro_init_t                      s_retro_init = nullptr;
static retro_deinit_t                    s_retro_deinit = nullptr;
static retro_api_version_t               s_retro_api_version = nullptr;
static retro_get_system_info_t           s_retro_get_system_info = nullptr;
static retro_get_system_av_info_t        s_retro_get_system_av_info = nullptr;
static retro_set_controller_port_device_t s_retro_set_controller_port_device = nullptr;
static retro_reset_t                     s_retro_reset = nullptr;
static retro_run_t                       s_retro_run = nullptr;
static retro_serialize_size_t            s_retro_serialize_size = nullptr;
static retro_serialize_t                 s_retro_serialize = nullptr;
static retro_unserialize_t               s_retro_unserialize = nullptr;
static retro_get_memory_data_t           s_retro_get_memory_data = nullptr;
static retro_get_memory_size_t           s_retro_get_memory_size = nullptr;
static retro_load_game_t                 s_retro_load_game = nullptr;
static retro_unload_game_t               s_retro_unload_game = nullptr;
static retro_set_environment_t           s_retro_set_environment = nullptr;
static retro_set_video_refresh_t         s_retro_set_video_refresh = nullptr;
static retro_set_audio_sample_t          s_retro_set_audio_sample = nullptr;
static retro_set_audio_sample_batch_t    s_retro_set_audio_sample_batch = nullptr;
static retro_set_input_poll_t            s_retro_set_input_poll = nullptr;
static retro_set_input_state_t           s_retro_set_input_state = nullptr;

static bool s_loaded = false;
static bool s_gameLoaded = false;
static int  s_sampleRate = 0;
static double s_refreshRate = 60.0;
static int  s_region = 0;
static std::string s_systemDir;
static std::string s_saveDir;
static std::string s_saveName;
static std::string s_lastRomPath;
static std::string s_coreMessage;
static std::string s_coreError;
static std::string s_coreLibPath;

// Persistent copy of the currently-loaded ROM path (gameInfo.path must
// outlive the JNI GetStringUTFChars / ReleaseStringUTFChars cycle).
static std::string s_romPath;

// Dynamic frame buffer (ARGB, 0xAARRGGBB).
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static unsigned s_frameW = 0;
static unsigned s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

static unsigned s_videoW = 640;
static unsigned s_videoH = 448;
static unsigned s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

// Gamepad bits (port 0..3, RETRO_DEVICE_JOYPAD — 16 buttons).
static std::atomic<uint32_t> s_pad1{0};
static std::atomic<uint32_t> s_pad2{0};
static std::atomic<uint32_t> s_pad3{0};
static std::atomic<uint32_t> s_pad4{0};

// Analog stick axes (port 0..3). Layout: [port][0..3] = LX, LY, RX, RY.
// int16 libretro range (-32768..32767). Written by setAnalogInput (UI
// thread), read by cb_input_state (emulation thread) — per-slot atomics.
static std::atomic<int16_t> s_axes[4][4] = {
    { {0}, {0}, {0}, {0} }, { {0}, {0}, {0}, {0} },
    { {0}, {0}, {0}, {0} }, { {0}, {0}, {0}, {0} }
};

static std::atomic<int>  s_videoFilter{0};
static std::atomic<bool> s_highQualityScaling{false};
static std::atomic<bool> s_fastForward{false};
static std::atomic<int>  s_ffFrameSkip{0};
static std::atomic<int>  s_ffMaxSkip{6};

// 2x / 4x upscale buffers for XBR / HQ2X / HQ4X filters.
// 滤镜以 PS2 base 分辨率 640x448/480 为上限（与 psx_loader 的 640x480 基准
// 一致）；超过基准的帧（2x/4x 倍率下的 1280x896 等）由 core_shared 直接
// blit 不走滤镜，缓冲按 640x480 滤镜需求固定分配。
static constexpr int kFilterBaseW = 640;
static constexpr int kFilterBaseH = 480;
static uint32_t s_xbrBuffer2x[kFilterBaseW * kFilterBaseH * 2 * 2];
static uint32_t s_xbrBuffer4x[kFilterBaseW * kFilterBaseH * 4 * 4];
static uint32_t s_xbrMidBuffer[kFilterBaseW * kFilterBaseH * 2 * 2];

static coreshared::AudioRingBuffer s_audio;
static coreshared::AudioResampler s_resampler;

static ANativeWindow* s_window = nullptr;
static std::mutex s_windowMtx;

static std::mutex s_optMtx;
static std::map<std::string, std::string> s_options;
static std::atomic<bool> s_optionsChanged{false};

// Controller-port device switching requested from the UI thread.
static std::atomic<uint32_t> s_pendingPortDevices{0xFFu << 24};

// ---------------------------------------------------------------------------
// Initialize PCEE2 (PCSX2) core options with sensible defaults.
// Keys verified against the core's own retro_core_option_v2 table
// (WizzardSK/pcee2-libretro Libretro.cpp definitions[]):
//   pcsx2_renderer            — "vulkan" | "software" (Android builds ship
//                               only these two; OpenGL is compiled out)
//   pcsx2_upscale_multiplier  — "1"|"2"|"3"|"4", plain integer strings
//   pcsx2_texture_filtering   — "nearest"|"bilinear_ps2"|"bilinear_forced"|
//                               "bilinear_forced_sprite"
// Unknown keys/values make the core silently keep its own default, so only
// send exactly these value enums from the Kotlin side too.
// ---------------------------------------------------------------------------
static void initDefaultOptions() {
    // --- 画面（分辨率倍数是本前端的核心卖点设置）---
    s_options["pcsx2_renderer"]           = "vulkan";       // vulkan | software
    s_options["pcsx2_upscale_multiplier"] = "1";            // "1"|"2"|"3"|"4"
    s_options["pcsx2_texture_filtering"]  = "bilinear_ps2"; // nearest|bilinear_ps2|...
}

// ---------------------------------------------------------------------------
// dlopen the core .so and resolve all retro_* symbols.
// ---------------------------------------------------------------------------
static bool loadCoreLib() {
    if (s_coreLib) return true;

    std::vector<std::string> candidates;
    if (!s_coreLibPath.empty()) candidates.push_back(s_coreLibPath);
    candidates.push_back("libpcee2_libretro_android.so");

    const char* lastDlError = nullptr;
    for (const auto& name : candidates) {
        s_coreLib = dlopen(name.c_str(), RTLD_NOW);
        if (s_coreLib) {
            LOGI("dlopen(%s) OK", name.c_str());
            break;
        } else {
            lastDlError = dlerror();
            LOGW("dlopen(%s) failed: %s", name.c_str(),
                 lastDlError ? lastDlError : "(unknown)");
        }
    }

    if (!s_coreLib) {
        s_coreError = "dlopen(libpcee2_libretro_android.so) failed: ";
        s_coreError += (lastDlError ? lastDlError : "(unknown)");
        LOGE("%s", s_coreError.c_str());
        return false;
    }

    #define RESOLVE(name) \
        s_##name = reinterpret_cast<name##_t>(dlsym(s_coreLib, #name)); \
        if (!s_##name) { \
            const char* _dlerr = dlerror(); \
            s_coreError = "dlsym(" #name ") failed: "; \
            s_coreError += (_dlerr ? _dlerr : "(unknown)"); \
            LOGE("%s", s_coreError.c_str()); \
            dlclose(s_coreLib); s_coreLib = nullptr; \
            return false; \
        }

    RESOLVE(retro_init);
    RESOLVE(retro_deinit);
    RESOLVE(retro_api_version);
    RESOLVE(retro_get_system_info);
    RESOLVE(retro_get_system_av_info);
    RESOLVE(retro_set_controller_port_device);
    RESOLVE(retro_reset);
    RESOLVE(retro_run);
    RESOLVE(retro_serialize_size);
    RESOLVE(retro_serialize);
    RESOLVE(retro_unserialize);
    RESOLVE(retro_load_game);
    RESOLVE(retro_unload_game);
    RESOLVE(retro_set_environment);
    RESOLVE(retro_set_video_refresh);
    RESOLVE(retro_set_audio_sample);
    RESOLVE(retro_set_audio_sample_batch);
    RESOLVE(retro_set_input_poll);
    RESOLVE(retro_set_input_state);

    // Optional — used for memory card (VMC) persistence via SAVE_RAM.
    s_retro_get_memory_data = reinterpret_cast<retro_get_memory_data_t>(
        dlsym(s_coreLib, "retro_get_memory_data"));
    s_retro_get_memory_size = reinterpret_cast<retro_get_memory_size_t>(
        dlsym(s_coreLib, "retro_get_memory_size"));

    #undef RESOLVE

    LOGI("All retro_* symbols resolved");
    return true;
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
    __android_log_vprint(prio, "pcee2", fmt, ap);
    va_end(ap);
}

static bool cb_environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (data) *static_cast<bool*>(data) = true;
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            if (data) {
                s_pixelFormat = *static_cast<const unsigned*>(data);
                LOGI("Pixel format set: %u (0=0RGB1555, 1=XRGB8888, 2=RGB565)",
                     s_pixelFormat);
            }
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

        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
#ifdef RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
#endif
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return true;

        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (data) *static_cast<unsigned*>(data) = 1;
            return true;

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
            return true;

        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            return true;

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            if (data) {
                auto* av2 = static_cast<retro_system_av_info*>(data);
                if (av2->timing.sample_rate > 8000) {
                    s_sampleRate = (int)av2->timing.sample_rate;
                    // Default audio — passthrough, no TV-mode resampling.
                    s_resampler.init(s_sampleRate, s_sampleRate);
                }
                if (av2->timing.fps > 10.0) {
                    s_refreshRate = av2->timing.fps;
                }
                LOGI("SET_SYSTEM_AV_INFO: %d Hz, %.4f fps", s_sampleRate, s_refreshRate);
            }
            return true;

        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *static_cast<int*>(data) = 3;
            return true;

        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            if (data) {
                auto* msg = static_cast<const retro_message*>(data);
                if (msg && msg->msg) {
                    s_coreMessage = msg->msg;
                    LOGI("Core message: %s", msg->msg);
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

        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            return false;

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return false;

        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
            return false;

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
            return true;

        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            if (data) *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;

        default:
            return false;
    }
}

// Convert one row of an oversized source frame into the capped frame buffer
// by nearest-neighbor box sampling (integer or fractional scale factor).
static void downsampleRow(const uint32_t* src, unsigned srcW,
                          uint32_t* dst, unsigned dstW, unsigned y) {
    const float ratio = (float)srcW / (float)dstW;
    for (unsigned x = 0; x < dstW; ++x) {
        unsigned sx = (unsigned)((x + 0.5f) * ratio);
        if (sx >= srcW) sx = srcW - 1;
        dst[(size_t)y * dstW + x] = src[y * srcW + sx];
    }
}

static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;

    s_videoW = width;
    s_videoH = height;

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);

        // Cap oversized frames (4x/8x resolution multiplier): box-downsample
        // into kMaxW x kMaxH so memory stays bounded at ~8 MB per frame.
        unsigned outW = width;
        unsigned outH = height;
        bool oversize = (width > (unsigned)kMaxW) || (height > (unsigned)kMaxH);
        if (oversize) {
            // Scale down uniformly to fit the cap.
            float scaleW = (float)kMaxW / (float)width;
            float scaleH = (float)kMaxH / (float)height;
            float scale  = (scaleW < scaleH) ? scaleW : scaleH;
            outW = (unsigned)(width * scale);
            outH = (unsigned)(height * scale);
            if (outW < 1) outW = 1;
            if (outH < 1) outH = 1;
            if (outW > (unsigned)kMaxW) outW = kMaxW;
            if (outH > (unsigned)kMaxH) outH = kMaxH;
        }

        const size_t need = (size_t)outW * outH;
        if (s_frameW != outW || s_frameH != outH || s_frame.size() < need) {
            s_frame.resize(need);
            s_frameW = outW;
            s_frameH = outH;
        }

        const uint8_t* base = static_cast<const uint8_t*>(data);

        if (s_pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
            if (!oversize) {
                const uint32_t* src = reinterpret_cast<const uint32_t*>(base);
                coreshared::convertXrgbRowsToArgb(
                    s_frame.data(), src,
                    (unsigned)(pitch / sizeof(uint32_t)), width,
                    width, height);
            } else {
                for (unsigned y = 0; y < outH; ++y) {
                    unsigned sy = (unsigned)((y + 0.5f) * (float)height / (float)outH);
                    if (sy >= height) sy = height - 1;
                    const uint32_t* srow = reinterpret_cast<const uint32_t*>(
                        base + (size_t)sy * pitch);
                    downsampleRow(srow, width, s_frame.data(), outW, y);
                }
            }
        } else if (s_pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
            for (unsigned y = 0; y < outH; ++y) {
                unsigned sy = (unsigned)((y + 0.5f) * (float)height / (float)outH);
                if (sy >= height) sy = height - 1;
                const uint16_t* srow = reinterpret_cast<const uint16_t*>(
                    base + (size_t)sy * pitch);
                uint32_t* drow = s_frame.data() + (size_t)y * outW;
                const float ratio = (float)width / (float)outW;
                for (unsigned x = 0; x < outW; ++x) {
                    unsigned sx = (unsigned)((x + 0.5f) * ratio);
                    if (sx >= width) sx = width - 1;
                    uint16_t px = srow[sx];
                    uint32_t r5 = (px >> 11) & 0x1F;
                    uint32_t g6 = (px >> 5)  & 0x3F;
                    uint32_t b5 = px & 0x1F;
                    uint32_t r = (r5 << 3) | (r5 >> 2);
                    uint32_t g = (g6 << 2) | (g6 >> 4);
                    uint32_t b = (b5 << 3) | (b5 >> 2);
                    drow[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
                }
            }
        } else { // 0RGB1555
            for (unsigned y = 0; y < outH; ++y) {
                unsigned sy = (unsigned)((y + 0.5f) * (float)height / (float)outH);
                if (sy >= height) sy = height - 1;
                const uint16_t* srow = reinterpret_cast<const uint16_t*>(
                    base + (size_t)sy * pitch);
                uint32_t* drow = s_frame.data() + (size_t)y * outW;
                const float ratio = (float)width / (float)outW;
                for (unsigned x = 0; x < outW; ++x) {
                    unsigned sx = (unsigned)((x + 0.5f) * ratio);
                    if (sx >= width) sx = width - 1;
                    uint16_t px = srow[sx];
                    uint32_t r5 = (px >> 10) & 0x1F;
                    uint32_t g5 = (px >> 5)  & 0x1F;
                    uint32_t b5 = px & 0x1F;
                    uint32_t r = (r5 << 3) | (r5 >> 2);
                    uint32_t g = (g5 << 3) | (g5 >> 2);
                    uint32_t b = (b5 << 3) | (b5 >> 2);
                    drow[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
                }
            }
        }
        s_newFrame.store(true, std::memory_order_release);
    }

    // Fast-forward frame skip — same pattern as psx_loader.
    if (s_fastForward.load(std::memory_order_relaxed)) {
        int skip = s_ffMaxSkip.load(std::memory_order_relaxed);
        if (skip > 0 && s_ffFrameSkip.fetch_add(1, std::memory_order_relaxed) % skip != 0)
            return;
    } else {
        s_ffFrameSkip.store(0, std::memory_order_relaxed);
    }

    const int filter = s_videoFilter.load(std::memory_order_relaxed);
    coreshared::applyFilterAndBlit(
        s_window, s_windowMtx,
        s_frame.data(), s_frameW, s_frameH, s_frameW,
        filter,
        s_xbrBuffer2x, s_xbrBuffer4x, s_xbrMidBuffer,
        (unsigned)kFilterBaseW, (unsigned)kFilterBaseH,
        s_highQualityScaling.load(std::memory_order_relaxed));
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
                              unsigned index, unsigned id) {
    if (port > 3) return 0;

    // Dual analog sticks: RETRO_DEVICE_ANALOG queries come as
    // (index: 0=left/1=right, id: 0=X/1=Y) with int16 answer.
    if (device == RETRO_DEVICE_ANALOG) {
        if (index <= 1 && id <= 1) {
            return s_axes[port][index * 2 + id].load(std::memory_order_relaxed);
        }
        return 0;
    }

    if (device != RETRO_DEVICE_JOYPAD) return 0;
    // 16-button DualShock: bit0..15 = Cross/Square/Select/Start/
    // Up/Down/Left/Right/Circle/Triangle/L1/R1/L2/R2/L3/R3.
    if (id >= 16) return 0;
    const uint32_t bits = (port == 0) ? s_pad1.load(std::memory_order_relaxed)
                         : (port == 1) ? s_pad2.load(std::memory_order_relaxed)
                         : (port == 2) ? s_pad3.load(std::memory_order_relaxed)
                                       : s_pad4.load(std::memory_order_relaxed);
    return (int16_t)((bits >> id) & 1u);
}

// ---------------------------------------------------------------------------
// Apply any controller-port device switch queued by setPortDevice().
// ---------------------------------------------------------------------------
static void applyPendingPortDevicesLockedStep() {
    uint32_t pending = s_pendingPortDevices.load(std::memory_order_acq_rel);
    if ((pending >> 24) == 0xFF || !s_retro_set_controller_port_device) return;
    for (int port = 0; port < 4; ++port) {
        unsigned dev = (pending >> (port * 8)) & 0xFFu;
        if (dev == 0xFF) dev = RETRO_DEVICE_ANALOG;
        s_retro_set_controller_port_device((unsigned)port, dev);
        LOGI("Controller port %d -> device %u", port, dev);
    }
    s_pendingPortDevices.store(0xFFu << 24, std::memory_order_release);
}

// ---------------------------------------------------------------------------
// File-extension helpers for loadFromFile.
// ---------------------------------------------------------------------------
static std::string getExtensionLower(const std::string& path) {
    size_t dot = path.find_last_of('.');
    if (dot == std::string::npos) return "";
    std::string ext = path.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](unsigned char c){ return (char)std::tolower(c); });
    return ext;
}

// ---------------------------------------------------------------------------
// BIOS layout migration for the PCEE2 switch.
//
// PCEE2 looks for PS2 BIOS images in <systemDir>/pcsx2/bios/. The previous
// Play!-era layout used <systemDir>/bios/ (with systemDir = <files>/ps2, so
// either way the user dropped scphXXXXX.bin somewhere under ps2/). To keep
// existing installs working without re-dumping their BIOS: if the new-style
// folder is missing but the legacy one exists, MOVE every bios image over
// once. Cheap (same filesystem rename) and idempotent.
// ---------------------------------------------------------------------------
static void ensurePcsxBiosLayout() {
    if (s_systemDir.empty()) return;
    const std::string newDir = s_systemDir + "/pcsx2/bios";
    const std::string oldDir = s_systemDir + "/bios";

    struct stat st;
    bool newExists = (::stat(newDir.c_str(), &st) == 0 && S_ISDIR(st.st_mode));
    bool oldExists = (::stat(oldDir.c_str(), &st) == 0 && S_ISDIR(st.st_mode));
    if (!oldExists || newExists) return; // nothing to migrate / already migrated

    // mkdir -p <systemDir>/pcsx2/bios
    ::mkdir((s_systemDir + "/pcsx2").c_str(), 0755);
    if (::mkdir(newDir.c_str(), 0755) != 0) {
        DIR* check = ::opendir(newDir.c_str());
        if (!check) { LOGW("BIOS migration: cannot create %s", newDir.c_str()); return; }
        ::closedir(check);
    }

    DIR* dir = ::opendir(oldDir.c_str());
    if (!dir) return;
    int moved = 0;
    while (dirent* entry = ::readdir(dir)) {
        const char* nm = entry->d_name;
        const size_t len = ::strlen(nm);
        if (len < 4) continue;
        const char* dot = nm + len - 4;
        // Match *.bin / *.rom (case-insensitive tail) — typical BIOS dumps.
        auto ciEq = [](const char a[], const char b[]) {
            for (int i = 0; i < 4; ++i)
                if (std::tolower((unsigned char)a[i]) != b[i]) return false;
            return true;
        };
        if (!ciEq(dot, ".bin") && !ciEq(dot, ".rom")) continue;
        const std::string src = oldDir + "/" + nm;
        const std::string dst = newDir + "/" + nm;
        struct stat fst;
        if (::stat(dst.c_str(), &fst) == 0) continue; // already present — keep it
        if (::rename(src.c_str(), dst.c_str()) == 0) ++moved;
        else LOGW("BIOS migration: rename %s -> %s failed", src.c_str(), dst.c_str());
    }
    ::closedir(dir);
    LOGI("BIOS layout migration: moved %d file(s) from %s to %s",
         moved, oldDir.c_str(), newDir.c_str());
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    regionOut = 0;

    if (!loadCoreLib()) {
        return s_coreError.empty()
            ? "Failed to load libpcee2_libretro_android.so"
            : s_coreError;
    }

    if (!s_loaded) {
        initDefaultOptions();

        s_retro_set_environment(cb_environment);
        s_retro_set_video_refresh(cb_video);
        s_retro_set_audio_sample(cb_audio_sample);
        s_retro_set_audio_sample_batch(cb_audio_batch);
        s_retro_set_input_poll(cb_input_poll);
        s_retro_set_input_state(cb_input_state);

        s_retro_init();
        s_loaded = true;
        LOGI("PCEE2 (PCSX2) core initialized (API version %u)",
             s_retro_api_version());
    }

    if (s_gameLoaded) {
        // Persist memory card (VMC) BEFORE unloading the previous game.
        if (!s_lastRomPath.empty() && s_retro_get_memory_data && s_retro_get_memory_size) {
            void* nvram = s_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
            size_t nvramSize = s_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
            coreshared::saveSramToDisk(nvram, nvramSize, s_saveDir, s_lastRomPath, s_saveName);
        }
        s_retro_unload_game();
        s_gameLoaded = false;
    }

    s_audio.reset();
    s_resampler.reset();

    // PCEE2 opens the disc image itself (needs_fullpath) — always pass by path,
    // after making sure the BIOS lives in its expected pcsx2/bios subfolder.
    ensurePcsxBiosLayout();
    s_romPath = path;

    retro_game_info gameInfo{};
    gameInfo.path = s_romPath.c_str();
    gameInfo.data = nullptr;
    gameInfo.size = 0;
    gameInfo.meta = nullptr;

    bool ok = s_retro_load_game(&gameInfo);

    if (!ok) {
        s_coreError = "retro_load_game() failed for: " + path;
        if (!s_coreMessage.empty()) {
            s_coreError += " (";
            s_coreError += s_coreMessage;
            s_coreError += ")";
            s_coreMessage.clear();
        }
        // Detailed Chinese explanation of common PS2 load failures.
        s_coreError += "\n\n常见原因:\n";
        s_coreError += "  1. BIOS 缺失 (最常见): PCSX2 核心需要真实的 PS2 BIOS 文件, "
                        "请将 scph10000.bin / scph39001.bin 等放到 "
                        "<应用私有目录>/ps2/pcsx2/bios/ 下 (旧版放在 ps2/bios 的文件会自动迁移). "
                        "没有 BIOS 大多数游戏无法启动.\n";
        s_coreError += "  2. 镜像格式不支持: 支持 .iso / .chd / .cue / .bin / "
                        ".cso / .zso / .gz / .mdf / .nrg / .elf, 请确认文件完整未损坏.\n";
        s_coreError += "  3. 镜像加密或非标准: 部分 .cso 压缩级别过高会解析失败, "
                        "建议使用 .iso 或 chdman 转换的 .chd.\n";
        s_coreError += "  4. Vulkan 不可用: 渲染器选了 Vulkan 但设备不支持时, 可在 "
                        "PS2 设置里把渲染器切换为 Software(软件渲染)后再试; "
                        "游戏兼容问题可先试关闭分辨率倍数(1x).\n";
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }

    s_gameLoaded = true;
    s_lastRomPath = path;

    // Load memory card (VMC) from disk into the core's SAVE_RAM region.
    if (s_retro_get_memory_data && s_retro_get_memory_size) {
        void* nvram = s_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
        size_t nvramSize = s_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
        coreshared::loadSramFromDisk(nvram, nvramSize, s_saveDir, path, s_saveName);
    }

    // All 4 controller ports default to RETRO_DEVICE_ANALOG — PS2 DualShock.
    // Analog stick axes are fed from the on-screen twin-stick UI; digital
    // d-pad face/shoulder buttons are always readable too (JOYPAD queries).
    if (s_retro_set_controller_port_device) {
        s_retro_set_controller_port_device(0, RETRO_DEVICE_ANALOG);
        s_retro_set_controller_port_device(1, RETRO_DEVICE_ANALOG);
        s_retro_set_controller_port_device(2, RETRO_DEVICE_ANALOG);
        s_retro_set_controller_port_device(3, RETRO_DEVICE_ANALOG);
    }

    retro_system_av_info av{};
    s_retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    s_refreshRate = (av.timing.fps > 10.0) ? av.timing.fps : 59.94;
    s_region = (av.timing.fps < 55.0) ? 1 : 0;
    regionOut = s_region;
    s_videoW = av.geometry.base_width;
    s_videoH = av.geometry.base_height;

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frameW = av.geometry.base_width;
        s_frameH = av.geometry.base_height;
        if (s_frameW == 0) s_frameW = 640;
        if (s_frameH == 0) s_frameH = 448;
        if (s_frameW > (unsigned)kMaxW) s_frameW = kMaxW;
        if (s_frameH > (unsigned)kMaxH) s_frameH = kMaxH;
        s_frame.assign((size_t)s_frameW * s_frameH, 0xFF000000u);
    }

    s_audio.reset();
    s_newFrame.store(false);

    if (s_sampleRate > 0) {
        // Default audio output — pure passthrough (src == dst).
        s_resampler.init(s_sampleRate, s_sampleRate);
        LOGI("Audio passthrough: %d Hz (ratio=%.6f, active=%d)",
             s_sampleRate, s_resampler.ratio, s_resampler.active ? 1 : 0);
    }

    LOGI("PS2 ROM loaded: %s  rate=%d  fps=%.2f  region=%d  geom=%ux%u  max=%ux%u",
         path.c_str(), s_sampleRate, av.timing.fps, s_region,
         av.geometry.base_width, av.geometry.base_height,
         av.geometry.max_width, av.geometry.max_height);
    return "";
}

void unload() {
    if (s_loaded) {
        if (s_gameLoaded) {
            // Persist memory card (VMC) BEFORE unloading.
            if (!s_lastRomPath.empty() && s_retro_get_memory_data && s_retro_get_memory_size) {
                void* nvram = s_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
                size_t nvramSize = s_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
                coreshared::saveSramToDisk(nvram, nvramSize, s_saveDir, s_lastRomPath, s_saveName);
            }
            s_retro_unload_game();
            s_gameLoaded = false;
        }
        s_retro_deinit();
        s_loaded = false;
    }
    s_sampleRate = 0;
    s_refreshRate = 60.0;
    s_region = 0;
    s_audio.reset();
    s_resampler.reset();
    s_newFrame.store(false);
    s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frame.clear();
        s_frameW = 0;
        s_frameH = 0;
    }
    s_videoW = 640;
    s_videoH = 448;
    s_lastRomPath.clear();
    s_saveName.clear();
    s_pad1.store(0, std::memory_order_relaxed);
    s_pad2.store(0, std::memory_order_relaxed);
    s_pad3.store(0, std::memory_order_relaxed);
    s_pad4.store(0, std::memory_order_relaxed);
    for (int p = 0; p < 4; ++p)
        for (int a = 0; a < 4; ++a)
            s_axes[p][a].store(0, std::memory_order_relaxed);
}

void resetEmulation(bool /*hard*/) {
    if (s_loaded && s_gameLoaded) s_retro_reset();
}

void stepFrame() {
    if (!s_loaded || !s_gameLoaded) return;
    applyPendingPortDevicesLockedStep();
    s_retro_run();
}

void setPortDevice(int port, int device) {
    if (port < 0 || port > 3) return;
    uint32_t next = s_pendingPortDevices.load(std::memory_order_relaxed);
    next = (next & ~(0xFFu << (port * 8))) |
           ((uint32_t)(device & 0xFF) << (port * 8));
    next &= 0x00FFFFFFu;                    // clear "no pending" flag byte
    s_pendingPortDevices.store(next, std::memory_order_acq_rel);
}

double videoRefreshRate() { return s_refreshRate; }

bool copyFramebufferARGB(uint32_t* out, int w, int h) {
    if (!out) return false;
    if (!s_loaded || !s_gameLoaded || s_frame.empty()) {
        std::memset(out, 0, (size_t)w * h * sizeof(uint32_t));
        return false;
    }
    std::lock_guard<std::mutex> lk(s_frameMtx);
    const int cw = (w < (int)s_frameW) ? w : (int)s_frameW;
    const int ch = (h < (int)s_frameH) ? h : (int)s_frameH;
    for (int y = 0; y < ch; ++y) {
        std::memcpy(out + (size_t)y * w,
                    s_frame.data() + (size_t)y * s_frameW,
                    (size_t)cw * sizeof(uint32_t));
    }
    return s_newFrame.exchange(false, std::memory_order_acq_rel);
}

int readAudio(int16_t* out, int maxFrames) {
    if (!s_loaded || !s_gameLoaded) return 0;
    return s_resampler.readResampled(s_audio, out, maxFrames);
}

int audioSampleRate() { return s_sampleRate; }
int audioTargetSampleRate() { return s_sampleRate; }  // default audio == core rate

void setControllerInput(int port, uint32_t bits) {
    if (port == 0)      s_pad1.store(bits, std::memory_order_relaxed);
    else if (port == 1) s_pad2.store(bits, std::memory_order_relaxed);
    else if (port == 2) s_pad3.store(bits, std::memory_order_relaxed);
    else if (port == 3) s_pad4.store(bits, std::memory_order_relaxed);
}

void setAnalogInput(int port, int16_t lx, int16_t ly, int16_t rx, int16_t ry) {
    if (port < 0 || port > 3) return;
    s_axes[port][0].store(lx, std::memory_order_relaxed);
    s_axes[port][1].store(ly, std::memory_order_relaxed);
    s_axes[port][2].store(rx, std::memory_order_relaxed);
    s_axes[port][3].store(ry, std::memory_order_relaxed);
}

void setPaths(const std::string& systemDir, const std::string& saveDir) {
    s_systemDir = systemDir;
    s_saveDir = saveDir;
}

void setSaveName(const std::string& name) {
    s_saveName = name;
    LOGI("VMC save name set: '%s'", name.c_str());
}

void setCoreLibPath(const std::string& path) {
    s_coreLibPath = path;
    LOGI("Core lib path set: %s", s_coreLibPath.c_str());
}

void applyRegion(int /*region*/) { /* region is auto-detected at load */ }
void applySampleRate(int /*hz*/) { /* fixed by the core */ }
void applySpeed(float multiplier) {
    s_fastForward.store(multiplier > 1.0f, std::memory_order_relaxed);
    s_ffMaxSkip.store((int)multiplier, std::memory_order_relaxed);
    s_ffFrameSkip.store(0, std::memory_order_relaxed);
}

void saveStateToPath(int /*slot*/, const std::string& path) {
    if (!s_loaded || !s_gameLoaded || !s_retro_serialize) return;
    size_t sz = s_retro_serialize_size();
    if (sz == 0) return;
    std::vector<uint8_t> buf(sz);
    if (!s_retro_serialize(buf.data(), sz)) { LOGE("retro_serialize failed"); return; }
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) { LOGE("Cannot open save state for write: %s", path.c_str()); return; }
    std::fwrite(buf.data(), 1, sz, f);
    std::fclose(f);
}

bool loadStateFromPath(int /*slot*/, const std::string& path) {
    if (!s_loaded || !s_gameLoaded || !s_retro_unserialize) return false;
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
    if (!s_retro_unserialize(buf.data(), sz)) { LOGE("retro_unserialize failed"); return false; }
    return true;
}

void setSurface(void* nativeWindow) {
    coreshared::setSurface(s_window, s_windowMtx, nativeWindow);
    if (nativeWindow) {
        if (s_highQualityScaling.load(std::memory_order_relaxed)) {
            ANativeWindow_setBuffersGeometry(s_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
        }
        LOGI("Surface attached (pixelFormat=%u, hqScaling=%d)",
             s_pixelFormat, s_highQualityScaling.load() ? 1 : 0);
    } else {
        LOGI("Surface detached");
    }
}

void setHighQualityScaling(bool enabled) {
    s_highQualityScaling.store(enabled, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lk(s_windowMtx);
    if (s_window) {
        if (enabled) {
            ANativeWindow_setBuffersGeometry(s_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
        } else {
            ANativeWindow_setBuffersGeometry(s_window, s_videoW, s_videoH, WINDOW_FORMAT_RGBA_8888);
        }
    }
    LOGI("High-quality scaling: %s", enabled ? "ON" : "OFF");
}

void setCoreOption(const std::string& key, const std::string& value) {
    {
        std::lock_guard<std::mutex> lk(s_optMtx);
        s_options[key] = value;
    }
    s_optionsChanged.store(true, std::memory_order_release);
    LOGI("Core option set: %s = %s", key.c_str(), value.c_str());
}

int videoWidth()  { return (int)s_videoW; }
int videoHeight() { return (int)s_videoH; }

void videoAspectRatio(int& num, int& den) {
    // PS2 default aspect is 4:3 (most games) — widescreen titles override
    // via SET_GEOMETRY which we don't track; 4:3 is the safe default.
    num = 4;
    den = 3;
}

void setVideoFilter(int filter) {
    s_videoFilter.store(filter, std::memory_order_relaxed);
    LOGI("Video filter set: %d", filter);
}

bool isCoreLoaded() {
    return s_coreLib != nullptr;
}

} // namespace ps2core::rom
