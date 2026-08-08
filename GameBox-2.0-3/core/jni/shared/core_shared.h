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
// 2xBR — Hyllian's 2xBR v3.3a (adapted from RetroArch)
// ---------------------------------------------------------------------------

static constexpr uint32_t XBR_RED_MASK   = 0x00FF0000;
static constexpr uint32_t XBR_GREEN_MASK = 0x0000FF00;
static constexpr uint32_t XBR_BLUE_MASK  = 0x000000FF;
static constexpr uint32_t XBR_LBMASK     = 0xFEFEFEFE;
static constexpr uint32_t XBR_ALPHA_MASK = 0xFF000000;

static inline float xbrDf8(uint32_t A, uint32_t B) {
    int r = abs((int)((A & XBR_RED_MASK)   >> 16) - (int)((B & XBR_RED_MASK)   >> 16));
    int g = abs((int)((A & XBR_GREEN_MASK) >>  8) - (int)((B & XBR_GREEN_MASK) >>  8));
    int b = abs((int)((A & XBR_BLUE_MASK)        ) - (int)((B & XBR_BLUE_MASK)       ));
    float y = fabsf(0.299f*r + 0.587f*g + 0.114f*b);
    float u = fabsf(-0.169f*r - 0.331f*g + 0.500f*b);
    float v = fabsf(0.500f*r - 0.419f*g - 0.081f*b);
    return 48.0f*y + 7.0f*u + 6.0f*v;
}

static inline int xbrEq8(uint32_t A, uint32_t B) {
    int r = abs((int)((A & XBR_RED_MASK)   >> 16) - (int)((B & XBR_RED_MASK)   >> 16));
    int g = abs((int)((A & XBR_GREEN_MASK) >>  8) - (int)((B & XBR_GREEN_MASK) >>  8));
    int b = abs((int)((A & XBR_BLUE_MASK)        ) - (int)((B & XBR_BLUE_MASK)       ));
    float y = fabsf(0.299f*r + 0.587f*g + 0.114f*b);
    float u = fabsf(-0.169f*r - 0.331f*g + 0.500f*b);
    float v = fabsf(0.500f*r - 0.419f*g - 0.081f*b);
    return (y <= 48.0f && u <= 7.0f && v <= 6.0f) ? 1 : 0;
}

#define XBR_BLEND_128(dst, src) \
    dst = (((src & XBR_LBMASK) >> 1) + ((dst & XBR_LBMASK) >> 1)) | XBR_ALPHA_MASK

// CRITICAL: All blend macros cast to int32_t before subtraction.
// Without this, (src & MASK) - (dst & MASK) is unsigned and wraps to a
// huge value when src < dst, causing corrupted pixels (scattered dots).
// The int32_t cast makes the subtraction signed, and >> on signed values
// is an arithmetic shift that preserves the sign, giving correct blending.
#define XBR_BLEND_32(dst, src) \
    dst = ( \
        (XBR_RED_MASK & (uint32_t)((int32_t)(dst & XBR_RED_MASK) + \
            (((int32_t)(src & XBR_RED_MASK) - (int32_t)(dst & XBR_RED_MASK)) >> 3))) | \
        (XBR_GREEN_MASK & (uint32_t)((int32_t)(dst & XBR_GREEN_MASK) + \
            (((int32_t)(src & XBR_GREEN_MASK) - (int32_t)(dst & XBR_GREEN_MASK)) >> 3))) | \
        (XBR_BLUE_MASK & (uint32_t)((int32_t)(dst & XBR_BLUE_MASK) + \
            (((int32_t)(src & XBR_BLUE_MASK) - (int32_t)(dst & XBR_BLUE_MASK)) >> 3))) ) + \
            XBR_ALPHA_MASK

#define XBR_BLEND_64(dst, src) \
    dst = ( \
        (XBR_RED_MASK & (uint32_t)((int32_t)(dst & XBR_RED_MASK) + \
            (((int32_t)(src & XBR_RED_MASK) - (int32_t)(dst & XBR_RED_MASK)) >> 2))) | \
        (XBR_GREEN_MASK & (uint32_t)((int32_t)(dst & XBR_GREEN_MASK) + \
            (((int32_t)(src & XBR_GREEN_MASK) - (int32_t)(dst & XBR_GREEN_MASK)) >> 2))) | \
        (XBR_BLUE_MASK & (uint32_t)((int32_t)(dst & XBR_BLUE_MASK) + \
            (((int32_t)(src & XBR_BLUE_MASK) - (int32_t)(dst & XBR_BLUE_MASK)) >> 2))) ) + \
            XBR_ALPHA_MASK

