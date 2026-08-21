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
#include <cstdio>
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
//
// PERFORMANCE: When the source dimensions (w×h) match the native window buffer
// dimensions (dstW×dstH), this is a fast 1:1 row-copy with no scaling math.
// The caller should call setBuffersGeometry(window, w, h, RGBA_8888) before
// the first blit so the buffer matches the source size — then the Android
// hardware compositor handles the upscale to the display resolution for free
// (GPU-accelerated), instead of the CPU doing per-pixel float scaling.
static inline void blitToSurface(ANativeWindow* window, std::mutex& windowMtx,
                                  const uint32_t* src, unsigned w, unsigned h,
                                  size_t srcStride,
                                  bool highQualityScaling = false) {
    std::lock_guard<std::mutex> lk(windowMtx);
    if (!window) return;

    // When highQualityScaling is true, the buffer was set to display
    // resolution (0x0) by setSurface() — DON'T override it here, so the
    // C++ per-pixel scaler runs (sharper but heavier CPU).
    // When false, set the buffer to source resolution for a fast 1:1 blit.
    if (!highQualityScaling) {
        int32_t geomRc = ANativeWindow_setBuffersGeometry(window, (int32_t)w, (int32_t)h, WINDOW_FORMAT_RGBA_8888);
        if (geomRc != 0) {
            LOGE("ANativeWindow_setBuffersGeometry(%ux%u) failed: %d", w, h, geomRc);
        }
    }

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
        LOGW("blitToSurface: buffer has zero dimensions (%ux%u) — skipping", dstW, dstH);
        ANativeWindow_unlockAndPost(window);
        return;
    }
    // Log buffer dimensions on first frame or when they change
    static uint32_t lastDstW = 0, lastDstH = 0;
    if (dstW != lastDstW || dstH != lastDstH) {
        LOGI("blitToSurface: src=%ux%u dst=%ux%u fmt=%d hq=%d",
             w, h, dstW, dstH, buf.format, highQualityScaling ? 1 : 0);
        lastDstW = dstW;
        lastDstH = dstH;
    }

    // Fast path: 1:1 copy when source and destination match.
    // Source is ARGB (0xFFRRGGBB). Android's WINDOW_FORMAT_RGBA_8888 is
    // actually stored as BGRA in memory on little-endian ARM, so we must
    // write each channel to the correct byte position explicitly.
    if (dstW == w && dstH == h &&
        (buf.format == WINDOW_FORMAT_RGBA_8888 ||
         buf.format == WINDOW_FORMAT_RGBX_8888)) {
        uint8_t* dst = static_cast<uint8_t*>(buf.bits);
        const uint32_t dstStridePx = (uint32_t)buf.stride;
        for (uint32_t y = 0; y < h; ++y) {
            const uint32_t* srow = src + y * srcStride;
            uint8_t* drow = dst + y * dstStridePx * 4;
            for (uint32_t x = 0; x < w; ++x) {
                uint32_t px = srow[x];
                drow[x * 4 + 0] = (px >> 16) & 0xFF;  // R
                drow[x * 4 + 1] = (px >> 8) & 0xFF;   // G
                drow[x * 4 + 2] = px & 0xFF;          // B
                drow[x * 4 + 3] = 0xFF;               // A
            }
        }
        ANativeWindow_unlockAndPost(window);
        return;
    }

    // Slow fallback path: per-pixel nearest-neighbor scaling.
    // Only hit when highQualityScaling=true (display-res buffer) or if the
    // surface format doesn't match expectations.
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
    unsigned maxSrcW, unsigned maxSrcH,
    bool highQualityScaling = false)
{
    const bool canUpscale = (width <= maxSrcW && height <= maxSrcH);

    // CPU path — 2xBR v3.3a (from RetroArch) with int32 blend fixes
    if ((filter == 4 || filter == 7) && canUpscale) {
        xbr2xUpscale(src, width, height, srcStride, xbrBuffer2x);
        blitToSurface(window, windowMtx, xbrBuffer2x, width * 2, height * 2, width * 2, highQualityScaling);
    } else if ((filter == 8 || filter == 9) && canUpscale) {
        xbr4xUpscale(src, width, height, srcStride, xbrBuffer4x, xbrMidBuffer);
        blitToSurface(window, windowMtx, xbrBuffer4x, width * 4, height * 4, width * 4, highQualityScaling);
    } else if (filter == 5 && canUpscale) {
        hq2x_32_rb(src, (uint32_t)(srcStride * sizeof(uint32_t)),
                   xbrBuffer2x, (uint32_t)(width * 2 * sizeof(uint32_t)),
                   (int)width, (int)height);
        blitToSurface(window, windowMtx, xbrBuffer2x, width * 2, height * 2, width * 2, highQualityScaling);
    } else if ((filter == 6 || filter == 10) && canUpscale) {
        hq4x_32_rb(src, (uint32_t)(srcStride * sizeof(uint32_t)),
                   xbrBuffer4x, (uint32_t)(width * 4 * sizeof(uint32_t)),
                   (int)width, (int)height);
        blitToSurface(window, windowMtx, xbrBuffer4x, width * 4, height * 4, width * 4, highQualityScaling);
    } else {
        blitToSurface(window, windowMtx, src, width, height, srcStride, highQualityScaling);
    }
}

