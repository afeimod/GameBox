cmake_minimum_required(VERSION 3.22.1)
project("nescore-stub" C CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(nescore SHARED src/fceumm_stub.cpp)
target_link_libraries(nescore log android)

# ---------------------------------------------------------------------------
# J2ME native libraries (libjavam3g.so + libmicro3d.so)
# ---------------------------------------------------------------------------
set(J2ME_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../j2me")

# libjavam3g.so — M3G/JSR-184 3D engine (GLES 1.1)
add_library(javam3g SHARED
    "${J2ME_NATIVE_DIR}/m3g/CSynchronization.cpp"
    "${J2ME_NATIVE_DIR}/m3g/m3g_android_java_api.cpp"
    "${J2ME_NATIVE_DIR}/m3g/src/m3g_core.c"
    "${J2ME_NATIVE_DIR}/m3g/src/m3g_android.cpp"
    "${J2ME_NATIVE_DIR}/m3g/src/m3g_android_gl.cpp"
)
target_include_directories(javam3g PRIVATE "${J2ME_NATIVE_DIR}/m3g/inc/")
target_compile_definitions(javam3g PRIVATE M3G_TARGET_ANDROID M3G_GL_ES_1_1)
target_compile_options(javam3g PRIVATE -O3 -Wno-unused-parameter -Wno-unused-variable)
target_link_libraries(javam3g log EGL GLESv1_CM z jnigraphics)

# libmicro3d.so — Mascot Capsule 3D helper (GLES 2.0)
add_library(micro3d SHARED
    "${J2ME_NATIVE_DIR}/micro3d/src/utils.cpp"
)
target_include_directories(micro3d PRIVATE "${J2ME_NATIVE_DIR}/micro3d/inc")
target_link_libraries(micro3d log GLESv2 jnigraphics)
