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

// FDS game detection (for logging only — the disk is auto-inserted
// by FDSInit() during PowerNES(), so no manual R button press is needed).
static std::atomic<bool> s_isFdsGame{false};

// Video filter type: 0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x, 7=xbr+dot
static std::atomic<int> s_videoFilter{0};

// 2x upscale buffer for XBR/HQ2X (256x240 → 512x480)
static uint32_t s_xbrBuffer[kNesW * 2 * kNesH * 2];

// 4x upscale buffer for HQ4X (256x240 → 1024x960)
static uint32_t s_hq4xBuffer[kNesW * 4 * kNesH * 4];

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
// Values MUST match FCEUmm's libretro_core_options.h defaults exactly.
// Audio options (sndquality, sndlowpass, sndvolume, sndrate_hint) are
// intentionally NOT set here — when the core calls GET_VARIABLE for a key
// that doesn't exist in our map, our callback returns false and the core
// falls back to its own built-in defaults from option_defs[]. This ensures
// audio always uses FCEUmm's native defaults without any frontend interference.
static void initDefaultOptions() {
    // --- System ---
    s_options["fceumm_region"]                  = "Auto";
    s_options["fceumm_game_genie"]              = "disabled";
    s_options["fceumm_show_adv_system_options"]  = "disabled";

    // --- Video ---
    s_options["fceumm_ntsc_filter"]             = "disabled";
    s_options["fceumm_palette"]                 = "default";
    s_options["fceumm_aspect"]                  = "8:7 PAR";
    s_options["fceumm_overscan_h_left"]         = "0";
    s_options["fceumm_overscan_h_right"]        = "0";
    s_options["fceumm_overscan_v_top"]          = "0";
    s_options["fceumm_overscan_v_bottom"]       = "0";

    // --- Audio (NOT set — let FCEUmm use its built-in defaults) ---
    // fceumm_sndrate_hint   default = "Auto"
    // fceumm_sndquality     default = "Low"
    // fceumm_sndlowpass     default = "disabled"
    // fceumm_sndvolume      default = "7" (70%)
    // fceumm_show_adv_sound_options default = "disabled"
    s_options["fceumm_show_adv_sound_options"]  = "disabled";

    // --- Input ---
    s_options["fceumm_turbo_enable"]            = "None";
    s_options["fceumm_turbo_delay"]             = "3";

    // --- Hacks ---
    s_options["fceumm_overclocking"]            = "disabled";
    s_options["fceumm_nospritelimit"]           = "disabled";

    // --- Other (matched from libretro_core_options.h) ---
    s_options["fceumm_show_crosshair"]          = "enabled";
    s_options["fceumm_aspect_orient"]           = "horizontal";
    s_options["fceumm_su_swap"]                 = "disabled";
    s_options["fceumm_disable_swap"]            = "disabled";
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

    const uint32_t dstW = (uint32_t)buf.width;
    const uint32_t dstH = (uint32_t)buf.height;
    if (dstW == 0 || dstH == 0) {
        ANativeWindow_unlockAndPost(s_window);
        return;
    }

    // All filters use the same blit path: nearest-neighbor scaling from the
    // source resolution (256x240, or 512x480 for XBR) to the display buffer.
    // The buffer geometry is always 0x0 (window default), so the compositor
    // does no additional scaling. Scanline/CRT/dot effects are GPU overlays.
    if (buf.format == WINDOW_FORMAT_RGBA_8888 ||
        buf.format == WINDOW_FORMAT_RGBX_8888) {
        uint8_t* dst = static_cast<uint8_t*>(buf.bits);
        const uint32_t dstStride = buf.stride * 4;
        const float sx = (float)w / (float)dstW;
        const float sy = (float)h / (float)dstH;
        for (uint32_t y = 0; y < dstH; ++y) {
            uint32_t srcY = (uint32_t)(y * sy);
            if (srcY >= h) srcY = h - 1;
            uint8_t* drow = dst + y * dstStride;
            const uint32_t* srow = src + srcY * srcStride;
            for (uint32_t x = 0; x < dstW; ++x) {
                uint32_t srcX = (uint32_t)(x * sx);
                if (srcX >= w) srcX = w - 1;
                uint32_t px = srow[srcX];
                drow[x * 4 + 0] = (px >> 16) & 0xFF;
                drow[x * 4 + 1] = (px >> 8) & 0xFF;
                drow[x * 4 + 2] = px & 0xFF;
                drow[x * 4 + 3] = 0xFF;
            }
        }
    } else if (buf.format == WINDOW_FORMAT_RGB_565) {
        uint16_t* dst = static_cast<uint16_t*>(buf.bits);
        const uint32_t dstStride = buf.stride;
        const float sx = (float)w / (float)dstW;
        const float sy = (float)h / (float)dstH;
        for (uint32_t y = 0; y < dstH; ++y) {
            uint32_t srcY = (uint32_t)(y * sy);
            if (srcY >= h) srcY = h - 1;
            uint16_t* drow = dst + y * dstStride;
            const uint32_t* srow = src + srcY * srcStride;
            for (uint32_t x = 0; x < dstW; ++x) {
                uint32_t srcX = (uint32_t)(x * sx);
                if (srcX >= w) srcX = w - 1;
                uint32_t px = srow[srcX];
                uint16_t r = ((px >> 16) & 0xF8) >> 3;
                uint16_t g = ((px >> 8) & 0xFC) >> 2;
                uint16_t b = (px & 0xF8) >> 3;
                drow[x] = (r << 11) | (g << 5) | b;
            }
        }
    }

    ANativeWindow_unlockAndPost(s_window);
}

