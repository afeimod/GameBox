package javax.microedition.lcdui.graphics;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * CPU-based bitmap filter for J2ME games.
 * ALL filters are applied directly to the game's rendered bitmap or canvas,
 * NOT as a separate full-screen overlay.
 *
 * Two categories of filters:
 * 1. Pixel-processing filters (XBR/4XBR/XBR+dot/4XBR+dot/HQ4x):
 *    Process each pixel of the game bitmap using edge-adaptive interpolation
 *    and produce an UPSCALED bitmap (2x for XBR, 4x for 4XBR/HQ4x).
 *    The upscaled bitmap is then drawn with NEAREST filtering so the
 *    algorithm's output is preserved on screen.
 * 2. Mask filters (scanline/CRT/dot):
 *    Draw the game bitmap first, then overlay a mask pattern directly
 *    on the canvas at screen resolution.
 *
 * This class is used in BOTH GL and non-GL rendering modes:
 * - In GL mode: Canvas.GLRenderer calls applyFilter() to get an upscaled
 *   filtered bitmap, uploads it as the GL texture, and draws with a
 *   passthrough shader + NEAREST filtering.
 * - In non-GL modes: Canvas.onDraw/repaintScreen calls drawFiltered()
 *   which applies the filter and draws the result to the canvas.
 */
public final class J2meBitmapFilter {

    /** Filter mode constants — must match MicroActivity.filterNames. */
    public static final int MODE_NONE = 0;
    public static final int MODE_SCANLINE = 1;
    public static final int MODE_CRT = 2;
    public static final int MODE_DOT = 3;
    public static final int MODE_XBR = 4;
    public static final int MODE_4XBR = 5;
    public static final int MODE_XBR_DOT = 6;
    public static final int MODE_4XBR_DOT = 7;
    public static final int MODE_HQ4X = 8;

    /** Luminance threshold for edge detection (0-255 scale). */
    private static final int EDGE_THRESHOLD = 24;
    private static final int EDGE_THRESHOLD_WIDE = 40;

    /** Paint for drawing bitmaps without filtering (nearest-neighbor). */
    private static final Paint sNearestPaint;
    /** Paint for drawing bitmaps with bilinear filtering. */
    private static final Paint sSmoothPaint;
    /** Paint for drawing mask overlays. */
    private static final Paint sMaskPaint = new Paint();

    static {
        sNearestPaint = new Paint();
        sNearestPaint.setFilterBitmap(false);
        sSmoothPaint = new Paint();
        sSmoothPaint.setFilterBitmap(true);
    }

    // ─── Cached filtered (upscaled) bitmap ──────────────────────────────

    private static Bitmap sFilteredBitmap;
    private static int sCachedSrcW = -1;
    private static int sCachedSrcH = -1;
    private static int sCachedMode = -1;

    private J2meBitmapFilter() {}

    // ─── Mode classification ────────────────────────────────────────────

    public static boolean isFilteredMode(int mode) {
        return mode != MODE_NONE;
    }

    /**
     * Returns true if the mode requires CPU pixel processing and upscaling.
     * These filters produce an upscaled bitmap that is drawn with NEAREST.
     */
    public static boolean isPixelProcessingMode(int mode) {
        return mode == MODE_XBR || mode == MODE_4XBR ||
               mode == MODE_XBR_DOT || mode == MODE_4XBR_DOT ||
               mode == MODE_HQ4X;
    }

    /**
     * Returns the upscale factor for pixel-processing modes.
     * XBR = 2x, 4XBR/HQ4x = 4x.
     */
    public static int getScaleFactor(int mode) {
        if (mode == MODE_XBR || mode == MODE_XBR_DOT) return 2;
        if (mode == MODE_4XBR || mode == MODE_4XBR_DOT || mode == MODE_HQ4X) return 4;
        return 1;
    }

    public static boolean isMaskMode(int mode) {
        return mode == MODE_SCANLINE || mode == MODE_CRT || mode == MODE_DOT;
    }

    // ─── Public API: applyFilter (used by GLRenderer) ───────────────────

