// SPDX-License-Identifier: MIT
// libretro frontend that drives the prebuilt DOSBox-Pure core.
//
// This loader is unique among the GameBox cores: instead of statically linking
// the core source (like FCEUmm / snes9x / mGBA), it dlopen()s the prebuilt
// libdosbox_pure_libretro_android.so at runtime and resolves the retro_*
// symbols via dlsym(). The .so file ships in app/src/main/jniLibs/<abi>/.
//
// The libretro API surface we need to resolve:
//   retro_init, retro_deinit, retro_load_game, retro_unload_game, retro_run,
//   retro_reset, retro_get_system_info, retro_get_system_av_info,
//   retro_set_environment, retro_set_video_refresh, retro_set_audio_sample,
//   retro_set_audio_sample_batch, retro_set_input_poll, retro_set_input_state,
//   retro_set_controller_port_device, retro_serialize_size, retro_serialize,
//   retro_unserialize, retro_get_memory_size, retro_get_memory_data
//
// Video resolution is dynamic (DOS supports 320x200, 640x400, 640x480,
// 800x600, 1024x768, etc.). The frame buffer uses a std::vector that resizes
// to the largest seen resolution. Filter buffers are sized to 1024x768 max.
//
// Audio: DOSBox-Pure typically outputs at 44100 Hz or 48000 Hz; the resampler
// converts to Android's 48000 Hz.
//
// Input: DOSBox-Pure supports three device types on each port:
//   - RETRO_DEVICE_JOYPAD (default — auto-mapped gamepad → DOS keys)
//   - RETRO_DEVICE_KEYBOARD (full IBM PC keyboard)
//   - RETRO_DEVICE_MOUSE (relative mouse with buttons + wheel)
// We register all three on port 0 and dispatch input events accordingly.
//
// All retro_* calls happen on a single emulation thread (see DosEngine in
// Kotlin), so no extra internal locking is needed around the core itself.

#include "dos_loader.h"
#include "shared/core_shared.h"

#include <libretro.h>
#include <android/log.h>
#include <android/native_window.h>

#include <dlfcn.h>
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