// ---------------------------------------------------------------------------
// 2xBR — Edge-preserving 2x upscale
//
// Based on Hyllian's 2xBR-lv1 algorithm (single-pass CPU port of the
// 2xBR-lv1-c-pass0 + pass1 reference shaders). Uses a full 5×5 neighborhood
// with weighted-distance edge detection and interpolation restriction rules.
//
// Reference layout (XBR notation):
//        A1 B1 C1
//     A0  A  B  C  C4
//     D0  D  E  F  F4
//     G0  G  H  I  I4
//        G5 H5 I5
//
// For each of the 4 output pixels (quadrants), the neighborhood is rotated
// so that the edge detection always checks the "outward" diagonal direction.
// ---------------------------------------------------------------------------

// Y-weighted luminance:  48 * (0.299 R + 0.587 G + 0.114 B)
// Matches the reference shader's yuv_weighted[0] = (14.352, 28.176, 5.472).
static inline int xbrYw(uint32_t px) {
    int r = (px >> 16) & 0xFF;
    int g = (px >> 8) & 0xFF;
    int b = px & 0xFF;
    return (r * 14352 + g * 28176 + b * 5472) / 1000;
}

// Absolute Y-weighted distance
static inline int xbrDf(int ya, int yb) {
    return ya > yb ? ya - yb : yb - ya;
}

// "close" — Y-weighted distance below threshold (15, from reference)
static inline bool xbrClose(int ya, int yb) {
    return xbrDf(ya, yb) < 15;
}

// weighted_distance(a,b,c,d,e,f,g,h) = df(a,b) + df(a,c) + df(d,e) + df(d,f) + 4*df(g,h)
static inline int xbrWd(int a, int b, int c, int d, int e, int f, int g, int h) {
    return xbrDf(a, b) + xbrDf(a, c) + xbrDf(d, e) + xbrDf(d, f) + 4 * xbrDf(g, h);
}

// 50 % alpha blend (alpha = 128 → 50 %)
static inline uint32_t xbrBlend50(uint32_t c1, uint32_t c2) {
    return ((((c1 & 0x00FF00FF) + (c2 & 0x00FF00FF)) >> 1) & 0x00FF00FF)
         | ((((c1 & 0x0000FF00) + (c2 & 0x0000FF00)) >> 1) & 0x0000FF00)
         | 0xFF000000u;
}

