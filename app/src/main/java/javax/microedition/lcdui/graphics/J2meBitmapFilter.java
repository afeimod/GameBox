package javax.microedition.lcdui.graphics;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * CPU-based bitmap filter for J2ME games (non-GL rendering modes).
 *
 * <p>Uses the <b>exact same algorithms</b> as the reference GLSL shaders:
 * <ul>
 *   <li>2xBR / 4xBR: Hyllian's 2xBR / 4xBR with {@code reduce()} color
 *       packing (matches the GLSL shader in {@link J2meFilterShaders}).</li>
 *   <li>HQ4x: guest(r)'s 4xGLSLHqFilter weighted interpolation.</li>
 *   <li>Dot: Themaister's LCD dot effect with {@code exp(-gamma * delta * bloom)}.</li>
 *   <li>Scanline: sine-modulated brightness.</li>
 *   <li>CRT: RGB phosphor mask + scanlines + vignette.</li>
 * </ul>
 *
 * <p>Filter modes (must match {@code J2meFilterShaders}):
 * <pre>
 *   0 = None
 *   1 = Scanline
 *   2 = CRT
 *   3 = Dot
 *   4 = 2xBR
 *   5 = 4xBR
 *   6 = 2xBR + Dot
 *   7 = 4xBR + Dot
 *   8 = HQ4x
 *   9 = HQ4x + Dot
 * </pre>
 */
public final class J2meBitmapFilter {

    public static final int MODE_NONE      = 0;
    public static final int MODE_SCANLINE  = 1;
    public static final int MODE_CRT       = 2;
    public static final int MODE_DOT       = 3;
    public static final int MODE_2XBR      = 4;
    public static final int MODE_4XBR      = 5;
    public static final int MODE_2XBR_DOT  = 6;
    public static final int MODE_4XBR_DOT  = 7;
    public static final int MODE_HQ4X      = 8;
    public static final int MODE_HQ4X_DOT  = 9;

    // Legacy aliases for backward compatibility
    public static final int MODE_XBR     = MODE_2XBR;
    public static final int MODE_XBR_DOT = MODE_2XBR_DOT;

    private static final Paint sNearestPaint;
    private static final Paint sSmoothPaint;
    private static final Paint sMaskPaint = new Paint();

    static {
        sNearestPaint = new Paint();
        sNearestPaint.setFilterBitmap(false);
        sSmoothPaint = new Paint();
        sSmoothPaint.setFilterBitmap(true);
    }

    // ─── Cached filtered bitmap ──────────────────────────────────────────
    private static Bitmap sFilteredBitmap;
    private static int sCachedSrcW = -1;
    private static int sCachedSrcH = -1;
    private static int sCachedMode = -1;

    private J2meBitmapFilter() {}

    // ─── Mode classification ─────────────────────────────────────────────

    public static boolean isFilteredMode(int mode) {
        return mode != MODE_NONE;
    }

    public static boolean isPixelProcessingMode(int mode) {
        return mode == MODE_2XBR || mode == MODE_4XBR ||
               mode == MODE_2XBR_DOT || mode == MODE_4XBR_DOT ||
               mode == MODE_HQ4X || mode == MODE_HQ4X_DOT;
    }

    public static int getScaleFactor(int mode) {
        if (mode == MODE_2XBR || mode == MODE_2XBR_DOT) return 2;
        if (mode == MODE_4XBR || mode == MODE_4XBR_DOT ||
            mode == MODE_HQ4X || mode == MODE_HQ4X_DOT) return 4;
        return 1;
    }

    public static boolean isMaskMode(int mode) {
        return mode == MODE_SCANLINE || mode == MODE_CRT || mode == MODE_DOT;
    }

    // ─── Public API: applyFilter (returns upscaled filtered bitmap) ──────