#define TAG "doscore-rom"
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace doscore::rom {

// ---------------------------------------------------------------------------
// Maximum video resolution supported by DOSBox-Pure (SVGA 1024x768).
// Filter buffers are sized for this maximum so they work for all modes.
// ---------------------------------------------------------------------------
static constexpr int kMaxW = 1024;
static constexpr int kMaxH = 768;

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
static std::string s_systemDir;
static std::string s_saveDir;
static std::string s_saveName;
static std::string s_lastRomPath;
static std::string s_coreMessage;
static std::string s_coreError;
// Absolute path to libdosbox_pure_libretro_android.so (set by Kotlin via
// setCoreLibPath). When non-empty, dlopen uses this path; otherwise it falls
// back to the bare library name.
static std::string s_coreLibPath;

// Dynamic frame buffer (ARGB, 0xAARRGGBB). DOSBox-Pure may emit at any SVGA
// resolution up to 1024x768.
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static unsigned s_frameW = 0;
static unsigned s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

static unsigned s_videoW = 0;
static unsigned s_videoH = 0;
static unsigned s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

// Gamepad bits (port 0, RETRO_DEVICE_JOYPAD).
static std::atomic<uint16_t> s_pad1{0};

// Keyboard state — bitmask of currently-pressed RETROK_* codes.
// We use a std::set-like map for O(1) press/release toggle, but a simpler
// approach: keep a static bool array indexed by RETROK_* (max ~320 keys).
static constexpr int kKeyArraySize = 512;
static std::atomic<bool> s_keysDown[kKeyArraySize]{};

// Mouse state (port 0, RETRO_DEVICE_MOUSE).
static std::atomic<int>  s_mouseDX{0};
static std::atomic<int>  s_mouseDY{0};
// Mouse buttons: index 0=left, 1=right, 2=middle, 3=wheel_up, 4=wheel_down,
// 5=horiz_wheel_up, 6=horiz_wheel_down, 7=button_4, 8=button_5
static std::atomic<bool> s_mouseBtn[9]{};
static std::atomic<int>  s_inputDeviceMode{0};  // 0=joypad,1=kbd,2=mouse,3=all

// Keyboard event callback — registered by the core via
// RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK. DOSBox-Pure uses this callback
// (NOT input_state polling) to receive key press/release events. Without
// calling this callback, keyboard input never reaches the DOSBox keyboard
// handler, and keys appear "dead" even though s_keysDown[] is updated.
static retro_keyboard_event_t s_keyboardCallback = nullptr;

static std::atomic<int>  s_videoFilter{0};
static std::atomic<bool> s_highQualityScaling{false};
static std::atomic<bool> s_fastForward{false};
static std::atomic<int>  s_ffFrameSkip{0};
static std::atomic<int>  s_ffMaxSkip{6};

// 2x upscale buffer (max 1024x768 → 2048x1536) — too large for static alloc
// on 32-bit ABIs; allocate on heap on first use.
static uint32_t* s_xbrBuffer2x = nullptr;
static uint32_t* s_xbrMidBuffer = nullptr;
static uint32_t* s_xbrBuffer4x = nullptr;
static size_t    s_xbrBufferSize2x = 0;
static size_t    s_xbrBufferSize4x = 0;

static coreshared::AudioRingBuffer s_audio;
static coreshared::AudioResampler s_resampler;

static ANativeWindow* s_window = nullptr;
static std::mutex s_windowMtx;

static std::mutex s_optMtx;
static std::map<std::string, std::string> s_options;
static std::atomic<bool> s_optionsChanged{false};

// ---------------------------------------------------------------------------
// Initialize DOSBox-Pure core options with sensible defaults.
// Values MUST match dosbox_pure's retro_set_variables() defaults where possible.
// ---------------------------------------------------------------------------
static void initDefaultOptions() {
    s_options["dosbox_pure_machine"]            = "svga_s3";
    s_options["dosbox_pure_cycles"]             = "auto";
    s_options["dosbox_pure_cycles_max"]         = "50000";
    s_options["dosbox_pure_time_announce"]      = "none";
    s_options["dosbox_pure_sblaster_type"]      = "sb16";
    s_options["dosbox_pure_sblaster_adlib_mode"]= "off";
    s_options["dosbox_pure_sblaster_adlib_emu"] = "default";
    s_options["dosbox_pure_gus"]                = "off";
    s_options["dosbox_pure_mouse_input"]        = "emulated";
    s_options["dosbox_pure_mouse_timeout"]      = "off";
    s_options["dosbox_pure_keyboard_layout"]    = "us";
    s_options["dosbox_pure_keyboard_delay"]     = "300";
    s_options["dosbox_pure_keyboard_rate"]      = "10";
    s_options["dosbox_pure_auto_mapping"]       = "on";
    s_options["dosbox_pure_savestate"]          = "on";
    s_options["dosbox_pure_dim_screen"]         = "off";
    s_options["dosbox_pure_resolution"]         = "original";
    s_options["dosbox_pure_scale"]              = "2";
    s_options["dosbox_pure_aspect_ratio"]       = "auto";
    s_options["dosbox_pure_cga_colors"]         = "default";
    s_options["dosbox_pure_voodoo"]             = "off";
    s_options["dosbox_pure_force60fps"]         = "on";
}

// ---------------------------------------------------------------------------
// dlopen the core .so and resolve all retro_* symbols.
// Returns true on success, false on failure (s_coreError is set).
// ---------------------------------------------------------------------------
static bool loadCoreLib() {
    if (s_coreLib) return true;

    // Try in order:
    //   1. Absolute path (set by Kotlin via setCoreLibPath) — works on all APIs.
    //   2. Bare library name — works on Android 7.0+ (API 24+) and sometimes
    //      on API 23 depending on the device.
    std::vector<std::string> candidates;
    if (!s_coreLibPath.empty()) candidates.push_back(s_coreLibPath);
    candidates.push_back("libdosbox_pure_libretro_android.so");

    for (const auto& name : candidates) {
        s_coreLib = dlopen(name.c_str(), RTLD_NOW);
        if (s_coreLib) {
            LOGI("dlopen(%s) OK", name.c_str());
            break;
        } else {
            LOGW("dlopen(%s) failed: %s", name.c_str(), dlerror());
        }
    }

    if (!s_coreLib) {
        s_coreError = "dlopen(libdosbox_pure_libretro_android.so) failed: ";
        s_coreError += dlerror();
        LOGE("%s", s_coreError.c_str());
        return false;
    }

    LOGI("dlopen(libdosbox_pure_libretro_android.so) OK");

    #define RESOLVE(name) \
        s_##name = reinterpret_cast<name##_t>(dlsym(s_coreLib, #name)); \
        if (!s_##name) { \
            s_coreError = "dlsym(" #name ") failed: "; \
            s_coreError += dlerror(); \
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

    // Optional — not all cores expose these
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
    __android_log_vprint(prio, "dosbox_pure", fmt, ap);
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
                LOGI("Pixel format set: %u", s_pixelFormat);
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
#ifdef RETRO_ENVIRONMENT_SET_CORE_OPTIONS
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
#endif
#ifdef RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
#endif
#ifdef RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
#endif
#ifdef RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
#endif
#ifdef RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
#endif
            return true;

        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (data) *static_cast<unsigned*>(data) = 1;
            return true;

        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            return true;

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            if (data) {
                auto* av = static_cast<const retro_system_av_info*>(data);
                int newRate = (int)av->timing.sample_rate;
                if (newRate > 0 && newRate != s_sampleRate) {
                    LOGI("Sample rate changed: %d -> %d", s_sampleRate, newRate);
                    s_sampleRate = newRate;
                    s_resampler.init(s_sampleRate, TARGET_SAMPLE_RATE);
                }
            }
            return true;
        }

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

        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            if (data) *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;

        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK: {
            // DOSBox-Pure registers a keyboard callback so it receives
            // key-down / key-up events. We store it and invoke it from
            // keyboardDown() / keyboardUp(). Without this, the on-screen
            // keyboard buttons update s_keysDown[] but DOSBox never sees
            // the key event — making all keys appear "dead".
            if (data) {
                auto* cb = static_cast<const retro_keyboard_callback*>(data);
                s_keyboardCallback = cb ? cb->callback : nullptr;
                LOGI("Keyboard callback registered: %p", s_keyboardCallback);
            }
            return true;
        }

        default:
            return false;
    }
}