// Process one quadrant of the 2x2 output block.
// All 18 Y-weighted values must be pre-computed.
// Returns the blended output color.
//
// Parameters follow the reference shader's variable names:
//   e  = center, b = up, d = left, h = down, f = right
//   c  = UR, a = UL, g = DL, i = DR  (diagonal neighbours)
//   f4, h5, i4, i5 = extended neighbours
//
// The edge detection checks whether there is an edge running through the
// diagonal `i` direction.  If yes, the pixel is blended 50 % toward the
// closer of f (right) or h (down).
static inline uint32_t xbrQuadrant(
    uint32_t eColor, uint32_t fColor, uint32_t hColor,
    int ye, int yb, int yd, int yh, int yf,
    int yc, int ya, int yg, int yi,
    int yf4, int yh5, int yi4, int yi5)
{
    // Interpolation restriction (r1-r7 from reference)
    bool r1 = (ye != yf) && (ye != yh);
    bool r2 = !xbrClose(yf, yb) && !xbrClose(yf, yc);
    bool r3 = !xbrClose(yh, yd) && !xbrClose(yh, yg);
    bool r4 = !xbrClose(yf, yf4) && !xbrClose(yf, yi4);
    bool r5 = !xbrClose(yh, yh5) && !xbrClose(yh, yi5);
    bool r6 = xbrClose(ye, yi) && (r4 || r5);
    bool r7 = xbrClose(ye, yg) || xbrClose(ye, yc);

    if (!(r1 && (r2 || r3 || r6 || r7)))
        return eColor;  // No interpolation needed

    // Edge detection: compare "no-edge" weight vs "edge" weight
    int noEdge = xbrWd(ye, yc, yg, yi, yh5, yf4, yh, yf);
    int edge   = xbrWd(yh, yd, yi5, yf, yi4, yb, ye, yi);

    if (noEdge >= edge)
        return eColor;  // No edge detected

    // Edge detected — blend 50 % toward the closer orthogonal neighbour
    bool px = (xbrDf(ye, yf) <= xbrDf(ye, yh));
    return xbrBlend50(eColor, px ? fColor : hColor);
}

static void xbr2xUpscale(const uint32_t* src, unsigned sw, unsigned sh,
                          size_t srcStride, uint32_t* dst) {
    const unsigned dw = sw * 2;

    // Helper: get pixel at (x,y) with clamp-to-edge
    auto getPx = [&](int x, int y) -> uint32_t {
        if (x < 0) x = 0; else if (x >= (int)sw) x = sw - 1;
        if (y < 0) y = 0; else if (y >= (int)sh) y = sh - 1;
        return src[y * srcStride + x];
    };

    for (unsigned y = 0; y < sh; ++y) {
        const unsigned y2 = y * 2;
        for (unsigned x = 0; x < sw; ++x) {
            const unsigned x2 = x * 2;

            // Full 5×5 neighborhood (clamp at borders)
            //        A1 B1 C1
            //     A0  A  B  C  C4
            //     D0  D  E  F  F4
            //     G0  G  H  I  I4
            //        G5 H5 I5
            uint32_t A1=getPx(x-1,y-2), B1=getPx(x,y-2), C1=getPx(x+1,y-2);
            uint32_t A0=getPx(x-2,y-1), A=getPx(x-1,y-1), B=getPx(x,y-1), C=getPx(x+1,y-1), C4=getPx(x+2,y-1);
            uint32_t D0=getPx(x-2,y),   D=getPx(x-1,y),   E=getPx(x,y),   F=getPx(x+1,y),   F4=getPx(x+2,y);
            uint32_t G0=getPx(x-2,y+1), G=getPx(x-1,y+1), H=getPx(x,y+1), I=getPx(x+1,y+1), I4=getPx(x+2,y+1);
            uint32_t G5=getPx(x-1,y+2), H5=getPx(x,y+2), I5=getPx(x+1,y+2);

            // Pre-compute Y-weighted values
            int yA1=xbrYw(A1), yB1=xbrYw(B1), yC1=xbrYw(C1);
            int yA0=xbrYw(A0), yA=xbrYw(A), yB=xbrYw(B), yC=xbrYw(C), yC4=xbrYw(C4);
            int yD0=xbrYw(D0), yD=xbrYw(D), yE=xbrYw(E), yF=xbrYw(F), yF4=xbrYw(F4);
            int yG0=xbrYw(G0), yG=xbrYw(G), yH=xbrYw(H), yI=xbrYw(I), yI4=xbrYw(I4);
            int yG5=xbrYw(G5), yH5=xbrYw(H5), yI5=xbrYw(I5);

            uint32_t* d0 = dst + y2 * dw;
            uint32_t* d1 = dst + (y2 + 1) * dw;

            // TL quadrant: identity — edge in I (DR) direction, blend F or H
            d0[x2] = xbrQuadrant(E, F, H,
                yE, yB, yD, yH, yF,
                yC, yA, yG, yI,
                yF4, yH5, yI4, yI5);

            // TR quadrant: vertical flip — edge in C (UR) direction, blend F or B
            // From reference GLSL swizzling: b=H, d=D, h=B, f=F, c=I, a=G, g=A, i=C
            d0[x2+1] = xbrQuadrant(E, F, B,
                yE, yH, yD, yB, yF,
                yI, yG, yA, yC,
                yF4, yB1, yC4, yC1);

            // BL quadrant: horizontal flip — edge in A (UL) direction, blend D or B
            // From reference GLSL swizzling: b=H, d=F, h=B, f=D, c=G, a=I, g=C, i=A
            d1[x2] = xbrQuadrant(E, D, B,
                yE, yH, yF, yB, yD,
                yG, yI, yC, yA,
                yD0, yB1, yA0, yA1);

            // BR quadrant: 180° rotation — edge in G (DL) direction, blend D or H
            // From reference GLSL swizzling: b=B, d=F, h=H, f=D, c=A, a=C, g=I, i=G
            d1[x2+1] = xbrQuadrant(E, D, H,
                yE, yB, yF, yH, yD,
                yA, yC, yI, yG,
                yD0, yH5, yG0, yG5);
        }
    }
}

