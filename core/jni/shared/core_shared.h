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

// Include GPU video filter for hardware-accelerated XBR rendering
#include "gpu_video_filter.h"

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
// 2xBR-lv2 — Hyllian's xBR-lv2 (adapted from mGBA Android GLSL shader)
//
// Replaces the old 2xBR v3.3a which produced scattered dot artifacts due to:
//   1. Overly loose equality threshold (y<=48,u<=7,v<=6) causing false edges
//   2. Overly aggressive conditions (xbrEq8(PE,PG)||xbrEq8(PE,PC)) firing on
//      too many pixels
//   3. The e<=i fallback applying BLEND_128 (50% blend) even for tiny color
//      differences, creating visible dots at high-contrast edges
//
// The lv2 algorithm fixes these by:
//   1. Using configurable Y-weighted luminance with tight eq threshold (25)
//   2. Multi-level edge detection (lv0, lv1, lv2_left, lv2_up) that prevents
//      false positives
//   3. Continuous interpolation based on sub-pixel position instead of
//      discrete blend amounts (128/192/224), producing smooth edges
//   4. Minimum-distance selection between two diagonal candidates
//
// Copyright (C) 2011-2015 Hyllian - sergiogdb@gmail.com
// SPDX-License-Identifier: MIT
// ---------------------------------------------------------------------------

// Configuration matching mGBA Android xbr-lv2.shader defaults
static constexpr float XBR_LV2_Y_WEIGHT    = 48.0f;
static constexpr float XBR_LV2_EQ_THRESH   = 25.0f;
static constexpr float XBR_LV2_SCALE       = 2.0f;
static constexpr float XBR_LV2_COEFF       = 2.0f;

// Rec. 709 luminance coefficients
static constexpr float XBR_LV2_YR = 0.2126f;
static constexpr float XBR_LV2_YG = 0.7152f;
static constexpr float XBR_LV2_YB = 0.0722f;

// Helpers for ARGB uint32 pixel access (0xAARRGGBB)
static inline float xbrR(uint32_t c) { return (float)((c >> 16) & 0xFF); }
static inline float xbrG(uint32_t c) { return (float)((c >>  8) & 0xFF); }
static inline float xbrB(uint32_t c) { return (float)( c        & 0xFF); }

// Y-weighted luminance for color comparison (matches shader's `Y_WEIGHT * Y` dot)
static inline float xbrLum(uint32_t c) {
    return XBR_LV2_Y_WEIGHT * (XBR_LV2_YR * xbrR(c) + XBR_LV2_YG * xbrG(c) + XBR_LV2_YB * xbrB(c));
}

// Absolute difference of luminance values (scalar version of shader's `df`)
static inline float xbrDf(float a, float b) { return fabsf(a - b); }

// Equality check: true if luminance difference is below threshold
// (scalar version of shader's `eq` using `XBR_EQ_THRESHOLD`)
static inline bool xbrEq(float a, float b) { return xbrDf(a, b) < XBR_LV2_EQ_THRESH; }

// Color distance in RGB space (Manhattan) for final candidate selection
static inline float xbrCdf(uint32_t c1, uint32_t c2) {
    return fabsf(xbrR(c1) - xbrR(c2)) + fabsf(xbrG(c1) - xbrG(c2)) + fabsf(xbrB(c1) - xbrB(c2));
}

// Pack RGB floats back to ARGB uint32 with clamping
static inline uint32_t xbrPack(float r, float g, float b) {
    int ri = (int)(r + 0.5f); if (ri < 0) ri = 0; if (ri > 255) ri = 255;
    int gi = (int)(g + 0.5f); if (gi < 0) gi = 0; if (gi > 255) gi = 255;
    int bi = (int)(b + 0.5f); if (bi < 0) bi = 0; if (bi > 255) bi = 255;
    return 0xFF000000u | ((uint32_t)ri << 16) | ((uint32_t)gi << 8) | (uint32_t)bi;
}

// Clamp float to [0, 1]
static inline float xbrClamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