// Convert source pixels to ARGB (0xFFRRGGBB) and store in s_frame.
static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;

    s_videoW = width;
    s_videoH = height;

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        const size_t need = (size_t)width * height;
        if (s_frameW != width || s_frameH != height || s_frame.size() < need) {
            s_frame.resize(need);
            s_frameW = width;
            s_frameH = height;
        }

        if (s_pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
            // dosbox_pure emits XRGB8888 (R in low byte). Convert to ARGB.
            const uint32_t* src = static_cast<const uint32_t*>(data);
            const size_t stride = pitch / sizeof(uint32_t);
            for (unsigned y = 0; y < height; ++y) {
                const uint32_t* srow = src + y * stride;
                uint32_t* drow = s_frame.data() + y * width;
                for (unsigned x = 0; x < width; ++x) {
                    drow[x] = srow[x] | 0xFF000000u;
                }
            }
        } else if (s_pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t* src = static_cast<const uint16_t*>(data);
            const size_t stride = pitch / sizeof(uint16_t);
            for (unsigned y = 0; y < height; ++y) {
                const uint16_t* srow = src + y * stride;
                uint32_t* drow = s_frame.data() + y * width;
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t px = srow[x];
                    uint8_t r = (px >> 11) & 0x1F;
                    uint8_t g = (px >> 5)  & 0x3F;
                    uint8_t b =  px        & 0x1F;
                    drow[x] = 0xFF000000u
                            | ((r << 3) | (r >> 2)) << 16
                            | ((g << 2) | (g >> 4)) << 8
                            | ((b << 3) | (b >> 2));
                }
            }
        } else { // 0RGB1555
            const uint16_t* src = static_cast<const uint16_t*>(data);
            const size_t stride = pitch / sizeof(uint16_t);
            for (unsigned y = 0; y < height; ++y) {
                const uint16_t* srow = src + y * stride;
                uint32_t* drow = s_frame.data() + y * width;
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t px = srow[x];
                    uint8_t r = (px >> 10) & 0x1F;
                    uint8_t g = (px >> 5)  & 0x1F;
                    uint8_t b =  px        & 0x1F;
                    drow[x] = 0xFF000000u
                            | ((r << 3) | (r >> 2)) << 16
                            | ((g << 3) | (g >> 2)) << 8
                            | ((b << 3) | (b >> 2));
                }
            }
        }
    }

    s_newFrame.store(true, std::memory_order_release);

    // Blit directly to the surface if attached.
    if (s_window) {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        coreshared::blitToSurface(s_window, s_windowMtx,
            s_frame.data(), s_frameW, s_frameH, s_frameW,
            s_highQualityScaling.load());
    }
}