// ---------------------------------------------------------------------------
// HQ2X — High Quality 2x scaler by Maxim Stepin
//
// Based on the classic HQ2X algorithm. Uses YUV color space comparison with
// threshold to build a 9-bit pattern from the 3x3 neighborhood, then blends
// the 4 output pixels using interpolation rules based on the pattern and
// cross-diagonal rules.
//
// Reference: https://web.archive.org/web/20131205091805/http://www.hiend3d.com/hq2x.html
// ---------------------------------------------------------------------------

// YUV threshold for HQ2X color difference detection
// Thresholds from HQ2X: Y>48, U>7, V>6
static inline bool hqDiff(uint32_t y1, uint32_t y2, uint32_t u1, uint32_t u2,
                          uint32_t v1, uint32_t v2) {
    int dy = (int)y1 - (int)y2; if (dy < 0) dy = -dy;
    int du = (int)u1 - (int)u2; if (du < 0) du = -du;
    int dv = (int)v1 - (int)v2; if (dv < 0) dv = -dv;
    return dy > 48 || du > 7 || dv > 6;
}

// Compute YUV components from ARGB pixel
static inline void toYUV(uint32_t px, uint8_t& y, uint8_t& u, uint8_t& v) {
    int r = (px >> 16) & 0xFF;
    int g = (px >> 8) & 0xFF;
    int b = px & 0xFF;
    y = (uint8_t)((r * 299 + g * 587 + b * 114) / 1000);
    u = (uint8_t)((-r * 169 - g * 331 + b * 500) / 1000 + 128);
    v = (uint8_t)((r * 500 - g * 419 - b * 81) / 1000 + 128);
}

// HQ2X interpolation helpers
// interp1: 50% c1 + 50% c2
static inline uint32_t hqInterp1(uint32_t c1, uint32_t c2) {
    return ((((c1 & 0x00FF00FF) + (c2 & 0x00FF00FF)) >> 1) & 0x00FF00FF)
         | ((((c1 & 0x0000FF00) + (c2 & 0x0000FF00)) >> 1) & 0x0000FF00)
         | 0xFF000000u;
}

// interp2: 50% c1 + 25% c2 + 25% c3 = (2*c1 + c2 + c3) / 4
static inline uint32_t hqInterp2(uint32_t c1, uint32_t c2, uint32_t c3) {
    return ((((c1 & 0x00FF00FF) * 2 + (c2 & 0x00FF00FF) + (c3 & 0x00FF00FF)) >> 2) & 0x00FF00FF)
         | ((((c1 & 0x0000FF00) * 2 + (c2 & 0x0000FF00) + (c3 & 0x0000FF00)) >> 2) & 0x0000FF00)
         | 0xFF000000u;
}

// interp3: 75% c1 + 25% c2 = (3*c1 + c2) / 4
static inline uint32_t hqInterp3(uint32_t c1, uint32_t c2) {
    return ((((c1 & 0x00FF00FF) * 3 + (c2 & 0x00FF00FF)) >> 2) & 0x00FF00FF)
         | ((((c1 & 0x0000FF00) * 3 + (c2 & 0x0000FF00)) >> 2) & 0x0000FF00)
         | 0xFF000000u;
}

