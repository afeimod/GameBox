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
 *   <li>2xBR / 4xBR: Hyllian's 5xBR v3.5a with weighted-luminance edge
 *       detection (matches the GLSL shader in {@link J2meFilterShaders}).</li>
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
    //  XBR Algorithm (Hyllian's 5xBR v3.5a — per-output-pixel)
    //  Matches the GLSL FRAGMENT_2XBR / FRAGMENT_4XBR shaders exactly.
    // ════════════════════════════════════════════════════════════════════

    // 5xBR v3.5a constants
    private static final float XBR_COEF = 2.0f;
    private static final float[] XBR_RGBW = {16.163f, 23.351f, 8.4772f};

    // Line-equation constants (vec4 components: x=N, y=W, z=S, w=E)
    private static final float[] XBR_Ao = {1, -1, -1, 1};
    private static final float[] XBR_Bo = {1, 1, -1, -1};
    private static final float[] XBR_Co = {1.5f, 0.5f, -0.5f, 0.5f};
    private static final float[] XBR_Ax = {1, -1, -1, 1};
    private static final float[] XBR_Bx = {0.5f, 2, -0.5f, -2};
    private static final float[] XBR_Cx = {1, 1, -0.5f, 0};
    private static final float[] XBR_Ay = {1, -1, -1, 1};
    private static final float[] XBR_By = {2, 0.5f, -2, -0.5f};
    private static final float[] XBR_Cy = {2, 0, -1, 0.5f};

    /**
     * 5xBR v3.5a upscaling. Produces a {@code scale}×{@code scale} output.
     *
     * <p>Processes each output pixel individually, computing the fractional
     * position within the source texel and performing full 4-direction edge
     * detection with weighted-luminance comparison — exactly matching the
     * {@link J2meFilterShaders#FRAGMENT_2XBR} GLSL shader.
     *
     * <p>Neighborhood layout (21 texels):
     * <pre>
     *           A0  B1  C4
     *       A0  A   B   C   C4
     *   D0  D   E   F   F4
     *       G0  G   H   I   I4
     *           G5  H5  I5
     * </pre>
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
                // Source texel coordinates and fractional position
                float sxf = (float) ox / scale;
                float syf = (float) oy / scale;
                int tx = (int) Math.floor(sxf);
                int ty = (int) Math.floor(syf);
                float fpx = sxf - tx;
                float fpy = syf - ty;

                dp[oy * dw + ox] = xbrPixel(sp, sw, sh, tx, ty, fpx, fpy);
            }
        }

        Bitmap result = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        result.setPixels(dp, 0, dw, 0, 0, dw, dh);
        return result;
    }

    /**
     * Computes one output pixel of the 5xBR v3.5a algorithm.
     * Samples the 21-texel neighborhood and selects the best neighbor.
     */
    private static int xbrPixel(int[] sp, int sw, int sh,
                                int tx, int ty, float fpx, float fpy) {
        // ── Sample 21-neighborhood (RGB as weighted luminance scalars) ──
        float la = lum(sp, sw, sh, tx - 1, ty - 1);  // A
        float lb = lum(sp, sw, sh, tx,     ty - 1);  // B
        float lc = lum(sp, sw, sh, tx + 1, ty - 1);  // C
        float ld = lum(sp, sw, sh, tx - 1, ty);      // D
        float le = lum(sp, sw, sh, tx,     ty);      // E
        float lf = lum(sp, sw, sh, tx + 1, ty);      // F
        float lg = lum(sp, sw, sh, tx - 1, ty + 1);  // G
        float lh = lum(sp, sw, sh, tx,     ty + 1);  // H
        float li = lum(sp, sw, sh, tx + 1, ty + 1);  // I

        float la1 = lum(sp, sw, sh, tx - 1, ty - 2); // A1
        float lc1 = lum(sp, sw, sh, tx + 1, ty - 2); // C1
        float la0 = lum(sp, sw, sh, tx - 2, ty - 1); // A0
        float lg0 = lum(sp, sw, sh, tx - 2, ty + 1); // G0
        float lc4 = lum(sp, sw, sh, tx + 2, ty - 1); // C4
        float li4 = lum(sp, sw, sh, tx + 2, ty + 1); // I4
        float lg5 = lum(sp, sw, sh, tx - 1, ty + 2); // G5
        float li5 = lum(sp, sw, sh, tx + 1, ty + 2); // I5
        float lb1 = lum(sp, sw, sh, tx,     ty - 2); // B1
        float ld0 = lum(sp, sw, sh, tx - 2, ty);     // D0
        float lh5 = lum(sp, sw, sh, tx,     ty + 2); // H5
        float lf4 = lum(sp, sw, sh, tx + 2, ty);     // F4

        // ── Pack into vec4 (4 directions: x=N, y=W, z=S, w=E) ──
        // vec4 b  = (B, D, H, F)
        float[] b  = {lb, ld, lh, lf};
        // vec4 c  = (C, A, G, I)
        float[] c  = {lc, la, lg, li};
        // vec4 d  = b.yzwx
        float[] d  = {b[1], b[2], b[3], b[0]};
        // vec4 e  = (E, E, E, E)
        float[] e  = {le, le, le, le};
        // vec4 f  = b.wxyz
        float[] f  = {b[3], b[0], b[1], b[2]};
        // vec4 g  = c.zwxy
        float[] g  = {c[2], c[3], c[0], c[1]};
        // vec4 h  = b.zwxy
        float[] hv = {b[2], b[3], b[0], b[1]};
        // vec4 i  = c.wxyz
        float[] iv = {c[3], c[0], c[1], c[2]};
        // vec4 i4 = (I4, C1, A0, G5)
        float[] i4 = {li4, lc1, la0, lg5};
        // vec4 i5 = (I5, C4, A1, G0)
        float[] i5 = {li5, lc4, la1, lg0};
        // vec4 h5 = (H5, F4, B1, D0)
        float[] h5 = {lh5, lf4, lb1, ld0};
        // vec4 f4 = h5.yzwx
        float[] f4 = {h5[1], h5[2], h5[3], h5[0]};

        // ── Line equations ──
        boolean[] fx      = gt4(lineEq(XBR_Ao, XBR_Bo, fpx, fpy), XBR_Co);
        boolean[] fxLeft  = gt4(lineEq(XBR_Ax, XBR_Bx, fpx, fpy), XBR_Cx);
        boolean[] fxUp    = gt4(lineEq(XBR_Ay, XBR_By, fpx, fpy), XBR_Cy);

        // ── Interpolation restrictions ──
        boolean[] irlv1    = and4(neq4(e, f), neq4(e, hv));
        boolean[] irlv2L   = and4(neq4(e, g), neq4(d, g));
        boolean[] irlv2U   = and4(neq4(e, c), neq4(b, c));

        // ── Edge detection rules ──
        float[] wd1 = wdist(e, c, g, iv, h5, f4, hv, f);
        float[] wd2 = wdist(hv, d, i5, f, i4, b, e, iv);
        boolean[] edr     = and4(lt4(wd1, wd2), irlv1);

        float[] dfg = df4(f, g);
        float[] dhc = df4(hv, c);
        boolean[] edrLeft = and4(le4(scl4(dfg, XBR_COEF), dhc), irlv2L);
        boolean[] edrUp   = and4(ge4(dfg, scl4(dhc, XBR_COEF)), irlv2U);

        // ── Neighbor selection (nc) ──
        boolean[] nc = new boolean[4];
        for (int k = 0; k < 4; k++) {
            nc[k] = edr[k] && (fx[k] || (edrLeft[k] && fxLeft[k]) || (edrUp[k] && fxUp[k]));
        }

        // ── Pixel selection (px) ──
        boolean[] px = le4(df4(e, f), df4(e, hv));

        // ── Final color selection ──
        // res = nc.x ? px.x ? F : H
        //     : nc.y ? px.y ? B : F
        //     : nc.z ? px.z ? D : B
        //     : nc.w ? px.w ? H : D
        //     : E;
        if (nc[0]) {
            return px[0] ? getPixelSafe(sp, sw, sh, tx + 1, ty)      // F
                         : getPixelSafe(sp, sw, sh, tx,     ty + 1); // H
        } else if (nc[1]) {
            return px[1] ? getPixelSafe(sp, sw, sh, tx,     ty - 1)  // B
                         : getPixelSafe(sp, sw, sh, tx + 1, ty);    // F
        } else if (nc[2]) {
            return px[2] ? getPixelSafe(sp, sw, sh, tx - 1, ty)     // D
                         : getPixelSafe(sp, sw, sh, tx,     ty - 1);// B
        } else if (nc[3]) {
            return px[3] ? getPixelSafe(sp, sw, sh, tx,     ty + 1) // H
                         : getPixelSafe(sp, sw, sh, tx - 1, ty);    // D
        } else {
            return getPixelSafe(sp, sw, sh, tx, ty);                // E
        }
    }

    // ── 5xBR vec4 helper methods ──────────────────────────────────────────

    /** Weighted luminance: dot(RGB, rgbw). */
    private static float lum(int[] sp, int sw, int sh, int x, int y) {
        int c = getPixelSafe(sp, sw, sh, x, y);
        return ((c >> 16) & 0xFF) * XBR_RGBW[0]
             + ((c >>  8) & 0xFF) * XBR_RGBW[1]
             + (c         & 0xFF) * XBR_RGBW[2];
    }

    /** Line equation: Ao*fpy + Bo*fpx → vec4. */
    private static float[] lineEq(float[] ao, float[] bo, float fpx, float fpy) {
        return new float[] {
            ao[0] * fpy + bo[0] * fpx,
            ao[1] * fpy + bo[1] * fpx,
            ao[2] * fpy + bo[2] * fpx,
            ao[3] * fpy + bo[3] * fpx
        };
    }

    /** abs(A - B) for vec4. */
    private static float[] df4(float[] a, float[] b) {
        return new float[] {
            Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]),
            Math.abs(a[2] - b[2]), Math.abs(a[3] - b[3])
        };
    }

    /** weighted_distance(a,b,c,d,e,f,g,h) = df(a,b)+df(a,c)+df(d,e)+df(d,f)+4*df(g,h). */
    private static float[] wdist(float[] a, float[] b, float[] c, float[] d,
                                  float[] e, float[] f, float[] g, float[] h) {
        float[] ab = df4(a, b), ac = df4(a, c);
        float[] de = df4(d, e), df = df4(d, f);
        float[] gh = df4(g, h);
        return new float[] {
            ab[0] + ac[0] + de[0] + df[0] + 4f * gh[0],
            ab[1] + ac[1] + de[1] + df[1] + 4f * gh[1],
            ab[2] + ac[2] + de[2] + df[2] + 4f * gh[2],
            ab[3] + ac[3] + de[3] + df[3] + 4f * gh[3]
        };
    }

    private static float[] scl4(float[] a, float s) {
        return new float[] { a[0]*s, a[1]*s, a[2]*s, a[3]*s };
    }

    private static boolean[] gt4(float[] a, float[] b) {
        return new boolean[] { a[0]>b[0], a[1]>b[1], a[2]>b[2], a[3]>b[3] };
    }

    private static boolean[] lt4(float[] a, float[] b) {
        return new boolean[] { a[0]<b[0], a[1]<b[1], a[2]<b[2], a[3]<b[3] };
    }

    private static boolean[] le4(float[] a, float[] b) {
        return new boolean[] { a[0]<=b[0], a[1]<=b[1], a[2]<=b[2], a[3]<=b[3] };
    }

    private static boolean[] ge4(float[] a, float[] b) {
        return new boolean[] { a[0]>=b[0], a[1]>=b[1], a[2]>=b[2], a[3]>=b[3] };
    }

    private static boolean[] neq4(float[] a, float[] b) {
        return new boolean[] { a[0]!=b[0], a[1]!=b[1], a[2]!=b[2], a[3]!=b[3] };
    }

    private static boolean[] and4(boolean[] a, boolean[] b) {
        return new boolean[] { a[0]&&b[0], a[1]&&b[1], a[2]&&b[2], a[3]&&b[3] };
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