static void cb_audio_sample(int16_t left, int16_t right) {
    int16_t pair[2] = { left, right };
    s_audio.push(pair, 2);
}

static size_t cb_audio_sample_batch(const int16_t* data, size_t frames) {
    s_audio.push(data, frames * 2);
    return frames;
}

static void cb_input_poll(void) {
    // No-op — input state is pushed via setControllerInput / keyboardDown etc.
}

static int16_t cb_input_state(unsigned port, unsigned device,
                              unsigned index, unsigned id) {
    if (port != 0) return 0;

    switch (device) {
        case RETRO_DEVICE_JOYPAD: {
            uint16_t bits = s_pad1.load(std::memory_order_relaxed);
            if (id < 16) return (bits >> id) & 1;
            return 0;
        }
        case RETRO_DEVICE_KEYBOARD: {
            if (id < kKeyArraySize) {
                return s_keysDown[id].load(std::memory_order_relaxed) ? 1 : 0;
            }
            return 0;
        }
        case RETRO_DEVICE_MOUSE: {
            switch (id) {
                case RETRO_DEVICE_ID_MOUSE_X: {
                    int dx = s_mouseDX.exchange(0, std::memory_order_acq_rel);
                    return (int16_t)dx;
                }
                case RETRO_DEVICE_ID_MOUSE_Y: {
                    int dy = s_mouseDY.exchange(0, std::memory_order_acq_rel);
                    return (int16_t)dy;
                }
                case RETRO_DEVICE_ID_MOUSE_LEFT:    return s_mouseBtn[0].load() ? 1 : 0;
                case RETRO_DEVICE_ID_MOUSE_RIGHT:   return s_mouseBtn[1].load() ? 1 : 0;
                case RETRO_DEVICE_ID_MOUSE_MIDDLE:  return s_mouseBtn[2].load() ? 1 : 0;
                case RETRO_DEVICE_ID_MOUSE_WHEELUP:   return s_mouseBtn[3].load() ? 1 : 0;
                case RETRO_DEVICE_ID_MOUSE_WHEELDOWN: return s_mouseBtn[4].load() ? 1 : 0;
                case RETRO_DEVICE_ID_MOUSE_HORIZ_WHEELUP:   return s_mouseBtn[5].load() ? 1 : 0;
                case RETRO_DEVICE_ID_MOUSE_HORIZ_WHEELDOWN: return s_mouseBtn[6].load() ? 1 : 0;
                case RETRO_DEVICE_ID_MOUSE_BUTTON_4: return s_mouseBtn[7].load() ? 1 : 0;
                case RETRO_DEVICE_ID_MOUSE_BUTTON_5: return s_mouseBtn[8].load() ? 1 : 0;
                default: return 0;
            }
        }
        case RETRO_DEVICE_ANALOG: {
            // We don't map analog sticks to anything — dosbox_pure queries
            // RETRO_DEVICE_MOUSE for mouse movement (handled above).
            return 0;
        }
        default:
            return 0;
    }
}

