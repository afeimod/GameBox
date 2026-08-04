package javax.microedition.lcdui.graphics;

/**
 * GLSL fragment shaders for J2ME video filters.
 * These shaders operate on the game's actual rendered texture (offscreenCopy),
 * performing real pixel-level processing for XBR / 4XBR / HQ4x type algorithms.
 *
 * Uniforms available (set by ShaderProgram):
 *   sampler0      — the game texture
 *   v_texcoord0   — texture coordinates
 *   u_texelDelta  — vec2(1/textureWidth, 1/textureHeight)
 *   u_pixelDelta  — vec2(1/screenWidth, 1/screenHeight)
 *
 * The vertex shader is the standard simple.vsh.
 */
public final class J2meFilterShaders {

    /** Standard vertex shader (same as simple.vsh). */
    public static final String VERTEX_SHADER =
            "attribute vec4 a_position;\n" +
            "attribute vec2 a_texcoord0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "void main() {\n" +
            "    gl_Position = a_position;\n" +
            "    v_texcoord0 = a_texcoord0;\n" +
            "}\n";

    /** Plain passthrough (no filter). */
    public static final String FRAGMENT_NONE =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sampler0, v_texcoord0);\n" +
            "}\n";

    /**
     * XBR level-2 fragment shader.
     * Performs edge-adaptive 2x interpolation on the game texture.
     * Based on the well-known XBR algorithm by Zenju / Hyllian.
     *
     * IMPORTANT: Color values from texture2D are normalized (0.0-1.0),
     * so dist() returns values in [0, 3]. The threshold must be small
     * (e.g. 0.01) — using 25.0 would make eq() always true and break
     * edge detection entirely.
     */
    public static final String FRAGMENT_XBR =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "\n" +
            "const float XBR_WEIGHT = 1.0;\n" +
            "const float XBR_EQ_THRESHOLD = 0.01;\n" +
            "\n" +
            "float rgb_to_y(vec3 c) {\n" +
            "    return dot(c, vec3(0.299, 0.587, 0.114));\n" +
            "}\n" +
            "\n" +
            "float dist(vec3 a, vec3 b) {\n" +
            "    float r = a.r - b.r;\n" +
            "    float g = a.g - b.g;\n" +
            "    float bl = a.b - b.b;\n" +
            "    return r*r + g*g + bl*bl;\n" +
            "}\n" +
            "\n" +
            "bool eq(vec3 a, vec3 b) {\n" +
            "    return dist(a, b) < XBR_EQ_THRESHOLD;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 tex = v_texcoord0;\n" +
            "    vec2 dx = vec2(u_texelDelta.x, 0.0);\n" +
            "    vec2 dy = vec2(0.0, u_texelDelta.y);\n" +
            "\n" +
            "    // Sample 3x3 neighborhood\n" +
            "    vec3 P0 = texture2D(sampler0, tex - dx - dy).rgb;\n" +
            "    vec3 P1 = texture2D(sampler0, tex - dy).rgb;\n" +
            "    vec3 P2 = texture2D(sampler0, tex + dx - dy).rgb;\n" +
            "    vec3 P3 = texture2D(sampler0, tex - dx).rgb;\n" +
            "    vec3 P4 = texture2D(sampler0, tex).rgb;\n" +
            "    vec3 P5 = texture2D(sampler0, tex + dx).rgb;\n" +
            "    vec3 P6 = texture2D(sampler0, tex - dx + dy).rgb;\n" +
            "    vec3 P7 = texture2D(sampler0, tex + dy).rgb;\n" +
            "    vec3 P8 = texture2D(sampler0, tex + dx + dy).rgb;\n" +
            "\n" +
            "    // Compute sub-pixel position within the texel\n" +
            "    vec2 fp = fract(tex / u_texelDelta);\n" +
            "    // Direction weights\n" +
            "    float yP4 = rgb_to_y(P4);\n" +
            "    float yP1 = rgb_to_y(P1);\n" +
            "    float yP3 = rgb_to_y(P3);\n" +
            "    float yP5 = rgb_to_y(P5);\n" +
            "    float yP7 = rgb_to_y(P7);\n" +
            "\n" +
            "    // Edge detection: compare luminance of neighbors\n" +
            "    float dEdge = abs(yP1 - yP7) + abs(yP3 - yP5);\n" +
            "\n" +
            "    if (dEdge < 0.01) {\n" +
            "        // Smooth area: use bilinear interpolation\n" +
            "        gl_FragColor = vec4(P4, 1.0);\n" +
            "        return;\n" +
            "    }\n" +
            "\n" +
            "    // XBR: detect which diagonal edge and interpolate\n" +
            "    vec3 a1, a2, b1, b2;\n" +
            "    if (eq(P3, P1)) { a1 = P3; a2 = P1; }\n" +
            "    else { a1 = P4; a2 = P4; }\n" +
            "    if (eq(P5, P7)) { b1 = P5; b2 = P7; }\n" +
            "    else { b1 = P4; b2 = P4; }\n" +
            "\n" +
            "    // Weight based on sub-pixel position\n" +
            "    float wa = XBR_WEIGHT * (1.0 - abs(fp.x - fp.y));\n" +
            "    float wb = XBR_WEIGHT * (1.0 - abs(fp.x + fp.y - 1.0));\n" +
            "\n" +
            "    // Blend\n" +
            "    vec3 result = P4;\n" +
            "    if (wa > 0.0) {\n" +
            "        result = mix(result, (a1 + a2) * 0.5, min(wa, 1.0));\n" +
            "    }\n" +
            "    if (wb > 0.0) {\n" +
            "        result = mix(result, (b1 + b2) * 0.5, min(wb, 1.0));\n" +
            "    }\n" +
            "\n" +
            "    gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);\n" +
            "}\n";

