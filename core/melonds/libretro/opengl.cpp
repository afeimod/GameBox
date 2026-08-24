// SPDX-License-Identifier: MIT
// OpenGL renderer initialization for the melonDS libretro wrapper.
//
// Creates a GLRenderer instance (GPU3D_OpenGL) and sets it on the NDS
// GPU, enabling hardware-accelerated 3D rendering and resolution scaling.
//
// The melonDS core provides:
//   - GLCompositor::New()   — creates a compositor for screen blending
//   - GLRenderer::New()     — creates the OpenGL 3D renderer
//   - GPU::SetRenderer3D()  — sets the renderer on the NDS GPU
//
// These are available in the melonDS 1.1 source under
// core/melonds_temp/melonDS-android-lib-master/src/.
//
// NOTE: The EGL/GLES context must be current on the calling thread
// before any of these functions are called. The EGL context is created
// in nds_loader.cpp's cb_environment handler (SET_HW_RENDER) and is
// re-bound in stepFrame() via ensureEglContextCurrent().

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

    LOGI("initialize_opengl: creating GLRenderer...");

    auto renderer = melonDS::GLRenderer::New();
    if (!renderer)
    {
        LOGE("initialize_opengl: GLRenderer::New() failed — "
             "GLSL shaders may not be supported on this device");
        enable_opengl = false;
        return false;
    }

    // Apply the resolution scale from the current VideoSettings.
    // video_settings.GL_ScaleFactor was set by check_variables(true)
    // during retro_load_game() based on the melonds_opengl_resolution
    // core option.
    renderer->SetScaleFactor(video_settings.GL_ScaleFactor);
    LOGI("initialize_opengl: scale factor = %d", video_settings.GL_ScaleFactor);

    // Store the raw pointer for later runtime updates.
    s_glRenderer = renderer.get();

    nds->GPU.SetRenderer3D(std::move(renderer));

    using_opengl = true;
    refresh_opengl = false;
    LOGI("initialize_opengl: GLRenderer created and set successfully");
    return true;
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