// SPDX-License-Identifier: MIT
// Shared utilities for all emulator cores (NES, SNES, GB/GBC/GBA).
//
// Contains:
//   * ANativeWindow surface blitting (hardware-accelerated rendering)
//   * 2xBR / 4xBR pixel art upscaling filters
//   * Stereo audio ring buffer
//
// All functions are static inline so each .so gets its own copy without
// symbol conflicts. Buffer sizes are parameterised per-core.
#pragma once

#include <android/log.h>
#include <android/native_window.h>
#include <atomic>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

// Include HQX library for HQ2X/HQ4X filters
#include "hqx/hqx.h"

// GPU video filter disabled — CPU-based simple 2xBR/4xBR is faster and artifact-free
// #include "gpu_video_filter.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "core-shared", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "core-shared", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "core-shared", __VA_ARGS__)

namespace coreshared {

// ---------------------------------------------------------------------------
// Surface blitting — hardware-accelerated ANativeWindow rendering
// ---------------------------------------------------------------------------

static inline uint32_t xrgbToRgba(uint32_t px) {
    return ((px & 0x00FFFFFF) << 8) | 0x000000FFu;
}

// Blit a source ARGB framebuffer to the ANativeWindow with nearest-neighbor
// scaling. Source must be 0xAARRGGBB (uint32_t per pixel).
static inline void blitToSurface(ANativeWindow* window, std::mutex& windowMtx,
                                  const uint32_t* src, unsigned w, unsigned h,
                                  size_t srcStride) {
    std::lock_guard<std::mutex> lk(windowMtx);
    if (!window) return;

    ANativeWindow_Buffer buf;
    memset(&buf, 0, sizeof(buf));
    int rc = ANativeWindow_lock(window, &buf, nullptr);
    if (rc != 0) {
        LOGE("ANativeWindow_lock failed: %d", rc);
        return;
    }

    const uint32_t dstW = (uint32_t)buf.width;
    const uint32_t dstH = (uint32_t)buf.height;
    if (dstW == 0 || dstH == 0) {
        ANativeWindow_unlockAndPost(window);
        return;
    }

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

    ANativeWindow_unlockAndPost(window);
}

// Blit RGB565 source data directly to the surface — no ARGB conversion.
// The surface MUST be set to WINDOW_FORMAT_RGB_565 via setSurface565().
// This produces exact pixel-accurate colors with zero conversion loss.
static inline void blitToSurface565(ANativeWindow* window, std::mutex& windowMtx,
                                     const uint16_t* src, unsigned w, unsigned h,
                                     size_t srcStride) {
    std::lock_guard<std::mutex> lk(windowMtx);
    if (!window) return;

    ANativeWindow_Buffer buf;
    memset(&buf, 0, sizeof(buf));
    int rc = ANativeWindow_lock(window, &buf, nullptr);
    if (rc != 0) {
        LOGE("ANativeWindow_lock failed (565): %d", rc);
        return;
    }

    const uint32_t dstW = (uint32_t)buf.width;
    const uint32_t dstH = (uint32_t)buf.height;
    if (dstW == 0 || dstH == 0) {
        ANativeWindow_unlockAndPost(window);
        return;
    }

    if (buf.format == WINDOW_FORMAT_RGB_565) {
        // Direct 565-to-565 blit — no conversion needed!
        uint16_t* dst = static_cast<uint16_t*>(buf.bits);
        const uint32_t dstStride = (uint32_t)buf.stride;
        const float sx = (float)w / (float)dstW;
        const float sy = (float)h / (float)dstH;
        for (uint32_t y = 0; y < dstH; ++y) {
            uint32_t srcY = (uint32_t)(y * sy);
            if (srcY >= h) srcY = h - 1;
            uint16_t* drow = dst + y * dstStride;
            const uint16_t* srow = src + srcY * srcStride;
            for (uint32_t x = 0; x < dstW; ++x) {
                uint32_t srcX = (uint32_t)(x * sx);
                if (srcX >= w) srcX = w - 1;
                drow[x] = srow[srcX];
            }
        }
    } else if (buf.format == WINDOW_FORMAT_RGBA_8888 ||
               buf.format == WINDOW_FORMAT_RGBX_8888) {
        // Fallback: convert 565 to RGBA8888 on the fly
        uint8_t* dst = static_cast<uint8_t*>(buf.bits);
        const uint32_t dstStride = buf.stride * 4;
        const float sx = (float)w / (float)dstW;
        const float sy = (float)h / (float)dstH;
        for (uint32_t y = 0; y < dstH; ++y) {
            uint32_t srcY = (uint32_t)(y * sy);
            if (srcY >= h) srcY = h - 1;
            uint8_t* drow = dst + y * dstStride;
            const uint16_t* srow = src + srcY * srcStride;
            for (uint32_t x = 0; x < dstW; ++x) {
                uint32_t srcX = (uint32_t)(x * sx);
                if (srcX >= w) srcX = w - 1;
                uint16_t px = srow[srcX];
                // Proper 5-bit to 8-bit expansion: (v << 3) | (v >> 2)
                uint32_t r5 = (px >> 11) & 0x1F;
                uint32_t g6 = (px >> 5) & 0x3F;
                uint32_t b5 = px & 0x1F;
                drow[x * 4 + 0] = (r5 << 3) | (r5 >> 2);
                drow[x * 4 + 1] = (g6 << 2) | (g6 >> 4);
                drow[x * 4 + 2] = (b5 << 3) | (b5 >> 2);
                drow[x * 4 + 3] = 0xFF;
            }
        }
    }

    ANativeWindow_unlockAndPost(window);
}

// ---------------------------------------------------------------------------
// 2xBR / 4xBR — Hyllian's simple xBR (v3.3a, adapted from 2xbr.fsh/4xbr.fsh)
//
// Uses exact integer color comparison (dot(color, dtt)) for edge detection,
// which avoids the scattered dot artifacts of approximate YUV thresholds
// while being extremely fast — each pixel only does one simple if-else test
// on an 8-texel cross neighborhood (B,C,D,E,F,G,H,I).
//
// Copyright (C) 2011 Hyllian/Jararaca - sergiogdb@gmail.com
// ---------------------------------------------------------------------------

// Pack a single RGB color into an integer for exact comparison.
// Uses dot(color, dtt) where dtt = (65536, 255, 1) — this maps
// each unique (R,G,B) to a unique int64, enabling exact equality.
static inline int64_t xbrReduce(uint32_t c) {
    int r = (c >> 16) & 0xFF;
    int g = (c >>  8) & 0xFF;
    int b =  c        & 0xFF;
    return (int64_t)r * 65536 + (int64_t)g * 255 + b;
}

// Simple 2xBR upscale: src(sw × sh) → dst(2sw × 2sh)
// Edge pixels get a 50% blend between E and F; non-edge pass through E.
static inline void xbr2xUpscale(const uint32_t* src, unsigned sw, unsigned sh,
                                 size_t srcStride, uint32_t* dst) {
    const unsigned dw = sw * 2;
    const unsigned dstStride = dw;

    auto getPx = [&](int x, int y) -> uint32_t {
        if (x < 0) x = 0; else if (x >= (int)sw) x = sw - 1;
        if (y < 0) y = 0; else if (y >= (int)sh) y = sh - 1;
        return src[y * srcStride + x];
    };

    for (unsigned y = 0; y < sh; ++y) {
        for (unsigned x = 0; x < sw; ++x) {
            uint32_t B = getPx(x, y-1);
            uint32_t C = getPx(x+1, y-1);
            uint32_t D = getPx(x-1, y);
            uint32_t E = getPx(x, y);
            uint32_t F = getPx(x+1, y);
            uint32_t G = getPx(x-1, y+1);
            uint32_t H = getPx(x, y+1);
            uint32_t I = getPx(x+1, y+1);

            int64_t b = xbrReduce(B), c = xbrReduce(C), d = xbrReduce(D);
            int64_t e = xbrReduce(E), f = xbrReduce(F), g = xbrReduce(G);
            int64_t h = xbrReduce(H), ii = xbrReduce(I);

            uint32_t result;
            if (h==f && h!=e && (e==g && (h==ii || e==d) || e==c && (h==ii || e==b))) {
                uint32_t rE = E, rF = F;
                uint32_t rr = (((rE >> 16) & 0xFF) + ((rF >> 16) & 0xFF)) >> 1;
                uint32_t rg = (((rE >>  8) & 0xFF) + ((rF >>  8) & 0xFF)) >> 1;
                uint32_t rb = (( rE         & 0xFF) + ( rF         & 0xFF)) >> 1;
                result = 0xFF000000u | (rr << 16) | (rg << 8) | rb;
            } else {
                result = E;
            }

            const unsigned x2 = x * 2;
            const unsigned y2 = y * 2;
            dst[y2 * dstStride + x2]     = result;
            dst[y2 * dstStride + x2 + 1] = result;
            dst[(y2+1) * dstStride + x2]     = result;
            dst[(y2+1) * dstStride + x2 + 1] = result;
        }
    }
}

// 4xBR upscale: src(sw × sh) → dst(4sw × 4sh) — direct single-pass
// Uses 4×4 sub-pixel pattern: E15 at corners, E11 at edges, E at center.
static inline void xbr4xUpscale(const uint32_t* src, unsigned sw, unsigned sh,
                                 size_t srcStride, uint32_t* dst) {
    const unsigned dw = sw * 4;
    const unsigned dstStride = dw;

    auto getPx = [&](int x, int y) -> uint32_t {
        if (x < 0) x = 0; else if (x >= (int)sw) x = sw - 1;
        if (y < 0) y = 0; else if (y >= (int)sh) y = sh - 1;
        return src[y * srcStride + x];
    };

    for (unsigned y = 0; y < sh; ++y) {
        for (unsigned x = 0; x < sw; ++x) {
            uint32_t B = getPx(x, y-1);
            uint32_t C = getPx(x+1, y-1);
            uint32_t D = getPx(x-1, y);
            uint32_t E = getPx(x, y);
            uint32_t F = getPx(x+1, y);
            uint32_t G = getPx(x-1, y+1);
            uint32_t H = getPx(x, y+1);
            uint32_t I = getPx(x+1, y+1);

            int64_t b = xbrReduce(B), c = xbrReduce(C), d = xbrReduce(D);
            int64_t e = xbrReduce(E), f = xbrReduce(F), g = xbrReduce(G);
            int64_t h = xbrReduce(H), ii = xbrReduce(I);

            uint32_t E11, E15;
            if (h==f && h!=e && (e==g && (h==ii || e==d) || e==c && (h==ii || e==b))) {
                uint32_t rE = E, rF = F;
                uint32_t rr = (((rE >> 16) & 0xFF) + ((rF >> 16) & 0xFF)) >> 1;
                uint32_t rg = (((rE >>  8) & 0xFF) + ((rF >>  8) & 0xFF)) >> 1;
                uint32_t rb = (( rE         & 0xFF) + ( rF         & 0xFF)) >> 1;
                E11 = 0xFF000000u | (rr << 16) | (rg << 8) | rb;
                E15 = F;
            } else {
                E11 = E;
                E15 = E;
            }

            const unsigned x4 = x * 4;
            const unsigned y4 = y * 4;
            dst[(y4+0)*dstStride + x4+0] = E15;
            dst[(y4+0)*dstStride + x4+1] = E11;
            dst[(y4+0)*dstStride + x4+2] = E11;
            dst[(y4+0)*dstStride + x4+3] = E15;
            dst[(y4+1)*dstStride + x4+0] = E11;
            dst[(y4+1)*dstStride + x4+1] = E;
            dst[(y4+1)*dstStride + x4+2] = E;
            dst[(y4+1)*dstStride + x4+3] = E11;
            dst[(y4+2)*dstStride + x4+0] = E11;
            dst[(y4+2)*dstStride + x4+1] = E;
            dst[(y4+2)*dstStride + x4+2] = E;
            dst[(y4+2)*dstStride + x4+3] = E11;
            dst[(y4+3)*dstStride + x4+0] = E15;
            dst[(y4+3)*dstStride + x4+1] = E11;
            dst[(y4+3)*dstStride + x4+2] = E11;
            dst[(y4+3)*dstStride + x4+3] = E15;
        }
    }
}

// ---------------------------------------------------------------------------
// Video filter dispatcher — applies the selected filter and blits to surface.
//
// filter values:
//   0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x,
//   7=xbr+dot, 8=4xbr, 9=4xbr+dot, 10=hq4x+dot
// Scanline/CRT/dot are GPU overlays handled in Kotlin (Compose); here we only
// do pixel upscaling (XBR/HQX) and direct blit.
// ---------------------------------------------------------------------------

static inline void applyFilterAndBlit(
    ANativeWindow* window, std::mutex& windowMtx,
    const uint32_t* src, unsigned width, unsigned height, size_t srcStride,
    int filter,
    uint32_t* xbrBuffer2x,    // at least width*2 * height*2
    uint32_t* xbrBuffer4x,    // at least width*4 * height*4
    uint32_t* xbrMidBuffer,   // at least width*2 * height*2 (unused — kept for ABI compat)
    unsigned maxSrcW, unsigned maxSrcH)
{
    const bool canUpscale = (width <= maxSrcW && height <= maxSrcH);

    if ((filter == 4 || filter == 7) && canUpscale) {
        xbr2xUpscale(src, width, height, srcStride, xbrBuffer2x);
        blitToSurface(window, windowMtx, xbrBuffer2x, width * 2, height * 2, width * 2);
    } else if ((filter == 8 || filter == 9) && canUpscale) {
        xbr4xUpscale(src, width, height, srcStride, xbrBuffer4x);
        blitToSurface(window, windowMtx, xbrBuffer4x, width * 4, height * 4, width * 4);
    } else if (filter == 5 && canUpscale) {
        hq2x_32_rb(src, (uint32_t)(srcStride * sizeof(uint32_t)),
                   xbrBuffer2x, (uint32_t)(width * 2 * sizeof(uint32_t)),
                   (int)width, (int)height);
        blitToSurface(window, windowMtx, xbrBuffer2x, width * 2, height * 2, width * 2);
    } else if ((filter == 6 || filter == 10) && canUpscale) {
        hq4x_32_rb(src, (uint32_t)(srcStride * sizeof(uint32_t)),
                   xbrBuffer4x, (uint32_t)(width * 4 * sizeof(uint32_t)),
                   (int)width, (int)height);
        blitToSurface(window, windowMtx, xbrBuffer4x, width * 4, height * 4, width * 4);
    } else {
        blitToSurface(window, windowMtx, src, width, height, srcStride);
    }
}

// ---------------------------------------------------------------------------
// Audio ring buffer — lock-based stereo int16 ring buffer
// ---------------------------------------------------------------------------

class AudioRingBuffer {
public:
    // Increased from 32768 to 65536 to accommodate resampled audio.
    // When resampling from 32768 Hz to 48000 Hz, the ring buffer needs more
    // capacity since the core produces samples at 32768 Hz but the consumer
    // reads at a higher effective rate after resampling. 65536 provides
    // ~136ms of buffering at 48000 Hz stereo, preventing underruns.
    static constexpr size_t kDefaultCap = 1u << 16; // 65536 samples