// Compute one output pixel of the xBR-lv2 algorithm.
// fpx, fpy: fractional position within the source texel (0..1 range)
// For 2x upscale, sub-pixel centers are at (0.25,0.25), (0.75,0.25), etc.
//
// The algorithm processes 4 edge directions simultaneously using arrays[4],
// mirroring the shader's vec4 operations. Each array index corresponds to a
// 90° rotation: [0]=right, [1]=down, [2]=left, [3]=up.
static inline uint32_t xbrLv2Pixel(
    uint32_t A1, uint32_t B1, uint32_t C1,
    uint32_t A,  uint32_t B,  uint32_t C,
    uint32_t D,  uint32_t E,  uint32_t F,
    uint32_t G,  uint32_t H,  uint32_t I,
    uint32_t A0, uint32_t D0, uint32_t G0,
    uint32_t C4, uint32_t F4, uint32_t I4,
    uint32_t G5, uint32_t H5, uint32_t I5,
    float fpx, float fpy)
{
    // Luminance values for the 4 orthogonal neighbors (B,D,H,F) and 4 diagonals (C,A,G,I)
    // Layout matches shader: b[0]=B, b[1]=D, b[2]=H, b[3]=F
    float b[4] = { xbrLum(B), xbrLum(D), xbrLum(H), xbrLum(F) };
    // c[0]=C, c[1]=A, c[2]=G, c[3]=I
    float c[4] = { xbrLum(C), xbrLum(A), xbrLum(G), xbrLum(I) };
    // e[0..3] = all E (center pixel)
    float e[4] = { xbrLum(E), xbrLum(E), xbrLum(E), xbrLum(E) };

    // Rotated permutations (shader's .yzwx, .wxyz, .zwxy, .wxyz)
    float d[4]  = { b[1], b[2], b[3], b[0] }; // b.yzwx
    float f[4]  = { b[3], b[0], b[1], b[2] }; // b.wxyz
    float g[4]  = { c[2], c[3], c[0], c[1] }; // c.zwxy
    float h[4]  = { b[2], b[3], b[0], b[1] }; // b.zwxy
    float iv[4] = { c[3], c[0], c[1], c[2] }; // c.wxyz

    // Outer-ring luminance values
    float i4[4] = { xbrLum(I4), xbrLum(C1), xbrLum(A0), xbrLum(G5) };
    float i5[4] = { xbrLum(I5), xbrLum(C4), xbrLum(A1), xbrLum(G0) };
    float h5[4] = { xbrLum(H5), xbrLum(F4), xbrLum(B1), xbrLum(D0) };
    float f4[4] = { h5[1], h5[2], h5[3], h5[0] }; // h5.yzwx

    // Fractional position within the texel
    float fp[2] = { fpx, fpy };

    // Shader constants for interpolation line equations
    static constexpr float Ao[4] = { 1.0f, -1.0f, -1.0f,  1.0f };
    static constexpr float Bo[4] = { 1.0f,  1.0f, -1.0f, -1.0f };
    static constexpr float Co[4] = { 1.5f,  0.5f, -0.5f,  0.5f };
    static constexpr float Ax[4] = { 1.0f, -1.0f, -1.0f,  1.0f };
    static constexpr float Bx[4] = { 0.5f,  2.0f, -0.5f, -2.0f };
    static constexpr float Ay[4] = { 1.0f, -1.0f, -1.0f,  1.0f };
    static constexpr float By[4] = { 2.0f,  0.5f, -2.0f, -0.5f };
    static constexpr float Ci[4] = { 0.25f, 0.25f, 0.25f, 0.25f };

    float delta  = 1.0f / XBR_LV2_SCALE;
    float deltaL[4] = { 0.5f/XBR_LV2_SCALE, 1.0f/XBR_LV2_SCALE, 0.5f/XBR_LV2_SCALE, 1.0f/XBR_LV2_SCALE };
    float deltaU[4] = { deltaL[1], deltaL[0], deltaL[3], deltaL[2] }; // deltaL.yxwz

    // Compute interpolation line equations for each direction
    float fx[4], fx_left[4], fx_up[4];
    for (int k = 0; k < 4; ++k) {
        fx[k]      = Ao[k]*fpy + Bo[k]*fpx;
        fx_left[k] = Ax[k]*fpy + Bx[k]*fpx;
        fx_up[k]   = Ay[k]*fpy + By[k]*fpx;
    }

    // Interpolation restrictions — multi-level edge detection
    // lv0/lv1: center pixel must differ from BOTH f and h neighbors
    bool interp_lv0[4], interp_lv1[4];
    bool interp_lv2_left[4], interp_lv2_up[4];
    for (int k = 0; k < 4; ++k) {
        interp_lv0[k] = (e[k] != f[k]) && (e[k] != h[k]);
        interp_lv1[k] = interp_lv0[k];
        // lv2: additional constraints to prevent false edge detection
        interp_lv2_left[k] = (e[k] != g[k]) && (d[k] != g[k]);
        interp_lv2_up[k]   = (e[k] != c[k]) && (b[k] != c[k]);
    }

    // Interpolation weights for 45°, 30°, 60°, and interior 45° lines
    float fx45i[4], fx45[4], fx30[4], fx60[4];
    for (int k = 0; k < 4; ++k) {
        fx45i[k] = xbrClamp01((fx[k]      + delta      - Co[k] - Ci[k]) / (2.0f * delta));
        fx45[k]  = xbrClamp01((fx[k]      + delta      - Co[k]         ) / (2.0f * delta));
        fx30[k]  = xbrClamp01((fx_left[k] + deltaL[k]  - Co[k]         ) / (2.0f * deltaL[k]));
        fx60[k]  = xbrClamp01((fx_up[k]   + deltaU[k]  - Co[k]         ) / (2.0f * deltaU[k]));
    }

    // Weighted distance: determines which diagonal has the stronger edge
    // wd1 = distance for the (e→f, e→h) diagonal
    // wd2 = distance for the (e→c, e→g) diagonal (rotated 45°)
    float wd1[4], wd2[4];
    for (int k = 0; k < 4; ++k) {
        wd1[k] = xbrDf(e[k],c[k]) + xbrDf(e[k],g[k]) + xbrDf(iv[k],h5[k]) + xbrDf(iv[k],f4[k]) + 4.0f*xbrDf(h[k],f[k]);
        wd2[k] = xbrDf(h[k],d[k]) + xbrDf(h[k],i5[k]) + xbrDf(f[k],i4[k]) + xbrDf(f[k],b[k]) + 4.0f*xbrDf(e[k],iv[k]);
    }

    // Edge detection rules at multiple levels
    bool edri[4], edr[4], edr_left[4], edr_up[4];
    for (int k = 0; k < 4; ++k) {
        // edri: inclusive edge detection (wd1 <= wd2) with lv0 restriction
        edri[k] = (wd1[k] <= wd2[k]) && interp_lv0[k];
        // edr: strict edge detection (wd1 < wd2) with lv1 restriction
        edr[k]  = (wd1[k] <  wd2[k]) && interp_lv1[k];

        // lv2-level edge direction checks using the LV2_COEFFICIENT
        edr_left[k] = (XBR_LV2_COEFF * xbrDf(f[k],g[k]) <= xbrDf(h[k],c[k])) && interp_lv2_left[k];
        edr_up[k]   = (xbrDf(f[k],g[k]) >= XBR_LV2_COEFF * xbrDf(h[k],c[k])) && interp_lv2_up[k];
    }

    // Additional constraints: edr excludes overlapping regions with edri
    // edr_left and edr_up are further constrained by edr and equality checks
    for (int k = 0; k < 4; ++k) {
        int k_prev = (k + 3) & 3; // (k-1) mod 4 = k.yzwx predecessor
        int k_next = (k + 1) & 3; // (k+1) mod 4 = k.wxyz successor
        // nand(edri.yzwx, edri.wxyz) — exclude if both neighboring directions are edri
        edr[k] = edr[k] && !(edri[k_prev] && edri[k_next]);
        // edr_left requires edr AND e==c (edge aligns with left direction)
        edr_left[k] = edr_left[k] && edr[k] && xbrEq(e[k], c[k]);
        // edr_up requires edr AND e==g (edge aligns with up direction)
        edr_up[k] = edr_up[k] && edr[k] && xbrEq(e[k], g[k]);
    }

    // Apply edge detection rules to interpolation weights
    for (int k = 0; k < 4; ++k) {
        fx45[k]  *= edr[k]        ? 1.0f : 0.0f;
        fx30[k]  *= edr_left[k]   ? 1.0f : 0.0f;
        fx60[k]  *= edr_up[k]     ? 1.0f : 0.0f;
        fx45i[k] *= edri[k]       ? 1.0f : 0.0f;
    }

    // Determine which neighbor (F or H) is closer to center E for each direction
    bool px[4];
    for (int k = 0; k < 4; ++k) {
        px[k] = xbrDf(e[k], f[k]) <= xbrDf(e[k], h[k]);
    }

    // Maximum interpolation weight across all angles for each direction
    float maximos[4];
    for (int k = 0; k < 4; ++k) {
        float m = fx30[k];
        if (fx60[k]  > m) m = fx60[k];
        if (fx45[k]  > m) m = fx45[k];
        if (fx45i[k] > m) m = fx45i[k];
        maximos[k] = m;
    }

    // Compute two candidate results using the actual RGB colors
    // res1 blends in directions 0 (right) and 2 (left)
    // res2 blends in directions 1 (down) and 3 (up)
    float Er = xbrR(E), Eg = xbrG(E), Eb = xbrB(E);

    // Direction 0: blend between H and F based on px[0]
    float m0r = px[0] ? xbrR(H) : xbrR(F);
    float m0g = px[0] ? xbrG(H) : xbrG(F);
    float m0b = px[0] ? xbrB(H) : xbrB(F);
    // Direction 2: blend between B and D based on px[2]
    float m2r = px[2] ? xbrR(B) : xbrR(D);
    float m2g = px[2] ? xbrG(B) : xbrG(D);
    float m2b = px[2] ? xbrB(B) : xbrB(D);

    // res1: start from E, blend direction 0, then direction 2
    float r1r = Er + (m0r - Er) * maximos[0];
    float r1g = Eg + (m0g - Eg) * maximos[0];
    float r1b = Eb + (m0b - Eb) * maximos[0];
    r1r = r1r + (m2r - r1r) * maximos[2];
    r1g = r1g + (m2g - r1g) * maximos[2];
    r1b = r1b + (m2b - r1b) * maximos[2];

    // Direction 1: blend between F and B based on px[1]
    float m1r = px[1] ? xbrR(F) : xbrR(B);
    float m1g = px[1] ? xbrG(F) : xbrG(B);
    float m1b = px[1] ? xbrB(F) : xbrB(B);
    // Direction 3: blend between D and H based on px[3]
    float m3r = px[3] ? xbrR(D) : xbrR(H);
    float m3g = px[3] ? xbrG(D) : xbrG(H);
    float m3b = px[3] ? xbrB(D) : xbrB(H);

    // res2: start from res1 (not E!), blend direction 1, then direction 3
    float r2r = r1r + (m1r - r1r) * maximos[1];
    float r2g = r1g + (m1g - r1g) * maximos[1];
    float r2b = r1b + (m1b - r1b) * maximos[1];
    r2r = r2r + (m3r - r2r) * maximos[3];
    r2g = r2g + (m3g - r2g) * maximos[3];
    r2b = r2b + (m3b - r2b) * maximos[3];

    // Select the result closer to center pixel E (minimum color distance)
    float dist1 = fabsf(Er-r1r) + fabsf(Eg-r1g) + fabsf(Eb-r1b);
    float dist2 = fabsf(Er-r2r) + fabsf(Eg-r2g) + fabsf(Eb-r2b);

    if (dist1 <= dist2) {
        return xbrPack(r1r, r1g, r1b);
    } else {
        return xbrPack(r2r, r2g, r2b);
    }
}

