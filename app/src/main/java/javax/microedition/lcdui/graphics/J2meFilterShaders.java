package javax.microedition.lcdui.graphics;

/**
 * GLSL vertex + fragment shader sources for J2ME video filters.
 *
 * <p>The shader algorithms are taken <b>exactly</b> from the reference PPSSPP-style
 * shader files (Hyllian's 2xBR / 4xBR, guest(r)'s 4xGLSLHqFilter, Themaister's
 * scanline / dot shaders and the PPSSPP CRT shader) and only adapted for OpenGL ES
 * 2.0:
 * <ul>
 *   <li>{@code attribute vec4 a_position} is replaced by {@code attribute vec2 a_position}
 *       together with {@code gl_Position = vec4(a_position, 0.0, 1.0)} (the VBO only
 *       feeds 2 floats per position anyway).</li>
 *   <li>{@code attribute vec2 a_texcoord0} and all {@code varying} declarations are
 *       kept verbatim (GL ES 2.0 uses {@code varying}, not {@code in}/{@code out}).</li>
 *   <li>Fragment shaders keep the precision qualifiers from the reference files.</li>
 *   <li>Uniforms {@code sampler0}, {@code u_texelDelta} and {@code u_pixelDelta} are
 *       provided by {@link ShaderProgram} (u_texelDelta = 1/textureSize,
 *       u_pixelDelta = 1/screenSize).</li>
 * </ul>
 *
 * <p>Filter modes (must match {@code J2meBitmapFilter} constants):
 * <pre>
 *   0 = None         (passthrough)
 *   1 = Scanline      (scanlines-emu)
 *   2 = CRT           (crt, u_time removed)
 *   3 = Dot           (LCD dot effect, full version)
 *   4 = 2xBR          (Hyllian 5xBR v3.5a)
 *   5 = 4xBR          (Hyllian xBR-lv2 Accuracy)
 *   6 = 2xBR + Dot
 *   7 = 4xBR + Dot
 *   8 = HQ4x          (4xGLSLHqFilter)
 *   9 = HQ4x + Dot    (NEW)
 * </pre>
 */
public final class J2meFilterShaders {

    // ─── Mode constants (mirror J2meBitmapFilter, plus the new HQ4x+Dot) ──────
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

    // ─── Default passthrough shaders (mode 0) ────────────────────────────────

    /**
     * Standard passthrough vertex shader (vec4 a_position). Used for mode 0 and as
     * the fallback when a mode does not need a custom vertex shader.
     */
    public static final String VERTEX_SHADER =
            "attribute vec4 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "void main() {\n" +
            "    gl_Position = a_position;\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "}\n";