// ---------------------------------------------------------------------------
// Apply input device mode: tell libretro which device types we want on port 0.
// ---------------------------------------------------------------------------
static void applyInputDeviceMode(int mode) {
    if (!s_retro_set_controller_port_device) return;

    // DOSBox-Pure handles keyboard input via the retro_keyboard_event
    // callback (registered through RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK)
    // and mouse input via input_state polling on RETRO_DEVICE_MOUSE.
    //
    // The port device registration only affects which device type the core
    // treats as the "primary" input. For combined mode (3), we register
    // JOYPAD so the auto-mapped gamepad works, but keyboard and mouse
    // still function through their respective mechanisms.
    //
    // IMPORTANT: We must NOT call set_controller_port_device(0, KEYBOARD)
    // for combined mode — doing so would disable the gamepad. The keyboard
    // callback works regardless of the registered port device.
    if (mode == 1) {
        // Keyboard-only mode — disable gamepad, enable keyboard.
        s_retro_set_controller_port_device(0, RETRO_DEVICE_KEYBOARD);
    } else if (mode == 2) {
        // Mouse-only mode — disable gamepad, enable mouse.
        s_retro_set_controller_port_device(0, RETRO_DEVICE_MOUSE);
    } else {
        // Mode 0 (default) and mode 3 (combined) — JOYPAD as primary.
        // Keyboard and mouse work through their own callback/polling paths.
        s_retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    }
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    regionOut = 0;

    if (!loadCoreLib()) {
        return s_coreError.empty() ? "Failed to load libdosbox_pure_libretro_android.so" : s_coreError;
    }

    if (!s_loaded) {
        initDefaultOptions();

        s_retro_set_environment(cb_environment);
        s_retro_set_video_refresh(cb_video);
        s_retro_set_audio_sample(cb_audio_sample);
        s_retro_set_audio_sample_batch(cb_audio_sample_batch);
        s_retro_set_input_poll(cb_input_poll);
        s_retro_set_input_state(cb_input_state);

        s_retro_init();
        s_loaded = true;
        LOGI("DOSBox-Pure core initialized (API version %u)", s_retro_api_version());
    }

    if (s_gameLoaded) {
        s_retro_unload_game();
        s_gameLoaded = false;
    }

    // Reset audio buffer and resampler state so leftover samples from a
    // previous game session don't bleed into the new one.
    s_audio.reset();
    s_resampler.reset();

    // DOSBox-Pure accepts file paths directly. For folders, the path should
    // be the folder itself (the core auto-detects the launcher).
    retro_game_info gameInfo{};
    gameInfo.path = path.c_str();
    gameInfo.data = nullptr;
    gameInfo.size = 0;
    gameInfo.meta = nullptr;

    // Determine whether to use full path or needs data. DOSBox-Pure supports
    // path-based loading (it has its own VFS), so we pass nullptr for data.
    bool ok = s_retro_load_game(&gameInfo);
    if (!ok) {
        // Try loading with data buffer for .bat / .exe / .com files.
        FILE* fp = fopen(path.c_str(), "rb");
        if (fp) {
            fseek(fp, 0, SEEK_END);
            long sz = ftell(fp);
            fseek(fp, 0, SEEK_SET);
            if (sz > 0 && sz < 64 * 1024 * 1024) {  // 64MB max
                std::vector<uint8_t> buf(sz);
                size_t rd = fread(buf.data(), 1, sz, fp);
                fclose(fp);
                if (rd == (size_t)sz) {
                    gameInfo.data = buf.data();
                    gameInfo.size = sz;
                    ok = s_retro_load_game(&gameInfo);
                }
            } else {
                fclose(fp);
            }
        }
    }

    if (!ok) {
        s_coreError = "retro_load_game() failed for: " + path;
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }

    s_gameLoaded = true;
    s_lastRomPath = path;

    retro_system_av_info av{};
    s_retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    if (s_sampleRate > 0) {
        s_resampler.init(s_sampleRate, TARGET_SAMPLE_RATE);
    }
    s_videoW = av.geometry.base_width;
    s_videoH = av.geometry.base_height;

    // Apply current input device mode
    applyInputDeviceMode(s_inputDeviceMode.load());

    LOGI("DOS game loaded: %s (rate=%d, %ux%u, max=%ux%u)",
         path.c_str(), s_sampleRate, s_videoW, s_videoH,
         av.geometry.max_width, av.geometry.max_height);

    return "";
}

