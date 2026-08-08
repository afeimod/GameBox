// SPDX-License-Identifier: MIT
// GPU-accelerated video filter using EGL + OpenGL ES 2.0.
//
// Replaces CPU-based XBR/HQX upscaling with GPU shader processing,
// providing massive performance improvement by offloading pixel
// computation to the GPU's parallel fragment shaders.
//
// Usage:
//   GpuVideoFilter gpuFilter;
//   gpuFilter.init(window, filterCode, srcWidth, srcHeight);
//   // each frame:
//   gpuFilter.renderFrame(framebuf, w, h, stride);
//   // cleanup:
//   gpuFilter.cleanup();
//
// Supported filters: xbr(4), xbr+dot(7), 4xbr(8), 4xbr+dot(9)
//
// Copyright (C) 2011-2015 Hyllian - sergiogdb@gmail.com (xBR algorithm)
#pragma once

#include <android/log.h>
#include <android/native_window.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <cstring>
#include <cstdlib>
#include <vector>
#include <mutex>
#include <atomic>
#include <pthread.h>

#define GPU_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "gpu-filter", __VA_ARGS__)
#define GPU_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "gpu-filter", __VA_ARGS__)
#define GPU_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "gpu-filter", __VA_ARGS__)

namespace gpufilter {

// ---------------------------------------------------------------------------
// GLSL shader source code — Hyllian's xBR-lv2 (from mGBA Android)
// ---------------------------------------------------------------------------

// Vertex shader: maps fullscreen quad and computes 6x6 neighborhood
// texture coordinate offsets for the fragment shader.
static const char* XBR_VERTEX_SHADER =
    "attribute vec2 a_position;\n"
    "attribute vec2 a_texcoord;\n"
    "varying vec2 texCoord;\n"
    "varying vec4 TEX1;\n"
    "varying vec4 TEX2;\n"
    "varying vec4 TEX3;\n"
    "varying vec4 TEX4;\n"
    "varying vec4 TEX5;\n"
    "varying vec4 TEX6;\n"
    "varying vec4 TEX7;\n"
    "uniform vec2 u_texSize;\n"
    "void main() {\n"
    "    gl_Position = vec4(a_position, 0.0, 1.0);\n"
    "    vec2 ps = vec2(1.0) / u_texSize;\n"
    "    float dx = ps.x;\n"
    "    float dy = ps.y;\n"
    "    texCoord = a_texcoord;\n"
    "    TEX1 = texCoord.xxxy + vec4(-dx, 0.0, dx, -2.0*dy);\n"
    "    TEX2 = texCoord.xxxy + vec4(-dx, 0.0, dx,    -dy);\n"
    "    TEX3 = texCoord.xxxy + vec4(-dx, 0.0, dx,   0.0);\n"
    "    TEX4 = texCoord.xxxy + vec4(-dx, 0.0, dx,     dy);\n"
    "    TEX5 = texCoord.xxxy + vec4(-dx, 0.0, dx, 2.0*dy);\n"
    "    TEX6 = texCoord.xyyy + vec4(-2.0*dx, -dy, 0.0, dy);\n"
    "    TEX7 = texCoord.xyyy + vec4( 2.0*dx, -dy, 0.0, dy);\n"
    "}\n";

// Fragment shader: Hyllian's xBR-lv2 with configurable scale.
// Adapted from mGBA's xbr-lv2.shader/xbr.fs for OpenGL ES 2.0.
static const char* XBR_FRAGMENT_SHADER =
    "precision highp float;\n"
    "uniform sampler2D u_texture;\n"
    "uniform float u_yWeight;\n"
    "uniform float u_eqThreshold;\n"
    "uniform float u_scale;\n"
    "uniform float u_lv2Coeff;\n"
    "uniform vec2 u_texSize;\n"
    "varying vec2 texCoord;\n"
    "varying vec4 TEX1;\n"
    "varying vec4 TEX2;\n"
    "varying vec4 TEX3;\n"
    "varying vec4 TEX4;\n"
    "varying vec4 TEX5;\n"
    "varying vec4 TEX6;\n"
    "varying vec4 TEX7;\n"
    "\n"
    "const vec4 Ao = vec4( 1.0,-1.0,-1.0, 1.0);\n"
    "const vec4 Bo = vec4( 1.0, 1.0,-1.0,-1.0);\n"
    "const vec4 Co = vec4( 1.5, 0.5,-0.5, 0.5);\n"
    "const vec4 Ax = vec4( 1.0,-1.0,-1.0, 1.0);\n"
    "const vec4 Bx = vec4( 0.5, 2.0,-0.5,-2.0);\n"
    "const vec4 Ay = vec4( 1.0,-1.0,-1.0, 1.0);\n"
    "const vec4 By = vec4( 2.0, 0.5,-2.0,-0.5);\n"
    "const vec4 Ci = vec4(0.25,0.25,0.25,0.25);\n"
    "const vec3 Y  = vec3(0.2126,0.7152,0.0722);\n"
    "\n"
    "vec4 df(vec4 A, vec4 B) { return abs(A - B); }\n"
    "float cdf(vec3 c1, vec3 c2) { vec3 d = abs(c1-c2); return d.r+d.g+d.b; }\n"
    "bvec4 eq(vec4 A, vec4 B) { return lessThan(df(A,B), vec4(u_eqThreshold)); }\n"
    "bvec4 bAnd(bvec4 A, bvec4 B) { return bvec4(A.x&&B.x,A.y&&B.y,A.z&&B.z,A.w&&B.w); }\n"
    "bvec4 nand(bvec4 A, bvec4 B) { return bvec4(!(A.x&&B.x),!(A.y&&B.y),!(A.z&&B.z),!(A.w&&B.w)); }\n"
    "\n"
    "vec4 wd(vec4 a, vec4 b, vec4 c, vec4 d, vec4 e, vec4 f, vec4 g, vec4 h) {\n"
    "    return df(a,b)+df(a,c)+df(d,e)+df(d,f)+4.0*df(g,h);\n"
    "}\n"
    "\n"
    "void main() {\n"
    "    vec3 A1=texture2D(u_texture,TEX1.xw).rgb;\n"
    "    vec3 B1=texture2D(u_texture,TEX1.yw).rgb;\n"
    "    vec3 C1=texture2D(u_texture,TEX1.zw).rgb;\n"
    "    vec3 A =texture2D(u_texture,TEX2.xw).rgb;\n"
    "    vec3 B =texture2D(u_texture,TEX2.yw).rgb;\n"
    "    vec3 C =texture2D(u_texture,TEX2.zw).rgb;\n"
    "    vec3 D =texture2D(u_texture,TEX3.xw).rgb;\n"
    "    vec3 E =texture2D(u_texture,TEX3.yw).rgb;\n"
    "    vec3 F =texture2D(u_texture,TEX3.zw).rgb;\n"
    "    vec3 G =texture2D(u_texture,TEX4.xw).rgb;\n"
    "    vec3 H =texture2D(u_texture,TEX4.yw).rgb;\n"
    "    vec3 I =texture2D(u_texture,TEX4.zw).rgb;\n"
    "    vec3 G5=texture2D(u_texture,TEX5.xw).rgb;\n"
    "    vec3 H5=texture2D(u_texture,TEX5.yw).rgb;\n"
    "    vec3 I5=texture2D(u_texture,TEX5.zw).rgb;\n"
    "    vec3 A0=texture2D(u_texture,TEX6.xy).rgb;\n"
    "    vec3 D0=texture2D(u_texture,TEX6.xz).rgb;\n"
    "    vec3 G0=texture2D(u_texture,TEX6.xw).rgb;\n"
    "    vec3 C4=texture2D(u_texture,TEX7.xy).rgb;\n"
    "    vec3 F4=texture2D(u_texture,TEX7.xz).rgb;\n"
    "    vec3 I4=texture2D(u_texture,TEX7.xw).rgb;\n"
    "\n"
    "    vec4 b = u_yWeight * vec4(dot(B,Y), dot(D,Y), dot(H,Y), dot(F,Y));\n"
    "    vec4 c = u_yWeight * vec4(dot(C,Y), dot(A,Y), dot(G,Y), dot(I,Y));\n"
    "    vec4 e = u_yWeight * vec4(dot(E,Y), dot(E,Y), dot(E,Y), dot(E,Y));\n"
    "    vec4 d = b.yzwx;\n"
    "    vec4 f = b.wxyz;\n"
    "    vec4 g = c.zwxy;\n"
    "    vec4 h = b.zwxy;\n"
    "    vec4 iv= c.wxyz;\n"
    "    vec4 i4= u_yWeight * vec4(dot(I4,Y), dot(C1,Y), dot(A0,Y), dot(G5,Y));\n"
    "    vec4 i5= u_yWeight * vec4(dot(I5,Y), dot(C4,Y), dot(A1,Y), dot(G0,Y));\n"
    "    vec4 h5= u_yWeight * vec4(dot(H5,Y), dot(F4,Y), dot(B1,Y), dot(D0,Y));\n"
    "    vec4 f4= h5.yzwx;\n"
    "\n"
    "    vec2 fp = fract(texCoord * u_texSize);\n"
    "    vec4 delta  = vec4(1.0/u_scale);\n"
    "    vec4 deltaL = vec4(0.5/u_scale, 1.0/u_scale, 0.5/u_scale, 1.0/u_scale);\n"
    "    vec4 deltaU = deltaL.yxwz;\n"
    "\n"
    "    vec4 fx      = Ao*fp.y + Bo*fp.x;\n"
    "    vec4 fx_left = Ax*fp.y + Bx*fp.x;\n"
    "    vec4 fx_up   = Ay*fp.y + By*fp.x;\n"
    "\n"
    "    bvec4 ir_lv0 = bAnd(notEqual(e,f), notEqual(e,h));\n"
    "    bvec4 ir_lv1 = ir_lv0;\n"
    "    bvec4 ir_lv2_left = bAnd(notEqual(e,g), notEqual(d,g));\n"
    "    bvec4 ir_lv2_up   = bAnd(notEqual(e,c), notEqual(b,c));\n"
    "\n"
    "    vec4 fx45i = clamp((fx      + delta  - Co - Ci)/(2.0*delta ), 0.0,1.0);\n"
    "    vec4 fx45  = clamp((fx      + delta  - Co     )/(2.0*delta ), 0.0,1.0);\n"
    "    vec4 fx30  = clamp((fx_left + deltaL - Co     )/(2.0*deltaL), 0.0,1.0);\n"
    "    vec4 fx60  = clamp((fx_up   + deltaU - Co     )/(2.0*deltaU), 0.0,1.0);\n"
    "\n"
    "    vec4 wd1 = wd(e,c,g,iv,h5,f4,h,f);\n"
    "    vec4 wd2 = wd(h,d,i5,f,i4,b,e,iv);\n"
    "\n"
    "    bvec4 edri = bAnd(lessThanEqual(wd1,wd2), ir_lv0);\n"
    "    bvec4 edr  = bAnd(lessThan(wd1,wd2), ir_lv1);\n"
    "    bvec4 edr_left = bAnd(lessThanEqual(u_lv2Coeff*df(f,g), df(h,c)), ir_lv2_left);\n"
    "    bvec4 edr_up   = bAnd(greaterThanEqual(df(f,g), u_lv2Coeff*df(h,c)), ir_lv2_up);\n"
    "\n"
    "    edr = bAnd(edr, nand(edri.yzwx, edri.wxyz));\n"
    "    edr_left = bAnd(bAnd(edr_left, edr), eq(e, c));\n"
    "    edr_up   = bAnd(bAnd(edr_up,   edr), eq(e, g));\n"
    "\n"
    "    fx45  *= vec4(edr);\n"
    "    fx30  *= vec4(edr_left);\n"
    "    fx60  *= vec4(edr_up);\n"
    "    fx45i *= vec4(edri);\n"
    "\n"
    "    vec4 px = vec4(lessThanEqual(df(e,f), df(e,h)));\n"
    "    vec4 maximos = max(max(fx30,fx60), max(fx45,fx45i));\n"
    "\n"
    "    vec3 res1 = E;\n"
    "    res1 = mix(res1, mix(H,F,px.x), maximos.x);\n"
    "    res1 = mix(res1, mix(B,D,px.z), maximos.z);\n"
    "    vec3 res2 = res1;\n"
    "    res2 = mix(res1, mix(F,B,px.y), maximos.y);\n"
    "    res2 = mix(res1, mix(D,H,px.w), maximos.w);\n"
    "\n"
    "    vec3 res = mix(res1, res2, step(cdf(E,res1), cdf(E,res2)));\n"
    "    gl_FragColor = vec4(res, 1.0);\n"
    "}\n";

// Passthrough vertex shader (for non-XBR rendering / fallback)
static const char* PASSTHROUGH_VERTEX =
    "attribute vec2 a_position;\n"
    "attribute vec2 a_texcoord;\n"
    "varying vec2 v_texcoord;\n"
    "void main() {\n"
    "    gl_Position = vec4(a_position, 0.0, 1.0);\n"
    "    v_texcoord = a_texcoord;\n"
    "}\n";

// Passthrough fragment shader
static const char* PASSTHROUGH_FRAGMENT =
    "precision mediump float;\n"
    "uniform sampler2D u_texture;\n"
    "varying vec2 v_texcoord;\n"
    "void main() {\n"
    "    gl_FragColor = texture2D(u_texture, v_texcoord);\n"
    "}\n";

// ---------------------------------------------------------------------------
// GpuVideoFilter — manages EGL context + GLES2 shader pipeline
//
// Performance optimizations vs. original:
//  1. No per-frame eglMakeCurrent — tracks owning thread ID
//  2. Cached uniforms — only set when texSize/filter changes
//  3. No glClear — fullscreen quad covers every pixel
//  4. Atomic flag for fast-path (avoid mutex in renderFrame)
//  5. FBO resize only when source size changes
// ---------------------------------------------------------------------------

struct GpuVideoFilter {
    // Thread safety: init/setFilter/cleanup are called from UI thread,
    // renderFrame is called from emulation thread. The mutex serializes
    // state changes with rendering to prevent use-after-cleanup races.
    // renderFrame uses an atomic check for the fast path (no contention).
    std::mutex mtx;
    std::atomic<bool> atomInit{false};