    /** Plain passthrough fragment shader (no filter). */
    public static final String FRAGMENT_NONE =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sampler0, v_texcoord0);\n" +
            "}\n";

    // ─── Vertex shaders ──────────────────────────────────────────────────────

    /** Scanline vertex shader (scanlines-emu.vsh). Computes the {@code omega} varying. */
    public static final String VERTEX_SCANLINE =
            "uniform vec2 u_texelDelta;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "varying vec2 omega;\n" +
            "\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "    omega = vec2(3.1415 / u_pixelDelta.x / u_texelDelta.x * u_texelDelta.x, 2.0 * 3.1415 / u_texelDelta.y);\n" +
            "}\n";

    /**
     * CRT vertex shader. The reference crt.fsh only needs {@code v_texcoord0} (no
     * custom .vsh exists), so this is the GL ES 2.0 vec2 passthrough used to keep
     * {@code a_position} consistent across all non-zero filter modes.
     */
    public static final String VERTEX_CRT =
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "}\n";

    /** Dot vertex shader (dot.vsh). Pre-computes the 3x3 neighborhood texcoords. */
    public static final String VERTEX_DOT =
            "uniform vec2 u_texelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "varying vec4 v_texcoord1; // c00_10\n" +
            "varying vec4 v_texcoord2; // c20_01\n" +
            "varying vec4 v_texcoord3; // c21_02\n" +
            "varying vec4 v_texcoord4; // c12_22\n" +
            "varying vec2 v_texcoord5; // c11\n" +
            "varying vec2 v_texcoord6; // pixel_no\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "\n" +
            "    float dx = u_texelDelta.x;\n" +
            "    float dy = u_texelDelta.y;\n" +
            "\n" +
            "    // c00_10\n" +
            "    v_texcoord1 = vec4(v_texcoord0 + vec2(-dx, -dy), v_texcoord0 + vec2(0.0, -dy));\n" +
            "\n" +
            "    // c20_01\n" +
            "    v_texcoord2 = vec4(v_texcoord0 + vec2(dx, -dy), v_texcoord0 + vec2(-dx, 0.0));\n" +
            "\n" +
            "    // c21_02\n" +
            "    v_texcoord3 = vec4(v_texcoord0 + vec2(dx, 0.0), v_texcoord0 + vec2(-dx, dy));\n" +
            "\n" +
            "    // c12_22\n" +
            "    v_texcoord4 = vec4(v_texcoord0 + vec2(0.0, dy), v_texcoord0 + vec2(dx, dy));\n" +
            "\n" +
            "    // c11\n" +
            "    v_texcoord5 = v_texcoord0;\n" +
            "\n" +
            "    // pixel_no\n" +
            "    v_texcoord6 = v_texcoord0 * (1.0 / u_texelDelta.xy);\n" +
            "}\n";

    /**
     * XBR vertex shader — standard passthrough (same as 5xBR.vsh reference).
     * Uses {@code attribute vec4 a_position} and {@code varying vec2 v_texcoord0}.
     * The 5xBR fragment shader computes all neighborhood texcoords itself using
     * {@code u_texelDelta} and {@code u_pixelDelta}, so no special vertex shader
     * is needed.
     */
    public static final String VERTEX_2XBR = VERTEX_SHADER;

    /** 4xBR vertex shader — identical to 2XBR (standard passthrough). */
    public static final String VERTEX_4XBR = VERTEX_SHADER;

    /** HQ4x vertex shader (hq4x.vsh). Provides {@code varying vec4 v_texcoord0[7]}. */
    public static final String VERTEX_HQ4X =
            "uniform vec2 u_texelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec4 v_texcoord0[7];\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    vec2 dg1 = 0.5 * u_texelDelta;\n" +
            "    vec2 dg2 = vec2(-dg1.x, dg1.y);\n" +
            "    vec2 sd1 = dg1 * 0.5;\n" +
            "    vec2 sd2 = dg2 * 0.5;\n" +
            "    vec2 ddx = vec2(dg1.x, 0.0);\n" +
            "    vec2 ddy = vec2(0.0, dg1.y);\n" +
            "\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "    v_texcoord0[0].xy = a_texcoord0;\n" +
            "    v_texcoord0[1].xy = a_texcoord0 - sd1;\n" +
            "    v_texcoord0[2].xy = a_texcoord0 - sd2;\n" +
            "    v_texcoord0[3].xy = a_texcoord0 + sd1;\n" +
            "    v_texcoord0[4].xy = a_texcoord0 + sd2;\n" +
            "    v_texcoord0[5].xy = a_texcoord0 - dg1;\n" +
            "    v_texcoord0[6].xy = a_texcoord0 + dg1;\n" +
            "    v_texcoord0[5].zw = a_texcoord0 - dg2;\n" +
            "    v_texcoord0[6].zw = a_texcoord0 + dg2;\n" +
            "    v_texcoord0[1].zw = a_texcoord0 - ddy;\n" +
            "    v_texcoord0[2].zw = a_texcoord0 + ddx;\n" +
            "    v_texcoord0[3].zw = a_texcoord0 + ddy;\n" +
            "    v_texcoord0[4].zw = a_texcoord0 - ddx;\n" +
            "}\n";

    // ─── Fragment shaders ────────────────────────────────────────────────────

    /** Scanline fragment shader (scanlines-emu.fsh). Uses the {@code omega} varying. */
    public static final String FRAGMENT_SCANLINE =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "varying vec2 omega;\n" +
            "\n" +
            "const float base_brightness = 0.95;\n" +
            "const vec2 sine_comp = vec2(0.05, 0.15);\n" +
            "\n" +
            "void main () {\n" +
            "    vec4 c11 = texture2D(sampler0, v_texcoord0);\n" +
            "\n" +
            "    vec4 scanline = c11 * (base_brightness + dot(sine_comp * sin(v_texcoord0 * omega), vec2(1.0)));\n" +
            "    gl_FragColor = clamp(scanline, 0.0, 1.0);\n" +
            "}\n";

    /**
     * CRT fragment shader (crt.fsh) with the {@code u_time} uniform removed (time
     * frozen at 0). Keeps the scanline + NTSC color-bleed shift + rollbar effect.
     */
    public static final String FRAGMENT_CRT =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "precision mediump int;\n" +
            "#endif\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    // scanlines (u_time.x == 0)\n" +
            "    int vPos = int( v_texcoord0.y * 272.0 );\n" +
            "    float line_intensity = mod( float(vPos), 2.0 );\n" +
            "\n" +
            "    // color shift\n" +
            "    float off = line_intensity * 0.0005;\n" +
            "    vec2 shift = vec2( off, 0 );\n" +
            "\n" +
            "    // shift R and G channels to simulate NTSC color bleed\n" +
            "    vec2 colorShift = vec2( 0.001, 0 );\n" +
            "    float r = texture2D( sampler0, v_texcoord0 + colorShift + shift ).x;\n" +
            "    float g = texture2D( sampler0, v_texcoord0 - colorShift + shift ).y;\n" +
            "    float b = texture2D( sampler0, v_texcoord0 ).z;\n" +
            "\n" +
            "    vec4 c = vec4( r, g * 0.99, b, 1.0 ) * clamp( line_intensity, 0.85, 1.0 );\n" +
            "\n" +
            "    float rollbar = sin( v_texcoord0.y * 4.0 );\n" +
            "\n" +
            "    gl_FragColor.rgba = c + (rollbar * 0.02);\n" +
            "}\n";

    /** Dot fragment shader (dot.fsh, full version). LCD dot effect with bloom. */
    public static final String FRAGMENT_DOT =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "precision mediump int;\n" +
            "#endif\n" +
            "\n" +
            "//=== Config\n" +
            "#define gamma 2.4\n" +
            "#define shine 0.05\n" +
            "#define blend 0.65\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "varying vec2 v_texcoord0;\n" +
            "varying vec4 v_texcoord1; // c00_10\n" +
            "varying vec4 v_texcoord2; // c20_01\n" +
            "varying vec4 v_texcoord3; // c21_02\n" +
            "varying vec4 v_texcoord4; // c12_22\n" +
            "varying vec2 v_texcoord5; // c11\n" +
            "varying vec2 v_texcoord6; // pixel_no\n" +
            "\n" +
            "float dist(vec2 coord, vec2 source)\n" +
            "{\n" +
            "    vec2 delta = coord - source;\n" +
            "    return sqrt(dot(delta, delta));\n" +
            "}\n" +
            "\n" +
            "float color_bloom(vec3 color)\n" +
            "{\n" +
            "    const vec3 gray_coeff = vec3(0.30, 0.59, 0.11);\n" +
            "    float bright = dot(color, gray_coeff);\n" +
            "    return mix(1.0 + shine, 1.0 - shine, bright);\n" +
            "}\n" +
            "\n" +
            "vec3 lookup(vec2 pixel_no, float offset_x, float offset_y, vec3 color)\n" +
            "{\n" +
            "    vec2 offset = vec2(offset_x, offset_y);\n" +
            "    float delta = dist(fract(pixel_no), offset + vec2(0.5, 0.5));\n" +
            "    return color * exp(-gamma * delta * color_bloom(color));\n" +
            "}\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    vec3 mid_color = lookup(v_texcoord6, 0.0, 0.0, texture2D(sampler0, v_texcoord5).rgb);\n" +
            "\n" +
            "    vec3 color = vec3(0.0, 0.0, 0.0);\n" +
            "\n" +
            "    color += lookup(v_texcoord6, -1.0, -1.0, texture2D(sampler0, v_texcoord1.xy).rgb);\n" +
            "    color += lookup(v_texcoord6,  0.0, -1.0, texture2D(sampler0, v_texcoord1.zw).rgb);\n" +
            "    color += lookup(v_texcoord6,  1.0, -1.0, texture2D(sampler0, v_texcoord2.xy).rgb);\n" +
            "    color += lookup(v_texcoord6, -1.0,  0.0, texture2D(sampler0, v_texcoord2.zw).rgb);\n" +
            "    color += mid_color;\n" +
            "    color += lookup(v_texcoord6,  1.0,  0.0, texture2D(sampler0, v_texcoord3.xy).rgb);\n" +
            "    color += lookup(v_texcoord6, -1.0,  1.0, texture2D(sampler0, v_texcoord3.zw).rgb);\n" +
            "    color += lookup(v_texcoord6,  0.0,  1.0, texture2D(sampler0, v_texcoord4.xy).rgb);\n" +
            "    color += lookup(v_texcoord6,  1.0,  1.0, texture2D(sampler0, v_texcoord4.zw).rgb);\n" +
            "\n" +
            "    vec3 out_color = mix(1.2 * mid_color, color, blend);\n" +
            "\n" +
            "    gl_FragColor = vec4(out_color, 1.0);\n" +
            "}\n";

    /**
     * XBR fragment shader — Hyllian's 5xBR v3.5a (5xBR.fsh reference).
     *
     * <p>Replaces the original 2xBR shader which used {@code reduce()} with
     * {@code dtt = vec3(65536.0, 255.0, 1.0)} — the value 65536.0 exceeds
     * {@code mediump} float max (65504), causing overflow and breaking edge
     * detection on mobile GPUs.
     *
     * <p>The 5xBR shader uses a completely different, mobile-safe approach:
     * <ul>
     *   <li>Weighted luminance via {@code dot(color, rgbw)} where
     *       {@code rgbw = vec3(16.163, 23.351, 8.4772)} — max value ~ 48,
     *       well within {@code mediump} range.</li>
     *   <li>Standard passthrough vertex shader ({@code varying vec2 v_texcoord0}).</li>
     *   <li>Uses both {@code u_texelDelta} and {@code u_pixelDelta} to compute
     *       neighborhood texcoords in the fragment shader.</li>
     *   <li>Advanced edge detection with 4-direction rules and line equations.</li>
     * </ul>
     */
    public static final String FRAGMENT_2XBR =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "precision mediump int;\n" +
            "#endif\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "varying vec2 v_texcoord0;\n" +
            "\n" +
            "const float coef = 2.0;\n" +
            "const vec3  rgbw = vec3(16.163, 23.351, 8.4772);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0 );\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5 );\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0 );\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0 );\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5 );\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5 );\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) {\n" +
            "    return abs(A-B);\n" +
            "}\n" +
            "\n" +
            "vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d, vec4 e, vec4 f, vec4 g, vec4 h) {\n" +
            "    return (df(a,b) + df(a,c) + df(d,e) + df(d,f) + 4.0*df(g,h));\n" +
            "}\n" +
            "\n" +
            "void main(){\n" +
            " bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            " vec3 res = texture2D(sampler0, v_texcoord0.xy).xyz;\n" +
            "\n" +
            " if (upscale) {\n" +
            "    bvec4 edr, edr_left, edr_up, px;\n" +
            "    bvec4 interp_restriction_lv1, interp_restriction_lv2_left, interp_restriction_lv2_up;\n" +
            "    bvec4 nc;\n" +
            "    bvec4 fx, fx_left, fx_up;\n" +
            "\n" +
            "    vec2 pS  = 1.0 / u_texelDelta.xy;\n" +
            "    vec2 fp  = fract(v_texcoord0.xy*pS.xy);\n" +
            "    vec2 TexCoord_0 = v_texcoord0.xy-fp*u_texelDelta.xy;\n" +
            "    vec2 dx  = vec2(u_texelDelta.x,0.0);\n" +
            "    vec2 dy  = vec2(0.0,u_texelDelta.y);\n" +
            "    vec2 y2  = dy + dy; vec2 x2  = dx + dx;\n" +
            "\n" +
            "    vec3 A  = texture2D(sampler0, TexCoord_0 -dx -dy).xyz;\n" +
            "    vec3 B  = texture2D(sampler0, TexCoord_0     -dy).xyz;\n" +
            "    vec3 C  = texture2D(sampler0, TexCoord_0 +dx -dy).xyz;\n" +
            "    vec3 D  = texture2D(sampler0, TexCoord_0 -dx    ).xyz;\n" +
            "    vec3 E  = texture2D(sampler0, TexCoord_0        ).xyz;\n" +
            "    vec3 F  = texture2D(sampler0, TexCoord_0 +dx    ).xyz;\n" +
            "    vec3 G  = texture2D(sampler0, TexCoord_0 -dx +dy).xyz;\n" +
            "    vec3 H  = texture2D(sampler0, TexCoord_0     +dy).xyz;\n" +
            "    vec3 I  = texture2D(sampler0, TexCoord_0 +dx +dy).xyz;\n" +
            "    vec3 A1 = texture2D(sampler0, TexCoord_0     -dx -y2).xyz;\n" +
            "    vec3 C1 = texture2D(sampler0, TexCoord_0     +dx -y2).xyz;\n" +
            "    vec3 A0 = texture2D(sampler0, TexCoord_0 -x2     -dy).xyz;\n" +
            "    vec3 G0 = texture2D(sampler0, TexCoord_0 -x2     +dy).xyz;\n" +
            "    vec3 C4 = texture2D(sampler0, TexCoord_0 +x2     -dy).xyz;\n" +
            "    vec3 I4 = texture2D(sampler0, TexCoord_0 +x2     +dy).xyz;\n" +
            "    vec3 G5 = texture2D(sampler0, TexCoord_0     -dx +y2).xyz;\n" +
            "    vec3 I5 = texture2D(sampler0, TexCoord_0     +dx +y2).xyz;\n" +
            "    vec3 B1 = texture2D(sampler0, TexCoord_0         -y2).xyz;\n" +
            "    vec3 D0 = texture2D(sampler0, TexCoord_0 -x2        ).xyz;\n" +
            "    vec3 H5 = texture2D(sampler0, TexCoord_0         +y2).xyz;\n" +
            "    vec3 F4 = texture2D(sampler0, TexCoord_0 +x2        ).xyz;\n" +
            "\n" +
            "    vec4 b  = vec4(dot(B ,rgbw), dot(D ,rgbw), dot(H ,rgbw), dot(F ,rgbw));\n" +
            "    vec4 c  = vec4(dot(C ,rgbw), dot(A ,rgbw), dot(G ,rgbw), dot(I ,rgbw));\n" +
            "    vec4 d  = vec4(b.y, b.z, b.w, b.x);\n" +
            "    vec4 e  = vec4(dot(E,rgbw));\n" +
            "    vec4 f  = vec4(b.w, b.x, b.y, b.z);\n" +
            "    vec4 g  = vec4(c.z, c.w, c.x, c.y);\n" +
            "    vec4 h  = vec4(b.z, b.w, b.x, b.y);\n" +
            "    vec4 i  = vec4(c.w, c.x, c.y, c.z);\n" +
            "    vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "    vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "    vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "    vec4 f4 = vec4(h5.y, h5.z, h5.w, h5.x);\n" +
            "\n" +
            "    fx       = greaterThan(Ao*fp.y+Bo*fp.x,Co);\n" +
            "    fx_left  = greaterThan(Ax*fp.y+Bx*fp.x,Cx);\n" +
            "    fx_up    = greaterThan(Ay*fp.y+By*fp.x,Cy);\n" +
            "\n" +
            "    interp_restriction_lv1     = bvec4(vec4(notEqual(e,f))*vec4(notEqual(e,h)));\n" +
            "    interp_restriction_lv2_left = bvec4(vec4(notEqual(e,g))*vec4(notEqual(d,g)));\n" +
            "    interp_restriction_lv2_up   = bvec4(vec4(notEqual(e,c))*vec4(notEqual(b,c)));\n" +
            "\n" +
            "    edr      = bvec4(vec4(lessThan(weighted_distance( e, c, g, i, h5, f4, h, f), weighted_distance( h, d, i5, f, i4, b, e, i)))*vec4(interp_restriction_lv1));\n" +
            "    edr_left = bvec4(vec4(lessThanEqual(coef*df(f,g),df(h,c)))*vec4(interp_restriction_lv2_left));\n" +
            "    edr_up   = bvec4(vec4(greaterThanEqual(df(f,g),coef*df(h,c)))*vec4(interp_restriction_lv2_up));\n" +
            "\n" +
            "    nc.x = ( edr.x && (fx.x || edr_left.x && fx_left.x || edr_up.x && fx_up.x) );\n" +
            "    nc.y = ( edr.y && (fx.y || edr_left.y && fx_left.y || edr_up.y && fx_up.y) );\n" +
            "    nc.z = ( edr.z && (fx.z || edr_left.z && fx_left.z || edr_up.z && fx_up.z) );\n" +
            "    nc.w = ( edr.w && (fx.w || edr_left.w && fx_left.w || edr_up.w && fx_up.w) );\n" +
            "\n" +
            "    px = lessThanEqual(df(e,f),df(e,h));\n" +
            "\n" +
            "    res = nc.x ? px.x ? F : H : nc.y ? px.y ? B : F : nc.z ? px.z ? D : B : nc.w ? px.w ? H : D : E;\n" +
            " }\n" +
            " gl_FragColor.rgb = res;\n" +
            " gl_FragColor.a = 1.0;\n" +
            "}\n";

    /**
     * 4XBR fragment shader — Hyllian's xBR-lv2 Accuracy (5xBR-lv2.fsh reference).
     *
     * <p>An enhanced version of 5xBR with level-2 interpolation restrictions,
     * corner rounding, and smooth tips. Preprocessed with CornerA=0, CornerB=0,
     * CornerD=0 (default CornerC behavior, SMOOTH_TIPS enabled).
     */
    public static final String FRAGMENT_4XBR =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "precision mediump int;\n" +
            "#endif\n" +
            "\n" +
            "const float XBR_SCALE = 3.0;\n" +
            "const float lv2_cf    = 2.0;\n" +
            "\n" +
            "const float coef          = 2.0;\n" +
            "const vec3  rgbw          = vec3(14.352, 28.176, 5.472);\n" +
            "const vec4  eq_threshold  = vec4(15.0, 15.0, 15.0, 15.0);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0 );\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5 );\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0 );\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0 );\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5 );\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5 );\n" +
            "const vec4 Ci = vec4(0.25, 0.25, 0.25, 0.25);\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "varying vec2 v_texcoord0;\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) {\n" +
            "    return vec4(abs(A-B));\n" +
            "}\n" +
            "\n" +
            "vec4 diff(vec4 A, vec4 B) {\n" +
            "    return vec4(notEqual(A, B));\n" +
            "}\n" +
            "\n" +
            "vec4 eq(vec4 A, vec4 B) {\n" +
            "    return (step(df(A, B), eq_threshold));\n" +
            "}\n" +
            "\n" +
            "vec4 neq(vec4 A, vec4 B) {\n" +
            "    return (vec4(1.0, 1.0, 1.0, 1.0) - eq(A, B));\n" +
            "}\n" +
            "\n" +
            "float c_df(vec3 c1, vec3 c2) {\n" +
            "    vec3 df = abs(c1 - c2);\n" +
            "    return df.r + df.g + df.b;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            " bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            " vec3 res = texture2D(sampler0, v_texcoord0.xy).xyz;\n" +
            "\n" +
            " if (upscale) {\n" +
            "    vec4 edri, edr, edr_l, edr_u, px;\n" +
            "    vec4 irlv0, irlv1, irlv2l, irlv2u;\n" +
            "    vec4 fx, fx_l, fx_u;\n" +
            "\n" +
            "    vec2 pS  = 1.0 / u_texelDelta.xy;\n" +
            "    vec2 fp  = fract(v_texcoord0.xy*pS.xy);\n" +
            "    vec2 TexCoord_0 = v_texcoord0.xy-fp*u_texelDelta.xy;\n" +
            "    vec2 dx  = vec2(u_texelDelta.x,0.0);\n" +
            "    vec2 dy  = vec2(0.0,u_texelDelta.y);\n" +
            "    vec2 y2  = dy + dy; vec2 x2  = dx + dx;\n" +
            "\n" +
            "    vec4 delta   = vec4(1.0/XBR_SCALE, 1.0/XBR_SCALE, 1.0/XBR_SCALE, 1.0/XBR_SCALE);\n" +
            "    vec4 delta_l = vec4(0.5/XBR_SCALE, 1.0/XBR_SCALE, 0.5/XBR_SCALE, 1.0/XBR_SCALE);\n" +
            "    vec4 delta_u = delta_l.yxwz;\n" +
            "\n" +
            "    vec3 A  = texture2D(sampler0, TexCoord_0 -dx -dy).xyz;\n" +
            "    vec3 B  = texture2D(sampler0, TexCoord_0     -dy).xyz;\n" +
            "    vec3 C  = texture2D(sampler0, TexCoord_0 +dx -dy).xyz;\n" +
            "    vec3 D  = texture2D(sampler0, TexCoord_0 -dx    ).xyz;\n" +
            "    vec3 E  = texture2D(sampler0, TexCoord_0        ).xyz;\n" +
            "    vec3 F  = texture2D(sampler0, TexCoord_0 +dx    ).xyz;\n" +
            "    vec3 G  = texture2D(sampler0, TexCoord_0 -dx +dy).xyz;\n" +
            "    vec3 H  = texture2D(sampler0, TexCoord_0     +dy).xyz;\n" +
            "    vec3 I  = texture2D(sampler0, TexCoord_0 +dx +dy).xyz;\n" +
            "    vec3 A1 = texture2D(sampler0, TexCoord_0     -dx -y2).xyz;\n" +
            "    vec3 C1 = texture2D(sampler0, TexCoord_0     +dx -y2).xyz;\n" +
            "    vec3 A0 = texture2D(sampler0, TexCoord_0 -x2     -dy).xyz;\n" +
            "    vec3 G0 = texture2D(sampler0, TexCoord_0 -x2     +dy).xyz;\n" +
            "    vec3 C4 = texture2D(sampler0, TexCoord_0 +x2     -dy).xyz;\n" +
            "    vec3 I4 = texture2D(sampler0, TexCoord_0 +x2     +dy).xyz;\n" +
            "    vec3 G5 = texture2D(sampler0, TexCoord_0     -dx +y2).xyz;\n" +
            "    vec3 I5 = texture2D(sampler0, TexCoord_0     +dx +y2).xyz;\n" +
            "    vec3 B1 = texture2D(sampler0, TexCoord_0         -y2).xyz;\n" +
            "    vec3 D0 = texture2D(sampler0, TexCoord_0 -x2        ).xyz;\n" +
            "    vec3 H5 = texture2D(sampler0, TexCoord_0         +y2).xyz;\n" +
            "    vec3 F4 = texture2D(sampler0, TexCoord_0 +x2        ).xyz;\n" +
            "\n" +
            "    vec4 b  = vec4(dot(B ,rgbw), dot(D ,rgbw), dot(H ,rgbw), dot(F ,rgbw));\n" +
            "    vec4 c  = vec4(dot(C ,rgbw), dot(A ,rgbw), dot(G ,rgbw), dot(I ,rgbw));\n" +
            "    vec4 d  = b.yzwx;\n" +
            "    vec4 e  = vec4(dot(E,rgbw));\n" +
            "    vec4 f  = b.wxyz;\n" +
            "    vec4 g  = c.zwxy;\n" +
            "    vec4 h  = b.zwxy;\n" +
            "    vec4 i  = c.wxyz;\n" +
            "    vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "    vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "    vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "    vec4 f4 = h5.yzwx;\n" +
            "\n" +
            "    fx   = (Ao*fp.y+Bo*fp.x);\n" +
            "    fx_l = (Ax*fp.y+Bx*fp.x);\n" +
            "    fx_u = (Ay*fp.y+By*fp.x);\n" +
            "    irlv1 = irlv0 = diff(e,f) * diff(e,h);\n" +
            "    irlv1 = (irlv0 * ( neq(f,b) * neq(f,c) + neq(h,d) * neq(h,g) + eq(e,i) * (neq(f,f4) * neq(f,i4) + neq(h,h5) * neq(h,i5)) + eq(e,g) + eq(e,c)) );\n" +
            "    irlv2l = diff(e,g) * diff(d,g);\n" +
            "    irlv2u = diff(e,c) * diff(b,c);\n" +
            "\n" +
            "    vec4 fx45i = clamp((fx   + delta   -Co - Ci) / (2.0*delta  ), 0.0, 1.0);\n" +
            "    vec4 fx45  = clamp((fx   + delta   -Co     ) / (2.0*delta  ), 0.0, 1.0);\n" +
            "    vec4 fx30  = clamp((fx_l + delta_l -Cx     ) / (2.0*delta_l), 0.0, 1.0);\n" +
            "    vec4 fx60  = clamp((fx_u + delta_u -Cy     ) / (2.0*delta_u), 0.0, 1.0);\n" +
            "    vec4 w1, w2;\n" +
            "\n" +
            "    w1.x = dot(abs(E-C),rgbw) + dot(abs(E-G),rgbw) + dot(abs(I-H5),rgbw) + dot(abs(I-F4),rgbw) + 4.0*dot(abs(H-F),rgbw);\n" +
            "    w1.y = dot(abs(E-A),rgbw) + dot(abs(E-I),rgbw) + dot(abs(C-F4),rgbw) + dot(abs(C-B1),rgbw) + 4.0*dot(abs(F-B),rgbw);\n" +
            "    w1.z = dot(abs(E-G),rgbw) + dot(abs(E-C),rgbw) + dot(abs(A-B1),rgbw) + dot(abs(A-D0),rgbw) + 4.0*dot(abs(B-D),rgbw);\n" +
            "    w1.w = dot(abs(E-I),rgbw) + dot(abs(E-A),rgbw) + dot(abs(G-D0),rgbw) + dot(abs(G-H5),rgbw) + 4.0*dot(abs(D-H),rgbw);\n" +
            "    w2.x = dot(abs(H-D),rgbw) + dot(abs(H-I5),rgbw) + dot(abs(F-I4),rgbw) + dot(abs(F-B),rgbw) + 4.0*dot(abs(E-I),rgbw);\n" +
            "    w2.y = dot(abs(F-H),rgbw) + dot(abs(F-C4),rgbw) + dot(abs(B-C1),rgbw) + dot(abs(B-D),rgbw) + 4.0*dot(abs(E-C),rgbw);\n" +
            "    w2.z = dot(abs(B-F),rgbw) + dot(abs(B-A1),rgbw) + dot(abs(D-A0),rgbw) + dot(abs(D-H),rgbw) + 4.0*dot(abs(E-A),rgbw);\n" +
            "    w2.w = dot(abs(D-B),rgbw) + dot(abs(D-G0),rgbw) + dot(abs(H-G5),rgbw) + dot(abs(H-F),rgbw) + 4.0*dot(abs(E-G),rgbw);\n" +
            "\n" +
            "    edri  = step(w1, w2) * irlv0;\n" +
            "    edr   = step(w1 + vec4(0.1, 0.1, 0.1, 0.1), w2) * step(vec4(0.5, 0.5, 0.5, 0.5), irlv1);\n" +
            "\n" +
            "    w1.x = dot(abs(F-G),rgbw); w1.y = dot(abs(B-I),rgbw); w1.z = dot(abs(D-C),rgbw); w1.w = dot(abs(H-A),rgbw);\n" +
            "    w2.x = dot(abs(H-C),rgbw); w2.y = dot(abs(F-A),rgbw); w2.z = dot(abs(B-G),rgbw); w2.w = dot(abs(D-I),rgbw);\n" +
            "\n" +
            "    edr_l = step( lv2_cf*w1, w2 ) * irlv2l * edr;\n" +
            "    edr_u = step( lv2_cf*w2, w1 ) * irlv2u * edr;\n" +
            "\n" +
            "    fx45  = edr   * fx45;\n" +
            "    fx30  = edr_l * fx30;\n" +
            "    fx60  = edr_u * fx60;\n" +
            "    fx45i = edri  * fx45i;\n" +
            "\n" +
            "    w1.x = dot(abs(E-F),rgbw); w1.y = dot(abs(E-B),rgbw); w1.z = dot(abs(E-D),rgbw); w1.w = dot(abs(E-H),rgbw);\n" +
            "    w2.x = dot(abs(E-H),rgbw); w2.y = dot(abs(E-F),rgbw); w2.z = dot(abs(E-B),rgbw); w2.w = dot(abs(E-D),rgbw);\n" +
            "\n" +
            "    px = step(w1, w2);\n" +
            "    vec4 maximos = max(max(fx30, fx60), max(fx45, fx45i));\n" +
            "    vec3 res1 = E;\n" +
            "    res1 = mix(res1, mix(H, F, px.x), maximos.x);\n" +
            "    res1 = mix(res1, mix(B, D, px.z), maximos.z);\n" +
            "\n" +
            "    vec3 res2 = E;\n" +
            "    res2 = mix(res2, mix(F, B, px.y), maximos.y);\n" +
            "    res2 = mix(res2, mix(D, H, px.w), maximos.w);\n" +
            "\n" +
            "    res = mix(res1, res2, step(c_df(E, res1), c_df(E, res2)));\n" +
            " }\n" +
            " gl_FragColor.xyz = res;\n" +
            " gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** HQ4x fragment shader (hq4x.fsh / 4xGLSLHqFilter). guest(r)'s high-quality 4x filter. */
    public static final String FRAGMENT_HQ4X =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec4 v_texcoord0[7];\n" +
            "\n" +
            "const float mx = 1.00;      // start smoothing wt.\n" +
            "const float k = -1.10;      // wt. decrease factor\n" +
            "const float max_w = 0.75;   // max filter weight\n" +
            "const float min_w = 0.03;   // min filter weight\n" +
            "const float lum_add = 0.33; // effects smoothing\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    vec3 c  = texture2D(sampler0, v_texcoord0[0].xy).xyz;\n" +
            "    vec3 i1 = texture2D(sampler0, v_texcoord0[1].xy).xyz;\n" +
            "    vec3 i2 = texture2D(sampler0, v_texcoord0[2].xy).xyz;\n" +
            "    vec3 i3 = texture2D(sampler0, v_texcoord0[3].xy).xyz;\n" +
            "    vec3 i4 = texture2D(sampler0, v_texcoord0[4].xy).xyz;\n" +
            "    vec3 o1 = texture2D(sampler0, v_texcoord0[5].xy).xyz;\n" +
            "    vec3 o3 = texture2D(sampler0, v_texcoord0[6].xy).xyz;\n" +
            "    vec3 o2 = texture2D(sampler0, v_texcoord0[5].zw).xyz;\n" +
            "    vec3 o4 = texture2D(sampler0, v_texcoord0[6].zw).xyz;\n" +
            "    vec3 s1 = texture2D(sampler0, v_texcoord0[1].zw).xyz;\n" +
            "    vec3 s2 = texture2D(sampler0, v_texcoord0[2].zw).xyz;\n" +
            "    vec3 s3 = texture2D(sampler0, v_texcoord0[3].zw).xyz;\n" +
            "    vec3 s4 = texture2D(sampler0, v_texcoord0[4].zw).xyz;\n" +
            "    vec3 dt = vec3(1.0, 1.0, 1.0);\n" +
            "\n" +
            "    float ko1 = dot(abs(o1-c), dt);\n" +
            "    float ko2 = dot(abs(o2-c), dt);\n" +
            "    float ko3 = dot(abs(o3-c), dt);\n" +
            "    float ko4 = dot(abs(o4-c), dt);\n" +
            "\n" +
            "    float k1 = min(dot(abs(i1-i3), dt), max(ko1, ko3));\n" +
            "    float k2 = min(dot(abs(i2-i4), dt), max(ko2, ko4));\n" +
            "\n" +
            "    float w1 = k2; if (ko3 < ko1) w1 *= ko3/ko1;\n" +
            "    float w2 = k1; if (ko4 < ko2) w2 *= ko4/ko2;\n" +
            "    float w3 = k2; if (ko1 < ko3) w3 *= ko1/ko3;\n" +
            "    float w4 = k1; if (ko2 < ko4) w4 *= ko2/ko4;\n" +
            "\n" +
            "    c = (w1*o1 + w2*o2 + w3*o3 + w4*o4 + 0.001*c) / (w1+w2+w3+w4+0.001);\n" +
            "\n" +
            "    w1 = k*dot(abs(i1-c)+abs(i3-c), dt) / (0.125*dot(i1+i3, dt) + lum_add);\n" +
            "    w2 = k*dot(abs(i2-c)+abs(i4-c), dt) / (0.125*dot(i2+i4, dt) + lum_add);\n" +
            "    w3 = k*dot(abs(s1-c)+abs(s3-c), dt) / (0.125*dot(s1+s3, dt) + lum_add);\n" +
            "    w4 = k*dot(abs(s2-c)+abs(s4-c), dt) / (0.125*dot(s2+s4, dt) + lum_add);\n" +
            "\n" +
            "    w1 = clamp(w1 + mx, min_w, max_w);\n" +
            "    w2 = clamp(w2 + mx, min_w, max_w);\n" +
            "    w3 = clamp(w3 + mx, min_w, max_w);\n" +
            "    w4 = clamp(w4 + mx, min_w, max_w);\n" +
            "\n" +
            "    vec3 result = (w1*(i1+i3) + w2*(i2+i4) + w3*(s1+s3) + w4*(s2+s4) + c) / (2.0*(w1+w2+w3+w4) + 1.0);\n" +
            "\n" +
            "    gl_FragColor = vec4(result, 1.0);\n" +
            "}\n";

    /**
     * 2xBR + Dot fragment shader — Hyllian's 5xBR v3.5a followed by LCD dot-mask
     * post-processing.
     *
     * <p>Uses the exact same 5xBR edge-detection algorithm as {@link #FRAGMENT_2XBR},
     * then applies the dot mask from the reference {@code dot.fsh} shader
     * (gamma=2.4, shine=0.05, blend=0.65) using the pixel-fraction {@code fp}
     * already computed by the XBR algorithm.
     *
     * <p>Uses {@code varying vec2 v_texcoord0} (single, matching {@link #VERTEX_2XBR})
     * — NOT the old {@code varying vec2 v_texcoord0[3]} array that caused
     * vertex/fragment linking failure.
     */
    public static final String FRAGMENT_2XBR_DOT =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "precision mediump int;\n" +
            "#endif\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "varying vec2 v_texcoord0;\n" +
            "\n" +
            "const float coef = 2.0;\n" +
            "const vec3  rgbw = vec3(16.163, 23.351, 8.4772);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0 );\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5 );\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0 );\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0 );\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5 );\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5 );\n" +
            "\n" +
            "// Dot mask config\n" +
            "const float gamma_dot = 2.4;\n" +
            "const float shine = 0.05;\n" +
            "const float blend_dot = 0.65;\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) {\n" +
            "    return abs(A-B);\n" +
            "}\n" +
            "\n" +
            "vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d, vec4 e, vec4 f, vec4 g, vec4 h) {\n" +
            "    return (df(a,b) + df(a,c) + df(d,e) + df(d,f) + 4.0*df(g,h));\n" +
            "}\n" +
            "\n" +
            "float color_bloom(vec3 color) {\n" +
            "    const vec3 gray_coeff = vec3(0.30, 0.59, 0.11);\n" +
            "    float bright = dot(color, gray_coeff);\n" +
            "    return mix(1.0 + shine, 1.0 - shine, bright);\n" +
            "}\n" +
            "\n" +
            "void main(){\n" +
            " bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            " vec3 res = texture2D(sampler0, v_texcoord0.xy).xyz;\n" +
            "\n" +
            " vec2 pS  = 1.0 / u_texelDelta.xy;\n" +
            " vec2 fp  = fract(v_texcoord0.xy*pS.xy);\n" +
            "\n" +
            " if (upscale) {\n" +
            "    bvec4 edr, edr_left, edr_up, px;\n" +
            "    bvec4 interp_restriction_lv1, interp_restriction_lv2_left, interp_restriction_lv2_up;\n" +
            "    bvec4 nc;\n" +
            "    bvec4 fx, fx_left, fx_up;\n" +
            "\n" +
            "    vec2 TexCoord_0 = v_texcoord0.xy-fp*u_texelDelta.xy;\n" +
            "    vec2 dx  = vec2(u_texelDelta.x,0.0);\n" +
            "    vec2 dy  = vec2(0.0,u_texelDelta.y);\n" +
            "    vec2 y2  = dy + dy; vec2 x2  = dx + dx;\n" +
            "\n" +
            "    vec3 A  = texture2D(sampler0, TexCoord_0 -dx -dy).xyz;\n" +
            "    vec3 B  = texture2D(sampler0, TexCoord_0     -dy).xyz;\n" +
            "    vec3 C  = texture2D(sampler0, TexCoord_0 +dx -dy).xyz;\n" +
            "    vec3 D  = texture2D(sampler0, TexCoord_0 -dx    ).xyz;\n" +
            "    vec3 E  = texture2D(sampler0, TexCoord_0        ).xyz;\n" +
            "    vec3 F  = texture2D(sampler0, TexCoord_0 +dx    ).xyz;\n" +
            "    vec3 G  = texture2D(sampler0, TexCoord_0 -dx +dy).xyz;\n" +
            "    vec3 H  = texture2D(sampler0, TexCoord_0     +dy).xyz;\n" +
            "    vec3 I  = texture2D(sampler0, TexCoord_0 +dx +dy).xyz;\n" +
            "    vec3 A1 = texture2D(sampler0, TexCoord_0     -dx -y2).xyz;\n" +
            "    vec3 C1 = texture2D(sampler0, TexCoord_0     +dx -y2).xyz;\n" +
            "    vec3 A0 = texture2D(sampler0, TexCoord_0 -x2     -dy).xyz;\n" +
            "    vec3 G0 = texture2D(sampler0, TexCoord_0 -x2     +dy).xyz;\n" +
            "    vec3 C4 = texture2D(sampler0, TexCoord_0 +x2     -dy).xyz;\n" +
            "    vec3 I4 = texture2D(sampler0, TexCoord_0 +x2     +dy).xyz;\n" +
            "    vec3 G5 = texture2D(sampler0, TexCoord_0     -dx +y2).xyz;\n" +
            "    vec3 I5 = texture2D(sampler0, TexCoord_0     +dx +y2).xyz;\n" +
            "    vec3 B1 = texture2D(sampler0, TexCoord_0         -y2).xyz;\n" +
            "    vec3 D0 = texture2D(sampler0, TexCoord_0 -x2        ).xyz;\n" +
            "    vec3 H5 = texture2D(sampler0, TexCoord_0         +y2).xyz;\n" +
            "    vec3 F4 = texture2D(sampler0, TexCoord_0 +x2        ).xyz;\n" +
            "\n" +
            "    vec4 b  = vec4(dot(B ,rgbw), dot(D ,rgbw), dot(H ,rgbw), dot(F ,rgbw));\n" +
            "    vec4 c  = vec4(dot(C ,rgbw), dot(A ,rgbw), dot(G ,rgbw), dot(I ,rgbw));\n" +
            "    vec4 d  = vec4(b.y, b.z, b.w, b.x);\n" +
            "    vec4 e  = vec4(dot(E,rgbw));\n" +
            "    vec4 f  = vec4(b.w, b.x, b.y, b.z);\n" +
            "    vec4 g  = vec4(c.z, c.w, c.x, c.y);\n" +
            "    vec4 h  = vec4(b.z, b.w, b.x, b.y);\n" +
            "    vec4 i  = vec4(c.w, c.x, c.y, c.z);\n" +
            "    vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "    vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "    vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "    vec4 f4 = vec4(h5.y, h5.z, h5.w, h5.x);\n" +
            "\n" +
            "    fx       = greaterThan(Ao*fp.y+Bo*fp.x,Co);\n" +
            "    fx_left  = greaterThan(Ax*fp.y+Bx*fp.x,Cx);\n" +
            "    fx_up    = greaterThan(Ay*fp.y+By*fp.x,Cy);\n" +
            "\n" +
            "    interp_restriction_lv1     = bvec4(vec4(notEqual(e,f))*vec4(notEqual(e,h)));\n" +
            "    interp_restriction_lv2_left = bvec4(vec4(notEqual(e,g))*vec4(notEqual(d,g)));\n" +
            "    interp_restriction_lv2_up   = bvec4(vec4(notEqual(e,c))*vec4(notEqual(b,c)));\n" +
            "\n" +
            "    edr      = bvec4(vec4(lessThan(weighted_distance( e, c, g, i, h5, f4, h, f), weighted_distance( h, d, i5, f, i4, b, e, i)))*vec4(interp_restriction_lv1));\n" +
            "    edr_left = bvec4(vec4(lessThanEqual(coef*df(f,g),df(h,c)))*vec4(interp_restriction_lv2_left));\n" +
            "    edr_up   = bvec4(vec4(greaterThanEqual(df(f,g),coef*df(h,c)))*vec4(interp_restriction_lv2_up));\n" +
            "\n" +
            "    nc.x = ( edr.x && (fx.x || edr_left.x && fx_left.x || edr_up.x && fx_up.x) );\n" +
            "    nc.y = ( edr.y && (fx.y || edr_left.y && fx_left.y || edr_up.y && fx_up.y) );\n" +
            "    nc.z = ( edr.z && (fx.z || edr_left.z && fx_left.z || edr_up.z && fx_up.z) );\n" +
            "    nc.w = ( edr.w && (fx.w || edr_left.w && fx_left.w || edr_up.w && fx_up.w) );\n" +
            "\n" +
            "    px = lessThanEqual(df(e,f),df(e,h));\n" +
            "\n" +
            "    res = nc.x ? px.x ? F : H : nc.y ? px.y ? B : F : nc.z ? px.z ? D : B : nc.w ? px.w ? H : D : E;\n" +
            " }\n" +
            "\n" +
            " // Dot mask post-processing — use output pixel position for per-pixel dots\n" +
            " vec2 fpDot = fract(v_texcoord0.xy / u_pixelDelta.xy);\n" +
            " float delta = length(fpDot - vec2(0.5));\n" +
            " float bloom = color_bloom(res);\n" +
            " float dotMask = exp(-gamma_dot * delta * bloom);\n" +
            " res = mix(min(1.2 * res, 1.0), res * dotMask, blend_dot);\n" +
            "\n" +
            " gl_FragColor.rgb = res;\n" +
            " gl_FragColor.a = 1.0;\n" +
            "}\n";

    /**
     * 4xBR + Dot fragment shader — Hyllian's xBR-lv2 Accuracy followed by LCD
     * dot-mask post-processing.
     *
     * <p>Uses the exact same xBR-lv2 edge-detection algorithm as
     * {@link #FRAGMENT_4XBR} (SMOOTH_TIPS enabled, CornerC default), then
     * applies the dot mask from the reference {@code dot.fsh} shader.
     *
     * <p>Uses {@code varying vec2 v_texcoord0} (single, matching
     * {@link #VERTEX_4XBR}) — NOT the old {@code varying vec2 v_texcoord0[3]}
     * array that caused vertex/fragment linking failure.
     */
    public static final String FRAGMENT_4XBR_DOT =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "precision mediump int;\n" +
            "#endif\n" +
            "\n" +
            "const float XBR_SCALE = 3.0;\n" +
            "const float lv2_cf    = 2.0;\n" +
            "\n" +
            "const float coef          = 2.0;\n" +
            "const vec3  rgbw          = vec3(14.352, 28.176, 5.472);\n" +
            "const vec4  eq_threshold  = vec4(15.0, 15.0, 15.0, 15.0);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0 );\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5 );\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0 );\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0 );\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0 );\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5 );\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5 );\n" +
            "const vec4 Ci = vec4(0.25, 0.25, 0.25, 0.25);\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "varying vec2 v_texcoord0;\n" +
            "\n" +
            "// Dot mask config\n" +
            "const float gamma_dot = 2.4;\n" +
            "const float shine = 0.05;\n" +
            "const float blend_dot = 0.65;\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) {\n" +
            "    return vec4(abs(A-B));\n" +
            "}\n" +
            "\n" +
            "vec4 diff(vec4 A, vec4 B) {\n" +
            "    return vec4(notEqual(A, B));\n" +
            "}\n" +
            "\n" +
            "vec4 eq(vec4 A, vec4 B) {\n" +
            "    return (step(df(A, B), eq_threshold));\n" +
            "}\n" +
            "\n" +
            "vec4 neq(vec4 A, vec4 B) {\n" +
            "    return (vec4(1.0, 1.0, 1.0, 1.0) - eq(A, B));\n" +
            "}\n" +
            "\n" +
            "float c_df(vec3 c1, vec3 c2) {\n" +
            "    vec3 df = abs(c1 - c2);\n" +
            "    return df.r + df.g + df.b;\n" +
            "}\n" +
            "\n" +
            "float color_bloom(vec3 color) {\n" +
            "    const vec3 gray_coeff = vec3(0.30, 0.59, 0.11);\n" +
            "    float bright = dot(color, gray_coeff);\n" +
            "    return mix(1.0 + shine, 1.0 - shine, bright);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            " bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            " vec3 res = texture2D(sampler0, v_texcoord0.xy).xyz;\n" +
            "\n" +
            " vec2 pS  = 1.0 / u_texelDelta.xy;\n" +
            " vec2 fp  = fract(v_texcoord0.xy*pS.xy);\n" +
            "\n" +
            " if (upscale) {\n" +
            "    vec4 edri, edr, edr_l, edr_u, px;\n" +
            "    vec4 irlv0, irlv1, irlv2l, irlv2u;\n" +
            "    vec4 fx, fx_l, fx_u;\n" +
            "\n" +
            "    vec2 TexCoord_0 = v_texcoord0.xy-fp*u_texelDelta.xy;\n" +
            "    vec2 dx  = vec2(u_texelDelta.x,0.0);\n" +
            "    vec2 dy  = vec2(0.0,u_texelDelta.y);\n" +
            "    vec2 y2  = dy + dy; vec2 x2  = dx + dx;\n" +
            "\n" +
            "    vec4 delta   = vec4(1.0/XBR_SCALE, 1.0/XBR_SCALE, 1.0/XBR_SCALE, 1.0/XBR_SCALE);\n" +
            "    vec4 delta_l = vec4(0.5/XBR_SCALE, 1.0/XBR_SCALE, 0.5/XBR_SCALE, 1.0/XBR_SCALE);\n" +
            "    vec4 delta_u = delta_l.yxwz;\n" +
            "\n" +
            "    vec3 A  = texture2D(sampler0, TexCoord_0 -dx -dy).xyz;\n" +
            "    vec3 B  = texture2D(sampler0, TexCoord_0     -dy).xyz;\n" +
            "    vec3 C  = texture2D(sampler0, TexCoord_0 +dx -dy).xyz;\n" +
            "    vec3 D  = texture2D(sampler0, TexCoord_0 -dx    ).xyz;\n" +
            "    vec3 E  = texture2D(sampler0, TexCoord_0        ).xyz;\n" +
            "    vec3 F  = texture2D(sampler0, TexCoord_0 +dx    ).xyz;\n" +
            "    vec3 G  = texture2D(sampler0, TexCoord_0 -dx +dy).xyz;\n" +
            "    vec3 H  = texture2D(sampler0, TexCoord_0     +dy).xyz;\n" +
            "    vec3 I  = texture2D(sampler0, TexCoord_0 +dx +dy).xyz;\n" +
            "    vec3 A1 = texture2D(sampler0, TexCoord_0     -dx -y2).xyz;\n" +
            "    vec3 C1 = texture2D(sampler0, TexCoord_0     +dx -y2).xyz;\n" +
            "    vec3 A0 = texture2D(sampler0, TexCoord_0 -x2     -dy).xyz;\n" +
            "    vec3 G0 = texture2D(sampler0, TexCoord_0 -x2     +dy).xyz;\n" +
            "    vec3 C4 = texture2D(sampler0, TexCoord_0 +x2     -dy).xyz;\n" +
            "    vec3 I4 = texture2D(sampler0, TexCoord_0 +x2     +dy).xyz;\n" +
            "    vec3 G5 = texture2D(sampler0, TexCoord_0     -dx +y2).xyz;\n" +
            "    vec3 I5 = texture2D(sampler0, TexCoord_0     +dx +y2).xyz;\n" +
            "    vec3 B1 = texture2D(sampler0, TexCoord_0         -y2).xyz;\n" +
            "    vec3 D0 = texture2D(sampler0, TexCoord_0 -x2        ).xyz;\n" +
            "    vec3 H5 = texture2D(sampler0, TexCoord_0         +y2).xyz;\n" +
            "    vec3 F4 = texture2D(sampler0, TexCoord_0 +x2        ).xyz;\n" +
            "\n" +
            "    vec4 b  = vec4(dot(B ,rgbw), dot(D ,rgbw), dot(H ,rgbw), dot(F ,rgbw));\n" +
            "    vec4 c  = vec4(dot(C ,rgbw), dot(A ,rgbw), dot(G ,rgbw), dot(I ,rgbw));\n" +
            "    vec4 d  = b.yzwx;\n" +
            "    vec4 e  = vec4(dot(E,rgbw));\n" +
            "    vec4 f  = b.wxyz;\n" +
            "    vec4 g  = c.zwxy;\n" +
            "    vec4 h  = b.zwxy;\n" +
            "    vec4 i  = c.wxyz;\n" +
            "    vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "    vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "    vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "    vec4 f4 = h5.yzwx;\n" +
            "\n" +
            "    fx   = (Ao*fp.y+Bo*fp.x);\n" +
            "    fx_l = (Ax*fp.y+Bx*fp.x);\n" +
            "    fx_u = (Ay*fp.y+By*fp.x);\n" +
            "    irlv1 = irlv0 = diff(e,f) * diff(e,h);\n" +
            "    irlv1 = (irlv0 * ( neq(f,b) * neq(f,c) + neq(h,d) * neq(h,g) + eq(e,i) * (neq(f,f4) * neq(f,i4) + neq(h,h5) * neq(h,i5)) + eq(e,g) + eq(e,c)) );\n" +
            "    irlv2l = diff(e,g) * diff(d,g);\n" +
            "    irlv2u = diff(e,c) * diff(b,c);\n" +
            "\n" +
            "    vec4 fx45i = clamp((fx   + delta   -Co - Ci) / (2.0*delta  ), 0.0, 1.0);\n" +
            "    vec4 fx45  = clamp((fx   + delta   -Co     ) / (2.0*delta  ), 0.0, 1.0);\n" +
            "    vec4 fx30  = clamp((fx_l + delta_l -Cx     ) / (2.0*delta_l), 0.0, 1.0);\n" +
            "    vec4 fx60  = clamp((fx_u + delta_u -Cy     ) / (2.0*delta_u), 0.0, 1.0);\n" +
            "    vec4 w1, w2;\n" +
            "\n" +
            "    w1.x = dot(abs(E-C),rgbw) + dot(abs(E-G),rgbw) + dot(abs(I-H5),rgbw) + dot(abs(I-F4),rgbw) + 4.0*dot(abs(H-F),rgbw);\n" +
            "    w1.y = dot(abs(E-A),rgbw) + dot(abs(E-I),rgbw) + dot(abs(C-F4),rgbw) + dot(abs(C-B1),rgbw) + 4.0*dot(abs(F-B),rgbw);\n" +
            "    w1.z = dot(abs(E-G),rgbw) + dot(abs(E-C),rgbw) + dot(abs(A-B1),rgbw) + dot(abs(A-D0),rgbw) + 4.0*dot(abs(B-D),rgbw);\n" +
            "    w1.w = dot(abs(E-I),rgbw) + dot(abs(E-A),rgbw) + dot(abs(G-D0),rgbw) + dot(abs(G-H5),rgbw) + 4.0*dot(abs(D-H),rgbw);\n" +
            "    w2.x = dot(abs(H-D),rgbw) + dot(abs(H-I5),rgbw) + dot(abs(F-I4),rgbw) + dot(abs(F-B),rgbw) + 4.0*dot(abs(E-I),rgbw);\n" +
            "    w2.y = dot(abs(F-H),rgbw) + dot(abs(F-C4),rgbw) + dot(abs(B-C1),rgbw) + dot(abs(B-D),rgbw) + 4.0*dot(abs(E-C),rgbw);\n" +
            "    w2.z = dot(abs(B-F),rgbw) + dot(abs(B-A1),rgbw) + dot(abs(D-A0),rgbw) + dot(abs(D-H),rgbw) + 4.0*dot(abs(E-A),rgbw);\n" +
            "    w2.w = dot(abs(D-B),rgbw) + dot(abs(D-G0),rgbw) + dot(abs(H-G5),rgbw) + dot(abs(H-F),rgbw) + 4.0*dot(abs(E-G),rgbw);\n" +
            "\n" +
            "    edri  = step(w1, w2) * irlv0;\n" +
            "    edr   = step(w1 + vec4(0.1, 0.1, 0.1, 0.1), w2) * step(vec4(0.5, 0.5, 0.5, 0.5), irlv1);\n" +
            "\n" +
            "    w1.x = dot(abs(F-G),rgbw); w1.y = dot(abs(B-I),rgbw); w1.z = dot(abs(D-C),rgbw); w1.w = dot(abs(H-A),rgbw);\n" +
            "    w2.x = dot(abs(H-C),rgbw); w2.y = dot(abs(F-A),rgbw); w2.z = dot(abs(B-G),rgbw); w2.w = dot(abs(D-I),rgbw);\n" +
            "\n" +
            "    edr_l = step( lv2_cf*w1, w2 ) * irlv2l * edr;\n" +
            "    edr_u = step( lv2_cf*w2, w1 ) * irlv2u * edr;\n" +
            "\n" +
            "    fx45  = edr   * fx45;\n" +
            "    fx30  = edr_l * fx30;\n" +
            "    fx60  = edr_u * fx60;\n" +
            "    fx45i = edri  * fx45i;\n" +
            "\n" +
            "    w1.x = dot(abs(E-F),rgbw); w1.y = dot(abs(E-B),rgbw); w1.z = dot(abs(E-D),rgbw); w1.w = dot(abs(E-H),rgbw);\n" +
            "    w2.x = dot(abs(E-H),rgbw); w2.y = dot(abs(E-F),rgbw); w2.z = dot(abs(E-B),rgbw); w2.w = dot(abs(E-D),rgbw);\n" +
            "\n" +
            "    px = step(w1, w2);\n" +
            "    vec4 maximos = max(max(fx30, fx60), max(fx45, fx45i));\n" +
            "    vec3 res1 = E;\n" +
            "    res1 = mix(res1, mix(H, F, px.x), maximos.x);\n" +
            "    res1 = mix(res1, mix(B, D, px.z), maximos.z);\n" +
            "\n" +
            "    vec3 res2 = E;\n" +
            "    res2 = mix(res2, mix(F, B, px.y), maximos.y);\n" +
            "    res2 = mix(res2, mix(D, H, px.w), maximos.w);\n" +
            "\n" +
            "    res = mix(res1, res2, step(c_df(E, res1), c_df(E, res2)));\n" +
            " }\n" +
            "\n" +
            " // Dot mask post-processing — use output pixel position for per-pixel dots\n" +
            " vec2 fpDot = fract(v_texcoord0.xy / u_pixelDelta.xy);\n" +
            " float delta = length(fpDot - vec2(0.5));\n" +
            " float bloom = color_bloom(res);\n" +
            " float dotMask = exp(-gamma_dot * delta * bloom);\n" +
            " res = mix(min(1.2 * res, 1.0), res * dotMask, blend_dot);\n" +
            "\n" +
            " gl_FragColor.xyz = res;\n" +
            " gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** HQ4x + Dot: HQ4x followed by a dot-mask post-processing pass. */
    public static final String FRAGMENT_HQ4X_DOT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D sampler0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "varying vec4 v_texcoord0[7];\n" +
            "\n" +
            "const float mx = 1.00;      // start smoothing wt.\n" +
            "const float k = -1.10;      // wt. decrease factor\n" +
            "const float max_w = 0.75;   // max filter weight\n" +
            "const float min_w = 0.03;   // min filter weight\n" +
            "const float lum_add = 0.33; // effects smoothing\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    vec3 c  = texture2D(sampler0, v_texcoord0[0].xy).xyz;\n" +
            "    vec3 i1 = texture2D(sampler0, v_texcoord0[1].xy).xyz;\n" +
            "    vec3 i2 = texture2D(sampler0, v_texcoord0[2].xy).xyz;\n" +
            "    vec3 i3 = texture2D(sampler0, v_texcoord0[3].xy).xyz;\n" +
            "    vec3 i4 = texture2D(sampler0, v_texcoord0[4].xy).xyz;\n" +
            "    vec3 o1 = texture2D(sampler0, v_texcoord0[5].xy).xyz;\n" +
            "    vec3 o3 = texture2D(sampler0, v_texcoord0[6].xy).xyz;\n" +
            "    vec3 o2 = texture2D(sampler0, v_texcoord0[5].zw).xyz;\n" +
            "    vec3 o4 = texture2D(sampler0, v_texcoord0[6].zw).xyz;\n" +
            "    vec3 s1 = texture2D(sampler0, v_texcoord0[1].zw).xyz;\n" +
            "    vec3 s2 = texture2D(sampler0, v_texcoord0[2].zw).xyz;\n" +
            "    vec3 s3 = texture2D(sampler0, v_texcoord0[3].zw).xyz;\n" +
            "    vec3 s4 = texture2D(sampler0, v_texcoord0[4].zw).xyz;\n" +
            "    vec3 dt = vec3(1.0, 1.0, 1.0);\n" +
            "\n" +
            "    float ko1 = dot(abs(o1-c), dt);\n" +
            "    float ko2 = dot(abs(o2-c), dt);\n" +
            "    float ko3 = dot(abs(o3-c), dt);\n" +
            "    float ko4 = dot(abs(o4-c), dt);\n" +
            "\n" +
            "    float k1 = min(dot(abs(i1-i3), dt), max(ko1, ko3));\n" +
            "    float k2 = min(dot(abs(i2-i4), dt), max(ko2, ko4));\n" +
            "\n" +
            "    float w1 = k2; if (ko3 < ko1) w1 *= ko3/ko1;\n" +
            "    float w2 = k1; if (ko4 < ko2) w2 *= ko4/ko2;\n" +
            "    float w3 = k2; if (ko1 < ko3) w3 *= ko1/ko3;\n" +
            "    float w4 = k1; if (ko2 < ko4) w4 *= ko2/ko4;\n" +
            "\n" +
            "    c = (w1*o1 + w2*o2 + w3*o3 + w4*o4 + 0.001*c) / (w1+w2+w3+w4+0.001);\n" +
            "\n" +
            "    w1 = k*dot(abs(i1-c)+abs(i3-c), dt) / (0.125*dot(i1+i3, dt) + lum_add);\n" +
            "    w2 = k*dot(abs(i2-c)+abs(i4-c), dt) / (0.125*dot(i2+i4, dt) + lum_add);\n" +
            "    w3 = k*dot(abs(s1-c)+abs(s3-c), dt) / (0.125*dot(s1+s3, dt) + lum_add);\n" +
            "    w4 = k*dot(abs(s2-c)+abs(s4-c), dt) / (0.125*dot(s2+s4, dt) + lum_add);\n" +
            "\n" +
            "    w1 = clamp(w1 + mx, min_w, max_w);\n" +
            "    w2 = clamp(w2 + mx, min_w, max_w);\n" +
            "    w3 = clamp(w3 + mx, min_w, max_w);\n" +
            "    w4 = clamp(w4 + mx, min_w, max_w);\n" +
            "\n" +
            "    vec3 result = (w1*(i1+i3) + w2*(i2+i4) + w3*(s1+s3) + w4*(s2+s4) + c) / (2.0*(w1+w2+w3+w4) + 1.0);\n" +
            "\n" +
            "    // Dot mask post-processing (center texcoord is v_texcoord0[0].xy)\n" +
            "    vec2 pixel_no = v_texcoord0[0].xy / u_texelDelta;\n" +
            "    vec2 fp = fract(pixel_no);\n" +
            "    float delta = length(fp - vec2(0.5));\n" +
            "    float bright = dot(result, vec3(0.30, 0.59, 0.11));\n" +
            "    float bloom = mix(1.05, 0.95, bright);\n" +
            "    float dotMask = exp(-2.4 * delta * bloom);\n" +
            "    result = mix(1.2 * result, result * dotMask, 0.65);\n" +
            "\n" +
            "    gl_FragColor = vec4(result, 1.0);\n" +
            "}\n";

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Returns both the vertex and fragment shader source for the given filter mode.
     *
     * @param mode filter mode (0-9)
     * @return a two-element array: {@code [vertexShader, fragmentShader]}
     */
    public static String[] getShader(int mode) {
        return new String[] { getVertexShader(mode), getFragmentShader(mode) };
    }

    /**
     * Returns the vertex shader source for the given filter mode.
     *
     * @param mode filter mode (0-9)
     * @return GLSL vertex shader source code
     */
    public static String getVertexShader(int mode) {
        switch (mode) {
            case MODE_SCANLINE: return VERTEX_SCANLINE;
            case MODE_CRT:      return VERTEX_CRT;
            case MODE_DOT:      return VERTEX_DOT;
            case MODE_2XBR:
            case MODE_2XBR_DOT: return VERTEX_2XBR;
            case MODE_4XBR:
            case MODE_4XBR_DOT: return VERTEX_4XBR;
            case MODE_HQ4X:
            case MODE_HQ4X_DOT: return VERTEX_HQ4X;
            case MODE_NONE:
            default:            return VERTEX_SHADER;
        }
    }

    /**
     * Returns the fragment shader source for the given filter mode.
     *
     * @param mode filter mode (0-9)
     * @return GLSL fragment shader source code
     */
    public static String getFragmentShader(int mode) {
        switch (mode) {
            case MODE_SCANLINE:  return FRAGMENT_SCANLINE;
            case MODE_CRT:       return FRAGMENT_CRT;
            case MODE_DOT:       return FRAGMENT_DOT;
            case MODE_2XBR:      return FRAGMENT_2XBR;
            case MODE_4XBR:      return FRAGMENT_4XBR;
            case MODE_2XBR_DOT:  return FRAGMENT_2XBR_DOT;
            case MODE_4XBR_DOT:  return FRAGMENT_4XBR_DOT;
            case MODE_HQ4X:      return FRAGMENT_HQ4X;
            case MODE_HQ4X_DOT:  return FRAGMENT_HQ4X_DOT;
            case MODE_NONE:
            default:             return FRAGMENT_NONE;
        }
    }

    /**
     * Returns whether the given mode uses a custom (vec2-based) vertex shader
     * rather than the default {@link #VERTEX_SHADER} passthrough.
     *
     * @param mode filter mode (0-9)
     * @return {@code true} for all modes except {@link #MODE_NONE}
     */
    public static boolean isCustomVertexShader(int mode) {
        return mode != MODE_NONE;
    }

    /**
     * Returns whether the given mode performs real pixel processing
     * (XBR / 4xBR / HQ4x and their +Dot variants). These shaders need the
     * source texture sampled with NEAREST filtering.
     *
     * @param mode filter mode (0-9)
     * @return {@code true} for modes 4, 5, 6, 7, 8, 9
     */
    public static boolean isPixelProcessingMode(int mode) {
        return mode == MODE_2XBR || mode == MODE_4XBR ||
               mode == MODE_2XBR_DOT || mode == MODE_4XBR_DOT ||
               mode == MODE_HQ4X || mode == MODE_HQ4X_DOT;
    }

    /**
     * Returns whether the given mode is a screen-space mask effect
     * (scanline / CRT / dot) rather than a pixel-processing upscaler.
     *
     * @param mode filter mode (0-9)
     * @return {@code true} for modes 1, 2, 3
     */
    public static boolean isMaskMode(int mode) {
        return mode == MODE_SCANLINE || mode == MODE_CRT || mode == MODE_DOT;
    }

    /**
     * Returns whether the source texture should be sampled with NEAREST
     * filtering for the given mode. XBR / HQ4x algorithms read exact texel
     * values, so they require NEAREST input.
     *
     * @param mode filter mode (0-9)
     * @return {@code true} for modes 4, 5, 6, 7, 8, 9
     */
    public static boolean usesNearestFiltering(int mode) {
        return isPixelProcessingMode(mode);
    }

    private J2meFilterShaders() {}
}