    /**
     * 4XBR fragment shader — stronger smoothing with larger neighborhood.
     * Uses a 5x5 kernel for more aggressive edge-adaptive interpolation.
     * Threshold is in normalized color space (0-1), not 0-255.
     */
    public static final String FRAGMENT_4XBR =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "\n" +
            "const float XBR_WEIGHT = 1.5;\n" +
            "const float XBR_EQ_THRESHOLD = 0.02;\n" +
            "\n" +
            "float rgb_to_y(vec3 c) {\n" +
            "    return dot(c, vec3(0.299, 0.587, 0.114));\n" +
            "}\n" +
            "\n" +
            "float dist(vec3 a, vec3 b) {\n" +
            "    float r = a.r - b.r;\n" +
            "    float g = a.g - b.g;\n" +
            "    float bl = a.b - b.b;\n" +
            "    return r*r + g*g + bl*bl;\n" +
            "}\n" +
            "\n" +
            "bool eq(vec3 a, vec3 b) {\n" +
            "    return dist(a, b) < XBR_EQ_THRESHOLD;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 tex = v_texcoord0;\n" +
            "    vec2 dx = vec2(u_texelDelta.x, 0.0);\n" +
            "    vec2 dy = vec2(0.0, u_texelDelta.y);\n" +
            "\n" +
            "    // Sample 5x5 neighborhood for stronger smoothing\n" +
            "    vec3 P0 = texture2D(sampler0, tex - dx - dy).rgb;\n" +
            "    vec3 P1 = texture2D(sampler0, tex - dy).rgb;\n" +
            "    vec3 P2 = texture2D(sampler0, tex + dx - dy).rgb;\n" +
            "    vec3 P3 = texture2D(sampler0, tex - dx).rgb;\n" +
            "    vec3 P4 = texture2D(sampler0, tex).rgb;\n" +
            "    vec3 P5 = texture2D(sampler0, tex + dx).rgb;\n" +
            "    vec3 P6 = texture2D(sampler0, tex - dx + dy).rgb;\n" +
            "    vec3 P7 = texture2D(sampler0, tex + dy).rgb;\n" +
            "    vec3 P8 = texture2D(sampler0, tex + dx + dy).rgb;\n" +
            "    // Extended neighbors\n" +
            "    vec3 PA = texture2D(sampler0, tex - 2.0*dx).rgb;\n" +
            "    vec3 PB = texture2D(sampler0, tex + 2.0*dx).rgb;\n" +
            "    vec3 PC = texture2D(sampler0, tex - 2.0*dy).rgb;\n" +
            "    vec3 PD = texture2D(sampler0, tex + 2.0*dy).rgb;\n" +
            "\n" +
            "    vec2 fp = fract(tex / u_texelDelta);\n" +
            "\n" +
            "    // Stronger edge detection using extended neighborhood\n" +
            "    float yP4 = rgb_to_y(P4);\n" +
            "    float yP1 = rgb_to_y(P1);\n" +
            "    float yP3 = rgb_to_y(P3);\n" +
            "    float yP5 = rgb_to_y(P5);\n" +
            "    float yP7 = rgb_to_y(P7);\n" +
            "    float yPA = rgb_to_y(PA);\n" +
            "    float yPB = rgb_to_y(PB);\n" +
            "    float yPC = rgb_to_y(PC);\n" +
            "    float yPD = rgb_to_y(PD);\n" +
            "\n" +
            "    float dEdge = abs(yP1 - yP7) + abs(yP3 - yP5) +\n" +
            "                  abs(yPA - yPB) * 0.5 + abs(yPC - yPD) * 0.5;\n" +
            "\n" +
            "    if (dEdge < 0.01) {\n" +
            "        gl_FragColor = vec4(P4, 1.0);\n" +
            "        return;\n" +
            "    }\n" +
            "\n" +
            "    // XBR with wider support\n" +
            "    vec3 a1, a2, b1, b2;\n" +
            "    if (eq(P3, P1) && eq(PA, PC)) { a1 = (P3 + PA) * 0.5; a2 = (P1 + PC) * 0.5; }\n" +
            "    else if (eq(P3, P1)) { a1 = P3; a2 = P1; }\n" +
            "    else { a1 = P4; a2 = P4; }\n" +
            "    if (eq(P5, P7) && eq(PB, PD)) { b1 = (P5 + PB) * 0.5; b2 = (P7 + PD) * 0.5; }\n" +
            "    else if (eq(P5, P7)) { b1 = P5; b2 = P7; }\n" +
            "    else { b1 = P4; b2 = P4; }\n" +
            "\n" +
            "    float wa = XBR_WEIGHT * (1.0 - abs(fp.x - fp.y));\n" +
            "    float wb = XBR_WEIGHT * (1.0 - abs(fp.x + fp.y - 1.0));\n" +
            "\n" +
            "    vec3 result = P4;\n" +
            "    if (wa > 0.0) {\n" +
            "        result = mix(result, (a1 + a2) * 0.5, min(wa, 1.0));\n" +
            "    }\n" +
            "    if (wb > 0.0) {\n" +
            "        result = mix(result, (b1 + b2) * 0.5, min(wb, 1.0));\n" +
            "    }\n" +
            "    // Extra smoothing pass: blend with 4-connected neighbors\n" +
            "    result = mix(result, (P1 + P3 + P5 + P7) * 0.25, 0.15);\n" +
            "\n" +
            "    gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);\n" +
            "}\n";

