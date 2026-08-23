// OpenGL renderer disabled.
//
// The melonDS 1.1 (2025) core no longer exposes the old GPU::InitRenderer /
// OpenGL::BuildShaderProgram / GPU::CurGLCompositor API this wrapper was based
// on. To keep the build simple and reliable, we force software rendering
// (the default SoftRenderer) and read back frames through GPU::Framebuffer,
// which libretro.cpp's software path already does.

#include "opengl.h"

#include "libretro_state.h"

// Defined in libretro.cpp
extern bool enable_opengl;
extern bool using_opengl;
extern bool refresh_opengl;

bool initialize_opengl()
{
   enable_opengl = false;
   using_opengl = false;
   return false;
}

void deinitialize_opengl_renderer(void)
{
   using_opengl = false;
}

void render_opengl_frame(bool sw)
{
}