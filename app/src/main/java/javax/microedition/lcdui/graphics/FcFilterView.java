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
 * Filter modes:
 *   0 = none (no filter)
 *   1 = scanline
 *   2 = CRT
 *   3 = dot
 *
 * The filter mode is stored in a J2ME-dedicated SharedPreferences file
 * ("j2me_prefs") under key "j2me_video_filter", completely separate from the
 * NES/FC emulator's filter preferences to prevent cross-contamination ("串滤镜").
 *
 * The view supports runtime filter switching via {@link #setFilterMode(int)}.
 * The view is transparent to touch events so it does not interfere with game input.
 */
public class FcFilterView extends View {

    /** J2ME-dedicated preferences file — never shared with NES/FC emulator. */
    private static final String J2ME_PREFS = "j2me_prefs";
    private static final String KEY_FILTER_MODE = "j2me_video_filter";

    /** Filter mode constants — must match MicroActivity.filterNames order. */
    public static final int MODE_NONE = 0;
    public static final int MODE_SCANLINE = 1;
    public static final int MODE_CRT = 2;
    public static final int MODE_DOT = 3;

    private int filterMode;
    private String filterType;
    private Bitmap patternBitmap;

    public FcFilterView(Context context) {
        this(context, null);
    }

    public FcFilterView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Do not consume touch events.
        setClickable(false);
        setFocusable(false);

        // Read the J2ME-dedicated filter mode (not shared with NES/FC emulator).
        // Default to scanline (MODE_SCANLINE) for the FC look.
        SharedPreferences prefs = context.getSharedPreferences(J2ME_PREFS, Context.MODE_PRIVATE);
        filterMode = prefs.getInt(KEY_FILTER_MODE, MODE_SCANLINE);

        applyFilterMode(filterMode);
    }

    /**
     * Sets the filter mode at runtime, updates the pattern, persists the choice,
     * and triggers a redraw.
     *
     * @param mode one of MODE_NONE, MODE_SCANLINE, MODE_CRT, MODE_DOT
     */
    public void setFilterMode(int mode) {
        if (mode < MODE_NONE || mode > MODE_DOT) {
            mode = MODE_SCANLINE;
        }
        if (mode == filterMode && patternBitmap != null) return;

        filterMode = mode;
        applyFilterMode(mode);

        // Persist to J2ME-dedicated preferences (not shared with NES).
        SharedPreferences prefs = getContext().getSharedPreferences(J2ME_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_FILTER_MODE, mode).apply();

        invalidate();
    }

    /**
     * Returns the current filter mode.
     */
    public int getFilterMode() {
        return filterMode;
    }

    /**
     * Maps a filter mode constant to a type string and (re)creates the pattern bitmap.
     */
    private void applyFilterMode(int mode) {
        switch (mode) {
            case MODE_SCANLINE:
                filterType = "scanline";
                patternBitmap = createScanlinePattern();
                break;
            case MODE_CRT:
                filterType = "crt";
                patternBitmap = createCrtPattern();
                break;
            case MODE_DOT:
                filterType = "dot";
                patternBitmap = createDotPattern();
                break;
            case MODE_NONE:
            default:
                filterType = "none";
                patternBitmap = null;
                break;
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