    // EGL state
    EGLDisplay eglDisplay  = EGL_NO_DISPLAY;
    EGLSurface eglSurface  = EGL_NO_SURFACE;
    EGLContext eglContext  = EGL_NO_CONTEXT;
    EGLConfig  eglConfig   = nullptr;

    // GLES2 state
    GLuint xbrProgram      = 0;   // XBR shader program
    GLuint passProgram     = 0;   // passthrough program
    GLuint sourceTexture   = 0;   // emulator frame texture
    GLuint vbo             = 0;   // vertex buffer (pos + texcoord)
    GLuint fbo             = 0;   // framebuffer object for multi-pass
    GLuint fboTexture      = 0;   // FBO color attachment

    // Attribute/uniform locations (XBR program)
    GLint xbr_aPosition    = -1;
    GLint xbr_aTexCoord    = -1;
    GLint xbr_uTexture     = -1;
    GLint xbr_uTexSize     = -1;
    GLint xbr_uYWeight     = -1;
    GLint xbr_uEqThreshold = -1;
    GLint xbr_uScale       = -1;
    GLint xbr_uLv2Coeff    = -1;

    // Attribute/uniform locations (passthrough program)
    GLint pass_aPosition   = -1;
    GLint pass_aTexCoord   = -1;
    GLint pass_uTexture    = -1;