    public static Bitmap applyFilter(Bitmap src, int mode) {
        int sw = src.getWidth();
        int sh = src.getHeight();

        if (sFilteredBitmap != null && (sCachedSrcW != sw || sCachedSrcH != sh || sCachedMode != mode)) {
            sFilteredBitmap.recycle();
            sFilteredBitmap = null;
        }
        sCachedSrcW = sw;
        sCachedSrcH = sh;
        sCachedMode = mode;

        switch (mode) {
            case MODE_2XBR:
                return xbrUpscale(src, 2);
            case MODE_4XBR:
                return xbrUpscale(src, 4);
            case MODE_2XBR_DOT:
                return applyDotMask(xbrUpscale(src, 2));
            case MODE_4XBR_DOT:
                return applyDotMask(xbrUpscale(src, 4));
            case MODE_HQ4X:
                return hq4xUpscale(src);
            case MODE_HQ4X_DOT:
                return applyDotMask(hq4xUpscale(src));
            default:
                return src;
        }
    }

    // ─── Public API: drawFiltered (non-GL canvas rendering) ──────────────

    public static void drawFiltered(Bitmap srcBitmap, Canvas dstCanvas,
                                    RectF dstRect, int mode) {
        if (mode == MODE_NONE) {
            dstCanvas.drawBitmap(srcBitmap,
                    new Rect(0, 0, srcBitmap.getWidth(), srcBitmap.getHeight()),
                    dstRect, sNearestPaint);
            return;
        }

        if (isPixelProcessingMode(mode)) {
            Bitmap filtered = applyFilter(srcBitmap, mode);
            if (filtered != null && filtered != srcBitmap) {
                dstCanvas.drawBitmap(filtered,
                        new Rect(0, 0, filtered.getWidth(), filtered.getHeight()),
                        dstRect, sNearestPaint);
            } else {
                dstCanvas.drawBitmap(srcBitmap,
                        new Rect(0, 0, srcBitmap.getWidth(), srcBitmap.getHeight()),
                        dstRect, sNearestPaint);
            }
        } else if (isMaskMode(mode)) {
            dstCanvas.drawBitmap(srcBitmap,
                    new Rect(0, 0, srcBitmap.getWidth(), srcBitmap.getHeight()),
                    dstRect, sNearestPaint);
            applyCanvasMask(dstCanvas, dstRect, mode);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  XBR Algorithm (Hyllian's 2xBR / 4xBR — reference implementation)
    //  Matches the GLSL FRAGMENT_2XBR / FRAGMENT_4XBR shaders exactly.
    // ════════════════════════════════════════════════════════════════════

    /**
     * {@code reduce()} from the reference shader — packs RGB into a single
     * scalar for exact equality comparison.
     * <p>Uses {@code R*65536 + G*256 + B} (standard 24-bit packing) which is
     * collision-free for 8-bit channels. The GLSL reference uses
     * {@code vec3(65536.0, 255.0, 1.0)} with normalized [0,1] colors where
     * 255 > 1 holds; for integer 0-255 values we use 256 to ensure uniqueness
     * (255 is not > 255, but 256 is).
     */
    private static long reduceColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (long) r * 65536 + (long) g * 256 + b;
    }

    /**
     * 2xBR / 4xBR upscaling — reference Hyllian algorithm.
     *
     * <p>For each output pixel, computes the fractional position ({@code fp})
     * within the source texel, determines the gradient vectors {@code g1}
     * and {@code g2} (which select the 3×3 neighborhood orientation), then
     * applies the exact edge-detection rule from the reference GLSL:
     * <pre>
     *   if (h==f && h!=e && ( e==g && (h==i || e==d)
     *                      || e==c && (h==i || e==b) ))
     * </pre>
     *
     * <p>For 2xBR: outputs {@code E} or {@code mix(E, F, 0.5)}.
     * For 4xBR: uses the 4×4 sub-pixel pattern with {@code E11} and {@code E15}.
     */
    private static Bitmap xbrUpscale(Bitmap src, int scale) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int dw = sw * scale;
        int dh = sh * scale;

        int[] sp = new int[sw * sh];
        src.getPixels(sp, 0, sw, 0, 0, sw, sh);
        int[] dp = new int[dw * dh];

        for (int oy = 0; oy < dh; oy++) {
            for (int ox = 0; ox < dw; ox++) {
                // Source texel coordinates
                int tx = ox / scale;
                int ty = oy / scale;

                // Fractional position within source texel (pixel-center sampled
                // to match GPU rasterisation: 0.125, 0.375, 0.625, 0.875 for 4x)
                float fpx = ((float) (ox - tx * scale) + 0.5f) / scale;
                float fpy = ((float) (oy - ty * scale) + 0.5f) / scale;

                // step(0.5, fp.x) / step(0.5, fp.y) — determines quadrant
                float sx = (fpx >= 0.5f) ? 1.0f : 0.0f;
                float sy = (fpy >= 0.5f) ? 1.0f : 0.0f;

                // Gradient vectors as integer pixel offsets.
                // v_texcoord0[1] = (0, -texelDelta.y) → up  = (0, -1)
                // v_texcoord0[2] = (-texelDelta.x, 0) → left = (-1, 0)
                // g1 = v[1]*(sx+sy-1) + v[2]*(sx-sy) → (sy-sx, 1-sx-sy)
                // g2 = v[1]*(sy-sx) + v[2]*(sx+sy-1) → (1-sx-sy, sx-sy)
                int g1x = (int) (sy - sx);
                int g1y = (int) (1 - sx - sy);
                int g2x = (int) (1 - sx - sy);
                int g2y = (int) (sx - sy);

                // Sample 3×3 neighborhood (B,C,D,E,F,G,H,I) using g1,g2
                int B = getPixelSafe(sp, sw, sh, tx + g1x,         ty + g1y);
                int C = getPixelSafe(sp, sw, sh, tx + g1x - g2x,   ty + g1y - g2y);
                int D = getPixelSafe(sp, sw, sh, tx + g2x,         ty + g2y);
                int E = getPixelSafe(sp, sw, sh, tx,               ty);
                int F = getPixelSafe(sp, sw, sh, tx - g2x,         ty - g2y);
                int G = getPixelSafe(sp, sw, sh, tx - g1x + g2x,   ty - g1y + g2y);
                int H = getPixelSafe(sp, sw, sh, tx - g1x,         ty - g1y);
                int I = getPixelSafe(sp, sw, sh, tx - g1x - g2x,   ty - g1y - g2y);

                // reduce() for exact color comparison
                long b = reduceColor(B);
                long c = reduceColor(C);
                long d = reduceColor(D);
                long e = reduceColor(E);
                long f = reduceColor(F);
                long g = reduceColor(G);
                long h = reduceColor(H);
                long i = reduceColor(I);

                // Edge detection (verbatim from reference shader)
                boolean edge = (h == f && h != e &&
                        ((e == g && (h == i || e == d)) ||
                         (e == c && (h == i || e == b))));

                if (scale == 2) {
                    // 2xBR: E or mix(E, F, 0.5)
                    dp[oy * dw + ox] = edge ? blendColor(E, F, 0.5f) : E;
                } else {
                    // 4xBR: 4×4 sub-pixel pattern
                    // E11 = E (default), E15 = E (default)
                    // if (edge) { E11 = mix(E, F, 0.5); E15 = F; }
                    int E11 = edge ? blendColor(E, F, 0.5f) : E;
                    int E15 = edge ? F : E;

                    int result;
                    if (fpx < 0.50f) {
                        if (fpx < 0.25f) {
                            // Column 0: E15, E11, E11, E15
                            if (fpy < 0.25f)      result = E15;
                            else if (fpy < 0.50f)  result = E11;
                            else if (fpy < 0.75f)  result = E11;
                            else                   result = E15;
                        } else {
                            // Column 1: E11, E, E, E11
                            if (fpy < 0.25f)      result = E11;
                            else if (fpy < 0.50f)  result = E;
                            else if (fpy < 0.75f)  result = E;
                            else                   result = E11;
                        }
                    } else {
                        if (fpx < 0.75f) {
                            // Column 2: E11, E, E, E11
                            if (fpy < 0.25f)      result = E11;
                            else if (fpy < 0.50f)  result = E;
                            else if (fpy < 0.75f)  result = E;
                            else                   result = E11;
                        } else {
                            // Column 3: E15, E11, E11, E15
                            if (fpy < 0.25f)      result = E15;
                            else if (fpy < 0.50f)  result = E11;
                            else if (fpy < 0.75f)  result = E11;
                            else                   result = E15;
                        }
                    }
                    dp[oy * dw + ox] = result;
                }
            }
        }

        Bitmap result = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        result.setPixels(dp, 0, dw, 0, 0, dw, dh);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  HQ4x Algorithm (guest(r)'s 4xGLSLHqFilter)
    //  Weighted interpolation using 13 texels
    // ════════════════════════════════════════════════════════════════════

    /**
     * HQ4x upscaling. Based on the 4xGLSLHqFilter shader by guest(r).
     *
     * <p>Samples 13 texels: center (c), 4 inner (i1-i4), 4 outer (o1-o4),
     * 4 side (s1-s4). Computes edge weights and applies weighted interpolation.
     * Each source pixel produces a 4×4 block with the same filtered color.
     */
    private static Bitmap hq4xUpscale(Bitmap src) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int dw = sw * 4;
        int dh = sh * 4;

        int[] sp = new int[sw * sh];
        src.getPixels(sp, 0, sw, 0, 0, sw, sh);
        int[] dp = new int[dw * dh];

        // Constants from the reference shader
        final float mx = 1.00f;       // start smoothing wt.
        final float k = -1.10f;       // wt. decrease factor
        final float max_w = 0.75f;    // max filter weight
        final float min_w = 0.03f;    // min filter weight
        final float lum_add = 0.33f;  // effects smoothing

        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                // Center
                int c = getPixelSafe(sp, sw, sh, x, y);

                // Inner neighbors (half-texel offsets)
                int i1 = getPixelSafe(sp, sw, sh, x,     y - 1); // top
                int i2 = getPixelSafe(sp, sw, sh, x + 1, y);     // right
                int i3 = getPixelSafe(sp, sw, sh, x,     y + 1); // bottom
                int i4 = getPixelSafe(sp, sw, sh, x - 1, y);     // left

                // Outer neighbors (full-texel diagonal offsets)
                int o1 = getPixelSafe(sp, sw, sh, x - 1, y - 1); // TL
                int o2 = getPixelSafe(sp, sw, sh, x + 1, y - 1); // TR
                int o3 = getPixelSafe(sp, sw, sh, x + 1, y + 1); // BR
                int o4 = getPixelSafe(sp, sw, sh, x - 1, y + 1); // BL

                // Side neighbors (quarter-texel offsets)
                int s1 = getPixelSafe(sp, sw, sh, x,     y - 2); // far top
                int s2 = getPixelSafe(sp, sw, sh, x + 2, y);     // far right
                int s3 = getPixelSafe(sp, sw, sh, x,     y + 2); // far bottom
                int s4 = getPixelSafe(sp, sw, sh, x - 2, y);     // far left

                // Convert to float vectors for computation
                float[] cv = toVec3(c);
                float[] i1v = toVec3(i1), i2v = toVec3(i2);
                float[] i3v = toVec3(i3), i4v = toVec3(i4);
                float[] o1v = toVec3(o1), o2v = toVec3(o2);
                float[] o3v = toVec3(o3), o4v = toVec3(o4);
                float[] s1v = toVec3(s1), s2v = toVec3(s2);
                float[] s3v = toVec3(s3), s4v = toVec3(s4);

                // ko = dot(abs(outer - center), dt)
                float ko1 = dotAbs(o1v, cv);
                float ko2 = dotAbs(o2v, cv);
                float ko3 = dotAbs(o3v, cv);
                float ko4 = dotAbs(o4v, cv);

                // k = min(dot(abs(inner_i - inner_j), dt), max(ko_a, ko_b))
                float sd1 = dotAbs(sub(i1v, i3v));
                float sd2 = dotAbs(sub(i2v, i4v));

                float k1 = Math.min(sd2, Math.max(ko1, ko3));
                float k2 = Math.min(sd1, Math.max(ko2, ko4));
                float k3 = Math.min(sd2, Math.max(ko3, ko1));
                float k4 = Math.min(sd1, Math.max(ko4, ko2));

                // First pass: smooth center
                float w1 = (ko3 < ko1) ? k1 * ko3 / Math.max(ko1, 0.001f) : k1;
                float w2 = (ko4 < ko2) ? k2 * ko4 / Math.max(ko2, 0.001f) : k2;
                float w3 = (ko1 < ko3) ? k3 * ko1 / Math.max(ko3, 0.001f) : k3;
                float w4 = (ko2 < ko4) ? k4 * ko2 / Math.max(ko4, 0.001f) : k4;

                float wsum = w1 + w2 + w3 + w4 + 0.001f;
                cv[0] = (w1 * o1v[0] + w2 * o2v[0] + w3 * o3v[0] + w4 * o4v[0] + 0.001f * cv[0]) / wsum;
                cv[1] = (w1 * o1v[1] + w2 * o2v[1] + w3 * o3v[1] + w4 * o4v[1] + 0.001f * cv[1]) / wsum;
                cv[2] = (w1 * o1v[2] + w2 * o2v[2] + w3 * o3v[2] + w4 * o4v[2] + 0.001f * cv[2]) / wsum;

                // Second pass: weighted interpolation
                float lc = cv[0] + cv[1] + cv[2] + 0.2f;

                w1 = clamp(k * dotAbs(sub(i1v, cv)) / (0.125f * (i1v[0] + i1v[1] + i1v[2]) + lum_add) + mx, min_w, max_w);
                w2 = clamp(k * dotAbs(sub(i2v, cv)) / (0.125f * (i2v[0] + i2v[1] + i2v[2]) + lum_add) + mx, min_w, max_w);
                w3 = clamp(k * dotAbs(sub(i3v, cv)) / (0.125f * (i3v[0] + i3v[1] + i3v[2]) + lum_add) + mx, min_w, max_w);
                w4 = clamp(k * dotAbs(sub(i4v, cv)) / (0.125f * (i4v[0] + i4v[1] + i4v[2]) + lum_add) + mx, min_w, max_w);

                float w5 = clamp(k * dotAbs(sub(s1v, cv)) / (0.125f * (s1v[0] + s1v[1] + s1v[2]) + lum_add) + mx, min_w, max_w);
                float w6 = clamp(k * dotAbs(sub(s2v, cv)) / (0.125f * (s2v[0] + s2v[1] + s2v[2]) + lum_add) + mx, min_w, max_w);
                float w7 = clamp(k * dotAbs(sub(s3v, cv)) / (0.125f * (s3v[0] + s3v[1] + s3v[2]) + lum_add) + mx, min_w, max_w);
                float w8 = clamp(k * dotAbs(sub(s4v, cv)) / (0.125f * (s4v[0] + s4v[1] + s4v[2]) + lum_add) + mx, min_w, max_w);

                float tw = 2.0f * (w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8) + 1.0f;
                float r = (w1 * (i1v[0] + i3v[0]) + w2 * (i2v[0] + i4v[0]) +
                           w5 * (s1v[0] + s3v[0]) + w6 * (s2v[0] + s4v[0]) + cv[0]) / tw;
                float g = (w1 * (i1v[1] + i3v[1]) + w2 * (i2v[1] + i4v[1]) +
                           w5 * (s1v[1] + s3v[1]) + w6 * (s2v[1] + s4v[1]) + cv[1]) / tw;
                float b = (w1 * (i1v[2] + i3v[2]) + w2 * (i2v[2] + i4v[2]) +
                           w5 * (s1v[2] + s3v[2]) + w6 * (s2v[2] + s4v[2]) + cv[2]) / tw;

                int result = clampRGB(r, g, b);

                // Fill 4x4 block
                for (int sy = 0; sy < 4; sy++) {
                    int rowStart = (y * 4 + sy) * dw + x * 4;
                    for (int sx = 0; sx < 4; sx++) {
                        dp[rowStart + sx] = result;
                    }
                }
            }
        }

        Bitmap result = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        result.setPixels(dp, 0, dw, 0, 0, dw, dh);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Dot Mask (Themaister's dot shader)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Applies the dot mask to an upscaled bitmap (for +dot modes).
     * Uses the reference dot shader's lookup function:
     * {@code color * exp(-gamma * delta * color_bloom(color))}
     * with 9-tap sampling and {@code mix(1.2 * mid, color, 0.65)}.
     */
    private static Bitmap applyDotMask(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        final float gamma = 2.4f;
        final float shine = 0.05f;
        final float blend = 0.65f;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // pixel_no = position in source-resolution space
                float px = x;
                float py = y;

                // 9-tap sampling
                float[] mid = lookup(px, py, 0, 0, pixels, w, h, gamma, shine);
                float[] sum = new float[3];

                addLookup(sum, px, py, -1, -1, pixels, w, h, gamma, shine);
                addLookup(sum, px, py,  0, -1, pixels, w, h, gamma, shine);
                addLookup(sum, px, py,  1, -1, pixels, w, h, gamma, shine);
                addLookup(sum, px, py, -1,  0, pixels, w, h, gamma, shine);
                addLookup(sum, mid);
                addLookup(sum, px, py,  1,  0, pixels, w, h, gamma, shine);
                addLookup(sum, px, py, -1,  1, pixels, w, h, gamma, shine);
                addLookup(sum, px, py,  0,  1, pixels, w, h, gamma, shine);
                addLookup(sum, px, py,  1,  1, pixels, w, h, gamma, shine);

                // mix(1.2 * mid_color, color, blend)
                float r = mix(1.2f * mid[0], sum[0], blend);
                float g = mix(1.2f * mid[1], sum[1], blend);
                float b = mix(1.2f * mid[2], sum[2], blend);

                int idx = y * w + x;
                int a = (pixels[idx] >> 24) & 0xFF;
                pixels[idx] = (a << 24) | clampRGB(r, g, b);
            }
        }

        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    private static float[] lookup(float px, float py, float ox, float oy,
                                  int[] pixels, int w, int h,
                                  float gamma, float shine) {
        int sx = clampInt((int)(px + ox), 0, w - 1);
        int sy = clampInt((int)(py + oy), 0, h - 1);
        int color = pixels[sy * w + sx];
        float[] rgb = toVec3(color);

        float deltaX = (float)(px - Math.floor(px)) - (ox + 0.5f);
        float deltaY = (float)(py - Math.floor(py)) - (oy + 0.5f);
        float delta = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);

        float bright = rgb[0] * 0.30f + rgb[1] * 0.59f + rgb[2] * 0.11f;
        float bloom = mix(1.0f + shine, 1.0f - shine, bright);

        float factor = (float) Math.exp(-gamma * delta * bloom);
        rgb[0] *= factor;
        rgb[1] *= factor;
        rgb[2] *= factor;
        return rgb;
    }

