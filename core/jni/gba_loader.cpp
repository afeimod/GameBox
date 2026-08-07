// SPDX-License-Identifier: MIT
// libretro frontend that drives the mGBA core (GB/GBC/GBA).
//
// Features:
//   * Hardware-accelerated rendering via ANativeWindow (SurfaceView)
//   * Core options variable system (GB model, colors, frameskip, audio, etc.)
//   * Dynamic ARGB frame buffer (160x144 for GB/GBC, 240x160 for GBA)
//   * Stereo audio ring buffer (shared implementation)
//   * Controller state (10 buttons incl. GBA L/R) + save-state serialization
//   * Pixel format conversion (XRGB8888 / RGB565 / 0RGB1555 -> ARGB)
//
// Shared filter/blit/audio code comes from shared/core_shared.h — this file
// does NOT re-implement blitToSurface, XBR, HQX, or the audio ring buffer.
//
// All retro_* calls happen on a single emulation thread (see GbaEngine in
// Kotlin), so no extra internal locking is needed around the core itself.
// The frame and audio buffers are mutex-guarded because the UI / AudioTrack
// threads read them concurrently.

#include "gba_loader.h"
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

#define TAG "gbacore-rom"
// core_shared.h already defines LOGI/LOGW/LOGE with tag "core-shared".
// Redefine them with our specific tag for better log filtering.
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace gbacore::rom {

// Maximum video resolution (GBA: 240x160). GB/GBC is 160x144.
// Filter buffers are sized for this maximum so they work for all modes.
static constexpr int kMaxW = 240;
static constexpr int kMaxH = 160;

// Android's native audio sample rate. All emulator cores should resample
// their output to this rate before sending to AudioTrack. This matches the
// mGBA Android reference project which uses 48000 Hz for Oboe output.
// Using the core's native rate (e.g. 32768 Hz for GBA) directly with
// AudioTrack causes poor-quality resampling in AudioFlinger, leading to
// pitch errors, crackling, and muffled audio.
static constexpr int TARGET_SAMPLE_RATE = 48000;

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
static bool s_loaded = false;
static int  s_sampleRate = 0;
static int  s_region = 0;
static std::string s_systemDir;
static std::string s_saveDir;
static std::string s_coreMessage;

// Dynamic frame buffer (ARGB, 0xAARRGGBB). Written by cb_video, read by
// copyFramebufferARGB. Also used as the source for ANativeWindow blitting.
// Uses std::vector because resolution changes between GB/GBC (160x144) and
// GBA (240x160).
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static unsigned s_frameW = 0;  // current frame buffer width
static unsigned s_frameH = 0;  // current frame buffer height
static std::atomic<bool> s_newFrame{false};

// Current video dimensions from the core.
static unsigned s_videoW = 0;
static unsigned s_videoH = 0;

// Pixel format requested by the core via SET_PIXEL_FORMAT.
// Default is 0RGB1555 (libretro default); mGBA typically requests XRGB8888.
static unsigned s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

// Controller bits (see setControllerInput layout).
// Uses uint16_t to accommodate GBA's 10-button layout (incl. L/R).
static std::atomic<uint16_t> s_pad1{0};
static std::atomic<uint16_t> s_pad2{0};

// Video filter type:
//   0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x, 7=xbr+dot,
//   8=4xbr, 9=4xbr+dot, 10=hq4x+dot
static std::atomic<int> s_videoFilter{0};

// 2x upscale buffer for XBR/HQ2X (max 240x160 -> 480x320)
static uint32_t s_xbrBuffer2x[kMaxW * 2 * kMaxH * 2];

// Intermediate buffer for 4xBR cascade pass 1 (max 240x160 -> 480x320)
static uint32_t s_xbrMidBuffer[kMaxW * 2 * kMaxH * 2];

// 4x upscale buffer for HQ4X/4xBR (max 240x160 -> 960x640)
static uint32_t s_xbrBuffer4x[kMaxW * 4 * kMaxH * 4];

// Audio ring buffer: interleaved stereo int16 samples (shared implementation).
static coreshared::AudioRingBuffer s_audio;

// ---------------------------------------------------------------------------
// Streaming audio resampler: converts from the core's native sample rate
// (e.g. 32768 Hz for GBA, 32768 Hz for GB/GBC) to Android's 48000 Hz.
//
// Uses linear interpolation, which is sufficient for GBA's 8-bit/4-bit audio
// source material. The resampler maintains state between calls so it can
// process partial frames and maintain continuity.
//
// Without this resampler, AudioTrack is created at 32768 Hz and Android's
// AudioFlinger performs low-quality resampling to 48000 Hz internally,
// causing pitch errors and audio artifacts.
// ---------------------------------------------------------------------------
static constexpr int RESAMPLER_SRC_BUF_SIZE = 4096; // Max source frames per resample pass

struct AudioResampler {
    double ratio;           // srcRate / dstRate (e.g. 32768/48000 ≈ 0.68267)
    double pos;            // Fractional position in source buffer
    int    srcRate;        // Source sample rate
    int    dstRate;        // Destination sample rate (TARGET_SAMPLE_RATE)
    int16_t prevL, prevR;  // Previous output sample for continuity at buffer edges
    bool   active;         // true if resampling is needed (srcRate != dstRate)

    // Internal source sample buffer - stores unconsumed samples between calls
    int    srcBufCount;                       // Number of valid frames in srcBuf
    double srcBufPos;                         // Fractional read position in srcBuf
    int16_t srcBuf[RESAMPLER_SRC_BUF_SIZE * 2]; // Interleaved stereo

    void init(int sourceRate, int destRate = TARGET_SAMPLE_RATE) {
        srcRate = sourceRate > 0 ? sourceRate : 32768;
        dstRate = destRate;
        ratio = (double)srcRate / dstRate;
        active = (srcRate != dstRate);
        reset();
    }

    void reset() {
        pos = 0.0;
        prevL = 0;
        prevR = 0;
        srcBufCount = 0;
        srcBufPos = 0.0;
    }

    // Produce up to maxFrames output frames at dstRate by pulling source
    // frames from the AudioRingBuffer and resampling.
    int readResampled(coreshared::AudioRingBuffer& audio, int16_t* out, int maxFrames) {
        if (!active) {
            // No resampling needed - pass through directly
            return audio.read(out, maxFrames);
        }

        int produced = 0;

        while (produced < maxFrames) {
            // Refill internal source buffer when we've consumed most of it
            // Keep at least 2 frames for interpolation
            int remaining = srcBufCount - (int)srcBufPos;
            if (remaining < 2) {
                // Shift unconsumed samples to the beginning
                if (remaining > 0 && (int)srcBufPos > 0) {
                    memmove(srcBuf, srcBuf + (int)srcBufPos * 2,
                            remaining * 2 * sizeof(int16_t));
                }

                // Read more source frames from the ring buffer
                int toRead = RESAMPLER_SRC_BUF_SIZE - remaining;
                int got = audio.read(srcBuf + remaining * 2, toRead);
                srcBufCount = remaining + got;
                srcBufPos = 0.0;

                if (srcBufCount < 2) {
                    // Not enough source samples for interpolation
                    // Fill remaining output with zeros (underrun)
                    for (int i = produced; i < maxFrames; i++) {
                        out[i * 2]     = 0;
                        out[i * 2 + 1] = 0;
                    }
                    return produced;
                }
            }

            // Linear interpolation at fractional position
            int idx = (int)srcBufPos;
            double frac = srcBufPos - idx;

            // Clamp to prevent out-of-bounds access
            if (idx + 1 >= srcBufCount) {
                // Use previous samples for edge case
                out[produced * 2]     = prevL;
                out[produced * 2 + 1] = prevR;
            } else {
                int16_t l0 = srcBuf[idx * 2];
                int16_t r0 = srcBuf[idx * 2 + 1];
                int16_t l1 = srcBuf[(idx + 1) * 2];
                int16_t r1 = srcBuf[(idx + 1) * 2 + 1];

                out[produced * 2]     = (int16_t)(l0 + (l1 - l0) * frac);
                out[produced * 2 + 1] = (int16_t)(r0 + (r1 - r0) * frac);
            }

            prevL = out[produced * 2];
            prevR = out[produced * 2 + 1];
            produced++;

            // Advance source position by ratio
            // Each output sample at dstRate corresponds to ratio source samples
            srcBufPos += ratio;
        }

        return produced;
    }
};

static AudioResampler s_resampler;

// ---------------------------------------------------------------------------
// ANativeWindow — hardware-accelerated direct surface rendering
// ---------------------------------------------------------------------------
static ANativeWindow* s_window = nullptr;
static std::mutex s_windowMtx;

// Fast-forward: when true, skip most surface blits to prevent
// ANativeWindow_lock from blocking the emulation thread.
static std::atomic<bool> s_fastForward{false};
static std::atomic<int>  s_ffFrameSkip{0};
static std::atomic<int>  s_ffMaxSkip{6};

// ---------------------------------------------------------------------------
// Core options — key/value map served to the core via GET_VARIABLE
// ---------------------------------------------------------------------------
static std::mutex s_optMtx;
static std::map<std::string, std::string> s_options;
static std::atomic<bool> s_optionsChanged{false};

// Initialize mGBA core options with sensible defaults.
// Values MUST match mGBA's libretro_core_options.h defaults.
static void initDefaultOptions() {
    // --- Solar sensor (Bokujou Monogatari / Harvest Moon) ---
    s_options["mgba_solar_sensor_level"]       = "0";

    // --- Input ---
    s_options["mgba_allow_opposite_directions"] = "OFF";

    // --- GB model selection ---
    s_options["mgba_gb_model"]                  = "Autodetect";

    // --- GB color correction ---
    s_options["mgba_gb_colors"]                 = "enabled";
    s_options["mgba_gb_colors_preset"]          = "default";

    // --- GBA color correction ---
    s_options["mgba_gba_colors"]                = "enabled";
    s_options["mgba_gba_colors_preset"]         = "default";

    // --- Video ---
    s_options["mgba_interframe_blending"]       = "OFF";

    // --- Frameskip ---
    s_options["mgba_frameskip"]                 = "0";
    s_options["mgba_frameskip_type"]            = "disabled";
    s_options["mgba_frameskip_threshold"]       = "33";

    // --- Audio ---
    // Enable the low-pass filter to smooth high-frequency aliasing artifacts
    // in GBA audio. The GBA's 8-bit/4-bit audio sources produce harsh
    // high-frequency content that benefits from gentle low-pass filtering.
    // This matches the mGBA Android reference project behavior.
    // The low-pass range of 40-60 is a good balance between clarity and
    // smoothness for GBA audio.
    s_options["mgba_audio_low_pass_filter"]          = "enabled";
    s_options["mgba_audio_low_pass_range"]            = "50";

    // --- GBA RTC ---
    s_options["mgba_gba_forceRTC"]              = "disabled";

    // --- GBA idle optimization ---
    s_options["mgba_gba_idle_optimization"]     = "disabled";

    // --- Super Game Boy borders ---
    s_options["mgba_sgb_borders"]               = "ON";
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
    __android_log_vprint(prio, "mgba", fmt, ap);
    va_end(ap);
}

static bool cb_environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (data) *static_cast<bool*>(data) = true;
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            // Accept the core's pixel format request and store it for
            // conversion in cb_video. mGBA typically requests XRGB8888
            // (compiled without COLOR_16_BIT). We always convert to ARGB
            // in cb_video and blit via RGBA_8888 surface — never change
            // the surface format here.
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

        // The core registers these; we accept.
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

        // Handle core options version query so mGBA uses the new API path.
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (data) *static_cast<unsigned*>(data) = 1;
            return true;

        // Geometry / AV info changes
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            return true;

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            // mGBA may call this to change the sample rate mid-game.
            // Update our stored sample rate and reinitialize the resampler.
            if (data) {
                auto* av = static_cast<const retro_system_av_info*>(data);
                int newRate = (int)av->timing.sample_rate;
                if (newRate > 0 && newRate != s_sampleRate) {
                    LOGI("Sample rate changed: %d -> %d, reinitializing resampler",
                         s_sampleRate, newRate);
                    s_sampleRate = newRate;
                    s_resampler.init(s_sampleRate, TARGET_SAMPLE_RATE);
                }
            }
            return true;
        }

        // Tell the core we want both audio and video enabled.
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *static_cast<int*>(data) = 3;
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

        // mGBA uses filestream_open() which falls back to the built-in VFS
        // implementation when no VFS interface is provided. This works on
        // Android for standard filesystem paths.
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            return false;

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return false; // we answer per-button input_state queries

        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
            return false;

        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            // mGBA may request the frontend language for ROM patching.
            if (data) {
                *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            }
            return true;

        default:
            return false;
    }
}