// 2xBR-lv2 upscale: src(w x h) -> dst(2w x 2h)
// Processes each source pixel and computes 4 output sub-pixels using the lv2 algorithm.
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
            // 12-texel neighborhood (6x6 minus corners)
            uint32_t A1=getPx(x-1,y-2), B1=getPx(x,y-2), C1=getPx(x+1,y-2);
            uint32_t A0=getPx(x-2,y-1), PA=getPx(x-1,y-1), PB=getPx(x,y-1), PC=getPx(x+1,y-1), C4=getPx(x+2,y-1);
            uint32_t D0=getPx(x-2,y),   PD=getPx(x-1,y),   PE=getPx(x,y),   PF=getPx(x+1,y),   F4=getPx(x+2,y);
            uint32_t G0=getPx(x-2,y+1), PG=getPx(x-1,y+1), PH=getPx(x,y+1), PI=getPx(x+1,y+1), I4=getPx(x+2,y+1);
            uint32_t G5=getPx(x-1,y+2), H5=getPx(x,y+2), I5=getPx(x+1,y+2);

            // Compute 4 sub-pixels at fractional positions within the texel
            // For 2x upscale, sub-pixel centers are at (0.25, 0.75) etc.
            // (matching how the GPU rasterizes at 2x resolution)
            const unsigned x2 = x * 2;
            const unsigned y2 = y * 2;

            dst[y2 * dstStride + x2] = xbrLv2Pixel(
                A1,B1,C1, PA,PB,PC, PD,PE,PF, PG,PH,PI,
                A0,D0,G0, C4,F4,I4, G5,H5,I5, 0.25f, 0.25f);

            dst[y2 * dstStride + x2 + 1] = xbrLv2Pixel(
                A1,B1,C1, PA,PB,PC, PD,PE,PF, PG,PH,PI,
                A0,D0,G0, C4,F4,I4, G5,H5,I5, 0.75f, 0.25f);

            dst[(y2+1) * dstStride + x2] = xbrLv2Pixel(
                A1,B1,C1, PA,PB,PC, PD,PE,PF, PG,PH,PI,
                A0,D0,G0, C4,F4,I4, G5,H5,I5, 0.25f, 0.75f);

            dst[(y2+1) * dstStride + x2 + 1] = xbrLv2Pixel(
                A1,B1,C1, PA,PB,PC, PD,PE,PF, PG,PH,PI,
                A0,D0,G0, C4,F4,I4, G5,H5,I5, 0.75f, 0.75f);
        }
    }
}

// 4xBR-lv2 cascade: two passes of 2xBR-lv2 (w x h -> 2w x 2h -> 4w x 4h)
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
    gpufilter::GpuVideoFilter* gpuFilter = nullptr)  // GPU filter (optional)
{
    const bool canUpscale = (width <= maxSrcW && height <= maxSrcH);

    // GPU-accelerated path for XBR filters (massive performance improvement)
    if (gpufilter::GpuVideoFilter::isGpuFilter(filter) && gpuFilter
        && gpuFilter->initialized && canUpscale) {
        gpuFilter->renderFrame(src, width, height, srcStride);
        return;
    }

    // CPU fallback path
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
    unsigned maxSrcW, unsigned maxSrcH,
    gpufilter::GpuVideoFilter* gpuFilter = nullptr)  // GPU filter (optional)
{
    const bool canUpscale = (width <= maxSrcW && height <= maxSrcH);

    // GPU-accelerated path for XBR filters
    if (gpufilter::GpuVideoFilter::isGpuFilter(filter) && gpuFilter
        && gpuFilter->initialized && canUpscale && srcArgb) {
        gpuFilter->renderFrame(srcArgb, width, height, width);
        return;
    }

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
