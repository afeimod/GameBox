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
 * <p>XBR shaders implement Hyllian's 5xBR v3.5a algorithm with weighted RGB luminance
 * edge detection, 21-pixel sampling, interpolation restriction, and line-inequality
 * edge positioning. Scale-independent (2xBR and 4xBR share the same fragment shader).
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
     * 2xBR vertex shader — passthrough for Hyllian's 5xBR v3.5a.
     * Fragment shader computes all texture coordinates internally
     * via u_texelDelta and u_pixelDelta uniforms.
     */
    public static final String VERTEX_2XBR =
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform mediump vec2 u_pixelDelta;\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_tc0;\n" +
            "\n" +
            "void main() {\n" +
            "    v_tc0 = a_texcoord0;\n" +
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
            "    vec3 out_color = mix(1.1 * mid_color, color, blend);\n" +
            "    out_color = clamp(out_color, 0.0, 1.0);\n" +
            "    gl_FragColor = vec4(out_color, 1.0);\n" +
            "}\n";

    /**
     * 2xBR fragment shader — Hyllian's 5xBR v3.5a algorithm.
     * Uses weighted RGB luminance for edge detection, samples 21 pixels
     * (3x3 core + 12 extended), and implements interpolation restriction
     * with line-inequality edge positioning. Skips processing when upscale
     * ratio is below 1.6x (falls back to nearest-neighbour).
     */
    public static final String FRAGMENT_2XBR =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform mediump vec2 u_pixelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "\n" +
            "const float coef = 2.0;\n" +
            "const vec3 rgbw = vec3(16.163, 23.351, 8.4772);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0);\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5);\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0);\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0);\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5);\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5);\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) { return abs(A - B); }\n" +
            "\n" +
            "vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d,\n" +
            "                       vec4 e, vec4 f, vec4 g, vec4 h) {\n" +
            "    return df(a,b) + df(c,d) + df(e,f) + df(g,h);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            "    vec3 res = texture2D(sampler0, v_tc0).xyz;\n" +
            "\n" +
            "    if (upscale) {\n" +
            "        bvec4 edr, edr_left, edr_up, px;\n" +
            "        bvec4 interp_restriction_lv1, interp_restriction_lv2_left, interp_restriction_lv2_up;\n" +
            "        bvec4 nc;\n" +
            "        bvec4 fx, fx_left, fx_up;\n" +
            "\n" +
            "        vec2 pS  = 1.0 / u_texelDelta;\n" +
            "        vec2 fp  = fract(v_tc0 * pS);\n" +
            "        vec2 TexCoord_0 = v_tc0 - fp * u_texelDelta;\n" +
            "        vec2 dx  = vec2(u_texelDelta.x, 0.0);\n" +
            "        vec2 dy  = vec2(0.0, u_texelDelta.y);\n" +
            "        vec2 y2  = dy + dy;\n" +
            "        vec2 x2  = dx + dx;\n" +
            "\n" +
            "        vec3 A  = texture2D(sampler0, TexCoord_0 -dx -dy).xyz;\n" +
            "        vec3 B  = texture2D(sampler0, TexCoord_0     -dy).xyz;\n" +
            "        vec3 C  = texture2D(sampler0, TexCoord_0 +dx -dy).xyz;\n" +
            "        vec3 D  = texture2D(sampler0, TexCoord_0 -dx    ).xyz;\n" +
            "        vec3 E  = texture2D(sampler0, TexCoord_0         ).xyz;\n" +
            "        vec3 F  = texture2D(sampler0, TexCoord_0 +dx    ).xyz;\n" +
            "        vec3 G  = texture2D(sampler0, TexCoord_0 -dx +dy).xyz;\n" +
            "        vec3 H  = texture2D(sampler0, TexCoord_0     +dy).xyz;\n" +
            "        vec3 I  = texture2D(sampler0, TexCoord_0 +dx +dy).xyz;\n" +
            "        vec3 A1 = texture2D(sampler0, TexCoord_0     -dx -y2).xyz;\n" +
            "        vec3 C1 = texture2D(sampler0, TexCoord_0     +dx -y2).xyz;\n" +
            "        vec3 A0 = texture2D(sampler0, TexCoord_0 -x2     -dy).xyz;\n" +
            "        vec3 G0 = texture2D(sampler0, TexCoord_0 -x2     +dy).xyz;\n" +
            "        vec3 C4 = texture2D(sampler0, TexCoord_0 +x2     -dy).xyz;\n" +
            "        vec3 I4 = texture2D(sampler0, TexCoord_0 +x2     +dy).xyz;\n" +
            "        vec3 G5 = texture2D(sampler0, TexCoord_0     -dx +y2).xyz;\n" +
            "        vec3 I5 = texture2D(sampler0, TexCoord_0     +dx +y2).xyz;\n" +
            "        vec3 B1 = texture2D(sampler0, TexCoord_0         -y2).xyz;\n" +
            "        vec3 D0 = texture2D(sampler0, TexCoord_0 -x2        ).xyz;\n" +
            "        vec3 H5 = texture2D(sampler0, TexCoord_0         +y2).xyz;\n" +
            "        vec3 F4 = texture2D(sampler0, TexCoord_0 +x2        ).xyz;\n" +
            "\n" +
            "        vec4 b  = vec4(dot(B,rgbw), dot(D,rgbw), dot(H,rgbw), dot(F,rgbw));\n" +
            "        vec4 c  = vec4(dot(C,rgbw), dot(A,rgbw), dot(G,rgbw), dot(I,rgbw));\n" +
            "        vec4 d  = vec4(b.y, b.z, b.w, b.x);\n" +
            "        vec4 e  = vec4(dot(E,rgbw));\n" +
            "        vec4 f  = vec4(b.w, b.x, b.y, b.z);\n" +
            "        vec4 g  = vec4(c.z, c.w, c.x, c.y);\n" +
            "        vec4 h  = vec4(b.z, b.w, b.x, b.y);\n" +
            "        vec4 i  = vec4(c.w, c.x, c.y, c.z);\n" +
            "        vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "        vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "        vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "        vec4 f4 = vec4(h5.y, h5.z, h5.w, h5.x);\n" +
            "\n" +
            "        fx        = greaterThan(Ao*fp.y+Bo*fp.x, Co);\n" +
            "        fx_left   = greaterThan(Ax*fp.y+Bx*fp.x, Cx);\n" +
            "        fx_up     = greaterThan(Ay*fp.y+By*fp.x, Cy);\n" +
            "\n" +
            "        interp_restriction_lv1      = bvec4(vec4(notEqual(e,f)) * vec4(notEqual(e,h)));\n" +
            "        interp_restriction_lv2_left  = bvec4(vec4(notEqual(e,g)) * vec4(notEqual(d,g)));\n" +
            "        interp_restriction_lv2_up    = bvec4(vec4(notEqual(e,c)) * vec4(notEqual(b,c)));\n" +
            "\n" +
            "        edr      = bvec4(vec4(lessThan(weighted_distance(e,c,g,i,h5,f4,h,f), weighted_distance(h,d,i5,f,i4,b,e,i))) * vec4(interp_restriction_lv1));\n" +
            "        edr_left = bvec4(vec4(lessThanEqual(coef*df(f,g), df(h,c))) * vec4(interp_restriction_lv2_left));\n" +
            "        edr_up   = bvec4(vec4(greaterThanEqual(df(f,g), coef*df(h,c))) * vec4(interp_restriction_lv2_up));\n" +
            "\n" +
            "        nc.x = (edr.x && (fx.x || edr_left.x && fx_left.x || edr_up.x && fx_up.x));\n" +
            "        nc.y = (edr.y && (fx.y || edr_left.y && fx_left.y || edr_up.y && fx_up.y));\n" +
            "        nc.z = (edr.z && (fx.z || edr_left.z && fx_left.z || edr_up.z && fx_up.z));\n" +
            "        nc.w = (edr.w && (fx.w || edr_left.w && fx_left.w || edr_up.w && fx_up.w));\n" +
            "\n" +
            "        px = lessThanEqual(df(e,f), df(e,h));\n" +
            "\n" +
            "        res = nc.x ? px.x ? F : H : nc.y ? px.y ? B : F : nc.z ? px.z ? D : B : nc.w ? px.w ? H : D : E;\n" +
            "\n" +
            "        // Anti-color-bleeding clamp: limit per-channel deviation from E\n" +
            "        // to prevent isolated color dots (red/purple/yellow) at hard edges.\n" +
            "        if (res != E) {\n" +
            "            const float BLEED_LIMIT = 80.0 / 255.0;\n" +
            "            vec3 diff = res - E;\n" +
            "            vec3 clamped = E + clamp(diff, -BLEED_LIMIT, BLEED_LIMIT);\n" +
            "            res = clamped;\n" +
            "        }\n" +
            "    }\n" +
            "    gl_FragColor.rgb = res;\n" +
            "    gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** 4xBR fragment shader — same Hyllian 5xBR v3.5a algorithm as 2xBR (scale-independent). */
    public static final String FRAGMENT_4XBR =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform mediump vec2 u_pixelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "\n" +
            "const float coef = 2.0;\n" +
            "const vec3 rgbw = vec3(16.163, 23.351, 8.4772);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0);\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5);\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0);\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0);\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5);\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5);\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) { return abs(A - B); }\n" +
            "\n" +
            "vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d,\n" +
            "                       vec4 e, vec4 f, vec4 g, vec4 h) {\n" +
            "    return df(a,b) + df(c,d) + df(e,f) + df(g,h);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            "    vec3 res = texture2D(sampler0, v_tc0).xyz;\n" +
            "\n" +
            "    if (upscale) {\n" +
            "        bvec4 edr, edr_left, edr_up, px;\n" +
            "        bvec4 interp_restriction_lv1, interp_restriction_lv2_left, interp_restriction_lv2_up;\n" +
            "        bvec4 nc;\n" +
            "        bvec4 fx, fx_left, fx_up;\n" +
            "\n" +
            "        vec2 pS  = 1.0 / u_texelDelta;\n" +
            "        vec2 fp  = fract(v_tc0 * pS);\n" +
            "        vec2 TexCoord_0 = v_tc0 - fp * u_texelDelta;\n" +
            "        vec2 dx  = vec2(u_texelDelta.x, 0.0);\n" +
            "        vec2 dy  = vec2(0.0, u_texelDelta.y);\n" +
            "        vec2 y2  = dy + dy;\n" +
            "        vec2 x2  = dx + dx;\n" +
            "\n" +
            "        vec3 A  = texture2D(sampler0, TexCoord_0 -dx -dy).xyz;\n" +
            "        vec3 B  = texture2D(sampler0, TexCoord_0     -dy).xyz;\n" +
            "        vec3 C  = texture2D(sampler0, TexCoord_0 +dx -dy).xyz;\n" +
            "        vec3 D  = texture2D(sampler0, TexCoord_0 -dx    ).xyz;\n" +
            "        vec3 E  = texture2D(sampler0, TexCoord_0         ).xyz;\n" +
            "        vec3 F  = texture2D(sampler0, TexCoord_0 +dx    ).xyz;\n" +
            "        vec3 G  = texture2D(sampler0, TexCoord_0 -dx +dy).xyz;\n" +
            "        vec3 H  = texture2D(sampler0, TexCoord_0     +dy).xyz;\n" +
            "        vec3 I  = texture2D(sampler0, TexCoord_0 +dx +dy).xyz;\n" +
            "        vec3 A1 = texture2D(sampler0, TexCoord_0     -dx -y2).xyz;\n" +
            "        vec3 C1 = texture2D(sampler0, TexCoord_0     +dx -y2).xyz;\n" +
            "        vec3 A0 = texture2D(sampler0, TexCoord_0 -x2     -dy).xyz;\n" +
            "        vec3 G0 = texture2D(sampler0, TexCoord_0 -x2     +dy).xyz;\n" +
            "        vec3 C4 = texture2D(sampler0, TexCoord_0 +x2     -dy).xyz;\n" +
            "        vec3 I4 = texture2D(sampler0, TexCoord_0 +x2     +dy).xyz;\n" +
            "        vec3 G5 = texture2D(sampler0, TexCoord_0     -dx +y2).xyz;\n" +
            "        vec3 I5 = texture2D(sampler0, TexCoord_0     +dx +y2).xyz;\n" +
            "        vec3 B1 = texture2D(sampler0, TexCoord_0         -y2).xyz;\n" +
            "        vec3 D0 = texture2D(sampler0, TexCoord_0 -x2        ).xyz;\n" +
            "        vec3 H5 = texture2D(sampler0, TexCoord_0         +y2).xyz;\n" +
            "        vec3 F4 = texture2D(sampler0, TexCoord_0 +x2        ).xyz;\n" +
            "\n" +
            "        vec4 b  = vec4(dot(B,rgbw), dot(D,rgbw), dot(H,rgbw), dot(F,rgbw));\n" +
            "        vec4 c  = vec4(dot(C,rgbw), dot(A,rgbw), dot(G,rgbw), dot(I,rgbw));\n" +
            "        vec4 d  = vec4(b.y, b.z, b.w, b.x);\n" +
            "        vec4 e  = vec4(dot(E,rgbw));\n" +
            "        vec4 f  = vec4(b.w, b.x, b.y, b.z);\n" +
            "        vec4 g  = vec4(c.z, c.w, c.x, c.y);\n" +
            "        vec4 h  = vec4(b.z, b.w, b.x, b.y);\n" +
            "        vec4 i  = vec4(c.w, c.x, c.y, c.z);\n" +
            "        vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "        vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "        vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "        vec4 f4 = vec4(h5.y, h5.z, h5.w, h5.x);\n" +
            "\n" +
            "        fx        = greaterThan(Ao*fp.y+Bo*fp.x, Co);\n" +
            "        fx_left   = greaterThan(Ax*fp.y+Bx*fp.x, Cx);\n" +
            "        fx_up     = greaterThan(Ay*fp.y+By*fp.x, Cy);\n" +
            "\n" +
            "        interp_restriction_lv1      = bvec4(vec4(notEqual(e,f)) * vec4(notEqual(e,h)));\n" +
            "        interp_restriction_lv2_left  = bvec4(vec4(notEqual(e,g)) * vec4(notEqual(d,g)));\n" +
            "        interp_restriction_lv2_up    = bvec4(vec4(notEqual(e,c)) * vec4(notEqual(b,c)));\n" +
            "\n" +
            "        edr      = bvec4(vec4(lessThan(weighted_distance(e,c,g,i,h5,f4,h,f), weighted_distance(h,d,i5,f,i4,b,e,i))) * vec4(interp_restriction_lv1));\n" +
            "        edr_left = bvec4(vec4(lessThanEqual(coef*df(f,g), df(h,c))) * vec4(interp_restriction_lv2_left));\n" +
            "        edr_up   = bvec4(vec4(greaterThanEqual(df(f,g), coef*df(h,c))) * vec4(interp_restriction_lv2_up));\n" +
            "\n" +
            "        nc.x = (edr.x && (fx.x || edr_left.x && fx_left.x || edr_up.x && fx_up.x));\n" +
            "        nc.y = (edr.y && (fx.y || edr_left.y && fx_left.y || edr_up.y && fx_up.y));\n" +
            "        nc.z = (edr.z && (fx.z || edr_left.z && fx_left.z || edr_up.z && fx_up.z));\n" +
            "        nc.w = (edr.w && (fx.w || edr_left.w && fx_left.w || edr_up.w && fx_up.w));\n" +
            "\n" +
            "        px = lessThanEqual(df(e,f), df(e,h));\n" +
            "\n" +
            "        res = nc.x ? px.x ? F : H : nc.y ? px.y ? B : F : nc.z ? px.z ? D : B : nc.w ? px.w ? H : D : E;\n" +
            "\n" +
            "        // Anti-color-bleeding clamp: limit per-channel deviation from E\n" +
            "        // to prevent isolated color dots (red/purple/yellow) at hard edges.\n" +
            "        if (res != E) {\n" +
            "            const float BLEED_LIMIT = 80.0 / 255.0;\n" +
            "            vec3 diff = res - E;\n" +
            "            vec3 clamped = E + clamp(diff, -BLEED_LIMIT, BLEED_LIMIT);\n" +
            "            res = clamped;\n" +
            "        }\n" +
            "    }\n" +
            "    gl_FragColor.rgb = res;\n" +
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

    /** 2xBR + Dot fragment shader — 5xBR v3.5a with LCD dot-mask post-processing. */
    public static final String FRAGMENT_2XBR_DOT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform mediump vec2 u_pixelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "\n" +
            "const float coef = 2.0;\n" +
            "const vec3 rgbw = vec3(16.163, 23.351, 8.4772);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0);\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5);\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0);\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0);\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5);\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5);\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) { return abs(A - B); }\n" +
            "\n" +
            "vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d,\n" +
            "                       vec4 e, vec4 f, vec4 g, vec4 h) {\n" +
            "    return df(a,b) + df(c,d) + df(e,f) + df(g,h);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            "    vec3 res = texture2D(sampler0, v_tc0).xyz;\n" +
            "\n" +
            "    if (upscale) {\n" +
            "        bvec4 edr, edr_left, edr_up, px;\n" +
            "        bvec4 interp_restriction_lv1, interp_restriction_lv2_left, interp_restriction_lv2_up;\n" +
            "        bvec4 nc;\n" +
            "        bvec4 fx, fx_left, fx_up;\n" +
            "\n" +
            "        vec2 pS  = 1.0 / u_texelDelta;\n" +
            "        vec2 fp  = fract(v_tc0 * pS);\n" +
            "        vec2 TexCoord_0 = v_tc0 - fp * u_texelDelta;\n" +
            "        vec2 dx  = vec2(u_texelDelta.x, 0.0);\n" +
            "        vec2 dy  = vec2(0.0, u_texelDelta.y);\n" +
            "        vec2 y2  = dy + dy;\n" +
            "        vec2 x2  = dx + dx;\n" +
            "\n" +
            "        vec3 A  = texture2D(sampler0, TexCoord_0 -dx -dy).xyz;\n" +
            "        vec3 B  = texture2D(sampler0, TexCoord_0     -dy).xyz;\n" +
            "        vec3 C  = texture2D(sampler0, TexCoord_0 +dx -dy).xyz;\n" +
            "        vec3 D  = texture2D(sampler0, TexCoord_0 -dx    ).xyz;\n" +
            "        vec3 E  = texture2D(sampler0, TexCoord_0         ).xyz;\n" +
            "        vec3 F  = texture2D(sampler0, TexCoord_0 +dx    ).xyz;\n" +
            "        vec3 G  = texture2D(sampler0, TexCoord_0 -dx +dy).xyz;\n" +
            "        vec3 H  = texture2D(sampler0, TexCoord_0     +dy).xyz;\n" +
            "        vec3 I  = texture2D(sampler0, TexCoord_0 +dx +dy).xyz;\n" +
            "        vec3 A1 = texture2D(sampler0, TexCoord_0     -dx -y2).xyz;\n" +
            "        vec3 C1 = texture2D(sampler0, TexCoord_0     +dx -y2).xyz;\n" +
            "        vec3 A0 = texture2D(sampler0, TexCoord_0 -x2     -dy).xyz;\n" +
            "        vec3 G0 = texture2D(sampler0, TexCoord_0 -x2     +dy).xyz;\n" +
            "        vec3 C4 = texture2D(sampler0, TexCoord_0 +x2     -dy).xyz;\n" +
            "        vec3 I4 = texture2D(sampler0, TexCoord_0 +x2     +dy).xyz;\n" +
            "        vec3 G5 = texture2D(sampler0, TexCoord_0     -dx +y2).xyz;\n" +
            "        vec3 I5 = texture2D(sampler0, TexCoord_0     +dx +y2).xyz;\n" +
            "        vec3 B1 = texture2D(sampler0, TexCoord_0         -y2).xyz;\n" +
            "        vec3 D0 = texture2D(sampler0, TexCoord_0 -x2        ).xyz;\n" +
            "        vec3 H5 = texture2D(sampler0, TexCoord_0         +y2).xyz;\n" +
            "        vec3 F4 = texture2D(sampler0, TexCoord_0 +x2        ).xyz;\n" +
            "\n" +
            "        vec4 b  = vec4(dot(B,rgbw), dot(D,rgbw), dot(H,rgbw), dot(F,rgbw));\n" +
            "        vec4 c  = vec4(dot(C,rgbw), dot(A,rgbw), dot(G,rgbw), dot(I,rgbw));\n" +
            "        vec4 d  = vec4(b.y, b.z, b.w, b.x);\n" +
            "        vec4 e  = vec4(dot(E,rgbw));\n" +
            "        vec4 f  = vec4(b.w, b.x, b.y, b.z);\n" +
            "        vec4 g  = vec4(c.z, c.w, c.x, c.y);\n" +
            "        vec4 h  = vec4(b.z, b.w, b.x, b.y);\n" +
            "        vec4 i  = vec4(c.w, c.x, c.y, c.z);\n" +
            "        vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "        vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "        vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "        vec4 f4 = vec4(h5.y, h5.z, h5.w, h5.x);\n" +
            "\n" +
            "        fx        = greaterThan(Ao*fp.y+Bo*fp.x, Co);\n" +
            "        fx_left   = greaterThan(Ax*fp.y+Bx*fp.x, Cx);\n" +
            "        fx_up     = greaterThan(Ay*fp.y+By*fp.x, Cy);\n" +
            "\n" +
            "        interp_restriction_lv1      = bvec4(vec4(notEqual(e,f)) * vec4(notEqual(e,h)));\n" +
            "        interp_restriction_lv2_left  = bvec4(vec4(notEqual(e,g)) * vec4(notEqual(d,g)));\n" +
            "        interp_restriction_lv2_up    = bvec4(vec4(notEqual(e,c)) * vec4(notEqual(b,c)));\n" +
            "\n" +
            "        edr      = bvec4(vec4(lessThan(weighted_distance(e,c,g,i,h5,f4,h,f), weighted_distance(h,d,i5,f,i4,b,e,i))) * vec4(interp_restriction_lv1));\n" +
            "        edr_left = bvec4(vec4(lessThanEqual(coef*df(f,g), df(h,c))) * vec4(interp_restriction_lv2_left));\n" +
            "        edr_up   = bvec4(vec4(greaterThanEqual(df(f,g), coef*df(h,c))) * vec4(interp_restriction_lv2_up));\n" +
            "\n" +
            "        nc.x = (edr.x && (fx.x || edr_left.x && fx_left.x || edr_up.x && fx_up.x));\n" +
            "        nc.y = (edr.y && (fx.y || edr_left.y && fx_left.y || edr_up.y && fx_up.y));\n" +
            "        nc.z = (edr.z && (fx.z || edr_left.z && fx_left.z || edr_up.z && fx_up.z));\n" +
            "        nc.w = (edr.w && (fx.w || edr_left.w && fx_left.w || edr_up.w && fx_up.w));\n" +
            "\n" +
            "        px = lessThanEqual(df(e,f), df(e,h));\n" +
            "\n" +
            "        res = nc.x ? px.x ? F : H : nc.y ? px.y ? B : F : nc.z ? px.z ? D : B : nc.w ? px.w ? H : D : E;\n" +
            "\n" +
            "        // Anti-color-bleeding clamp\n" +
            "        if (res != E) {\n" +
            "            const float BLEED_LIMIT = 80.0 / 255.0;\n" +
            "            vec3 diff = res - E;\n" +
            "            vec3 clamped = E + clamp(diff, -BLEED_LIMIT, BLEED_LIMIT);\n" +
            "            res = clamped;\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    vec2 pixel_no = v_tc0 / u_texelDelta;\n" +
            "    vec2 fp_dot = fract(pixel_no);\n" +
            "    float delta = length(fp_dot - vec2(0.5));\n" +
            "    float bright = dot(res, vec3(0.30, 0.59, 0.11));\n" +
            "    float bloom = mix(1.05, 0.95, bright);\n" +
            "    float dotMask = exp(-2.4 * delta * bloom);\n" +
            "    res = mix(1.1 * res, res * dotMask, 0.65);\n" +
            "    res = clamp(res, 0.0, 1.0);\n" +
            "\n" +
            "    gl_FragColor.rgb = res;\n" +
            "    gl_FragColor.a = 1.0;\n" +
            "}\n";

    /** 4xBR + Dot fragment shader — 5xBR v3.5a with LCD dot-mask post-processing. */
    public static final String FRAGMENT_4XBR_DOT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform mediump vec2 u_texelDelta;\n" +
            "uniform mediump vec2 u_pixelDelta;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_tc0;\n" +
            "\n" +
            "const float coef = 2.0;\n" +
            "const vec3 rgbw = vec3(16.163, 23.351, 8.4772);\n" +
            "\n" +
            "const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bo = vec4( 1.0,  1.0, -1.0,-1.0);\n" +
            "const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5);\n" +
            "const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 Bx = vec4( 0.5,  2.0, -0.5,-2.0);\n" +
            "const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0);\n" +
            "const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0);\n" +
            "const vec4 By = vec4( 2.0,  0.5, -2.0,-0.5);\n" +
            "const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5);\n" +
            "\n" +
            "vec4 df(vec4 A, vec4 B) { return abs(A - B); }\n" +
            "\n" +
            "vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d,\n" +
            "                       vec4 e, vec4 f, vec4 g, vec4 h) {\n" +
            "    return df(a,b) + df(c,d) + df(e,f) + df(g,h);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    bool upscale = u_texelDelta.x > (1.6 * u_pixelDelta.x);\n" +
            "    vec3 res = texture2D(sampler0, v_tc0).xyz;\n" +
            "\n" +
            "    if (upscale) {\n" +
            "        bvec4 edr, edr_left, edr_up, px;\n" +
            "        bvec4 interp_restriction_lv1, interp_restriction_lv2_left, interp_restriction_lv2_up;\n" +
            "        bvec4 nc;\n" +
            "        bvec4 fx, fx_left, fx_up;\n" +
            "\n" +
            "        vec2 pS  = 1.0 / u_texelDelta;\n" +
            "        vec2 fp  = fract(v_tc0 * pS);\n" +
            "        vec2 TexCoord_0 = v_tc0 - fp * u_texelDelta;\n" +
            "        vec2 dx  = vec2(u_texelDelta.x, 0.0);\n" +
            "        vec2 dy  = vec2(0.0, u_texelDelta.y);\n" +
            "        vec2 y2  = dy + dy;\n" +
            "        vec2 x2  = dx + dx;\n" +
            "\n" +
            "        vec3 A  = texture2D(sampler0, TexCoord_0 -dx -dy).xyz;\n" +
            "        vec3 B  = texture2D(sampler0, TexCoord_0     -dy).xyz;\n" +
            "        vec3 C  = texture2D(sampler0, TexCoord_0 +dx -dy).xyz;\n" +
            "        vec3 D  = texture2D(sampler0, TexCoord_0 -dx    ).xyz;\n" +
            "        vec3 E  = texture2D(sampler0, TexCoord_0         ).xyz;\n" +
            "        vec3 F  = texture2D(sampler0, TexCoord_0 +dx    ).xyz;\n" +
            "        vec3 G  = texture2D(sampler0, TexCoord_0 -dx +dy).xyz;\n" +
            "        vec3 H  = texture2D(sampler0, TexCoord_0     +dy).xyz;\n" +
            "        vec3 I  = texture2D(sampler0, TexCoord_0 +dx +dy).xyz;\n" +
            "        vec3 A1 = texture2D(sampler0, TexCoord_0     -dx -y2).xyz;\n" +
            "        vec3 C1 = texture2D(sampler0, TexCoord_0     +dx -y2).xyz;\n" +
            "        vec3 A0 = texture2D(sampler0, TexCoord_0 -x2     -dy).xyz;\n" +
            "        vec3 G0 = texture2D(sampler0, TexCoord_0 -x2     +dy).xyz;\n" +
            "        vec3 C4 = texture2D(sampler0, TexCoord_0 +x2     -dy).xyz;\n" +
            "        vec3 I4 = texture2D(sampler0, TexCoord_0 +x2     +dy).xyz;\n" +
            "        vec3 G5 = texture2D(sampler0, TexCoord_0     -dx +y2).xyz;\n" +
            "        vec3 I5 = texture2D(sampler0, TexCoord_0     +dx +y2).xyz;\n" +
            "        vec3 B1 = texture2D(sampler0, TexCoord_0         -y2).xyz;\n" +
            "        vec3 D0 = texture2D(sampler0, TexCoord_0 -x2        ).xyz;\n" +
            "        vec3 H5 = texture2D(sampler0, TexCoord_0         +y2).xyz;\n" +
            "        vec3 F4 = texture2D(sampler0, TexCoord_0 +x2        ).xyz;\n" +
            "\n" +
            "        vec4 b  = vec4(dot(B,rgbw), dot(D,rgbw), dot(H,rgbw), dot(F,rgbw));\n" +
            "        vec4 c  = vec4(dot(C,rgbw), dot(A,rgbw), dot(G,rgbw), dot(I,rgbw));\n" +
            "        vec4 d  = vec4(b.y, b.z, b.w, b.x);\n" +
            "        vec4 e  = vec4(dot(E,rgbw));\n" +
            "        vec4 f  = vec4(b.w, b.x, b.y, b.z);\n" +
            "        vec4 g  = vec4(c.z, c.w, c.x, c.y);\n" +
            "        vec4 h  = vec4(b.z, b.w, b.x, b.y);\n" +
            "        vec4 i  = vec4(c.w, c.x, c.y, c.z);\n" +
            "        vec4 i4 = vec4(dot(I4,rgbw), dot(C1,rgbw), dot(A0,rgbw), dot(G5,rgbw));\n" +
            "        vec4 i5 = vec4(dot(I5,rgbw), dot(C4,rgbw), dot(A1,rgbw), dot(G0,rgbw));\n" +
            "        vec4 h5 = vec4(dot(H5,rgbw), dot(F4,rgbw), dot(B1,rgbw), dot(D0,rgbw));\n" +
            "        vec4 f4 = vec4(h5.y, h5.z, h5.w, h5.x);\n" +
            "\n" +
            "        fx        = greaterThan(Ao*fp.y+Bo*fp.x, Co);\n" +
            "        fx_left   = greaterThan(Ax*fp.y+Bx*fp.x, Cx);\n" +
            "        fx_up     = greaterThan(Ay*fp.y+By*fp.x, Cy);\n" +
            "\n" +
            "        interp_restriction_lv1      = bvec4(vec4(notEqual(e,f)) * vec4(notEqual(e,h)));\n" +
            "        interp_restriction_lv2_left  = bvec4(vec4(notEqual(e,g)) * vec4(notEqual(d,g)));\n" +
            "        interp_restriction_lv2_up    = bvec4(vec4(notEqual(e,c)) * vec4(notEqual(b,c)));\n" +
            "\n" +
            "        edr      = bvec4(vec4(lessThan(weighted_distance(e,c,g,i,h5,f4,h,f), weighted_distance(h,d,i5,f,i4,b,e,i))) * vec4(interp_restriction_lv1));\n" +
            "        edr_left = bvec4(vec4(lessThanEqual(coef*df(f,g), df(h,c))) * vec4(interp_restriction_lv2_left));\n" +
            "        edr_up   = bvec4(vec4(greaterThanEqual(df(f,g), coef*df(h,c))) * vec4(interp_restriction_lv2_up));\n" +
            "\n" +
            "        nc.x = (edr.x && (fx.x || edr_left.x && fx_left.x || edr_up.x && fx_up.x));\n" +
            "        nc.y = (edr.y && (fx.y || edr_left.y && fx_left.y || edr_up.y && fx_up.y));\n" +
            "        nc.z = (edr.z && (fx.z || edr_left.z && fx_left.z || edr_up.z && fx_up.z));\n" +
            "        nc.w = (edr.w && (fx.w || edr_left.w && fx_left.w || edr_up.w && fx_up.w));\n" +
            "\n" +
            "        px = lessThanEqual(df(e,f), df(e,h));\n" +
            "\n" +
            "        res = nc.x ? px.x ? F : H : nc.y ? px.y ? B : F : nc.z ? px.z ? D : B : nc.w ? px.w ? H : D : E;\n" +
            "\n" +
            "        // Anti-color-bleeding clamp\n" +
            "        if (res != E) {\n" +
            "            const float BLEED_LIMIT = 80.0 / 255.0;\n" +
            "            vec3 diff = res - E;\n" +
            "            vec3 clamped = E + clamp(diff, -BLEED_LIMIT, BLEED_LIMIT);\n" +
            "            res = clamped;\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    vec2 pixel_no = v_tc0 / u_texelDelta;\n" +
            "    vec2 fp_dot = fract(pixel_no);\n" +
            "    float delta = length(fp_dot - vec2(0.5));\n" +
            "    float bright = dot(res, vec3(0.30, 0.59, 0.11));\n" +
            "    float bloom = mix(1.05, 0.95, bright);\n" +
            "    float dotMask = exp(-2.4 * delta * bloom);\n" +
            "    res = mix(1.1 * res, res * dotMask, 0.65);\n" +
            "    res = clamp(res, 0.0, 1.0);\n" +
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
            "    result = mix(1.1 * result, result * dotMask, 0.65);\n" +
            "    result = clamp(result, 0.0, 1.0);\n" +
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