// ---------------------------------------------------------------------------
// Audio ring buffer — lock-based stereo int16 ring buffer
// ---------------------------------------------------------------------------

class AudioRingBuffer {
public:
    // LOW-LATENCY: Reduced from 65536 to 8192 samples (~85ms at 48kHz stereo
    // was ~340ms before). The previous huge buffer caused noticeable audio
    // delay in DOSBox-Pure (sound effects lagging behind gameplay by
    // 100-300ms). 8192 samples = ~42 stereo frames * 2 = ~85ms max buffer,
    // which is enough to absorb emulation thread jitter without underrunning.
    //
    // If underruns cause crackling, increase to 16384. Do NOT return to
    // 65536 Б─■ that reintroduces the latency problem.
    static constexpr size_t kDefaultCap = 1u << 13; // 8192 samples

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
// Streaming audio resampler (shared by all cores)
//
// Converts from the core's native sample rate (e.g. 32768 Hz for GBA,
// ~32040 Hz for SNES, 44100 Hz for NES) to Android's native 48000 Hz.
//
// Uses linear interpolation, which is sufficient for the 8-bit/4-bit audio
// sources used by these consoles. The resampler maintains state between
// calls so it can process partial frames and maintain continuity.
//
// Without this resampler, AudioTrack is created at the core's native rate
// and Android's AudioFlinger performs low-quality resampling to 48000 Hz
// internally. On phones the native rate is often 44100 Hz (so NES/FCEUmm
// is unaffected), but on TV boxes with HDMI output the native rate is
// always 48000 Hz, so AudioFlinger resamples 44100→48000 (NES) and
// 32040→48000 (SNES) — producing audible buzzing, crackling, and
// muffled audio. Doing the resampling in our own native code with a
// proper linear interpolator eliminates these artifacts.
//
// This implementation is shared by NES, SNES, and GB/GBC/GBA cores.
// ---------------------------------------------------------------------------
static constexpr int TARGET_SAMPLE_RATE = 48000; // Android native rate

class AudioResampler {
public:
    static constexpr int SRC_BUF_SIZE = 4096; // Max source frames per pass

    double ratio;           // srcRate / dstRate (e.g. 32768/48000 ≈ 0.68267)
    double pos;             // Fractional position in source buffer (unused; kept for compat)
    int    srcRate;         // Source sample rate
    int    dstRate;         // Destination sample rate (TARGET_SAMPLE_RATE)
    int16_t prevL, prevR;   // Previous output sample for continuity at buffer edges
    bool   active;          // true if resampling is needed (srcRate != dstRate)

    int    srcBufCount;                          // Number of valid frames in srcBuf
    double srcBufPos;                            // Fractional read position in srcBuf
    int16_t srcBuf[SRC_BUF_SIZE * 2];            // Interleaved stereo