    private static void addLookup(float[] sum, float px, float py, float ox, float oy,
                                  int[] pixels, int w, int h,
                                  float gamma, float shine) {
        float[] v = lookup(px, py, ox, oy, pixels, w, h, gamma, shine);
        sum[0] += v[0];
        sum[1] += v[1];
        sum[2] += v[2];
    }

    private static void addLookup(float[] sum, float[] v) {
        sum[0] += v[0];
        sum[1] += v[1];
        sum[2] += v[2];
    }

    // ════════════════════════════════════════════════════════════════════
    //  Canvas Mask Overlay (scanline/CRT/dot for non-GL mode)
    // ════════════════════════════════════════════════════════════════════

    private static void applyCanvasMask(Canvas canvas, RectF rect, int mode) {
        sMaskPaint.setAntiAlias(false);
        switch (mode) {
            case MODE_SCANLINE:
                drawScanlineMask(canvas, rect);
                break;
            case MODE_CRT:
                drawCrtMask(canvas, rect);
                break;
            case MODE_DOT:
                drawDotMask(canvas, rect);
                break;
        }
    }

    private static void drawScanlineMask(Canvas canvas, RectF rect) {
        float left = rect.left;
        float right = rect.right;
        float top = rect.top;
        float bottom = rect.bottom;
        float height = bottom - top;

        for (float y = top; y < bottom; y += 1.0f) {
            float t = (y - top) / height;
            float sine = (float) Math.sin(t * Math.PI * 2.0 * height / 4.0f);
            float brightness = 0.95f + 0.15f * sine;
            brightness = Math.max(0.0f, Math.min(1.0f, brightness));
            int alpha = (int) ((1.0f - brightness) * 200);
            sMaskPaint.setColor((alpha << 24));
            canvas.drawRect(left, y, right, y + 1, sMaskPaint);
        }
    }