    /**
     * HQ4x fragment shader — high-quality 4x interpolation.
     * Compares the center pixel against 8 neighbors with multiple thresholds
     * to determine the best interpolation direction.
     */
    public static final String FRAGMENT_HQ4X =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "\n" +
            "const float THRESHOLD1 = 0.015625;\n" +  // 4/256
            "const float THRESHOLD2 = 0.0625;\n" +    // 16/256
            "const float THRESHOLD3 = 0.1875;\n" +    // 48/256
            "\n" +
            "float rgb_to_y(vec3 c) {\n" +
            "    return dot(c, vec3(0.299, 0.587, 0.114));\n" +
            "}\n" +
            "\n" +
            "float diff(float y1, float y2) {\n" +
            "    return abs(y1 - y2);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 tex = v_texcoord0;\n" +
            "    vec2 dx = vec2(u_texelDelta.x, 0.0);\n" +
            "    vec2 dy = vec2(0.0, u_texelDelta.y);\n" +
            "\n" +
            "    // 3x3 neighborhood\n" +
            "    vec3 c00 = texture2D(sampler0, tex - dx - dy).rgb;\n" +
            "    vec3 c01 = texture2D(sampler0, tex - dy).rgb;\n" +
            "    vec3 c02 = texture2D(sampler0, tex + dx - dy).rgb;\n" +
            "    vec3 c10 = texture2D(sampler0, tex - dx).rgb;\n" +
            "    vec3 c11 = texture2D(sampler0, tex).rgb;\n" +
            "    vec3 c12 = texture2D(sampler0, tex + dx).rgb;\n" +
            "    vec3 c20 = texture2D(sampler0, tex - dx + dy).rgb;\n" +
            "    vec3 c21 = texture2D(sampler0, tex + dy).rgb;\n" +
            "    vec3 c22 = texture2D(sampler0, tex + dx + dy).rgb;\n" +
            "\n" +
            "    float y00 = rgb_to_y(c00);\n" +
            "    float y01 = rgb_to_y(c01);\n" +
            "    float y02 = rgb_to_y(c02);\n" +
            "    float y10 = rgb_to_y(c10);\n" +
            "    float y11 = rgb_to_y(c11);\n" +
            "    float y12 = rgb_to_y(c12);\n" +
            "    float y20 = rgb_to_y(c20);\n" +
            "    float y21 = rgb_to_y(c21);\n" +
            "    float y22 = rgb_to_y(c22);\n" +
            "\n" +
            "    // Determine sub-pixel position\n" +
            "    vec2 fp = fract(tex / u_texelDelta);\n" +
            "\n" +
            "    // HQ4x: compute edge flags from neighbor comparisons\n" +
            "    bool edge_t  = diff(y01, y11) > THRESHOLD1;\n" +
            "    bool edge_b  = diff(y21, y11) > THRESHOLD1;\n" +
            "    bool edge_l  = diff(y10, y11) > THRESHOLD1;\n" +
            "    bool edge_r  = diff(y12, y11) > THRESHOLD1;\n" +
            "    bool edge_tl = diff(y00, y11) > THRESHOLD1;\n" +
            "    bool edge_tr = diff(y02, y11) > THRESHOLD1;\n" +
            "    bool edge_bl = diff(y20, y11) > THRESHOLD1;\n" +
            "    bool edge_br = diff(y22, y11) > THRESHOLD1;\n" +
            "\n" +
            "    // If no edges, output center pixel directly\n" +
            "    if (!edge_t && !edge_b && !edge_l && !edge_r &&\n" +
            "        !edge_tl && !edge_tr && !edge_bl && !edge_br) {\n" +
            "        gl_FragColor = vec4(c11, 1.0);\n" +
            "        return;\n" +
            "    }\n" +
            "\n" +
            "    // Compute weights for diagonal and orthogonal interpolation\n" +
            "    // Determine the best blend based on pattern and sub-pixel position\n" +
            "    vec3 result = c11;\n" +
            "    float fx = fp.x;\n" +
            "    float fy = fp.y;\n" +
            "\n" +
            "    // Apply interpolation based on sub-pixel quadrant\n" +
            "    if (fx < 0.5 && fy < 0.5) {\n" +
            "        // Top-left quadrant\n" +
            "        if (edge_l && edge_t) {\n" +
            "            result = mix(c11, mix(c10, c01, 0.5), 0.5 * (1.0 - fx) * (1.0 - fy));\n" +
            "        } else if (edge_l) {\n" +
            "            result = mix(c11, c10, (1.0 - fx) * 0.5);\n" +
            "        } else if (edge_t) {\n" +
            "            result = mix(c11, c01, (1.0 - fy) * 0.5);\n" +
            "        } else if (edge_tl) {\n" +
            "            result = mix(c11, c00, (1.0 - fx) * (1.0 - fy) * 0.5);\n" +
            "        }\n" +
            "    } else if (fx >= 0.5 && fy < 0.5) {\n" +
            "        // Top-right quadrant\n" +
            "        if (edge_r && edge_t) {\n" +
            "            result = mix(c11, mix(c12, c01, 0.5), 0.5 * fx * (1.0 - fy));\n" +
            "        } else if (edge_r) {\n" +
            "            result = mix(c11, c12, fx * 0.5);\n" +
            "        } else if (edge_t) {\n" +
            "            result = mix(c11, c01, (1.0 - fy) * 0.5);\n" +
            "        } else if (edge_tr) {\n" +
            "            result = mix(c11, c02, fx * (1.0 - fy) * 0.5);\n" +
            "        }\n" +
            "    } else if (fx < 0.5 && fy >= 0.5) {\n" +
            "        // Bottom-left quadrant\n" +
            "        if (edge_l && edge_b) {\n" +
            "            result = mix(c11, mix(c10, c21, 0.5), 0.5 * (1.0 - fx) * fy);\n" +
            "        } else if (edge_l) {\n" +
            "            result = mix(c11, c10, (1.0 - fx) * 0.5);\n" +
            "        } else if (edge_b) {\n" +
            "            result = mix(c11, c21, fy * 0.5);\n" +
            "        } else if (edge_bl) {\n" +
            "            result = mix(c11, c20, (1.0 - fx) * fy * 0.5);\n" +
            "        }\n" +
            "    } else {\n" +
            "        // Bottom-right quadrant\n" +
            "        if (edge_r && edge_b) {\n" +
            "            result = mix(c11, mix(c12, c21, 0.5), 0.5 * fx * fy);\n" +
            "        } else if (edge_r) {\n" +
            "            result = mix(c11, c12, fx * 0.5);\n" +
            "        } else if (edge_b) {\n" +
            "            result = mix(c11, c21, fy * 0.5);\n" +
            "        } else if (edge_br) {\n" +
            "            result = mix(c11, c22, fx * fy * 0.5);\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    // Additional smoothing: blend with orthogonal neighbors\n" +
            "    if (diff(y10, y11) < THRESHOLD2 && diff(y12, y11) < THRESHOLD2 &&\n" +
            "        diff(y01, y11) < THRESHOLD2 && diff(y21, y11) < THRESHOLD2) {\n" +
            "        result = mix(result, (c01 + c10 + c12 + c21) * 0.25, 0.2);\n" +
            "    }\n" +
            "\n" +
            "    gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);\n" +
            "}\n";