    AudioResampler() : pos(0.0), srcRate(0), dstRate(TARGET_SAMPLE_RATE),
                       prevL(0), prevR(0), active(false),
                       srcBufCount(0), srcBufPos(0.0) {
        ratio = 1.0;
    }

    void init(int sourceRate, int destRate = TARGET_SAMPLE_RATE) {
        srcRate = sourceRate > 0 ? sourceRate : 48000;
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
    int readResampled(AudioRingBuffer& audio, int16_t* out, int maxFrames) {
        if (!active) {
            // No resampling needed — pass through directly
            return audio.read(out, maxFrames);
        }

        int produced = 0;

        while (produced < maxFrames) {
            // Refill internal source buffer when we've consumed most of it.
            // Keep at least 2 frames for interpolation.
            int remaining = srcBufCount - (int)srcBufPos;
            if (remaining < 2) {
                // Shift unconsumed samples to the beginning
                if (remaining > 0 && (int)srcBufPos > 0) {
                    memmove(srcBuf, srcBuf + (int)srcBufPos * 2,
                            remaining * 2 * sizeof(int16_t));
                }

                // Read more source frames from the ring buffer
                int toRead = SRC_BUF_SIZE - remaining;
                int got = audio.read(srcBuf + remaining * 2, toRead);
                srcBufCount = remaining + got;
                srcBufPos = 0.0;

                if (srcBufCount < 2) {
                    // Not enough source samples for interpolation.
                    // Fill remaining output with zeros (underrun).
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

            // Advance source position by ratio.
            // Each output sample at dstRate corresponds to ratio source samples.
            srcBufPos += ratio;
        }

        return produced;
    }
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
        int32_t geomRc = ANativeWindow_setBuffersGeometry(window, 0, 0, WINDOW_FORMAT_RGBA_8888);
        if (geomRc != 0) {
            LOGE("setSurface: ANativeWindow_setBuffersGeometry(0,0) failed: %d", geomRc);
        } else {
            LOGI("setSurface: window attached (0x0 -> display resolution)");
        }
    } else {
        LOGI("setSurface: window detached");
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

// ---------------------------------------------------------------------------
// Battery-backed SRAM (cartridge save RAM) persistence
// ---------------------------------------------------------------------------
//
// libretro cores do NOT auto-persist cartridge SRAM to disk. The frontend is
// responsible for:
//   1. Loading the .srm file into the core's SAVE_RAM region after
//      retro_load_game() succeeds.
//   2. Writing the SAVE_RAM region back to the .srm file before
//      retro_unload_game() is called.
//
// The .srm file is placed in the frontend's save directory, named after the
// ROM's basename with a .srm extension (RetroArch convention).
//
// These helpers take the SRAM pointer and size (obtained via
// retro_get_memory_data(RETRO_MEMORY_SAVE_RAM) and
// retro_get_memory_size(RETRO_MEMORY_SAVE_RAM)) so they don't need to
// depend on libretro.h directly.

// Derive the .srm path for a given ROM path and save directory.
// e.g. "/sdcard/roms/game.nes" + "/data/saves" -> "/data/saves/game.srm"
//
// If `explicitName` is non-empty, it is used as the basename verbatim
// (no extension stripping). This lets the frontend pass a stable game
// identifier (e.g. "pokemon_emerald") so that content:// URI ROMs that
// are copied to a shared temp file (e.g. "temp_rom.gba") still get
// per-game .srm files instead of clobbering each other.
static inline std::string getSrmPath(const std::string& saveDir,
                                      const std::string& romPath,
                                      const std::string& explicitName = "") {
    std::string basename;
    if (!explicitName.empty()) {
        // Use the frontend-provided stable name verbatim. We still strip
        // a trailing ".srm"/".sav" if present to avoid double extensions.
        basename = explicitName;
        if (basename.size() >= 4) {
            std::string tail = basename.substr(basename.size() - 4);
            for (auto& c : tail) c = (char)tolower((unsigned char)c);
            if (tail == ".srm" || tail == ".sav") {
                basename = basename.substr(0, basename.size() - 4);
            }
        }
    } else {
        // Extract basename (filename without directory)
        size_t slash = romPath.find_last_of('/');
        basename = (slash != std::string::npos)
                   ? romPath.substr(slash + 1)
                   : romPath;
        // Strip extension
        size_t dot = basename.find_last_of('.');
        if (dot != std::string::npos) {
            basename = basename.substr(0, dot);
        }
    }
    if (saveDir.empty()) {
        return basename + ".srm";
    }
    // Ensure saveDir doesn't end with '/'
    std::string dir = saveDir;
    if (!dir.empty() && dir.back() == '/') dir.pop_back();
    return dir + "/" + basename + ".srm";
}

// Load cartridge SRAM from disk into the core's SAVE_RAM buffer.
// Called AFTER retro_load_game() succeeds.
// sram        — pointer from retro_get_memory_data(RETRO_MEMORY_SAVE_RAM)
// sramSize    — size  from retro_get_memory_size(RETRO_MEMORY_SAVE_RAM)
// saveDir     — frontend save directory (RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY)
// romPath     — absolute path to the ROM file (used to derive the .srm filename
//               if `explicitName` is empty)
// explicitName— optional stable game identifier (e.g. "pokemon_emerald") used
//               as the .srm basename. Required for content:// URI ROMs that are
//               copied to a shared temp file.
static inline void loadSramFromDisk(void* sram, size_t sramSize,
                                     const std::string& saveDir,
                                     const std::string& romPath,
                                     const std::string& explicitName = "") {
    if (!sram || sramSize == 0) {
        LOGI("SRAM load: no SAVE_RAM region (sram=%p, size=%zu) — skipping",
             sram, sramSize);
        return;
    }
    std::string srmPath = getSrmPath(saveDir, romPath, explicitName);
    FILE* f = std::fopen(srmPath.c_str(), "rb");
    if (!f) {
        LOGI("SRAM load: no existing save at %s — starting fresh", srmPath.c_str());
        return;
    }
    std::fseek(f, 0, SEEK_END);
    long fileSize = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (fileSize <= 0) {
        std::fclose(f);
        LOGW("SRAM load: %s is empty — skipping", srmPath.c_str());
        return;
    }
    // Read at most sramSize bytes (ignore trailing data if file is larger)
    size_t toRead = ((size_t)fileSize < sramSize) ? (size_t)fileSize : sramSize;
    size_t rd = std::fread(sram, 1, toRead, f);
    std::fclose(f);
    // If the file is smaller than the buffer, zero-fill the remainder so we
    // don't leave stale data from a previous game in the buffer.
    if (rd < sramSize) {
        std::memset(static_cast<uint8_t*>(sram) + rd, 0, sramSize - rd);
    }
    LOGI("SRAM load: %zu bytes from %s (buffer=%zu, file=%ld)",
         rd, srmPath.c_str(), sramSize, fileSize);
}

// Write the core's SAVE_RAM buffer back to disk as a .srm file.
// Called BEFORE retro_unload_game() so the buffer is still valid.
static inline void saveSramToDisk(void* sram, size_t sramSize,
                                   const std::string& saveDir,
                                   const std::string& romPath,
                                   const std::string& explicitName = "") {
    if (!sram || sramSize == 0) {
        LOGI("SRAM save: no SAVE_RAM region (sram=%p, size=%zu) — skipping",
             sram, sramSize);
        return;
    }
    std::string srmPath = getSrmPath(saveDir, romPath, explicitName);
    FILE* f = std::fopen(srmPath.c_str(), "wb");
    if (!f) {
        LOGE("SRAM save: cannot open %s for write", srmPath.c_str());
        return;
    }
    size_t wr = std::fwrite(sram, 1, sramSize, f);
    std::fclose(f);
    LOGI("SRAM save: %zu bytes to %s", wr, srmPath.c_str());
}

} // namespace coreshared