    private static void drawCrtMask(Canvas canvas, RectF rect) {
        float left = rect.left;
        float top = rect.top;
        float right = rect.right;
        float bottom = rect.bottom;

        // RGB phosphor stripes
        sMaskPaint.setColor(0x22FF0000);
        for (float x = left; x < right; x += 3) {
            canvas.drawRect(x, top, Math.min(x + 1, right), bottom, sMaskPaint);
        }
        sMaskPaint.setColor(0x2200FF00);
        for (float x = left + 1; x < right; x += 3) {
            canvas.drawRect(x, top, Math.min(x + 1, right), bottom, sMaskPaint);
        }
        sMaskPaint.setColor(0x220000FF);
        for (float x = left + 2; x < right; x += 3) {
            canvas.drawRect(x, top, Math.min(x + 1, right), bottom, sMaskPaint);
        }

        // Scanlines
        sMaskPaint.setColor(0x50000000);
        for (float y = top; y < bottom; y += 3) {
            canvas.drawRect(left, y, right, Math.min(y + 1, bottom), sMaskPaint);
        }

        // Vignette
        float cx = (left + right) / 2;
        float cy = (top + bottom) / 2;
        float maxDist = (float) Math.sqrt(
                (right - cx) * (right - cx) + (bottom - cy) * (bottom - cy));
        sMaskPaint.setColor(0x30000000);
        for (float r = maxDist * 0.6f; r < maxDist; r += 2) {
            int alpha = (int) (80 * (r - maxDist * 0.6f) / (maxDist * 0.4f));
            sMaskPaint.setColor((alpha << 24));
            canvas.drawCircle(cx, cy, r, sMaskPaint);
        }
    }