    /**
     * XBR + dot mask: applies XBR smoothing then overlays a dot mask pattern.
     * Threshold is in normalized color space (0-1).
     */
    public static final String FRAGMENT_XBR_DOT =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "\n" +
            "const float XBR_WEIGHT = 1.0;\n" +
            "const float XBR_EQ_THRESHOLD = 0.01;\n" +
            "\n" +
            "float rgb_to_y(vec3 c) {\n" +
            "    return dot(c, vec3(0.299, 0.587, 0.114));\n" +
            "}\n" +
            "float dist(vec3 a, vec3 b) {\n" +
            "    float r = a.r - b.r;\n" +
            "    float g = a.g - b.g;\n" +
            "    float bl = a.b - b.b;\n" +
            "    return r*r + g*g + bl*bl;\n" +
            "}\n" +
            "bool eq(vec3 a, vec3 b) {\n" +
            "    return dist(a, b) < XBR_EQ_THRESHOLD;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 tex = v_texcoord0;\n" +
            "    vec2 dx = vec2(u_texelDelta.x, 0.0);\n" +
            "    vec2 dy = vec2(0.0, u_texelDelta.y);\n" +
            "    vec3 P0 = texture2D(sampler0, tex - dx - dy).rgb;\n" +
            "    vec3 P1 = texture2D(sampler0, tex - dy).rgb;\n" +
            "    vec3 P2 = texture2D(sampler0, tex + dx - dy).rgb;\n" +
            "    vec3 P3 = texture2D(sampler0, tex - dx).rgb;\n" +
            "    vec3 P4 = texture2D(sampler0, tex).rgb;\n" +
            "    vec3 P5 = texture2D(sampler0, tex + dx).rgb;\n" +
            "    vec3 P6 = texture2D(sampler0, tex - dx + dy).rgb;\n" +
            "    vec3 P7 = texture2D(sampler0, tex + dy).rgb;\n" +
            "    vec3 P8 = texture2D(sampler0, tex + dx + dy).rgb;\n" +
            "    vec2 fp = fract(tex / u_texelDelta);\n" +
            "    float yP4 = rgb_to_y(P4);\n" +
            "    float yP1 = rgb_to_y(P1);\n" +
            "    float yP3 = rgb_to_y(P3);\n" +
            "    float yP5 = rgb_to_y(P5);\n" +
            "    float yP7 = rgb_to_y(P7);\n" +
            "    float dEdge = abs(yP1 - yP7) + abs(yP3 - yP5);\n" +
            "    vec3 result = P4;\n" +
            "    if (dEdge >= 0.01) {\n" +
            "        vec3 a1, a2, b1, b2;\n" +
            "        if (eq(P3, P1)) { a1 = P3; a2 = P1; } else { a1 = P4; a2 = P4; }\n" +
            "        if (eq(P5, P7)) { b1 = P5; b2 = P7; } else { b1 = P4; b2 = P4; }\n" +
            "        float wa = XBR_WEIGHT * (1.0 - abs(fp.x - fp.y));\n" +
            "        float wb = XBR_WEIGHT * (1.0 - abs(fp.x + fp.y - 1.0));\n" +
            "        if (wa > 0.0) result = mix(result, (a1 + a2) * 0.5, min(wa, 1.0));\n" +
            "        if (wb > 0.0) result = mix(result, (b1 + b2) * 0.5, min(wb, 1.0));\n" +
            "    }\n" +
            "    // Dot mask overlay\n" +
            "    vec2 screenPos = gl_FragCoord.xy;\n" +
            "    vec2 dotCoord = mod(screenPos, 4.0) / 4.0;\n" +
            "    float dotDist = length(dotCoord - 0.5);\n" +
            "    float dotMask = smoothstep(0.2, 0.5, dotDist);\n" +
            "    result *= mix(1.0, 0.5 + 0.5 * dotMask, 0.4);\n" +
            "    gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);\n" +
            "}\n";