// HQ2X single output pixel computation for one quadrant.
// Based on the standard HQ2X case analysis with cross-diagonal rules.
//
// Parameters:
//   c  = center pixel
//   d  = diagonal neighbor (w1 for TL, w3 for TR, w7 for BL, w9 for BR)
//   o1 = first orthogonal neighbor (w2 for TL, w6 for TR, w4 for BL, w6 for BR)
//   o2 = second orthogonal neighbor (w4 for TL, w2 for TR, w8 for BL, w8 for BR)
//   dDiff = diagonal differs from center?
//   o1Diff = first orthogonal differs from center?
//   o2Diff = second orthogonal differs from center?
//   cross = diagonal matches both orthogonal neighbors?
static inline uint32_t hq2xPixel(
    uint32_t c, uint32_t d, uint32_t o1, uint32_t o2,
    bool dDiff, bool o1Diff, bool o2Diff, bool cross)
{
    if (dDiff) {
        if (cross) {
            // Diagonal matches both orthogonals → smooth area
            if (o1Diff && o2Diff)
                return hqInterp2(c, o1, o2);  // 50% c + 25% each
            else if (o1Diff)
                return hqInterp1(c, o1);       // 50% c + 50% o1
            else if (o2Diff)
                return hqInterp1(c, o2);       // 50% c + 50% o2
            else
                return c;
        } else {
            // Corner detected at diagonal
            if (o1Diff && o2Diff)
                return hqInterp1(c, d);        // 50% c + 50% diagonal
            else if (o1Diff)
                return hqInterp1(c, o1);
            else if (o2Diff)
                return hqInterp1(c, o2);
            else
                return hqInterp1(c, d);
        }
    } else {
        // Diagonal same as center
        if (o1Diff && o2Diff)
            return hqInterp2(c, o1, o2);       // 50% c + 25% each
        else if (o1Diff)
            return hqInterp3(c, o1);           // 75% c + 25% o1
        else if (o2Diff)
            return hqInterp3(c, o2);           // 75% c + 25% o2
        else
            return c;
    }
}

static void hq2xUpscale(const uint32_t* src, unsigned sw, unsigned sh,
                         size_t srcStride, uint32_t* dst) {
    const unsigned dw = sw * 2;

    auto getPx = [&](int x, int y) -> uint32_t {
        if (x < 0) x = 0; else if (x >= (int)sw) x = sw - 1;
        if (y < 0) y = 0; else if (y >= (int)sh) y = sh - 1;
        return src[y * srcStride + x];
    };

    for (unsigned y = 0; y < sh; ++y) {
        const unsigned y2 = y * 2;
        for (unsigned x = 0; x < sw; ++x) {
            const unsigned x2 = x * 2;

            // 3x3 neighborhood
            uint32_t w1 = getPx(x-1, y-1);  // UL
            uint32_t w2 = getPx(x,   y-1);  // U
            uint32_t w3 = getPx(x+1, y-1);  // UR
            uint32_t w4 = getPx(x-1, y);    // L
            uint32_t c  = getPx(x,   y);    // center
            uint32_t w6 = getPx(x+1, y);    // R
            uint32_t w7 = getPx(x-1, y+1);  // DL
            uint32_t w8 = getPx(x,   y+1);  // D
            uint32_t w9 = getPx(x+1, y+1);  // DR

            // YUV conversion of all 9 pixels
            uint8_t cy, cu, cv;
            toYUV(c, cy, cu, cv);
            uint8_t y1,u1,v1, y2,u2,v2, y3,u3,v3, y4,u4,v4;
            uint8_t y6,u6,v6, y7,u7,v7, y8,u8,v8, y9,u9,v9;
            toYUV(w1, y1,u1,v1); toYUV(w2, y2,u2,v2); toYUV(w3, y3,u3,v3);
            toYUV(w4, y4,u4,v4);                         toYUV(w6, y6,u6,v6);
            toYUV(w7, y7,u7,v7); toYUV(w8, y8,u8,v8); toYUV(w9, y9,u9,v9);

            // Pattern bits: 1 if neighbor differs from center
            bool d1 = hqDiff(y1,cy,u1,cu,v1,cv);  // w1 != c
            bool d2 = hqDiff(y2,cy,u2,cu,v2,cv);  // w2 != c
            bool d3 = hqDiff(y3,cy,u3,cu,v3,cv);  // w3 != c
            bool d4 = hqDiff(y4,cy,u4,cu,v4,cv);  // w4 != c
            bool d6 = hqDiff(y6,cy,u6,cu,v6,cv);  // w6 != c
            bool d7 = hqDiff(y7,cy,u7,cu,v7,cv);  // w7 != c
            bool d8 = hqDiff(y8,cy,u8,cu,v8,cv);  // w8 != c
            bool d9 = hqDiff(y9,cy,u9,cu,v9,cv);  // w9 != c

            // Cross rules: diagonal matches both adjacent orthogonals
            bool cross1 = !d2 && !d4;   // w1 cross: w2==c && w4==c → no corner at UL
            bool cross3 = !d2 && !d6;   // w3 cross: w2==c && w6==c → no corner at UR
            bool cross7 = !d4 && !d8;   // w7 cross: w4==c && w8==c → no corner at DL
            bool cross9 = !d6 && !d8;   // w9 cross: w6==c && w8==c → no corner at DR

            uint32_t* row0 = dst + y2 * dw;
            uint32_t* row1 = dst + (y2 + 1) * dw;

            // TL: diagonal=w1, orthogonals=w2(up), w4(left)
            row0[x2] = hq2xPixel(c, w1, w2, w4, d1, d2, d4, cross1);

            // TR: diagonal=w3, orthogonals=w2(up), w6(right)
            row0[x2+1] = hq2xPixel(c, w3, w2, w6, d3, d2, d6, cross3);

            // BL: diagonal=w7, orthogonals=w4(left), w8(down)
            row1[x2] = hq2xPixel(c, w7, w4, w8, d7, d4, d8, cross7);

            // BR: diagonal=w9, orthogonals=w6(right), w8(down)
            row1[x2+1] = hq2xPixel(c, w9, w6, w8, d9, d6, d8, cross9);
        }
    }
}