    private static void drawDotMask(Canvas canvas, RectF rect) {
        float left = rect.left;
        float top = rect.top;
        float right = rect.right;
        float bottom = rect.bottom;

        final float gamma = 2.4f;
        final float shine = 0.05f;

        for (float y = top; y < bottom; y += 1.0f) {
            for (float x = left; x < right; x += 1.0f) {
                float dx = (float)(x - Math.floor(x)) - 0.5f;
                float dy = (float)(y - Math.floor(y)) - 0.5f;
                float delta = (float) Math.sqrt(dx * dx + dy * dy);
                float factor = (float) Math.exp(-gamma * delta * (1.0f - shine));
                int alpha = (int) ((1.0f - factor) * 120);
                if (alpha > 0) {
                    sMaskPaint.setColor((alpha << 24));
                    canvas.drawPoint(x, y, sMaskPaint);
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Utility methods
    // ════════════════════════════════════════════════════════════════════

    private static int getPixelSafe(int[] pixels, int w, int h, int x, int y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= w) x = w - 1;
        if (y >= h) y = h - 1;
        return pixels[y * w + x];
    }

    private static float[] toVec3(int color) {
        return new float[] {
            ((color >> 16) & 0xFF) / 255.0f,
            ((color >> 8) & 0xFF) / 255.0f,
            (color & 0xFF) / 255.0f
        };
    }

    private static float dotAbs(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }

    private static float dotAbs(float[] v) {
        return Math.abs(v[0]) + Math.abs(v[1]) + Math.abs(v[2]);
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float mix(float a, float b, float t) {
        return a * (1.0f - t) + b * t;
    }

    private static int clampRGB(float r, float g, float b) {
        int ri = clampInt(Math.round(r * 255), 0, 255);
        int gi = clampInt(Math.round(g * 255), 0, 255);
        int bi = clampInt(Math.round(b * 255), 0, 255);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static int blendColor(int c1, int c2, float ratio) {
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        int r = Math.round(r1 * (1 - ratio) + r2 * ratio);
        int g = Math.round(g1 * (1 - ratio) + g2 * ratio);
        int b = Math.round(b1 * (1 - ratio) + b2 * ratio);
        int a = (c1 >> 24) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static void release() {
        if (sFilteredBitmap != null) {
            sFilteredBitmap.recycle();
            sFilteredBitmap = null;
        }
        sCachedSrcW = -1;
        sCachedSrcH = -1;
        sCachedMode = -1;
    }
}