#define XBR_BLEND_192(dst, src) \
    dst = ( \
        (XBR_RED_MASK & (uint32_t)((int32_t)(dst & XBR_RED_MASK) + \
            (((int32_t)(src & XBR_RED_MASK) - (int32_t)(dst & XBR_RED_MASK)) * 192 >> 8))) | \
        (XBR_GREEN_MASK & (uint32_t)((int32_t)(dst & XBR_GREEN_MASK) + \
            (((int32_t)(src & XBR_GREEN_MASK) - (int32_t)(dst & XBR_GREEN_MASK)) * 192 >> 8))) | \
        (XBR_BLUE_MASK & (uint32_t)((int32_t)(dst & XBR_BLUE_MASK) + \
            (((int32_t)(src & XBR_BLUE_MASK) - (int32_t)(dst & XBR_BLUE_MASK)) * 192 >> 8))) ) + \
            XBR_ALPHA_MASK

#define XBR_BLEND_224(dst, src) \
    dst = ( \
        (XBR_RED_MASK & (uint32_t)((int32_t)(dst & XBR_RED_MASK) + \
            (((int32_t)(src & XBR_RED_MASK) - (int32_t)(dst & XBR_RED_MASK)) * 224 >> 8))) | \
        (XBR_GREEN_MASK & (uint32_t)((int32_t)(dst & XBR_GREEN_MASK) + \
            (((int32_t)(src & XBR_GREEN_MASK) - (int32_t)(dst & XBR_GREEN_MASK)) * 224 >> 8))) | \
        (XBR_BLUE_MASK & (uint32_t)((int32_t)(dst & XBR_BLUE_MASK) + \
            (((int32_t)(src & XBR_BLUE_MASK) - (int32_t)(dst & XBR_BLUE_MASK)) * 224 >> 8))) ) + \
            XBR_ALPHA_MASK

#define XBR_LEFT_UP(N3, N2, N1, px) \
    XBR_BLEND_224(E[N3], px); \
    XBR_BLEND_64(E[N2], px);  \
    E[N1] = E[N2];

#define XBR_LEFT(N3, N2, px) \
    XBR_BLEND_192(E[N3], px); \
    XBR_BLEND_64(E[N2], px);

#define XBR_UP(N3, N1, px) \
    XBR_BLEND_192(E[N3], px); \
    XBR_BLEND_64(E[N1], px);

#define XBR_DIA(N3, px) \
    XBR_BLEND_128(E[N3], px);

#define XBR_FILTRO(PE, _PI, PH, PF, PG, PC, PD, PB, PA, \
    G5, C4, G0, D0, C1, B1, F4, I4, H5, I5, A0, A1, N0, N1, N2, N3) \
    ex = (PE != PH && PE != PF); \
    if (ex) { \
        e = (xbrDf8(PE,PC) + xbrDf8(PE,PG) + xbrDf8(_PI,H5) + xbrDf8(_PI,F4)) \
             + (4.0f * xbrDf8(PH,PF)); \
        i = (xbrDf8(PH,PD) + xbrDf8(PH,I5) + xbrDf8(PF,I4) + xbrDf8(PF,PB)) \
             + (4.0f * xbrDf8(PE,_PI)); \
        if ((e < i) && ( \
            (!xbrEq8(PF,PB) && !xbrEq8(PF,PC)) || \
            (!xbrEq8(PH,PD) && !xbrEq8(PH,PG)) || \
            (xbrEq8(PE,_PI) && ((!xbrEq8(PF,F4) && !xbrEq8(PF,I4)) || \
                                (!xbrEq8(PH,H5) && !xbrEq8(PH,I5)))) || \
            xbrEq8(PE,PG) || xbrEq8(PE,PC))) \
        { \
            ke = xbrDf8(PF,PG); \
            ki = xbrDf8(PH,PC); \
            ex2 = (PE != PC && PB != PC); \
            ex3 = (PE != PG && PD != PG); \
            px = (xbrDf8(PE,PF) <= xbrDf8(PE,PH)) ? PF : PH; \
            if ((ke*2 <= ki) && ex3 && (ke >= ki*2) && ex2) { \
                XBR_LEFT_UP(N3, N2, N1, px) \
            } else if ((ke*2 <= ki) && ex3) { \
                XBR_LEFT(N3, N2, px); \
            } else if ((ke >= ki*2) && ex2) { \
                XBR_UP(N3, N1, px); \
            } else { \
                XBR_DIA(N3, px); \
            } \
        } else if (e <= i) { \
            XBR_BLEND_128(E[N3], \
                (xbrDf8(PE,PF) <= xbrDf8(PE,PH)) ? PF : PH); \
        } \
    }