    /**
     * Applies a pixel-processing filter to the source bitmap and returns
     * an UPSCALED result. Used by Canvas.GLRenderer to pre-process the
     * game texture before uploading to GL.
     *
     * @param src  the game's offscreenCopy bitmap
     * @param mode one of MODE_XBR, MODE_4XBR, MODE_XBR_DOT, MODE_4XBR_DOT, MODE_HQ4X
     * @return upscaled filtered bitmap (2x or 4x the source dimensions)
     */
    public static Bitmap applyFilter(Bitmap src, int mode) {
        int sw = src.getWidth();
        int sh = src.getHeight();

        // Check cache
        if (sFilteredBitmap == null || sCachedSrcW != sw || sCachedSrcH != sh || sCachedMode != mode) {
            if (sFilteredBitmap != null) {
                sFilteredBitmap.recycle();
            }
            sCachedSrcW = sw;
            sCachedSrcH = sh;
            sCachedMode = mode;
        } else {
            // Reuse cached bitmap — just re-process pixels
        }

        switch (mode) {
            case MODE_XBR:
                return xbrUpscale(src, 2, false);
            case MODE_4XBR:
                return xbrUpscale(src, 4, true);
            case MODE_XBR_DOT:
                return applyDotMaskToBitmap(xbrUpscale(src, 2, false));
            case MODE_4XBR_DOT:
                return applyDotMaskToBitmap(xbrUpscale(src, 4, true));
            case MODE_HQ4X:
                return hq4xUpscale(src);
            default:
                return src;
        }
    }

    // ─── Public API: drawFiltered (used by non-GL Canvas) ───────────────

    /**
     * Applies the selected filter and draws the result to the destination canvas.
     *
     * For pixel-processing filters: produces an upscaled bitmap (2x/4x) and
     * draws it with NEAREST filtering to preserve the algorithm's output.
     * For mask filters: draws the bitmap then overlays a mask pattern.
     */
    public static void drawFiltered(Bitmap srcBitmap, Canvas dstCanvas,
                                    RectF dstRect, int mode) {
        if (mode == MODE_NONE) {
            dstCanvas.drawBitmap(srcBitmap,
                    new Rect(0, 0, srcBitmap.getWidth(), srcBitmap.getHeight()),
                    dstRect, sNearestPaint);
            return;
        }

        if (isPixelProcessingMode(mode)) {
            // Process and upscale the bitmap, then draw with NEAREST
            Bitmap filtered = applyFilter(srcBitmap, mode);
            if (filtered != null && filtered != srcBitmap) {
                dstCanvas.drawBitmap(filtered,
                        new Rect(0, 0, filtered.getWidth(), filtered.getHeight()),
                        dstRect, sNearestPaint); // NEAREST to preserve algorithm output
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

    // ─── XBR Upscaling Algorithm ────────────────────────────────────────

    /**
     * XBR upscaling algorithm.
     * For each source pixel, examines the 3x3 neighborhood, detects diagonal
     * edges, and produces scale×scale output pixels with edge-adaptive blending.
     *
     * @param src       source bitmap
     * @param scale     upscale factor (2 or 4)
     * @param stronger  if true, applies additional smoothing (4XBR mode)
     */
    private static Bitmap xbrUpscale(Bitmap src, int scale, boolean stronger) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int dw = sw * scale;
        int dh = sh * scale;

        int[] sp = new int[sw * sh];
        src.getPixels(sp, 0, sw, 0, 0, sw, sh);
        int[] dp = new int[dw * dh];

        int threshold = stronger ? EDGE_THRESHOLD_WIDE : EDGE_THRESHOLD;

        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int c = sp[y * sw + x];

                // 3x3 neighborhood
                int p00 = getPixelSafe(sp, sw, sh, x - 1, y - 1);
                int p01 = getPixelSafe(sp, sw, sh, x,     y - 1);
                int p02 = getPixelSafe(sp, sw, sh, x + 1, y - 1);
                int p10 = getPixelSafe(sp, sw, sh, x - 1, y);
                int p12 = getPixelSafe(sp, sw, sh, x + 1, y);
                int p20 = getPixelSafe(sp, sw, sh, x - 1, y + 1);
                int p21 = getPixelSafe(sp, sw, sh, x,     y + 1);
                int p22 = getPixelSafe(sp, sw, sh, x + 1, y + 1);

                int yc = rgbToY(c);
                int y01 = rgbToY(p01);
                int y10 = rgbToY(p10);
                int y12 = rgbToY(p12);
                int y21 = rgbToY(p21);

                // Edge detection: compare opposite neighbors
                int edgeV = Math.abs(y01 - y21);
                int edgeH = Math.abs(y10 - y12);
                int dEdge = edgeV + edgeH;

                // Diagonal edge detection
                int y00 = rgbToY(p00);
                int y02 = rgbToY(p02);
                int y20 = rgbToY(p20);
                int y22 = rgbToY(p22);

                int dTL_BR = Math.abs(y00 - y22) + Math.abs(y00 - yc) + Math.abs(y22 - yc);
                int dTR_BL = Math.abs(y02 - y20) + Math.abs(y02 - yc) + Math.abs(y20 - yc);

                for (int sy = 0; sy < scale; sy++) {
                    for (int sx = 0; sx < scale; sx++) {
                        int dx = x * scale + sx;
                        int dy = y * scale + sy;
                        int di = dy * dw + dx;

                        // Normalized position within the block (0.0 to 1.0)
                        float nx = (sx + 0.5f) / scale;
                        float ny = (sy + 0.5f) / scale;

                        int result = c;

                        if (dEdge >= threshold) {
                            // There's an edge — apply XBR diagonal interpolation
                            if (dTL_BR > dTR_BL) {
                                // Edge runs TL→BR
                                // Top-left quadrant: blend toward TL
                                if (nx + ny < 1.0f) {
                                    float w = (1.0f - nx - ny) * 0.5f;
                                    // Only blend if TL is similar to center (edge, not noise)
                                    if (colorDist(p00, c) < threshold * threshold * 3) {
                                        result = blendColor(c, p00, w);
                                    }
                                }
                                // Bottom-right quadrant: blend toward BR
                                else {
                                    float w = (nx + ny - 1.0f) * 0.5f;
                                    if (colorDist(p22, c) < threshold * threshold * 3) {
                                        result = blendColor(c, p22, w);
                                    }
                                }
                            } else if (dTR_BL > dTL_BR) {
                                // Edge runs TR→BL
                                // Top-right quadrant: blend toward TR
                                if (nx > ny) {
                                    float w = (nx - ny) * 0.5f;
                                    if (colorDist(p02, c) < threshold * threshold * 3) {
                                        result = blendColor(c, p02, w);
                                    }
                                }
                                // Bottom-left quadrant: blend toward BL
                                else {
                                    float w = (ny - nx) * 0.5f;
                                    if (colorDist(p20, c) < threshold * threshold * 3) {
                                        result = blendColor(c, p20, w);
                                    }
                                }
                            }

                            // Orthogonal edge blending
                            if (edgeV >= threshold) {
                                // Vertical edge: blend top/bottom
                                if (ny < 0.5f && colorDist(p01, c) < threshold * threshold * 3) {
                                    result = blendColor(result, p01, (0.5f - ny) * 0.3f);
                                } else if (ny >= 0.5f && colorDist(p21, c) < threshold * threshold * 3) {
                                    result = blendColor(result, p21, (ny - 0.5f) * 0.3f);
                                }
                            }
                            if (edgeH >= threshold) {
                                // Horizontal edge: blend left/right
                                if (nx < 0.5f && colorDist(p10, c) < threshold * threshold * 3) {
                                    result = blendColor(result, p10, (0.5f - nx) * 0.3f);
                                } else if (nx >= 0.5f && colorDist(p12, c) < threshold * threshold * 3) {
                                    result = blendColor(result, p12, (nx - 0.5f) * 0.3f);
                                }
                            }
                        }

                        // 4XBR extra smoothing: blend with 4-connected average
                        if (stronger && dEdge < threshold * 2) {
                            int avg4 = blendColor(
                                    blendColor(p01, p21, 0.5f),
                                    blendColor(p10, p12, 0.5f), 0.5f);
                            result = blendColor(result, avg4, 0.15f);
                        }

                        dp[di] = result;
                    }
                }
            }
        }

        Bitmap result = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        result.setPixels(dp, 0, dw, 0, 0, dw, dh);
        return result;
    }

