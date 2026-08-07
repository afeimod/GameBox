package javax.microedition.lcdui.graphics;

/**
 * GLSL vertex + fragment shader sources for J2ME video filters.
 *
 * <p>Filter modes (must match {@code J2meBitmapFilter} constants):
 * <pre>
 *   0 = None         (passthrough)
 *   1 = Scanline      (scanlines-emu)
 *   2 = CRT           (crt, u_time removed)
 *   3 = Dot           (LCD dot effect, full version)
 *   4 = 2xBR          (Hyllian 2xBR)
 *   5 = 4xBR          (Hyllian 4xBR)
 *   6 = 2xBR + Dot
 *   7 = 4xBR + Dot
 *   8 = HQ4x          (4xGLSLHqFilter)
 *   9 = HQ4x + Dot
 * </pre>
 *
 * <p><b>CRITICAL:</b> Array varyings ({@code varying vec2 v[3]}) are NOT reliably
 * supported across Android GPU drivers (Adreno 3xx, Mali-T6xx, PowerVR SGX all fail).
 * All shaders use individual varyings instead ({@code v_tc0, v_tc1, v_tc2}).
 */
public final class J2meFilterShaders {

    // ─── Mode constants ──────────────────────────────────────────────────────
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

    /** Passthrough vertex shader — uses vec2 a_position (consistent with all filter shaders). */
    public static final String VERTEX_SHADER =
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
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

    /** Scanline vertex shader. */
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

    /** CRT vertex shader. */
    public static final String VERTEX_CRT =
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "}\n";

    /** Dot vertex shader. Pre-computes the 3x3 neighborhood texcoords. */
    public static final String VERTEX_DOT =
            "uniform vec2 u_texelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "varying vec4 v_texcoord1;\n" +
            "varying vec4 v_texcoord2;\n" +
            "varying vec4 v_texcoord3;\n" +
            "varying vec4 v_texcoord4;\n" +
            "varying vec2 v_texcoord5;\n" +
            "varying vec2 v_texcoord6;\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "\n" +
            "    float dx = u_texelDelta.x;\n" +
            "    float dy = u_texelDelta.y;\n" +
            "\n" +
            "    v_texcoord1 = vec4(v_texcoord0 + vec2(-dx, -dy), v_texcoord0 + vec2(0.0, -dy));\n" +
            "    v_texcoord2 = vec4(v_texcoord0 + vec2(dx, -dy), v_texcoord0 + vec2(-dx, 0.0));\n" +
            "    v_texcoord3 = vec4(v_texcoord0 + vec2(dx, 0.0), v_texcoord0 + vec2(-dx, dy));\n" +
            "    v_texcoord4 = vec4(v_texcoord0 + vec2(0.0, dy), v_texcoord0 + vec2(dx, dy));\n" +
            "    v_texcoord5 = v_texcoord0;\n" +
            "    v_texcoord6 = v_texcoord0 * (1.0 / u_texelDelta.xy);\n" +
            "}\n";