    // Filter state
    bool   initialized     = false;
    int    currentFilter   = 0;   // 0=none, 4=xbr, 7=xbr+dot, 8=4xbr, 9=4xbr+dot
    int    scaleFactor     = 2;   // 2 for xbr, 4 for 4xbr
    unsigned srcW          = 0;
    unsigned srcH          = 0;
    ANativeWindow* window  = nullptr;

    // Frame size tracking (re-upload texture only when size changes)
    unsigned lastTexW      = 0;
    unsigned lastTexH      = 0;

    // Cached uniform values — avoid redundant glUniform calls per frame.
    // These are set once in init/setFilter and only updated on size change.
    unsigned cachedTexW    = 0;
    unsigned cachedTexH    = 0;
    float   cachedScale    = 0.0f;

    // Thread that currently owns the EGL context.
    // We only call eglMakeCurrent when the thread changes (e.g., after init
    // or when the emulation thread renders the first frame).
    pthread_t contextOwner = 0;
    bool     hasContext    = false;

    // ARGB→RGBA conversion buffer (reused across frames to avoid allocation)
    std::vector<uint32_t> convertBuf;

    // Fast-forward state
    bool   fastForward     = false;
    int    ffFrameSkip     = 0;
    static constexpr int FF_MAX_SKIP = 4;