void unload() {
    if (!s_loaded) return;
    if (s_gameLoaded) {
        s_retro_unload_game();
        s_gameLoaded = false;
    }
}

void resetEmulation(bool /*hard*/) {
    if (s_loaded && s_gameLoaded) s_retro_reset();
}

void stepFrame() {
    if (!s_loaded || !s_gameLoaded) return;

    // Fast-forward skip-blit logic
    if (s_fastForward.load()) {
        int skip = s_ffFrameSkip.fetch_add(1);
        if (skip >= s_ffMaxSkip.load()) {
            s_ffFrameSkip.store(0);
        }
    }

    s_retro_run();
}

bool copyFramebufferARGB(uint32_t* out, int w, int h) {
    if (!s_loaded || !s_gameLoaded) return false;
    std::lock_guard<std::mutex> lk(s_frameMtx);
    if (s_frame.empty()) return false;
    int srcW = (int)s_frameW;
    int srcH = (int)s_frameH;
    if (srcW <= 0 || srcH <= 0) return false;
    int copyW = std::min(w, srcW);
    int copyH = std::min(h, srcH);
    for (int y = 0; y < copyH; ++y) {
        memcpy(out + y * w, s_frame.data() + y * srcW, copyW * sizeof(uint32_t));
    }
    bool fresh = s_newFrame.exchange(false, std::memory_order_acq_rel);
    return fresh;
}

int readAudio(int16_t* out, int maxFrames) {
    if (!s_loaded || !s_gameLoaded) return 0;
    // readResampled handles both the resampling path and the passthrough
    // (no-resampling) path internally, matching the GBA/SNES core pattern.
    return s_resampler.readResampled(s_audio, out, maxFrames);
}

int audioSampleRate() {
    return s_sampleRate;
}

int audioTargetSampleRate() {
    return TARGET_SAMPLE_RATE;
}

void setControllerInput(int port, uint16_t bits) {
    if (port == 0) s_pad1.store(bits, std::memory_order_relaxed);
}

void keyboardDown(int keyCode, int /*modifiers*/) {
    if (keyCode >= 0 && keyCode < kKeyArraySize) {
        s_keysDown[keyCode].store(true, std::memory_order_relaxed);
    }
    // Forward the key-down event to the core's keyboard callback.
    // DOSBox-Pure relies on this callback (not input_state polling) to
    // receive key press events. Without this call, keys are dead.
    if (s_keyboardCallback) {
        s_keyboardCallback(true, (unsigned)keyCode, 0, (uint16_t)0);
    }
}

void keyboardUp(int keyCode, int /*modifiers*/) {
    if (keyCode >= 0 && keyCode < kKeyArraySize) {
        s_keysDown[keyCode].store(false, std::memory_order_relaxed);
    }
    // Forward the key-up event to the core's keyboard callback.
    if (s_keyboardCallback) {
        s_keyboardCallback(false, (unsigned)keyCode, 0, (uint16_t)0);
    }
}

