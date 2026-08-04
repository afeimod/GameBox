package javax.microedition.lcdui.graphics;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * FcFilterView is a custom Android View that draws FC (NES/Famicom) video filter
 * patterns (scanline/CRT/dot) over the J2ME game surface as a global filter overlay.
 *
 * The filter type is read from SharedPreferences key "video_filter"
 * (default "scanline").
 *
 * The view is transparent to touch events so it does not interfere with game input.
 */
public class FcFilterView extends View {

    private final String filterType;
    private final Bitmap patternBitmap;

    public FcFilterView(Context context) {
        this(context, null);
    }

    public FcFilterView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Do not consume touch events.
        setClickable(false);
        setFocusable(false);

        // Read the video filter type from the same SharedPreferences as the NES/FC emulator
        // (PadLayoutStore uses "pad_layout_v2"). J2ME uses FC video filter as global filter:
        // if NES filter is "none", default to "scanline" so J2ME always has the FC look.
        SharedPreferences prefs = context.getSharedPreferences("pad_layout_v2", Context.MODE_PRIVATE);
        String rawFilter = prefs.getString("video_filter", "scanline");
        if (rawFilter == null || "none".equals(rawFilter) || rawFilter.startsWith("xbr") || rawFilter.startsWith("hq")) {
            // For composite filter types like "xbr_dot", extract the dot part
            if (rawFilter != null && rawFilter.endsWith("_dot")) {
                filterType = "dot";
            } else {
                filterType = "scanline"; // FC global filter default
            }
        } else {
            filterType = rawFilter;
        }

        // Create the pattern bitmap based on the filter type.
        patternBitmap = createPattern(filterType);
    }

    /**
     * Creates the filter pattern bitmap based on the given filter type.
     *
     * @param type the filter type ("scanline", "crt", "dot", or "none")
     * @return the pattern bitmap, or null if no filter ("none")
     */
    private static Bitmap createPattern(String type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case "scanline":
                return createScanlinePattern();
            case "crt":
                return createCrtPattern();
            case "dot":
                return createDotPattern();
            case "none":
            default:
                return null;
        }
    }

    /**
     * Scanline pattern: 2x4 bitmap, 3 transparent rows + 1 row of 0x8C000000 (55% black).
     */
    private static Bitmap createScanlinePattern() {
        Bitmap bmp = Bitmap.createBitmap(2, 4, Bitmap.Config.ARGB_8888);
        for (int x = 0; x <= 1; x++) {
            bmp.setPixel(x, 0, 0x00000000);
            bmp.setPixel(x, 1, 0x00000000);
            bmp.setPixel(x, 2, 0x00000000);
            bmp.setPixel(x, 3, 0x8C000000); // 55% black
        }
        return bmp;
    }

    /**
     * CRT pattern: 3x6 bitmap, 5 rows with RGB phosphor tints
     * (0x26FF0000, 0x2600FF00, 0x260000FF) + 1 row of 0x80000000 (50% black scanline).
     */
    private static Bitmap createCrtPattern() {
        Bitmap bmp = Bitmap.createBitmap(3, 6, Bitmap.Config.ARGB_8888);
        for (int y = 0; y <= 4; y++) {
            bmp.setPixel(0, y, 0x26FF0000); // red phosphor
            bmp.setPixel(1, y, 0x2600FF00); // green phosphor
            bmp.setPixel(2, y, 0x260000FF); // blue phosphor
        }
        for (int x = 0; x <= 2; x++) {
            bmp.setPixel(x, 5, 0x80000000); // 50% black scanline
        }
        return bmp;
    }

    /**
     * Dot pattern: 4x4 bitmap with smoothstep circular dots, alpha from 0 at center
     * to 128 at corners.
     */
    private static Bitmap createDotPattern() {
        int size = 4;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        float center = (size - 1) / 2.0f; // 1.5
        float dotRadius = 1.0f;
        float maxDist = (float) Math.sqrt(center * center + center * center);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - center;
                float dy = y - center;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float t = Math.min(1f, Math.max(0f, (dist - dotRadius) / (maxDist - dotRadius)));
                float smoothT = t * t * (3 - 2 * t); // smoothstep
                int alpha = Math.min(255, Math.max(0, (int) (smoothT * 128f)));
                bmp.setPixel(x, y, (alpha << 24));
            }
        }
        return bmp;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (patternBitmap == null) return;
        BitmapShader shader = new BitmapShader(patternBitmap,
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Paint paint = new Paint();
        paint.setShader(shader);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        if ("crt".equals(filterType)) {
            // CRT vignette: transparent center to 35% black edges.
            RadialGradient vignette = new RadialGradient(
                    getWidth() / 2f, getHeight() / 2f,
                    Math.min(getWidth(), getHeight()) * 0.7f,
                    new int[]{Color.TRANSPARENT, Color.argb(89, 0, 0, 0)}, // 35% black
                    null, Shader.TileMode.CLAMP
            );
            Paint vignettePaint = new Paint();
            vignettePaint.setShader(vignette);
            canvas.drawRect(0, 0, getWidth(), getHeight(), vignettePaint);
        }
    }

    /**
     * The view is transparent to touch events so game input passes through.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return false;
    }
}