    /**
     * 2xBR vertex shader — Hyllian's 2xBR (adapted from reference 2xbr.vsh).
     * Uses INDIVIDUAL varyings (v_tc0, v_tc1, v_tc2) instead of array
     * varying vec2 v_texcoord0[3] for universal GPU driver compatibility.
     */
    public static final String VERTEX_2XBR =
            "uniform mediump vec2 u_texelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_tc0;\n" +
            "varying vec2 v_tc1;\n" +
            "varying vec2 v_tc2;\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 ps = u_texelDelta;\n" +
            "    v_tc0 = a_texcoord0;\n" +
            "    v_tc1 = vec2(0.0, -ps.y);\n" +
            "    v_tc2 = vec2(-ps.x, 0.0);\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "}\n";

    /** 4xBR vertex shader — identical to 2xBR. */
    public static final String VERTEX_4XBR = VERTEX_2XBR;

    /**
     * HQ4x vertex shader (hq4x.vsh) — Uses INDIVIDUAL vec4 varyings
     * (v_tc0..v_tc6) instead of array varying vec4 v_texcoord0[7].
     */
    public static final String VERTEX_HQ4X =
            "uniform vec2 u_texelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec4 v_tc0;\n" +
            "varying vec4 v_tc1;\n" +
            "varying vec4 v_tc2;\n" +
            "varying vec4 v_tc3;\n" +
            "varying vec4 v_tc4;\n" +
            "varying vec4 v_tc5;\n" +
            "varying vec4 v_tc6;\n" +
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
            "    v_tc0.xy = a_texcoord0;\n" +
            "    v_tc1.xy = a_texcoord0 - sd1;\n" +
            "    v_tc2.xy = a_texcoord0 - sd2;\n" +
            "    v_tc3.xy = a_texcoord0 + sd1;\n" +
            "    v_tc4.xy = a_texcoord0 + sd2;\n" +
            "    v_tc5.xy = a_texcoord0 - dg1;\n" +
            "    v_tc6.xy = a_texcoord0 + dg1;\n" +
            "    v_tc5.zw = a_texcoord0 - dg2;\n" +
            "    v_tc6.zw = a_texcoord0 + dg2;\n" +
            "    v_tc1.zw = a_texcoord0 - ddy;\n" +
            "    v_tc2.zw = a_texcoord0 + ddx;\n" +
            "    v_tc3.zw = a_texcoord0 + ddy;\n" +
            "    v_tc4.zw = a_texcoord0 - ddx;\n" +
            "}\n";

    // ─── Fragment shaders ────────────────────────────────────────────────────

    /** Scanline fragment shader. */
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
            "    vec4 scanline = c11 * (base_brightness + dot(sine_comp * sin(v_texcoord0 * omega), vec2(1.0)));\n" +
            "    gl_FragColor = clamp(scanline, 0.0, 1.0);\n" +
            "}\n";

    /** CRT fragment shader. */
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
            "    int vPos = int( v_texcoord0.y * 272.0 );\n" +
            "    float line_intensity = mod( float(vPos), 2.0 );\n" +
            "    float off = line_intensity * 0.0005;\n" +
            "    vec2 shift = vec2( off, 0 );\n" +
            "    vec2 colorShift = vec2( 0.001, 0 );\n" +
            "    float r = texture2D( sampler0, v_texcoord0 + colorShift + shift ).x;\n" +
            "    float g = texture2D( sampler0, v_texcoord0 - colorShift + shift ).y;\n" +
            "    float b = texture2D( sampler0, v_texcoord0 ).z;\n" +
            "    vec4 c = vec4( r, g * 0.99, b, 1.0 ) * clamp( line_intensity, 0.85, 1.0 );\n" +
            "    float rollbar = sin( v_texcoord0.y * 4.0 );\n" +
            "    gl_FragColor.rgba = c + (rollbar * 0.02);\n" +
            "}\n";

    /** Dot fragment shader. */
    public static final String FRAGMENT_DOT =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "precision mediump int;\n" +
            "#endif\n" +
            "\n" +
            "#define gamma 2.4\n" +
            "#define shine 0.05\n" +
            "#define blend 0.65\n" +
            "\n" +
            "uniform sampler2D sampler0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "varying vec2 v_texcoord0;\n" +
            "varying vec4 v_texcoord1;\n" +
            "varying vec4 v_texcoord2;\n" +
            "varying vec4 v_texcoord3;\n" +
            "varying vec4 v_texcoord4;\n" +
            "varying vec2 v_texcoord5;\n" +
            "varying vec2 v_texcoord6;\n" +
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
            "    vec3 color = vec3(0.0, 0.0, 0.0);\n" +
            "    color += lookup(v_texcoord6, -1.0, -1.0, texture2D(sampler0, v_texcoord1.xy).rgb);\n" +
            "    color += lookup(v_texcoord6,  0.0, -1.0, texture2D(sampler0, v_texcoord1.zw).rgb);\n" +
            "    color += lookup(v_texcoord6,  1.0, -1.0, texture2D(sampler0, v_texcoord2.xy).rgb);\n" +
            "    color += lookup(v_texcoord6, -1.0,  0.0, texture2D(sampler0, v_texcoord2.zw).rgb);\n" +
            "    color += mid_color;\n" +
            "    color += lookup(v_texcoord6,  1.0,  0.0, texture2D(sampler0, v_texcoord3.xy).rgb);\n" +
            "    color += lookup(v_texcoord6, -1.0,  1.0, texture2D(sampler0, v_texcoord3.zw).rgb);\n" +
            "    color += lookup(v_texcoord6,  0.0,  1.0, texture2D(sampler0, v_texcoord4.xy).rgb);\n" +
            "    color += lookup(v_texcoord6,  1.0,  1.0, texture2D(sampler0, v_texcoord4.zw).rgb);\n" +
            "    vec3 out_color = mix(1.2 * mid_color, color, blend);\n" +
            "    gl_FragColor = vec4(out_color, 1.0);\n" +
            "}\n";

    /**
     * 2xBR fragment shader — Uses individual varyings (v_tc0, v_tc1, v_tc2)
     * instead of array varying for universal GPU driver compatibility.
     */
    public static final String FRAGMENT_2XBR =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "varying vec2 v_tc1;\n" +
            "varying vec2 v_tc2;\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 fp = fract(v_tc0 / u_texelDelta);\n" +
            "\n" +
            "    vec2 g1 = v_tc1 * (step(0.5, fp.x) + step(0.5, fp.y) - 1.0) +\n" +
            "            v_tc2 * (step(0.5, fp.x) - step(0.5, fp.y));\n" +
            "    vec2 g2 = v_tc1 * (step(0.5, fp.y) - step(0.5, fp.x)) +\n" +
            "            v_tc2 * (step(0.5, fp.x) + step(0.5, fp.y) - 1.0);\n" +
            "\n" +
            "    vec3 B = texture2D(sampler0, v_tc0 + g1     ).xyz;\n" +
            "    vec3 C = texture2D(sampler0, v_tc0 + g1 - g2).xyz;\n" +
            "    vec3 D = texture2D(sampler0, v_tc0      + g2).xyz;\n" +
            "    vec3 E = texture2D(sampler0, v_tc0          ).xyz;\n" +
            "    vec3 F = texture2D(sampler0, v_tc0      - g2).xyz;\n" +
            "    vec3 G = texture2D(sampler0, v_tc0 - g1 + g2).xyz;\n" +
            "    vec3 H = texture2D(sampler0, v_tc0 - g1     ).xyz;\n" +
            "    vec3 I = texture2D(sampler0, v_tc0 - g1 - g2).xyz;\n" +
            "\n" +
            "    gl_FragColor.rgb = E;\n" +
            "\n" +
            "    bool hf = (H.x == F.x && H.y == F.y && H.z == F.z);\n" +
            "    bool he = (H.x != E.x || H.y != E.y || H.z != E.z);\n" +
            "    bool eg = (E.x == G.x && E.y == G.y && E.z == G.z);\n" +
            "    bool hi = (H.x == I.x && H.y == I.y && H.z == I.z);\n" +
            "    bool ed = (E.x == D.x && E.y == D.y && E.z == D.z);\n" +
            "    bool ec = (E.x == C.x && E.y == C.y && E.z == C.z);\n" +
            "    bool eb = (E.x == B.x && E.y == B.y && E.z == B.z);\n" +
            "\n" +
            "    if (hf && he && (eg && (hi || ed) || ec && (hi || eb)))\n" +
            "    {\n" +
            "        gl_FragColor.rgb = mix(E, F, 0.5);\n" +
            "    }\n" +
            "    gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** 4xBR fragment shader — individual varyings, 4x4 sub-pixel pattern. */
    public static final String FRAGMENT_4XBR =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "varying vec2 v_tc1;\n" +
            "varying vec2 v_tc2;\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 fp = fract(v_tc0 / u_texelDelta);\n" +
            "\n" +
            "    vec2 g1 = v_tc1 * (step(0.5, fp.x) + step(0.5, fp.y) - 1.0) +\n" +
            "            v_tc2 * (step(0.5, fp.x) - step(0.5, fp.y));\n" +
            "    vec2 g2 = v_tc1 * (step(0.5, fp.y) - step(0.5, fp.x)) +\n" +
            "            v_tc2 * (step(0.5, fp.x) + step(0.5, fp.y) - 1.0);\n" +
            "\n" +
            "    vec3 B = texture2D(sampler0, v_tc0 + g1     ).xyz;\n" +
            "    vec3 C = texture2D(sampler0, v_tc0 + g1 - g2).xyz;\n" +
            "    vec3 D = texture2D(sampler0, v_tc0      + g2).xyz;\n" +
            "    vec3 E = texture2D(sampler0, v_tc0          ).xyz;\n" +
            "    vec3 F = texture2D(sampler0, v_tc0      - g2).xyz;\n" +
            "    vec3 G = texture2D(sampler0, v_tc0 - g1 + g2).xyz;\n" +
            "    vec3 H = texture2D(sampler0, v_tc0 - g1     ).xyz;\n" +
            "    vec3 I = texture2D(sampler0, v_tc0 - g1 - g2).xyz;\n" +
            "\n" +
            "    vec3 E11 = E;\n" +
            "    vec3 E15 = E;\n" +
            "\n" +
            "    bool hf = (H.x == F.x && H.y == F.y && H.z == F.z);\n" +
            "    bool he = (H.x != E.x || H.y != E.y || H.z != E.z);\n" +
            "    bool eg = (E.x == G.x && E.y == G.y && E.z == G.z);\n" +
            "    bool hi = (H.x == I.x && H.y == I.y && H.z == I.z);\n" +
            "    bool ed = (E.x == D.x && E.y == D.y && E.z == D.z);\n" +
            "    bool ec = (E.x == C.x && E.y == C.y && E.z == C.z);\n" +
            "    bool eb = (E.x == B.x && E.y == B.y && E.z == B.z);\n" +
            "\n" +
            "    if (hf && he && (eg && (hi || ed) || ec && (hi || eb))) {\n" +
            "        E11 = E11 * 0.5 + F * 0.5;\n" +
            "        E15 = F;\n" +
            "    }\n" +
            "\n" +
            "    gl_FragColor.rgb = (fp.x < 0.50) ? ((fp.x < 0.25) ? ((fp.y < 0.25) ? E15: (fp.y < 0.50) ? E11: (fp.y < 0.75) ? E11: E15) : ((fp.y < 0.25) ? E11: (fp.y < 0.50) ? E  : (fp.y < 0.75) ? E  : E11)) : ((fp.x < 0.75) ? ((fp.y < 0.25) ? E11: (fp.y < 0.50) ? E  : (fp.y < 0.75) ? E   : E11) : ((fp.y < 0.25) ? E15: (fp.y < 0.50) ? E11: (fp.y < 0.75) ? E11 : E15));\n" +
            "    gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** HQ4x fragment shader — individual vec4 varyings (v_tc0..v_tc6). */
    public static final String FRAGMENT_HQ4X =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec4 v_tc0;\n" +
            "varying vec4 v_tc1;\n" +
            "varying vec4 v_tc2;\n" +
            "varying vec4 v_tc3;\n" +
            "varying vec4 v_tc4;\n" +
            "varying vec4 v_tc5;\n" +
            "varying vec4 v_tc6;\n" +
            "\n" +
            "const float mx = 1.00;\n" +
            "const float k = -1.10;\n" +
            "const float max_w = 0.75;\n" +
            "const float min_w = 0.03;\n" +
            "const float lum_add = 0.33;\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    vec3 c  = texture2D(sampler0, v_tc0.xy).xyz;\n" +
            "    vec3 i1 = texture2D(sampler0, v_tc1.xy).xyz;\n" +
            "    vec3 i2 = texture2D(sampler0, v_tc2.xy).xyz;\n" +
            "    vec3 i3 = texture2D(sampler0, v_tc3.xy).xyz;\n" +
            "    vec3 i4 = texture2D(sampler0, v_tc4.xy).xyz;\n" +
            "    vec3 o1 = texture2D(sampler0, v_tc5.xy).xyz;\n" +
            "    vec3 o3 = texture2D(sampler0, v_tc6.xy).xyz;\n" +
            "    vec3 o2 = texture2D(sampler0, v_tc5.zw).xyz;\n" +
            "    vec3 o4 = texture2D(sampler0, v_tc6.zw).xyz;\n" +
            "    vec3 s1 = texture2D(sampler0, v_tc1.zw).xyz;\n" +
            "    vec3 s2 = texture2D(sampler0, v_tc2.zw).xyz;\n" +
            "    vec3 s3 = texture2D(sampler0, v_tc3.zw).xyz;\n" +
            "    vec3 s4 = texture2D(sampler0, v_tc4.zw).xyz;\n" +
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
            "    gl_FragColor = vec4(result, 1.0);\n" +
            "}\n";

    /** 2xBR + Dot fragment shader. */
    public static final String FRAGMENT_2XBR_DOT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "varying vec2 v_tc1;\n" +
            "varying vec2 v_tc2;\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 fp = fract(v_tc0 / u_texelDelta);\n" +
            "\n" +
            "    vec2 g1 = v_tc1 * (step(0.5, fp.x) + step(0.5, fp.y) - 1.0) +\n" +
            "            v_tc2 * (step(0.5, fp.x) - step(0.5, fp.y));\n" +
            "    vec2 g2 = v_tc1 * (step(0.5, fp.y) - step(0.5, fp.x)) +\n" +
            "            v_tc2 * (step(0.5, fp.x) + step(0.5, fp.y) - 1.0);\n" +
            "\n" +
            "    vec3 B = texture2D(sampler0, v_tc0 + g1     ).xyz;\n" +
            "    vec3 C = texture2D(sampler0, v_tc0 + g1 - g2).xyz;\n" +
            "    vec3 D = texture2D(sampler0, v_tc0      + g2).xyz;\n" +
            "    vec3 E = texture2D(sampler0, v_tc0          ).xyz;\n" +
            "    vec3 F = texture2D(sampler0, v_tc0      - g2).xyz;\n" +
            "    vec3 G = texture2D(sampler0, v_tc0 - g1 + g2).xyz;\n" +
            "    vec3 H = texture2D(sampler0, v_tc0 - g1     ).xyz;\n" +
            "    vec3 I = texture2D(sampler0, v_tc0 - g1 - g2).xyz;\n" +
            "\n" +
            "    vec3 res = E;\n" +
            "\n" +
            "    bool hf = (H.x == F.x && H.y == F.y && H.z == F.z);\n" +
            "    bool he = (H.x != E.x || H.y != E.y || H.z != E.z);\n" +
            "    bool eg = (E.x == G.x && E.y == G.y && E.z == G.z);\n" +
            "    bool hi = (H.x == I.x && H.y == I.y && H.z == I.z);\n" +
            "    bool ed = (E.x == D.x && E.y == D.y && E.z == D.z);\n" +
            "    bool ec = (E.x == C.x && E.y == C.y && E.z == C.z);\n" +
            "    bool eb = (E.x == B.x && E.y == B.y && E.z == B.z);\n" +
            "\n" +
            "    if (hf && he && (eg && (hi || ed) || ec && (hi || eb)))\n" +
            "    {\n" +
            "        res = mix(E, F, 0.5);\n" +
            "    }\n" +
            "\n" +
            "    vec2 pixel_no = v_tc0 / u_texelDelta;\n" +
            "    vec2 fp_dot = fract(pixel_no);\n" +
            "    float delta = length(fp_dot - vec2(0.5));\n" +
            "    float bright = dot(res, vec3(0.30, 0.59, 0.11));\n" +
            "    float bloom = mix(1.05, 0.95, bright);\n" +
            "    float dotMask = exp(-2.4 * delta * bloom);\n" +
            "    res = mix(1.2 * res, res * dotMask, 0.65);\n" +
            "\n" +
            "    gl_FragColor.rgb = res;\n" +
            "    gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** 4xBR + Dot fragment shader. */
    public static final String FRAGMENT_4XBR_DOT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "varying vec2 v_tc1;\n" +
            "varying vec2 v_tc2;\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 fp = fract(v_tc0 / u_texelDelta);\n" +
            "\n" +
            "    vec2 g1 = v_tc1 * (step(0.5, fp.x) + step(0.5, fp.y) - 1.0) +\n" +
            "            v_tc2 * (step(0.5, fp.x) - step(0.5, fp.y));\n" +
            "    vec2 g2 = v_tc1 * (step(0.5, fp.y) - step(0.5, fp.x)) +\n" +
            "            v_tc2 * (step(0.5, fp.x) + step(0.5, fp.y) - 1.0);\n" +
            "\n" +
            "    vec3 B = texture2D(sampler0, v_tc0 + g1     ).xyz;\n" +
            "    vec3 C = texture2D(sampler0, v_tc0 + g1 - g2).xyz;\n" +
            "    vec3 D = texture2D(sampler0, v_tc0      + g2).xyz;\n" +
            "    vec3 E = texture2D(sampler0, v_tc0          ).xyz;\n" +
            "    vec3 F = texture2D(sampler0, v_tc0      - g2).xyz;\n" +
            "    vec3 G = texture2D(sampler0, v_tc0 - g1 + g2).xyz;\n" +
            "    vec3 H = texture2D(sampler0, v_tc0 - g1     ).xyz;\n" +
            "    vec3 I = texture2D(sampler0, v_tc0 - g1 - g2).xyz;\n" +
            "\n" +
            "    vec3 E11 = E;\n" +
            "    vec3 E15 = E;\n" +
            "\n" +
            "    bool hf = (H.x == F.x && H.y == F.y && H.z == F.z);\n" +
            "    bool he = (H.x != E.x || H.y != E.y || H.z != E.z);\n" +
            "    bool eg = (E.x == G.x && E.y == G.y && E.z == G.z);\n" +
            "    bool hi = (H.x == I.x && H.y == I.y && H.z == I.z);\n" +
            "    bool ed = (E.x == D.x && E.y == D.y && E.z == D.z);\n" +
            "    bool ec = (E.x == C.x && E.y == C.y && E.z == C.z);\n" +
            "    bool eb = (E.x == B.x && E.y == B.y && E.z == B.z);\n" +
            "\n" +
            "    if (hf && he && (eg && (hi || ed) || ec && (hi || eb))) {\n" +
            "        E11 = E11 * 0.5 + F * 0.5;\n" +
            "        E15 = F;\n" +
            "    }\n" +
            "\n" +
            "    vec3 res = (fp.x < 0.50) ? ((fp.x < 0.25) ? ((fp.y < 0.25) ? E15: (fp.y < 0.50) ? E11: (fp.y < 0.75) ? E11: E15) : ((fp.y < 0.25) ? E11: (fp.y < 0.50) ? E  : (fp.y < 0.75) ? E  : E11)) : ((fp.x < 0.75) ? ((fp.y < 0.25) ? E11: (fp.y < 0.50) ? E  : (fp.y < 0.75) ? E   : E11) : ((fp.y < 0.25) ? E15: (fp.y < 0.50) ? E11: (fp.y < 0.75) ? E11 : E15));\n" +
            "\n" +
            "    vec2 pixel_no = v_tc0 / u_texelDelta;\n" +
            "    vec2 fp_dot = fract(pixel_no);\n" +
            "    float delta = length(fp_dot - vec2(0.5));\n" +
            "    float bright = dot(res, vec3(0.30, 0.59, 0.11));\n" +
            "    float bloom = mix(1.05, 0.95, bright);\n" +
            "    float dotMask = exp(-2.4 * delta * bloom);\n" +
            "    res = mix(1.2 * res, res * dotMask, 0.65);\n" +
            "\n" +
            "    gl_FragColor.rgb = res;\n" +
            "    gl_FragColor.a = 1.0;\n" +
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
            "varying vec4 v_tc0;\n" +
            "varying vec4 v_tc1;\n" +
            "varying vec4 v_tc2;\n" +
            "varying vec4 v_tc3;\n" +
            "varying vec4 v_tc4;\n" +
            "varying vec4 v_tc5;\n" +
            "varying vec4 v_tc6;\n" +
            "\n" +
            "const float mx = 1.00;\n" +
            "const float k = -1.10;\n" +
            "const float max_w = 0.75;\n" +
            "const float min_w = 0.03;\n" +
            "const float lum_add = 0.33;\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    vec3 c  = texture2D(sampler0, v_tc0.xy).xyz;\n" +
            "    vec3 i1 = texture2D(sampler0, v_tc1.xy).xyz;\n" +
            "    vec3 i2 = texture2D(sampler0, v_tc2.xy).xyz;\n" +
            "    vec3 i3 = texture2D(sampler0, v_tc3.xy).xyz;\n" +
            "    vec3 i4 = texture2D(sampler0, v_tc4.xy).xyz;\n" +
            "    vec3 o1 = texture2D(sampler0, v_tc5.xy).xyz;\n" +
            "    vec3 o3 = texture2D(sampler0, v_tc6.xy).xyz;\n" +
            "    vec3 o2 = texture2D(sampler0, v_tc5.zw).xyz;\n" +
            "    vec3 o4 = texture2D(sampler0, v_tc6.zw).xyz;\n" +
            "    vec3 s1 = texture2D(sampler0, v_tc1.zw).xyz;\n" +
            "    vec3 s2 = texture2D(sampler0, v_tc2.zw).xyz;\n" +
            "    vec3 s3 = texture2D(sampler0, v_tc3.zw).xyz;\n" +
            "    vec3 s4 = texture2D(sampler0, v_tc4.zw).xyz;\n" +
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
            "    vec2 pixel_no = v_tc0.xy / u_texelDelta;\n" +
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

    public static String[] getShader(int mode) {
        return new String[] { getVertexShader(mode), getFragmentShader(mode) };
    }

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

    public static boolean isCustomVertexShader(int mode) {
        return mode != MODE_NONE;
    }

    public static boolean isPixelProcessingMode(int mode) {
        return mode == MODE_2XBR || mode == MODE_4XBR ||
               mode == MODE_2XBR_DOT || mode == MODE_4XBR_DOT ||
               mode == MODE_HQ4X || mode == MODE_HQ4X_DOT;
    }

    public static boolean isMaskMode(int mode) {
        return mode == MODE_SCANLINE || mode == MODE_CRT || mode == MODE_DOT;
    }

    public static boolean usesNearestFiltering(int mode) {
        return isPixelProcessingMode(mode);
    }

    private J2meFilterShaders() {}
}