void mouseMove(int dx, int dy) {
    s_mouseDX.fetch_add(dx, std::memory_order_acq_rel);
    s_mouseDY.fetch_add(dy, std::memory_order_acq_rel);
}

void mouseButton(int button, bool pressed) {
    if (button >= 0 && button < 9) {
        s_mouseBtn[button].store(pressed, std::memory_order_relaxed);
    }
}

void setInputDeviceMode(int mode) {
    s_inputDeviceMode.store(mode, std::memory_order_relaxed);
    if (s_loaded && s_gameLoaded) applyInputDeviceMode(mode);
}

void setPaths(const std::string& systemDir, const std::string& saveDir) {
    s_systemDir = systemDir;
    s_saveDir = saveDir;
}

void setSaveName(const std::string& name) {
    s_saveName = name;
}

// Set the absolute path to libdosbox_pure_libretro_android.so — used by dlopen.
void setCoreLibPath(const std::string& path) {
    s_coreLibPath = path;
    LOGI("Core lib path set: %s", s_coreLibPath.c_str());
}

void applyRegion(int /*region*/) {
    // DOS has no region concept — no-op.
}

void applySampleRate(int /*hz*/) {
    // Sample rate is fixed by the core at load time — no-op.
}

void applySpeed(float multiplier) {
    // DOSBox-Pure manages its own timing via cycles; we just toggle fast-forward.
    s_fastForward.store(multiplier > 1.0f, std::memory_order_relaxed);
}

void saveStateToPath(int slot, const std::string& path) {
    if (!s_loaded || !s_gameLoaded || !s_retro_serialize) return;
    size_t size = s_retro_serialize_size();
    if (size == 0) return;
    std::vector<uint8_t> buf(size);
    if (s_retro_serialize(buf.data(), size)) {
        FILE* fp = fopen(path.c_str(), "wb");
        if (fp) {
            fwrite(buf.data(), 1, size, fp);
            fclose(fp);
        }
    }
}

bool loadStateFromPath(int slot, const std::string& path) {
    if (!s_loaded || !s_gameLoaded || !s_retro_unserialize) return false;
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) return false;
    fseek(fp, 0, SEEK_END);
    long sz = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    if (sz <= 0) { fclose(fp); return false; }
    std::vector<uint8_t> buf(sz);
    size_t rd = fread(buf.data(), 1, sz, fp);
    fclose(fp);
    if (rd != (size_t)sz) return false;
    return s_retro_unserialize(buf.data(), sz);
}

void setSurface(void* nativeWindow) {
    std::lock_guard<std::mutex> lk(s_windowMtx);
    ANativeWindow* old = s_window;
    s_window = static_cast<ANativeWindow*>(nativeWindow);
    if (s_window) ANativeWindow_acquire(s_window);
    if (old) ANativeWindow_release(old);
    LOGI("Surface set: %p", s_window);
}

void setCoreOption(const std::string& key, const std::string& value) {
    {
        std::lock_guard<std::mutex> lk(s_optMtx);
        s_options[key] = value;
    }
    s_optionsChanged.store(true, std::memory_order_release);
}

int videoWidth() {
    return (int)s_videoW;
}

int videoHeight() {
    return (int)s_videoH;
}

void videoAspectRatio(int& num, int& den) {
    num = (int)s_videoW;
    den = (int)s_videoH;
    if (num <= 0 || den <= 0) { num = 4; den = 3; }
}

void setVideoFilter(int filter) {
    s_videoFilter.store(filter, std::memory_order_relaxed);
}

void setHighQualityScaling(bool enabled) {
    s_highQualityScaling.store(enabled, std::memory_order_relaxed);
}

bool isCoreLoaded() {
    return s_coreLib != nullptr;
}

} // namespace doscore::rom