    AudioRingBuffer(size_t cap = kDefaultCap) : kCap(cap) {
        ring = new int16_t[cap];
    }
    ~AudioRingBuffer() { delete[] ring; }

    void push(const int16_t* samples, size_t count) {
        std::lock_guard<std::mutex> lk(mtx);
        for (size_t i = 0; i < count; ++i) {
            if (count_ >= kCap) {
                readPos = (readPos + 1) % kCap;
                count_--;
            }
            ring[writePos] = samples[i];
            writePos = (writePos + 1) % kCap;
            count_++;
        }
    }

    // Returns number of *stereo frames* written (each frame = 2 samples)
    int read(int16_t* out, int maxFrames) {
        if (!out || maxFrames <= 0) return 0;
        const size_t want = (size_t)maxFrames * 2;
        std::lock_guard<std::mutex> lk(mtx);
        size_t n = (count_ < want) ? count_ : want;
        for (size_t i = 0; i < n; ++i) {
            out[i] = ring[readPos];
            readPos = (readPos + 1) % kCap;
        }
        count_ -= n;
        for (size_t i = n; i < want; ++i) out[i] = 0;
        return (int)(n / 2);
    }

    void reset() {
        std::lock_guard<std::mutex> lk(mtx);
        readPos = writePos = 0;
        count_ = 0;
    }

private:
    const size_t kCap;
    int16_t* ring;
    size_t writePos = 0;
    size_t readPos = 0;
    size_t count_ = 0;
    std::mutex mtx;
};

// ---------------------------------------------------------------------------
// Surface management
// ---------------------------------------------------------------------------

static inline void setSurface(ANativeWindow*& window, std::mutex& windowMtx,
                               void* nativeWindow) {
    std::lock_guard<std::mutex> lk(windowMtx);
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
    if (nativeWindow) {
        window = static_cast<ANativeWindow*>(nativeWindow);
        ANativeWindow_acquire(window);
        ANativeWindow_setBuffersGeometry(window, 0, 0, WINDOW_FORMAT_RGBA_8888);
    }
}

// Set surface with a specific pixel format (e.g. WINDOW_FORMAT_RGB_565 for
// direct 16-bit blitting from cores that output RGB565).
static inline void setSurfaceFormat(ANativeWindow*& window, std::mutex& windowMtx,
                                     void* nativeWindow, int format) {
    std::lock_guard<std::mutex> lk(windowMtx);
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
    if (nativeWindow) {
        window = static_cast<ANativeWindow*>(nativeWindow);
        ANativeWindow_acquire(window);
        ANativeWindow_setBuffersGeometry(window, 0, 0, format);
    }
}

// Apply filter and blit for RGB565 source data.
// When no filter is selected, blits RGB565 directly to the surface (no ARGB
// conversion — maximum color accuracy). When a filter is selected, the caller
// must provide a pre-converted ARGB buffer (srcArgb) for the filter path.
static inline void applyFilterAndBlit565(
    ANativeWindow* window, std::mutex& windowMtx,
    const uint16_t* src565, unsigned width, unsigned height, size_t srcStride,
    const uint32_t* srcArgb,  // pre-converted ARGB buffer (for filters)
    int filter,
    uint32_t* xbrBuffer2x, uint32_t* xbrBuffer4x, uint32_t* xbrMidBuffer,
    unsigned maxSrcW, unsigned maxSrcH)
{
    const bool canUpscale = (width <= maxSrcW && height <= maxSrcH);

    if (filter == 0 || !canUpscale || !srcArgb) {
        // No filter: direct RGB565 blit — zero conversion loss
        blitToSurface565(window, windowMtx, src565, width, height, srcStride);
    } else if (filter == 4 || filter == 7) {
        xbr2xUpscale(srcArgb, width, height, width, xbrBuffer2x);
        blitToSurface(window, windowMtx, xbrBuffer2x, width * 2, height * 2, width * 2);
    } else if (filter == 8 || filter == 9) {
        xbr4xUpscale(srcArgb, width, height, width, xbrBuffer4x);
        blitToSurface(window, windowMtx, xbrBuffer4x, width * 4, height * 4, width * 4);
    } else if (filter == 5) {
        hq2x_32_rb(srcArgb, (uint32_t)(width * sizeof(uint32_t)),
                   xbrBuffer2x, (uint32_t)(width * 2 * sizeof(uint32_t)),
                   (int)width, (int)height);
        blitToSurface(window, windowMtx, xbrBuffer2x, width * 2, height * 2, width * 2);
    } else if (filter == 6 || filter == 10) {
        hq4x_32_rb(srcArgb, (uint32_t)(width * sizeof(uint32_t)),
                   xbrBuffer4x, (uint32_t)(width * 4 * sizeof(uint32_t)),
                   (int)width, (int)height);
        blitToSurface(window, windowMtx, xbrBuffer4x, width * 4, height * 4, width * 4);
    } else {
        blitToSurface565(window, windowMtx, src565, width, height, srcStride);
    }
}

// ---------------------------------------------------------------------------
// libretro common callbacks
// ---------------------------------------------------------------------------

static inline void libretroLog(int level, const char* fmt, ...) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case 0: prio = ANDROID_LOG_ERROR; break; // RETRO_LOG_ERROR
        case 1: prio = ANDROID_LOG_WARN;  break; // RETRO_LOG_WARN
        case 3: prio = ANDROID_LOG_DEBUG; break; // RETRO_LOG_DEBUG
        default: break;
    }
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(prio, "libretro", fmt, ap);
    va_end(ap);
}

} // namespace coreshared