    // -----------------------------------------------------------------------
    // Make EGL context current on the calling thread (only if needed)
    // -----------------------------------------------------------------------
    bool ensureContext() {
        pthread_t self = pthread_self();
        if (hasContext && pthread_equal(contextOwner, self)) {
            return true; // already current on this thread
        }
        if (eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) != EGL_TRUE) {
            GPU_LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
            return false;
        }
        contextOwner = self;
        hasContext = true;
        return true;
    }

    // -----------------------------------------------------------------------
    // Initialize EGL + GLES2 from the ANativeWindow surface
    // -----------------------------------------------------------------------
    bool init(ANativeWindow* wnd, int filter, unsigned w, unsigned h) {
        std::lock_guard<std::mutex> lk(mtx);
        if (initialized) cleanupLocked();
        if (!wnd) return false;

        window = wnd;
        srcW = w;
        srcH = h;
        currentFilter = filter;
        scaleFactor = (filter == 8 || filter == 9) ? 4 : 2;
        cachedScale = (float)scaleFactor;

        ANativeWindow_acquire(window);

        // Set the window buffer geometry to match the scaled output size.
        // This ensures the EGL surface buffers are large enough for 2x/4x output.
        ANativeWindow_setBuffersGeometry(window,
            srcW * scaleFactor, srcH * scaleFactor,
            WINDOW_FORMAT_RGBAX_8888);

        // --- EGL initialization ---
        eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL_NO_DISPLAY) {
            GPU_LOGE("eglGetDisplay failed");
            return false;
        }