    // ─── HQ4x Upscaling Algorithm ───────────────────────────────────────

    /**
     * HQ4x-inspired 4x upscaling algorithm.
     * Detects edge patterns using luminance thresholds and applies
     * directional interpolation for each of the 16 output pixels.
     */
    private static Bitmap hq4xUpscale(Bitmap src) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int dw = sw * 4;
        int dh = sh * 4;

        int[] sp = new int[sw * sh];
        src.getPixels(sp, 0, sw, 0, 0, sw, sh);
        int[] dp = new int[dw * dh];

        final int T1 = 8;   // low threshold for edge detection
        final int T2 = 24;  // medium threshold for smoothing

        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int c = sp[y * sw + x];
                int yc = rgbToY(c);

                // 3x3 neighborhood
                int p00 = getPixelSafe(sp, sw, sh, x - 1, y - 1);
                int p01 = getPixelSafe(sp, sw, sh, x,     y - 1);
                int p02 = getPixelSafe(sp, sw, sh, x + 1, y - 1);
                int p10 = getPixelSafe(sp, sw, sh, x - 1, y);
                int p12 = getPixelSafe(sp, sw, sh, x + 1, y);
                int p20 = getPixelSafe(sp, sw, sh, x - 1, y + 1);
                int p21 = getPixelSafe(sp, sw, sh, x,     y + 1);
                int p22 = getPixelSafe(sp, sw, sh, x + 1, y + 1);