// ---------------------------------------------------------------------------
// HQ4X — 4x scale using same HQ2X pattern detection with 4x4 output blocks.
// Each source pixel → 4x4 output block. The 4 corner sub-pixels use the HQ2X
// interpolation, and the edge/center sub-pixels use graduated interpolation
// between the corner values and the center for smooth transitions.
// ---------------------------------------------------------------------------
static void hq4xUpscale(const uint32_t* src, unsigned sw, unsigned sh,
                         size_t srcStride, uint32_t* dst) {
    const unsigned dw = sw * 4;

    auto getPx = [&](int x, int y) -> uint32_t {
        if (x < 0) x = 0; else if (x >= (int)sw) x = sw - 1;
        if (y < 0) y = 0; else if (y >= (int)sh) y = sh - 1;
        return src[y * srcStride + x];
    };

    for (unsigned y = 0; y < sh; ++y) {
        const unsigned y4 = y * 4;
        for (unsigned x = 0; x < sw; ++x) {
            const unsigned x4 = x * 4;

            uint32_t w1 = getPx(x-1, y-1), w2 = getPx(x, y-1), w3 = getPx(x+1, y-1);
            uint32_t w4 = getPx(x-1, y),   c  = getPx(x, y),   w6 = getPx(x+1, y);
            uint32_t w7 = getPx(x-1, y+1), w8 = getPx(x, y+1), w9 = getPx(x+1, y+1);

            uint8_t cy, cu, cv;
            toYUV(c, cy, cu, cv);
            uint8_t y1,u1,v1, y2,u2,v2, y3,u3,v3, y4,u4,v4;
            uint8_t y6,u6,v6, y7,u7,v7, y8,u8,v8, y9,u9,v9;
            toYUV(w1, y1,u1,v1); toYUV(w2, y2,u2,v2); toYUV(w3, y3,u3,v3);
            toYUV(w4, y4,u4,v4);                         toYUV(w6, y6,u6,v6);
            toYUV(w7, y7,u7,v7); toYUV(w8, y8,u8,v8); toYUV(w9, y9,u9,v9);

            bool d1 = hqDiff(y1,cy,u1,cu,v1,cv);
            bool d2 = hqDiff(y2,cy,u2,cu,v2,cv);
            bool d3 = hqDiff(y3,cy,u3,cu,v3,cv);
            bool d4 = hqDiff(y4,cy,u4,cu,v4,cv);
            bool d6 = hqDiff(y6,cy,u6,cu,v6,cv);
            bool d7 = hqDiff(y7,cy,u7,cu,v7,cv);
            bool d8 = hqDiff(y8,cy,u8,cu,v8,cv);
            bool d9 = hqDiff(y9,cy,u9,cu,v9,cv);

            bool cross1 = !d2 && !d4;
            bool cross3 = !d2 && !d6;
            bool cross7 = !d4 && !d8;
            bool cross9 = !d6 && !d8;

            // Compute the 4 corner colors using HQ2X interpolation
            uint32_t tl = hq2xPixel(c, w1, w2, w4, d1, d2, d4, cross1);
            uint32_t tr = hq2xPixel(c, w3, w2, w6, d3, d2, d6, cross3);
            uint32_t bl = hq2xPixel(c, w7, w4, w8, d7, d4, d8, cross7);
            uint32_t br = hq2xPixel(c, w9, w6, w8, d9, d6, d8, cross9);

            uint32_t* rows[4] = {
                dst + y4 * dw,
                dst + (y4 + 1) * dw,
                dst + (y4 + 2) * dw,
                dst + (y4 + 3) * dw
            };

            // Fill 4x4 block with graduated interpolation:
            //   0  1  2  3
            //   4  5  6  7
            //   8  9 10 11
            //  12 13 14 15
            // Corners use HQ2X result, edges interpolate between corners,
            // center uses center pixel.
            rows[0][x4]   = tl;
            rows[0][x4+1] = hqInterp1(tl, tr);
            rows[0][x4+2] = hqInterp1(tl, tr);
            rows[0][x4+3] = tr;

            rows[1][x4]   = hqInterp1(tl, bl);
            rows[1][x4+1] = hqInterp2(c, tl, br);
            rows[1][x4+2] = hqInterp2(c, tr, bl);
            rows[1][x4+3] = hqInterp1(tr, br);

            rows[2][x4]   = hqInterp1(tl, bl);
            rows[2][x4+1] = hqInterp2(c, bl, tr);
            rows[2][x4+2] = hqInterp2(c, br, tl);
            rows[2][x4+3] = hqInterp1(tr, br);

            rows[3][x4]   = bl;
            rows[3][x4+1] = hqInterp1(bl, br);
            rows[3][x4+2] = hqInterp1(bl, br);
            rows[3][x4+3] = br;
        }
    }
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
    const int filter = s_videoFilter.load(std::memory_order_relaxed);
    if (filter == 4 || filter == 7) {
        // XBR / XBR+dot: 2x edge-preserving upscale (256x240 → 512x480)
        xbr2xUpscale(src, width, height, srcStride, s_xbrBuffer);
        blitToSurface(s_xbrBuffer, width * 2, height * 2, width * 2);
    } else if (filter == 5) {
        // HQ2X: 2x high-quality scaler (256x240 → 512x480)
        hq2xUpscale(src, width, height, srcStride, s_xbrBuffer);
        blitToSurface(s_xbrBuffer, width * 2, height * 2, width * 2);
    } else if (filter == 6) {
        // HQ4X: 4x high-quality scaler (256x240 → 1024x960)
        hq4xUpscale(src, width, height, srcStride, s_hq4xBuffer);
        blitToSurface(s_hq4xBuffer, width * 4, height * 4, width * 4);
    } else {
        blitToSurface(src, width, height, srcStride);
    }
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

    // Reset FDS state (no auto-insert needed — FDSInit called by PowerNES
    // sets InDisk=0, meaning the disk is already inserted after loading)
    s_isFdsGame.store(false, std::memory_order_relaxed);

    // --- FDS BIOS pre-check ---
    // If this is an FDS game, verify the BIOS file (disksys.rom) exists in
    // the system directory BEFORE calling retro_load_game. The FCEUmm core
    // looks for it at {systemDir}/disksys.rom. If it's missing, FDSLoad()
    // fails silently (returns 0) and the core tries other loaders, all of
    // which fail for .fds files, resulting in a generic "ROM loading failed"
    // error that doesn't mention the BIOS.
    {
        std::string ext;
        size_t dot = path.find_last_of('.');
        if (dot != std::string::npos) {
            ext = path.substr(dot);
            std::transform(ext.begin(), ext.end(), ext.begin(),
                           [](unsigned char c) { return std::tolower(c); });
        }
        if (ext == ".fds") {
            std::string biosPath = s_systemDir + "/disksys.rom";
            FILE* biosF = std::fopen(biosPath.c_str(), "rb");
            if (!biosF) {
                LOGE("FDS BIOS NOT FOUND at: %s (systemDir=%s)",
                     biosPath.c_str(), s_systemDir.c_str());
                retro_deinit();
                return "FDS BIOS缺失: 请在设置中导入disksys.rom (8KB, MD5: ca30b50f880eb660a320674ed365ef7a)";
            } else {
                std::fseek(biosF, 0, SEEK_END);
                long biosSize = std::ftell(biosF);
                std::fseek(biosF, 0, SEEK_SET);

                // Read first 64 bytes to check for corruption
                uint8_t header[64];
                size_t hdrRead = std::fread(header, 1, 64, biosF);

                // Read reset vector at offset 0x1FFC
                uint8_t vec[2] = {0, 0};
                std::fseek(biosF, 0x1FFC, SEEK_SET);
                std::fread(vec, 1, 2, biosF);
                std::fclose(biosF);

                LOGI("FDS BIOS found at: %s (%ld bytes)", biosPath.c_str(), biosSize);

                if (biosSize != 8192) {
                    LOGE("FDS BIOS size mismatch: expected 8192, got %ld", biosSize);
                    retro_deinit();
                    return "FDS BIOS大小错误: 需要8192字节, 当前" + std::to_string(biosSize)
                         + "字节。请在设置中导入正确的disksys.rom";
                }

                // Check if first 64 bytes are all zeros (corruption indicator)
                bool allZeros = true;
                for (size_t i = 0; i < hdrRead && i < 64; ++i) {
                    if (header[i] != 0) { allZeros = false; break; }
                }
                if (allZeros) {
                    LOGE("FDS BIOS corrupted: first 64 bytes are all zeros");
                    retro_deinit();
                    return "FDS BIOS已损坏: 文件前64字节全为零。"
                           "请在设置中导入正确的disksys.rom "
                           "(MD5: ca30b50f880eb660a320674ed365ef7a)";
                }

                // Check reset vector (should point to $E040 = 0x40, 0xE0)
                if (vec[0] != 0x40 || vec[1] != 0xE0) {
                    LOGE("FDS BIOS reset vector invalid: %02X %02X (expected 40 E0)",
                         vec[0], vec[1]);
                    retro_deinit();
                    return "FDS BIOS复位向量错误: 请在设置中导入正确的disksys.rom";
                }

                LOGI("FDS BIOS verification passed");
            }
            LOGI("Loading FDS game: %s", path.c_str());
        }
    }

    LOGI("About to call retro_load_game for: %s (systemDir=%s)",
         path.c_str(), s_systemDir.c_str());

    if (!retro_load_game(&game)) {
        retro_unload_game();
        retro_deinit();
        LOGE("retro_load_game FAILED for: %s", path.c_str());
        LOGE("Core message: %s", s_coreMessage.c_str());
        // Return the core's own error message if available (e.g. FDS BIOS missing)
        if (!s_coreMessage.empty()) {
            std::string err = s_coreMessage;
            s_coreMessage.clear();
            return err;
        }
        return "FCEUmm rejected the ROM (unsupported mapper or corrupt file)";
    }
    s_loaded = true;
    LOGI("retro_load_game SUCCEEDED for: %s", path.c_str());

    // Detect FDS games by file extension for logging.
    // NOTE: No R button auto-press is needed. The FCEUmm core's retro_load_game
    // calls PowerNES() → FDSInit() which sets InDisk=0 (disk inserted).
    // The FDS BIOS boots automatically and reads the disk. Pressing R would
    // actually EJECT the disk (toggling InDisk from 0 to 255), causing a
    // permanent gray screen.
    {
        std::string ext;
        size_t dot = path.find_last_of('.');
        if (dot != std::string::npos) {
            ext = path.substr(dot);
            std::transform(ext.begin(), ext.end(), ext.begin(),
                           [](unsigned char c) { return std::tolower(c); });
        }
        if (ext == ".fds") {
            s_isFdsGame.store(true, std::memory_order_relaxed);
            LOGI("FDS game detected — disk is auto-inserted by FDSInit, no R press needed");
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
    s_isFdsGame.store(false, std::memory_order_relaxed);
}

void resetEmulation(bool /*hard*/) {
    if (!s_loaded) return;
    retro_reset();
    // After reset, PowerNES() is called internally by the core, which calls
    // FDSInit() for FDS games, setting InDisk=0 (disk inserted). No manual
    // R button press is needed.
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
    LOGI("Video filter set: %d (0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x, 7=xbr+dot)", filter);
    // No buffer geometry changes needed. The buffer is always at 0x0 (window
    // default). For XBR (filter 4/7), the 2xBR-lv1 upscale happens in cb_video
    // before blitting — it uses a full 5×5 neighborhood with weighted-distance
    // edge detection to produce smooth, artifact-free pixel art upscaling.
    // For HQ2X (5) and HQ4X (6), the HQX scaler runs in cb_video with YUV
    // pattern detection and cross-diagonal interpolation rules.
    // For scanline/CRT/dot, the visual effect is a GPU-accelerated Compose
    // overlay drawn on top of the SurfaceView. XBR+dot (7) combines the C++
    // XBR upscale with the Compose dot overlay.
}

} // namespace nescore::rom