// Convert and store the incoming video frame into the internal ARGB buffer.
// Handles XRGB8888, RGB565, and 0RGB1555 pixel formats, normalizing all to
// 0xFFRRGGBB (opaque ARGB). Uses proper bit expansion for accurate colors.
static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return; // duplicate frame / no data this frame

    s_videoW = width;
    s_videoH = height;

    // Convert source to ARGB and store in s_frame (for fallback rendering,
    // screenshots, and filter upscaling).
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);

        // Resize frame buffer if dimensions changed
        const size_t need = (size_t)width * height;
        if (s_frameW != width || s_frameH != height || s_frame.size() < need) {
            s_frame.resize(need);
            s_frameW = width;
            s_frameH = height;
        }

        if (s_pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
            // mGBA outputs XBGR8 (R in bits 0-7, G in bits 8-15, B in bits 16-23)
            // despite declaring RETRO_PIXEL_FORMAT_XRGB8888. This is because mGBA's
            // mColor type in 32-bit mode uses M_COLOR_RED=0x000000FF, M_COLOR_BLUE=0x00FF0000.
            // Swap R and B with a single mask-and-shift (3 ops per pixel).
            const uint32_t* src = static_cast<const uint32_t*>(data);
            const size_t stride = pitch / sizeof(uint32_t);
            for (unsigned y = 0; y < height; ++y) {
                const uint32_t* srow = src + y * stride;
                uint32_t* drow = s_frame.data() + y * width;
                for (unsigned x = 0; x < width; ++x) {
                    uint32_t px = srow[x];
                    drow[x] = 0xFF000000u |
                              ((px & 0x0000FF) << 16) |
                              (px & 0x00FF00) |
                              ((px & 0xFF0000) >> 16);
                }
            }
        } else if (s_pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
            // RGB565: rrrrrggggggbbbbb -> ARGB 0xFFRRGGBB
            // Proper bit expansion: (v << 3) | (v >> 2) for 5-bit,
            // (v << 2) | (v >> 4) for 6-bit.
            const uint16_t* src = static_cast<const uint16_t*>(data);
            const size_t stride = pitch / sizeof(uint16_t);
            for (unsigned y = 0; y < height; ++y) {
                const uint16_t* srow = src + y * stride;
                uint32_t* drow = s_frame.data() + y * width;
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t px = srow[x];
                    uint32_t r5 = (px >> 11) & 0x1F;
                    uint32_t g6 = (px >> 5)  & 0x3F;
                    uint32_t b5 = px & 0x1F;
                    uint32_t r = (r5 << 3) | (r5 >> 2);
                    uint32_t g = (g6 << 2) | (g6 >> 4);
                    uint32_t b = (b5 << 3) | (b5 >> 2);
                    drow[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
                }
            }
        } else {
            // 0RGB1555: 0rrrrrgggggbbbbb -> ARGB 0xFFRRGGBB
            const uint16_t* src = static_cast<const uint16_t*>(data);
            const size_t stride = pitch / sizeof(uint16_t);
            for (unsigned y = 0; y < height; ++y) {
                const uint16_t* srow = src + y * stride;
                uint32_t* drow = s_frame.data() + y * width;
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t px = srow[x];
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

    // During fast-forward, skip most blits to prevent ANativeWindow_lock
    // from blocking the emulation thread. Only blit every 6th frame so the
    // user can still see the game. ARGB conversion above still runs every
    // frame (for screenshots).
    if (s_fastForward.load(std::memory_order_relaxed)) {
        int skip = s_ffMaxSkip.load(std::memory_order_relaxed);
        if (skip > 0 && s_ffFrameSkip.fetch_add(1, std::memory_order_relaxed) % skip != 0)
            return;
    } else {
        s_ffFrameSkip.store(0, std::memory_order_relaxed);
    }

    const int filter = s_videoFilter.load(std::memory_order_relaxed);

    // Always use the ARGB blit path. The cb_video function above already
    // converted the core's native pixel format (XRGB8888/RGB565/0RGB1555)
    // to ARGB 0xFFRRGGBB in s_frame. The surface is always RGBA_8888.
    coreshared::applyFilterAndBlit(
        s_window, s_windowMtx,
        s_frame.data(), width, height, width,
        filter,
        s_xbrBuffer2x, s_xbrBuffer4x, s_xbrMidBuffer,
        (unsigned)kMaxW, (unsigned)kMaxH);
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
    const uint16_t bits = (port == 0) ? s_pad1.load(std::memory_order_relaxed)
                                      : (port == 1) ? s_pad2.load(std::memory_order_relaxed)
                                                    : 0;
    // Bit layout:
    //   bit0=A, bit1=B, bit2=Select, bit3=Start,
    //   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
    //   bit8=L (GBA), bit9=R (GBA)
    // GB/GBC only queries buttons 0-7 (A/B/Select/Start/DPad).
    // GBA queries all 10 (adds L/R). The extra bits are simply zero for
    // GB/GBC since the Kotlin side never sets them.
    switch (id) {
        case RETRO_DEVICE_ID_JOYPAD_A:      return (bits >> 0) & 1;
        case RETRO_DEVICE_ID_JOYPAD_B:      return (bits >> 1) & 1;
        case RETRO_DEVICE_ID_JOYPAD_SELECT: return (bits >> 2) & 1;
        case RETRO_DEVICE_ID_JOYPAD_START:  return (bits >> 3) & 1;
        case RETRO_DEVICE_ID_JOYPAD_UP:     return (bits >> 4) & 1;
        case RETRO_DEVICE_ID_JOYPAD_DOWN:   return (bits >> 5) & 1;
        case RETRO_DEVICE_ID_JOYPAD_LEFT:   return (bits >> 6) & 1;
        case RETRO_DEVICE_ID_JOYPAD_RIGHT:  return (bits >> 7) & 1;
        case RETRO_DEVICE_ID_JOYPAD_L:      return (bits >> 8) & 1;
        case RETRO_DEVICE_ID_JOYPAD_R:      return (bits >> 9) & 1;
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
        auto saved = s_options;
        initDefaultOptions();
        for (auto& [k, v] : saved) {
            s_options[k] = v;
        }
    }
    s_optionsChanged.store(true, std::memory_order_release);

    // Reset pixel format to libretro default before init.
    s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

    // CRITICAL: retro_set_environment MUST be called before retro_init().
    retro_set_environment(cb_environment);
    retro_init();
    retro_set_video_refresh(cb_video);
    retro_set_audio_sample(cb_audio_sample);
    retro_set_audio_sample_batch(cb_audio_batch);
    retro_set_input_poll(cb_input_poll);
    retro_set_input_state(cb_input_state);

    // mGBA accepts both path and data. Provide both so the core can use
    // whichever mode it prefers.
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
        if (!s_coreMessage.empty()) {
            std::string err = s_coreMessage;
            s_coreMessage.clear();
            return err;
        }
        return "mGBA rejected the ROM (unsupported or corrupt file)";
    }
    s_loaded = true;
    LOGI("retro_load_game SUCCEEDED for: %s", path.c_str());

    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);

    struct retro_system_av_info av;
    retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    s_region = (av.timing.fps < 55.0) ? 1 : 0;
    regionOut = s_region;
    s_videoW = av.geometry.base_width;
    s_videoH = av.geometry.base_height;

    s_audio.reset();
    s_newFrame.store(false);

    // Initialize the resampler from the core's native rate to Android's 48000 Hz.
    // This replaces the previous approach of passing 32768 Hz directly to
    // AudioTrack, which caused poor-quality resampling in AudioFlinger.
    s_resampler.init(s_sampleRate, TARGET_SAMPLE_RATE);
    LOGI("Audio resampler: %d Hz -> %d Hz (ratio=%.6f)",
         s_sampleRate, TARGET_SAMPLE_RATE, s_resampler.ratio);

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
    s_resampler.reset();
    s_newFrame.store(false);
    s_videoW = 0;
    s_videoH = 0;
    s_frameW = 0;
    s_frameH = 0;
    s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
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
    if (!s_loaded || s_frameW == 0 || s_frameH == 0) {
        std::memset(out, 0, (size_t)w * h * sizeof(uint32_t));
        return false;
    }
    std::lock_guard<std::mutex> lk(s_frameMtx);
    // w/h are the destination dimensions; copy from s_frame (s_frameW x s_frameH).
    // If the source is smaller (e.g. GB 160x144 into a 240x160 dest), the
    // remaining area is left as zeros (cleared below).
    const int cw = (w < (int)s_frameW) ? w : (int)s_frameW;
    const int ch = (h < (int)s_frameH) ? h : (int)s_frameH;
    std::memset(out, 0, (size_t)w * h * sizeof(uint32_t));
    for (int y = 0; y < ch; ++y) {
        std::memcpy(out + (size_t)y * w,
                    s_frame.data() + (size_t)y * s_frameW,
                    (size_t)cw * sizeof(uint32_t));
    }
    return s_newFrame.exchange(false, std::memory_order_acq_rel);
}

