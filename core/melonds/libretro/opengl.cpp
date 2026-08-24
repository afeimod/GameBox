// SPDX-License-Identifier: MIT
// OpenGL renderer handling for the melonDS libretro wrapper.
//
// The OpenGL 3D renderer (GPU3D_OpenGL / GLRenderer) requires:
//   - an EGL context created on the emulation thread, and
//   - the glad_glXxx function-pointer table initialized via
//     gladLoadGLLoader() (the core resolves every GL call through it).
//
// Neither exists on this build: the wrapper never sends
// RETRO_ENVIRONMENT_SET_HW_RENDER, so nds_loader.cpp's createEglContext()
// is never triggered and glad is never initialized. Calling
// GLRenderer::New() in that state jumps through NULL GL pointers and
// SIGSEGVs at pc=0 (that was the loadRom crash).
//
// Therefore initialize_opengl() here forces the software 3D renderer
// (GPU3D_Soft), which is the known-good path on Android. This mirrors
// the stable behavior from the cc98d3b fix.

#include "opengl.h"
#include "screenlayout.h"

#include "NDS.h"
#include "GPU.h"
#include "GPU3D_OpenGL.h"

#include <android/log.h>

#define TAG "melonds-gl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// The NDS instance is created in libretro.cpp's retro_load_game().
// We access it here to set the OpenGL renderer on the GPU.
// Declared as non-static in libretro.cpp.
extern melonDS::NDS* nds;

// Keep a reference to the GLRenderer so we can update its scale factor
// at runtime when the user changes the resolution option.
static melonDS::GLRenderer* s_glRenderer = nullptr;

bool initialize_opengl()
{
    if (using_opengl)
    {
        // Already initialized — this is the normal case when
        // render_frame() calls initialize_opengl() again.
        return true;
    }

    if (!nds)
    {
        LOGE("initialize_opengl: nds is null, cannot set renderer");
        enable_opengl = false;
        return false;
    }

    // OpenGL renderer is NOT available on this Android build.
    //
    // The melonDS core resolves every GL call through the glad_glXxx
    // function-pointer table (PlatformOGL.h → frontend/glad/glad.h).
    // Those pointers are only populated by gladLoadGLLoader(), which runs
    // inside createEglContext() in nds_loader.cpp — and that handler is
    // only triggered when the core sends RETRO_ENVIRONMENT_SET_HW_RENDER.
    // This wrapper never sends SET_HW_RENDER and never creates an EGL
    // context, so every glad_glXxx pointer stays NULL.
    //
    // GLRenderer::New() immediately calls glEnable/glDepthRangef/... through
    // those pointers; with NDEBUG the built-in assert(glEnable != nullptr)
    // is compiled out, so execution jumps to address 0 — SIGSEGV at
    // pc=0x0 inside retro_load_game() (observed under loadRom).
    //
    // The bundled glad is also generated for *desktop* OpenGL 4.3
    // (APIs: gl=4.3); entry points used by the core such as
    // glBindFragDataLocation / glFramebufferTexture do not exist in an
    // OpenGL ES context anyway, so the GL path cannot work here as-is.
    //
    // Fall back to the known-good software 3D renderer (GPU3D_Soft).
    // check_variables(true) has already updated the screen layout with
    // opengl=true (4x) — libretro.cpp's _handle_load_game re-runs
    // update_screenlayout() with the new enable_opengl=false (1x) right
    // after this function returns.
    LOGE("initialize_opengl: OpenGL renderer unavailable on Android "
         "(no EGL context / glad not initialized) — "
         "forcing software 3D renderer");
    enable_opengl = false;
    using_opengl = false;
    return false;
}

void deinitialize_opengl_renderer(void)
{
    if (!using_opengl)
        return;

    LOGI("deinitialize_opengl_renderer: resetting to software renderer");

    if (nds)
    {
        // Reset to the default SoftRenderer.
        // SetRenderer3D with nullptr creates a new SoftRenderer internally.
        nds->GPU.SetRenderer3D(nullptr);
    }

    s_glRenderer = nullptr;
    using_opengl = false;
}

void render_opengl_frame(bool /*sw*/)
{
    // Handle dynamic resolution scale changes at runtime.
    // When the user changes the melonds_opengl_resolution option,
    // check_variables(false) sets video_settings.GL_ScaleFactor and
    // refresh_opengl = true. We apply the new scale factor here.
    if (refresh_opengl)
    {
        refresh_opengl = false;

        if (s_glRenderer)
        {
            int newScale = video_settings.GL_ScaleFactor;
            if (s_glRenderer->GetScaleFactor() != newScale)
            {
                s_glRenderer->SetScaleFactor(newScale);
                LOGI("render_opengl_frame: updated scale factor to %d", newScale);
            }
        }
    }

    // This function is called from libretro.cpp's render_frame() when
    // using_opengl is true. The melonDS core's GLRenderer handles all
    // rendering internally via NDS::Run(), so we don't need to do
    // anything else here — the renderer's RenderFrame() is called by
    // the emulation loop.
    //
    // The `sw` parameter: if true, the core requests a software fallback
    // frame from the OpenGL renderer (read-back mode). The GLRenderer
    // handles this internally.
}