// 2xBR upscale: src(w x h) -> dst(2w x 2h)
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
            uint32_t A1=getPx(x-1,y-2), B1=getPx(x,y-2), C1=getPx(x+1,y-2);
            uint32_t A0=getPx(x-2,y-1), PA=getPx(x-1,y-1), PB=getPx(x,y-1), PC=getPx(x+1,y-1), C4=getPx(x+2,y-1);
            uint32_t D0=getPx(x-2,y),   PD=getPx(x-1,y),   PE=getPx(x,y),   PF=getPx(x+1,y),   F4=getPx(x+2,y);
            uint32_t G0=getPx(x-2,y+1), PG=getPx(x-1,y+1), PH=getPx(x,y+1), _PI=getPx(x+1,y+1), I4=getPx(x+2,y+1);
            uint32_t G5=getPx(x-1,y+2), H5=getPx(x,y+2), I5=getPx(x+1,y+2);

            uint32_t E[4];
            float e, i, ke, ki;
            uint32_t ex, ex2, ex3, px;

            E[0] = E[1] = E[2] = E[3] = PE;

            XBR_FILTRO(PE, _PI, PH, PF, PG, PC, PD, PB, PA,
                G5, C4, G0, D0, C1, B1, F4, I4, H5, I5, A0, A1, 0, 1, 2, 3);
            XBR_FILTRO(PE, PC, PF, PB, _PI, PA, PH, PD, PG,
                I4, A1, I5, H5, A0, D0, B1, C1, F4, C4, G5, G0, 2, 0, 3, 1);
            XBR_FILTRO(PE, PA, PB, PD, PC, PG, PF, PH, _PI,
                C1, G0, C4, F4, G5, H5, D0, A0, B1, A1, I4, I5, 3, 2, 1, 0);
            XBR_FILTRO(PE, PG, PD, PH, PA, _PI, PB, PF, PC,
                A0, I5, A1, B1, I4, F4, H5, G5, D0, G0, C1, C4, 1, 3, 0, 2);

            const unsigned x2 = x * 2;
            const unsigned y2 = y * 2;
            dst[y2 * dstStride + x2]       = E[0];
            dst[y2 * dstStride + x2 + 1]  = E[1];
            dst[(y2+1) * dstStride + x2]   = E[2];
            dst[(y2+1) * dstStride + x2+1] = E[3];
        }
    }
}

// 4xBR cascade: two passes of 2xBR (w x h -> 2w x 2h -> 4w x 4h)
// Uses caller-provided midBuffer for the intermediate pass.
static inline void xbr4xUpscale(const uint32_t* src, unsigned sw, unsigned sh,
                                 size_t srcStride, uint32_t* dst, uint32_t* midBuffer) {
    xbr2xUpscale(src, sw, sh, srcStride, midBuffer);
    xbr2xUpscale(midBuffer, sw * 2, sh * 2, sw * 2, dst);
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
    uint32_t* xbrMidBuffer,   // at least width*2 * height*2 (for 4xBR cascade)
    unsigned maxSrcW, unsigned maxSrcH)
{
    const bool canUpscale = (width <= maxSrcW && height <= maxSrcH);

    if ((filter == 4 || filter == 7) && canUpscale) {
        xbr2xUpscale(src, width, height, srcStride, xbrBuffer2x);
        blitToSurface(window, windowMtx, xbrBuffer2x, width * 2, height * 2, width * 2);
    } else if ((filter == 8 || filter == 9) && canUpscale) {
        xbr4xUpscale(src, width, height, srcStride, xbrBuffer4x, xbrMidBuffer);
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
        xbr4xUpscale(srcArgb, width, height, width, xbrBuffer4x, xbrMidBuffer);
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