                int y00 = rgbToY(p00), y01 = rgbToY(p01), y02 = rgbToY(p02);
                int y10 = rgbToY(p10), y12 = rgbToY(p12);
                int y20 = rgbToY(p20), y21 = rgbToY(p21), y22 = rgbToY(p22);

                // Edge flags
                boolean eT = Math.abs(y01 - yc) > T1;
                boolean eB = Math.abs(y21 - yc) > T1;
                boolean eL = Math.abs(y10 - yc) > T1;
                boolean eR = Math.abs(y12 - yc) > T1;
                boolean eTL = Math.abs(y00 - yc) > T1;
                boolean eTR = Math.abs(y02 - yc) > T1;
                boolean eBL = Math.abs(y20 - yc) > T1;
                boolean eBR = Math.abs(y22 - yc) > T1;

                // Smooth area check
                boolean smoothH = Math.abs(y10 - yc) < T2 && Math.abs(y12 - yc) < T2;
                boolean smoothV = Math.abs(y01 - yc) < T2 && Math.abs(y21 - yc) < T2;

                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        int dx = x * 4 + sx;
                        int dy = y * 4 + sy;
                        int di = dy * dw + dx;

                        float fx = (sx + 0.5f) / 4.0f;
                        float fy = (sy + 0.5f) / 4.0f;

                        int result = c;

                        // No edges → output center
                        if (!eT && !eB && !eL && !eR &&
                            !eTL && !eTR && !eBL && !eBR) {
                            dp[di] = result;
                            continue;
                        }

                        // Smooth area → slight blending with orthogonal neighbors
                        if (smoothH && smoothV) {
                            int avg4 = blendColor(
                                    blendColor(p01, p21, 0.5f),
                                    blendColor(p10, p12, 0.5f), 0.5f);
                            result = blendColor(c, avg4, 0.2f);
                            dp[di] = result;
                            continue;
                        }

                        // Edge-adaptive interpolation based on sub-pixel quadrant
                        if (fx < 0.5f && fy < 0.5f) {
                            // Top-left quadrant
                            if (eL && eT) {
                                result = blendColor(c, blendColor(p10, p01, 0.5f),
                                        0.4f * (1.0f - fx) * (1.0f - fy));
                            } else if (eL) {
                                result = blendColor(c, p10, (1.0f - fx) * 0.35f);
                            } else if (eT) {
                                result = blendColor(c, p01, (1.0f - fy) * 0.35f);
                            } else if (eTL) {
                                result = blendColor(c, p00, (1.0f - fx) * (1.0f - fy) * 0.4f);
                            }
                        } else if (fx >= 0.5f && fy < 0.5f) {
                            // Top-right quadrant
                            if (eR && eT) {
                                result = blendColor(c, blendColor(p12, p01, 0.5f),
                                        0.4f * fx * (1.0f - fy));
                            } else if (eR) {
                                result = blendColor(c, p12, fx * 0.35f);
                            } else if (eT) {
                                result = blendColor(c, p01, (1.0f - fy) * 0.35f);
                            } else if (eTR) {
                                result = blendColor(c, p02, fx * (1.0f - fy) * 0.4f);
                            }
                        } else if (fx < 0.5f && fy >= 0.5f) {
                            // Bottom-left quadrant
                            if (eL && eB) {
                                result = blendColor(c, blendColor(p10, p21, 0.5f),
                                        0.4f * (1.0f - fx) * fy);
                            } else if (eL) {
                                result = blendColor(c, p10, (1.0f - fx) * 0.35f);
                            } else if (eB) {
                                result = blendColor(c, p21, fy * 0.35f);
                            } else if (eBL) {
                                result = blendColor(c, p20, (1.0f - fx) * fy * 0.4f);
                            }
                        } else {
                            // Bottom-right quadrant
                            if (eR && eB) {
                                result = blendColor(c, blendColor(p12, p21, 0.5f),
                                        0.4f * fx * fy);
                            } else if (eR) {
                                result = blendColor(c, p12, fx * 0.35f);
                            } else if (eB) {
                                result = blendColor(c, p21, fy * 0.35f);
                            } else if (eBR) {
                                result = blendColor(c, p22, fx * fy * 0.4f);
                            }
                        }