    /**
     * 4XBR + dot mask: stronger smoothing with dot overlay.
     * Threshold is in normalized color space (0-1).
     */
    public static final String FRAGMENT_4XBR_DOT =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "uniform vec2 u_texelDelta;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "\n" +
            "const float XBR_WEIGHT = 1.5;\n" +
            "const float XBR_EQ_THRESHOLD = 0.02;\n" +
            "\n" +
            "float rgb_to_y(vec3 c) {\n" +
            "    return dot(c, vec3(0.299, 0.587, 0.114));\n" +
            "}\n" +
            "float dist(vec3 a, vec3 b) {\n" +
            "    float r = a.r - b.r;\n" +
            "    float g = a.g - b.g;\n" +
            "    float bl = a.b - b.b;\n" +
            "    return r*r + g*g + bl*bl;\n" +
            "}\n" +
            "bool eq(vec3 a, vec3 b) {\n" +
            "    return dist(a, b) < XBR_EQ_THRESHOLD;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 tex = v_texcoord0;\n" +
            "    vec2 dx = vec2(u_texelDelta.x, 0.0);\n" +
            "    vec2 dy = vec2(0.0, u_texelDelta.y);\n" +
            "    vec3 P0 = texture2D(sampler0, tex - dx - dy).rgb;\n" +
            "    vec3 P1 = texture2D(sampler0, tex - dy).rgb;\n" +
            "    vec3 P2 = texture2D(sampler0, tex + dx - dy).rgb;\n" +
            "    vec3 P3 = texture2D(sampler0, tex - dx).rgb;\n" +
            "    vec3 P4 = texture2D(sampler0, tex).rgb;\n" +
            "    vec3 P5 = texture2D(sampler0, tex + dx).rgb;\n" +
            "    vec3 P6 = texture2D(sampler0, tex - dx + dy).rgb;\n" +
            "    vec3 P7 = texture2D(sampler0, tex + dy).rgb;\n" +
            "    vec3 P8 = texture2D(sampler0, tex + dx + dy).rgb;\n" +
            "    vec3 PA = texture2D(sampler0, tex - 2.0*dx).rgb;\n" +
            "    vec3 PB = texture2D(sampler0, tex + 2.0*dx).rgb;\n" +
            "    vec3 PC = texture2D(sampler0, tex - 2.0*dy).rgb;\n" +
            "    vec3 PD = texture2D(sampler0, tex + 2.0*dy).rgb;\n" +
            "    vec2 fp = fract(tex / u_texelDelta);\n" +
            "    float yP4 = rgb_to_y(P4);\n" +
            "    float yP1 = rgb_to_y(P1);\n" +
            "    float yP3 = rgb_to_y(P3);\n" +
            "    float yP5 = rgb_to_y(P5);\n" +
            "    float yP7 = rgb_to_y(P7);\n" +
            "    float yPA = rgb_to_y(PA);\n" +
            "    float yPB = rgb_to_y(PB);\n" +
            "    float yPC = rgb_to_y(PC);\n" +
            "    float yPD = rgb_to_y(PD);\n" +
            "    float dEdge = abs(yP1 - yP7) + abs(yP3 - yP5) +\n" +
            "                  abs(yPA - yPB) * 0.5 + abs(yPC - yPD) * 0.5;\n" +
            "    vec3 result = P4;\n" +
            "    if (dEdge >= 0.01) {\n" +
            "        vec3 a1, a2, b1, b2;\n" +
            "        if (eq(P3, P1) && eq(PA, PC)) { a1 = (P3 + PA) * 0.5; a2 = (P1 + PC) * 0.5; }\n" +
            "        else if (eq(P3, P1)) { a1 = P3; a2 = P1; }\n" +
            "        else { a1 = P4; a2 = P4; }\n" +
            "        if (eq(P5, P7) && eq(PB, PD)) { b1 = (P5 + PB) * 0.5; b2 = (P7 + PD) * 0.5; }\n" +
            "        else if (eq(P5, P7)) { b1 = P5; b2 = P7; }\n" +
            "        else { b1 = P4; b2 = P4; }\n" +
            "        float wa = XBR_WEIGHT * (1.0 - abs(fp.x - fp.y));\n" +
            "        float wb = XBR_WEIGHT * (1.0 - abs(fp.x + fp.y - 1.0));\n" +
            "        if (wa > 0.0) result = mix(result, (a1 + a2) * 0.5, min(wa, 1.0));\n" +
            "        if (wb > 0.0) result = mix(result, (b1 + b2) * 0.5, min(wb, 1.0));\n" +
            "        result = mix(result, (P1 + P3 + P5 + P7) * 0.25, 0.15);\n" +
            "    }\n" +
            "    // Dot mask overlay\n" +
            "    vec2 screenPos = gl_FragCoord.xy;\n" +
            "    vec2 dotCoord = mod(screenPos, 4.0) / 4.0;\n" +
            "    float dotDist = length(dotCoord - 0.5);\n" +
            "    float dotMask = smoothstep(0.2, 0.5, dotDist);\n" +
            "    result *= mix(1.0, 0.5 + 0.5 * dotMask, 0.4);\n" +
            "    gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);\n" +
            "}\n";