        EGLint major, minor;
        if (!eglInitialize(eglDisplay, &major, &minor)) {
            GPU_LOGE("eglInitialize failed");
            eglDisplay = EGL_NO_DISPLAY;
            return false;
        }

        // Choose EGL config — RGBA8888
        const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
            EGL_RED_SIZE,   8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE,  8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_STENCIL_SIZE, 0,
            EGL_NONE
        };

        EGLint numConfigs = 0;
        if (!eglChooseConfig(eglDisplay, configAttribs, &eglConfig, 1, &numConfigs)
            || numConfigs < 1) {
            GPU_LOGE("eglChooseConfig failed (numConfigs=%d)", numConfigs);
            cleanupEgl();
            return false;
        }

        // Create EGL window surface from ANativeWindow
        eglSurface = eglCreateWindowSurface(eglDisplay, eglConfig, window, nullptr);
        if (eglSurface == EGL_NO_SURFACE) {
            GPU_LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
            cleanupEgl();
            return false;
        }

        // Create EGL context with OpenGL ES 2.0
        const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
        eglContext = eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT, ctxAttribs);
        if (eglContext == EGL_NO_CONTEXT) {
            GPU_LOGE("eglCreateContext failed: 0x%x", eglGetError());
            cleanupEgl();
            return false;
        }

        // Make context current on this (UI) thread for shader compilation
        if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            GPU_LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
            cleanupEgl();
            return false;
        }
        contextOwner = pthread_self();
        hasContext = true;

        GPU_LOGI("EGL initialized: v%d.%d, surface %dx%d",
                 major, minor, ANativeWindow_getWidth(window), ANativeWindow_getHeight(window));

        // --- Compile shaders ---
        xbrProgram = compileProgram(XBR_VERTEX_SHADER, XBR_FRAGMENT_SHADER);
        if (!xbrProgram) {
            GPU_LOGE("XBR shader compilation failed");
            cleanupEgl();
            return false;
        }

        passProgram = compileProgram(PASSTHROUGH_VERTEX, PASSTHROUGH_FRAGMENT);
        if (!passProgram) {
            GPU_LOGE("Passthrough shader compilation failed");
            cleanupEgl();
            return false;
        }

        // Get attribute/uniform locations for XBR program
        xbr_aPosition    = glGetAttribLocation(xbrProgram,  "a_position");
        xbr_aTexCoord    = glGetAttribLocation(xbrProgram,  "a_texcoord");
        xbr_uTexture     = glGetUniformLocation(xbrProgram, "u_texture");
        xbr_uTexSize     = glGetUniformLocation(xbrProgram, "u_texSize");
        xbr_uYWeight     = glGetUniformLocation(xbrProgram, "u_yWeight");
        xbr_uEqThreshold = glGetUniformLocation(xbrProgram, "u_eqThreshold");
        xbr_uScale       = glGetUniformLocation(xbrProgram, "u_scale");
        xbr_uLv2Coeff    = glGetUniformLocation(xbrProgram, "u_lv2Coeff");

        // Get attribute/uniform locations for passthrough
        pass_aPosition = glGetAttribLocation(passProgram,  "a_position");
        pass_aTexCoord = glGetAttribLocation(passProgram,  "a_texcoord");
        pass_uTexture  = glGetUniformLocation(passProgram, "u_texture");

        // --- Create VBO: fullscreen quad (TRIANGLE_STRIP) ---
        // Position (x,y) + TexCoord (s,t) per vertex, 4 vertices
        //   v0: (-1,-1) tex (0,1)   v1: (1,-1) tex (1,1)
        //   v2: (-1, 1) tex (0,0)   v3: (1, 1) tex (1,0)
        const float vboData[] = {
            -1.f, -1.f,  0.f, 1.f,
             1.f, -1.f,  1.f, 1.f,
            -1.f,  1.f,  0.f, 0.f,
             1.f,  1.f,  1.f, 0.f,
        };
        glGenBuffers(1, &vbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(vboData), vboData, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        // --- Create source texture ---
        glGenTextures(1, &sourceTexture);
        glBindTexture(GL_TEXTURE_2D, sourceTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        // --- Create FBO for 4xBR multi-pass ---
        glGenFramebuffers(1, &fbo);
        glGenTextures(1, &fboTexture);
        glBindTexture(GL_TEXTURE_2D, fboTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        // Allocate FBO texture at 2x intermediate size
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, srcW * 2, srcH * 2, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glBindTexture(GL_TEXTURE_2D, 0);

        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, fboTexture, 0);
        GLenum fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
            GPU_LOGW("FBO not complete: 0x%x — 4xBR will use single-pass fallback", fboStatus);
            glDeleteFramebuffers(1, &fbo); fbo = 0;
            glDeleteTextures(1, &fboTexture); fboTexture = 0;
        }

        // Disable depth test — not needed for 2D rendering
        glDisable(GL_DEPTH_TEST);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        initialized = true;
        atomInit.store(true, std::memory_order_release);

        // Set static XBR uniforms once (they never change)
        // texSize and scale will be set per-frame when they change
        glUseProgram(xbrProgram);
        glUniform1f(xbr_uYWeight, 48.0f);
        glUniform1f(xbr_uEqThreshold, 25.0f);
        glUniform1f(xbr_uLv2Coeff, 2.0f);
        glUseProgram(0);

        // Initialize cached texSize to force first-frame update
        cachedTexW = 0;
        cachedTexH = 0;

        GPU_LOGI("GPU filter initialized: filter=%d, scale=%dx, src=%dx%d",
                 filter, scaleFactor, w, h);
        return true;
    }

    // -----------------------------------------------------------------------
    // Render one frame through the GPU XBR shader pipeline
    // -----------------------------------------------------------------------
    void renderFrame(const uint32_t* frame, unsigned w, unsigned h, size_t stride) {
        // Fast path: atomic check without mutex (hot path — every frame)
        if (!atomInit.load(std::memory_order_acquire) || !frame) return;

        // Fast-forward: skip some frames
        if (fastForward) {
            if (ffFrameSkip++ % FF_MAX_SKIP != 0) return;
        }

        // Make EGL context current on the emulation thread (only on first
        // call or if thread changed — much cheaper than calling every frame)
        if (!ensureContext()) return;

        // Upload emulator framebuffer as GL texture.
        // Input is ARGB 0xAARRGGBB. On little-endian ARM, bytes in memory
        // are [BB] [GG] [RR] [AA]. GL_BGRA_EXT+UNSIGNED_BYTE reads as
        // [B] [G] [R] [A] which matches our layout perfectly — zero-copy.
        glBindTexture(GL_TEXTURE_2D, sourceTexture);

        bool sizeChanged = (w != lastTexW || h != lastTexH);
        if (sizeChanged) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                         GL_BGRA_EXT, GL_UNSIGNED_BYTE, nullptr);
            lastTexW = w;
            lastTexH = h;
        }

        if (stride == w) {
            // Contiguous: upload entire image at once (zero-copy with BGRA)
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h,
                            GL_BGRA_EXT, GL_UNSIGNED_BYTE, frame);
        } else {
            // Strided: upload row by row
            for (unsigned y = 0; y < h; ++y) {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y, w, 1,
                                GL_BGRA_EXT, GL_UNSIGNED_BYTE,
                                frame + y * stride);
            }
        }
        glBindTexture(GL_TEXTURE_2D, 0);

        // Render through the XBR shader
        if (scaleFactor == 4 && fbo != 0) {
            render4xBR(w, h);
        } else {
            render2xBR(w, h);
        }

        // Swap buffers (present to screen)
        eglSwapBuffers(eglDisplay, eglSurface);
    }

    // -----------------------------------------------------------------------
    // Set the video filter (can be called at runtime to switch filters)
    // -----------------------------------------------------------------------
    bool setFilter(int filter) {
        std::lock_guard<std::mutex> lk(mtx);
        if (!initialized) return false;

        int newScale = (filter == 8 || filter == 9) ? 4 : 2;
        currentFilter = filter;
        scaleFactor = newScale;
        cachedScale = (float)newScale;

        // Update scale uniform if context is current
        if (hasContext) {
            glUseProgram(xbrProgram);
            glUniform1f(xbr_uScale, cachedScale);
            glUseProgram(0);
        }

        GPU_LOGI("GPU filter changed: filter=%d, scale=%dx", filter, newScale);
        return true;
    }

    // -----------------------------------------------------------------------
    // Cleanup all GPU resources
    // -----------------------------------------------------------------------
    void cleanup() {
        std::lock_guard<std::mutex> lk(mtx);
        cleanupLocked();
    }

    void cleanupLocked() {
        if (!initialized && eglDisplay == EGL_NO_DISPLAY) return;

        atomInit.store(false, std::memory_order_release);

        // Make context current for resource cleanup
        if (eglDisplay != EGL_NO_DISPLAY && eglContext != EGL_NO_CONTEXT) {
            eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

            if (xbrProgram)  { glDeleteProgram(xbrProgram);  xbrProgram = 0; }
            if (passProgram) { glDeleteProgram(passProgram); passProgram = 0; }
            if (sourceTexture) { glDeleteTextures(1, &sourceTexture); sourceTexture = 0; }
            if (vbo) { glDeleteBuffers(1, &vbo); vbo = 0; }
            if (fbo) { glDeleteFramebuffers(1, &fbo); fbo = 0; }
            if (fboTexture) { glDeleteTextures(1, &fboTexture); fboTexture = 0; }
        }

        cleanupEgl();

        if (window) {
            ANativeWindow_release(window);
            window = nullptr;
        }

        initialized = false;
        hasContext = false;
        currentFilter = 0;
        scaleFactor = 2;
        lastTexW = 0;
        lastTexH = 0;
        cachedTexW = 0;
        cachedTexH = 0;
        GPU_LOGI("GPU filter cleanup complete");
    }

    // -----------------------------------------------------------------------
    // Check if the filter is a GPU-accelerable one
    // -----------------------------------------------------------------------
    static bool isGpuFilter(int filter) {
        return filter == 4 || filter == 7 || filter == 8 || filter == 9;
    }