                        dp[di] = result;
                    }
                }
            }
        }

        Bitmap result = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        result.setPixels(dp, 0, dw, 0, 0, dw, dh);
        return result;
    }

    // ─── Dot mask (applied to upscaled bitmap for XBR+dot modes) ────────

    /**
     * Applies a dot mask pattern to the bitmap in-place.
     * Darkens pixels in a circular pattern within each 4x4 cell.
     */
    private static Bitmap applyDotMaskToBitmap(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int pixel = pixels[idx];
                float cellX = (x % 4) / 3.0f - 0.5f;
                float cellY = (y % 4) / 3.0f - 0.5f;
                float dist = (float) Math.sqrt(cellX * cellX + cellY * cellY);
                float mask = Math.min(1.0f, Math.max(0.5f,
                        0.5f + 0.5f * smoothstep(0.2f, 0.5f, dist)));

                int r = (int) (((pixel >> 16) & 0xFF) * mask);
                int g = (int) (((pixel >> 8) & 0xFF) * mask);
                int b = (int) ((pixel & 0xFF) * mask);
                int a = (pixel >> 24) & 0xFF;
                pixels[idx] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    // ─── Canvas mask overlay (for scanline/CRT/dot in non-GL mode) ───────

    private static void applyCanvasMask(Canvas canvas, RectF rect, int mode) {
        sMaskPaint.setAntiAlias(false);
        sMaskPaint.setFilterBitmap(false);

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
        float top = rect.top;
        float bottom = rect.bottom;
        float left = rect.left;
        float right = rect.right;

        sMaskPaint.setColor(0x8C000000);
        for (float y = top; y < bottom; y += 4) {
            float rowBottom = Math.min(y + 1, bottom);
            canvas.drawRect(left, y, right, rowBottom, sMaskPaint);
        }
    }

    private static void drawCrtMask(Canvas canvas, RectF rect) {
        float left = rect.left;
        float top = rect.top;
        float right = rect.right;
        float bottom = rect.bottom;

        sMaskPaint.setColor(0x26FF0000);
        for (float x = left; x < right; x += 3) {
            canvas.drawRect(x, top, Math.min(x + 1, right), bottom, sMaskPaint);
        }
        sMaskPaint.setColor(0x2600FF00);
        for (float x = left + 1; x < right; x += 3) {
            canvas.drawRect(x, top, Math.min(x + 1, right), bottom, sMaskPaint);
        }
        sMaskPaint.setColor(0x260000FF);
        for (float x = left + 2; x < right; x += 3) {
            canvas.drawRect(x, top, Math.min(x + 1, right), bottom, sMaskPaint);
        }

        sMaskPaint.setColor(0x80000000);
        for (float y = top; y < bottom; y += 6) {
            float rowBottom = Math.min(y + 1, bottom);
            canvas.drawRect(left, y, right, rowBottom, sMaskPaint);
        }
    }

    private static void drawDotMask(Canvas canvas, RectF rect) {
        float left = rect.left;
        float top = rect.top;
        float right = rect.right;
        float bottom = rect.bottom;

        sMaskPaint.setColor(0x66000000);
        for (float y = top; y < bottom; y += 4) {
            for (float x = left; x < right; x += 4) {
                canvas.drawPoint(x, y, sMaskPaint);
                canvas.drawPoint(x + 3, y, sMaskPaint);
                canvas.drawPoint(x, y + 3, sMaskPaint);
                canvas.drawPoint(x + 3, y + 3, sMaskPaint);
            }
        }
    }

    // ─── Utility methods ─────────────────────────────────────────────────

    private static int getPixelSafe(int[] pixels, int w, int h, int x, int y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= w) x = w - 1;
        if (y >= h) y = h - 1;
        return pixels[y * w + x];
    }

    private static int rgbToY(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r * 77 + g * 150 + b * 29) >> 8;
    }

    private static int colorDist(int c1, int c2) {
        int dr = ((c1 >> 16) & 0xFF) - ((c2 >> 16) & 0xFF);
        int dg = ((c1 >> 8) & 0xFF) - ((c2 >> 8) & 0xFF);
        int db = (c1 & 0xFF) - (c2 & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    private static int blendColor(int c1, int c2, float ratio) {
        float r1 = (c1 >> 16) & 0xFF;
        float g1 = (c1 >> 8) & 0xFF;
        float b1 = c1 & 0xFF;
        float r2 = (c2 >> 16) & 0xFF;
        float g2 = (c2 >> 8) & 0xFF;
        float b2 = c2 & 0xFF;
        int r = Math.round(r1 * (1 - ratio) + r2 * ratio);
        int g = Math.round(g1 * (1 - ratio) + g2 * ratio);
        int b = Math.round(b1 * (1 - ratio) + b2 * ratio);
        int a = (c1 >> 24) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.min(1f, Math.max(0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
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