    /** Mask-type fragment shaders (scanline, CRT, dot) for GL mode. */
    public static final String FRAGMENT_SCANLINE =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(sampler0, v_texcoord0);\n" +
            "    float line = mod(gl_FragCoord.y, 4.0);\n" +
            "    float mask = line < 1.0 ? 0.55 : 1.0;\n" +
            "    gl_FragColor = vec4(c.rgb * mask, c.a);\n" +
            "}\n";

    public static final String FRAGMENT_CRT =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "uniform vec2 u_pixelDelta;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(sampler0, v_texcoord0);\n" +
            "    // RGB phosphor mask\n" +
            "    float col = mod(gl_FragCoord.x, 3.0);\n" +
            "    vec3 phosphor = vec3(1.0);\n" +
            "    if (col < 1.0) phosphor = vec3(1.0, 0.85, 0.85);\n" +
            "    else if (col < 2.0) phosphor = vec3(0.85, 1.0, 0.85);\n" +
            "    else phosphor = vec3(0.85, 0.85, 1.0);\n" +
            "    // Scanline\n" +
            "    float line = mod(gl_FragCoord.y, 6.0);\n" +
            "    float scan = line < 1.0 ? 0.5 : 1.0;\n" +
            "    // Vignette\n" +
            "    vec2 v = v_texcoord0 - 0.5;\n" +
            "    float vig = 1.0 - dot(v, v) * 0.8;\n" +
            "    gl_FragColor = vec4(c.rgb * phosphor * scan * vig, c.a);\n" +
            "}\n";