private:
    // -----------------------------------------------------------------------
    // Set texSize and scale uniforms only when they change
    // -----------------------------------------------------------------------
    void updateUniforms(unsigned w, unsigned h, float scale) {
        if (w != cachedTexW || h != cachedTexH || scale != cachedScale) {
            glUniform2f(xbr_uTexSize, (float)w, (float)h);
            glUniform1f(xbr_uScale, scale);
            cachedTexW = w;
            cachedTexH = h;
            cachedScale = scale;
        }
    }

    // -----------------------------------------------------------------------
    // Render 2xBR: single-pass shader rendering to screen
    // -----------------------------------------------------------------------
    void render2xBR(unsigned w, unsigned h) {
        const unsigned outW = w * 2;
        const unsigned outH = h * 2;

        // Render directly to screen (default framebuffer = 0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, outW, outH);
        // No glClear — the fullscreen quad covers every pixel

        glUseProgram(xbrProgram);

        // Bind VBO
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(xbr_aPosition);
        glVertexAttribPointer(xbr_aPosition, 2, GL_FLOAT, GL_FALSE, 16, (void*)0);
        glEnableVertexAttribArray(xbr_aTexCoord);
        glVertexAttribPointer(xbr_aTexCoord, 2, GL_FLOAT, GL_FALSE, 16, (void*)8);

        // Bind texture
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sourceTexture);
        glUniform1i(xbr_uTexture, 0);

        // Set uniforms (only if changed)
        updateUniforms(w, h, 2.0f);

        // Draw fullscreen quad
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(xbr_aPosition);
        glDisableVertexAttribArray(xbr_aTexCoord);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glUseProgram(0);
    }

    // -----------------------------------------------------------------------
    // Render 4xBR: two-pass shader rendering
    //   Pass 1: source → FBO at 2x (first 2xBR upscale)
    //   Pass 2: FBO texture → screen at 2x (second 2xBR upscale)
    // -----------------------------------------------------------------------
    void render4xBR(unsigned w, unsigned h) {
        const unsigned midW = w * 2;
        const unsigned midH = h * 2;
        const unsigned outW = w * 4;
        const unsigned outH = h * 4;

        // Resize FBO texture if source size changed
        if (w != cachedTexW || h != cachedTexH) {
            glBindTexture(GL_TEXTURE_2D, fboTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, midW, midH, 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            glBindTexture(GL_TEXTURE_2D, 0);
        }

        // --- Pass 1: Render to FBO at 2x intermediate size ---
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, midW, midH);
        // No glClear — fullscreen quad covers all pixels

        glUseProgram(xbrProgram);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(xbr_aPosition);
        glVertexAttribPointer(xbr_aPosition, 2, GL_FLOAT, GL_FALSE, 16, (void*)0);
        glEnableVertexAttribArray(xbr_aTexCoord);
        glVertexAttribPointer(xbr_aTexCoord, 2, GL_FLOAT, GL_FALSE, 16, (void*)8);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sourceTexture);
        glUniform1i(xbr_uTexture, 0);

        // Set uniforms for pass 1 (source → 2x)
        updateUniforms(w, h, 2.0f);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(xbr_aPosition);
        glDisableVertexAttribArray(xbr_aTexCoord);

        // --- Pass 2: Render FBO texture to screen at 2x ---
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, outW, outH);
        // No glClear — fullscreen quad covers all pixels

        // Re-use XBR shader with FBO texture as input
        glEnableVertexAttribArray(xbr_aPosition);
        glVertexAttribPointer(xbr_aPosition, 2, GL_FLOAT, GL_FALSE, 16, (void*)0);
        glEnableVertexAttribArray(xbr_aTexCoord);
        glVertexAttribPointer(xbr_aTexCoord, 2, GL_FLOAT, GL_FALSE, 16, (void*)8);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fboTexture);
        glUniform1i(xbr_uTexture, 0);

        // Set uniforms for pass 2 (2x intermediate → 4x output)
        // texSize is the intermediate size (midW, midH)
        if (midW != cachedTexW || midH != cachedTexH) {
            glUniform2f(xbr_uTexSize, (float)midW, (float)midH);
            cachedTexW = midW;
            cachedTexH = midH;
        }

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(xbr_aPosition);
        glDisableVertexAttribArray(xbr_aTexCoord);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glUseProgram(0);
    }

    // -----------------------------------------------------------------------
    // Compile a GLSL shader program from vertex + fragment source
    // -----------------------------------------------------------------------
    GLuint compileProgram(const char* vsSrc, const char* fsSrc) {
        GLuint vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, 1, &vsSrc, nullptr);
        glCompileShader(vs);
        if (!checkShader(vs, "vertex")) {
            glDeleteShader(vs);
            return 0;
        }

        GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, 1, &fsSrc, nullptr);
        glCompileShader(fs);
        if (!checkShader(fs, "fragment")) {
            glDeleteShader(vs);
            glDeleteShader(fs);
            return 0;
        }

        GLuint prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);

        GLint linked = 0;
        glGetProgramiv(prog, GL_LINK_STATUS, &linked);
        if (!linked) {
            char log[512];
            glGetProgramInfoLog(prog, sizeof(log), nullptr, log);
            GPU_LOGE("Program link failed: %s", log);
            glDeleteProgram(prog);
            glDeleteShader(vs);
            glDeleteShader(fs);
            return 0;
        }

        // Shaders can be detached/deleted after linking
        glDetachShader(prog, vs);
        glDetachShader(prog, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);

        return prog;
    }

    bool checkShader(GLuint shader, const char* type) {
        GLint compiled = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            char log[1024];
            glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
            GPU_LOGE("%s shader compile failed: %s", type, log);
            return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Cleanup EGL resources (called from cleanup() and failed init())
    // -----------------------------------------------------------------------
    void cleanupEgl() {
        if (eglDisplay == EGL_NO_DISPLAY) return;

        // Make no context current (releases GPU resources)
        eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        hasContext = false;

        if (eglSurface != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = EGL_NO_SURFACE;
        }
        if (eglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(eglDisplay, eglContext);
            eglContext = EGL_NO_CONTEXT;
        }
        eglTerminate(eglDisplay);
        eglDisplay = EGL_NO_DISPLAY;
    }
};

} // namespace gpufilter