int readAudio(int16_t* out, int maxFrames) {
    return s_resampler.readResampled(s_audio, out, maxFrames);
}

int audioSampleRate() { return s_sampleRate; }

int audioTargetSampleRate() { return TARGET_SAMPLE_RATE; }

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
void applySpeed(float multiplier) {
    s_fastForward.store(multiplier > 1.0f, std::memory_order_relaxed);
    // Frame skip: higher speed = skip more frames between renders.
    // For 2x: skip 1, render 1 (every 2nd frame)
    // For 4x: skip 3, render 1 (every 4th frame)
    // For 6x: skip 5, render 1 (every 6th frame)
    // For 8x: skip 7, render 1 (every 8th frame)
    s_ffMaxSkip.store((int)multiplier, std::memory_order_relaxed);
    s_ffFrameSkip.store(0, std::memory_order_relaxed);
}

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
    // Lock to prevent race with video callback
    std::lock_guard<std::mutex> lk(s_windowMtx);

    if (s_window) {
        ANativeWindow_release(s_window);
        s_window = nullptr;
    }
    if (nativeWindow) {
        s_window = static_cast<ANativeWindow*>(nativeWindow);
        ANativeWindow_acquire(s_window);
        ANativeWindow_setBuffersGeometry(s_window, 0, 0, WINDOW_FORMAT_RGBA_8888);

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
    // Compute from current video dimensions.
    // GB/GBC: 160:144 -> 10:9
    // GBA:    240:160 -> 3:2
    if (s_videoH == 0 || s_videoW == 0) {
        num = 4; den = 3;
        return;
    }
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

    LOGI("Video filter set: %d (0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x, 7=xbr+dot)", filter);
}

} // namespace gbacore::rom