    public static final String FRAGMENT_DOT =
            "precision mediump float;\n" +
            "uniform sampler2D sampler0;\n" +
            "varying vec2 v_texcoord0;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(sampler0, v_texcoord0);\n" +
            "    vec2 grid = mod(gl_FragCoord.xy, 4.0) / 4.0;\n" +
            "    float d = length(grid - 0.5);\n" +
            "    float mask = smoothstep(0.2, 0.5, d);\n" +
            "    gl_FragColor = vec4(c.rgb * mix(1.0, 0.5 + 0.5 * mask, 0.5), c.a);\n" +
            "}\n";

    /**
     * Returns the appropriate fragment shader code for the given filter mode.
     *
     * @param mode filter mode (0=none, 1=scanline, 2=CRT, 3=dot, 4=XBR, 5=4XBR,
     *             6=XBR+dot, 7=4XBR+dot, 8=HQ4x)
     * @return GLSL fragment shader source code
     */
    public static String getFragmentShader(int mode) {
        switch (mode) {
            case 1: return FRAGMENT_SCANLINE;
            case 2: return FRAGMENT_CRT;
            case 3: return FRAGMENT_DOT;
            case 4: return FRAGMENT_XBR;
            case 5: return FRAGMENT_4XBR;
            case 6: return FRAGMENT_XBR_DOT;
            case 7: return FRAGMENT_4XBR_DOT;
            case 8: return FRAGMENT_HQ4X;
            case 0:
            default: return FRAGMENT_NONE;
        }
    }

    private J2meFilterShaders() {}
}
