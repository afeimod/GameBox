cmake_minimum_required(VERSION 3.22.1)
project("nesstation-core" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# libretro-fceumm (the best-in-class NES/Famicom core) is consumed as a
# source-only dependency. We drive it through the standard libretro API
# (retro_init / retro_load_game / retro_run ...) from our own JNI frontend
# in core/jni/rom_loader.cpp. This mirrors what upstream's jni/Android.mk
# produces, so the resulting libnescore.so behaves like the real FCEUmm core.
# ---------------------------------------------------------------------------

set(FCEUMM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../fceumm")
set(FCEUMM_SRC  "${FCEUMM_DIR}/src")

if(NOT EXISTS "${FCEUMM_SRC}/drivers/libretro/libretro.c")
    message(FATAL_ERROR
        "FCEUmm sources not found at ${FCEUMM_DIR}.\n"
        "Fix with:\n"
        "  git clone --depth 1 https://github.com/libretro/libretro-fceumm.git core/fceumm\n"
        "or build with the stub core: -PuseStubCore=true")
endif()

set(LIBRETRO_COMM "${FCEUMM_SRC}/drivers/libretro/libretro-common")

# --- Source set, copied 1:1 from Makefile.common (Android profile) ----------
# boards/ and input/ are wildcard-globbed exactly like the Makefile.
file(GLOB FCEUMM_BOARD_SOURCES CONFIGURE_DEPENDS "${FCEUMM_SRC}/boards/*.c")
file(GLOB FCEUMM_INPUT_SOURCES CONFIGURE_DEPENDS "${FCEUMM_SRC}/input/*.c")

# Core .c files are an explicit list in Makefile.common (NOT a glob), so we
# keep them explicit too in case upstream adds a non-compiled root file later.
set(FCEUMM_CORE_SOURCES
    "${FCEUMM_SRC}/cart.c"
    "${FCEUMM_SRC}/cheat.c"
    "${FCEUMM_SRC}/crc32.c"
    "${FCEUMM_SRC}/fceu-endian.c"
    "${FCEUMM_SRC}/fceu-memory.c"
    "${FCEUMM_SRC}/fceu.c"
    "${FCEUMM_SRC}/fds.c"
    "${FCEUMM_SRC}/fds_apu.c"
    "${FCEUMM_SRC}/file.c"
    "${FCEUMM_SRC}/filter.c"
    "${FCEUMM_SRC}/general.c"
    "${FCEUMM_SRC}/input.c"
    "${FCEUMM_SRC}/md5.c"
    "${FCEUMM_SRC}/nsf.c"
    "${FCEUMM_SRC}/palette.c"
    "${FCEUMM_SRC}/ppu.c"
    "${FCEUMM_SRC}/sound.c"
    "${FCEUMM_SRC}/state.c"
    "${FCEUMM_SRC}/video.c"
    "${FCEUMM_SRC}/vsuni.c"
    "${FCEUMM_SRC}/ines.c"
    "${FCEUMM_SRC}/unif.c"
    "${FCEUMM_SRC}/x6502.c"
    # NTSC filter (HAVE_NTSC_FILTER)
    "${FCEUMM_SRC}/ntsc/nes_ntsc.c"
    # libretro frontend (provides retro_init / retro_load_game / retro_run ...)
    "${FCEUMM_SRC}/drivers/libretro/libretro.c"
    "${FCEUMM_SRC}/drivers/libretro/libretro_dipswitch.c"
)

# libretro-common helpers. memory_stream is always linked (used by savestate
# code). The rest are the non-static frontend helpers (STATIC_LINKING=0).
# NOTE: HAVE_HDPACK is intentionally OFF — it matches upstream's Android.mk
# (only unix/osx/ios enable HD pack) and removes the risky image-format
# decoders (rpng/rwebp/rvp8/...) from the build.
set(FCEUMM_LIBRETRO_COMMON_SOURCES
    "${LIBRETRO_COMM}/streams/memory_stream.c"
    "${LIBRETRO_COMM}/compat/compat_posix_string.c"
    "${LIBRETRO_COMM}/compat/compat_snprintf.c"
    "${LIBRETRO_COMM}/compat/compat_strcasestr.c"
    "${LIBRETRO_COMM}/compat/compat_strl.c"
    "${LIBRETRO_COMM}/compat/fopen_utf8.c"
    "${LIBRETRO_COMM}/encodings/encoding_utf.c"
    "${LIBRETRO_COMM}/file/file_path.c"
    "${LIBRETRO_COMM}/file/file_path_io.c"
    "${LIBRETRO_COMM}/streams/file_stream.c"
    "${LIBRETRO_COMM}/streams/file_stream_transforms.c"
    "${LIBRETRO_COMM}/string/stdstring.c"
    "${LIBRETRO_COMM}/time/rtime.c"
    "${LIBRETRO_COMM}/vfs/vfs_implementation.c"
)

# --- Our JNI bridge + libretro frontend -------------------------------------
set(APP_JNI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jni")
set(APP_JNI_SOURCES
    "${APP_JNI_DIR}/bridge.cpp"
    "${APP_JNI_DIR}/rom_loader.cpp"
)

# --- HQX library (Maxim Stepin's HQ2X/HQ4X reference implementation) -------
set(HQX_DIR "${APP_JNI_DIR}/hqx")
set(HQX_SOURCES
    "${HQX_DIR}/init.c"
    "${HQX_DIR}/hq2x.c"
    "${HQX_DIR}/hq4x.c"
)

add_library(nescore SHARED
    ${FCEUMM_CORE_SOURCES}
    ${FCEUMM_BOARD_SOURCES}
    ${FCEUMM_INPUT_SOURCES}
    ${FCEUMM_LIBRETRO_COMMON_SOURCES}
    ${APP_JNI_SOURCES}
    ${HQX_SOURCES}
)

target_include_directories(nescore PRIVATE
    "${FCEUMM_SRC}"
    "${FCEUMM_SRC}/drivers/libretro"
    "${LIBRETRO_COMM}/include"
    "${FCEUMM_SRC}/boards"
    "${FCEUMM_SRC}/input"
    "${FCEUMM_SRC}/ntsc"
    "${APP_JNI_DIR}"
    "${HQX_DIR}"
)

# Defines mirror upstream's COREDEFINES (Android profile) plus our 32-bpp
# choice. FRONTEND_SUPPORTS_RGB888 makes the core emit XRGB8888 frames, which
# map to Android's Bitmap.Config.ARGB_8888 with a single | 0xFF000000.
target_compile_definitions(nescore PRIVATE
    __LIBRETRO__
    PATH_MAX=1024
    FCEU_VERSION_NUMERIC=9813
    PSS_STYLE=1
    FRONTEND_SUPPORTS_RGB888
    HAVE_NTSC_FILTER
    GIT_VERSION=\"local\"
    NDEBUG
)

target_compile_options(nescore PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-unused-but-set-variable
    -Wno-sign-compare
    -Wno-write-strings
    -Wno-missing-prototypes
)

# Android log + native_window (ANativeWindow) for hardware-accelerated surface
# rendering. Video frames are blitted directly to the SurfaceView's surface
# buffer, bypassing the JNI frame copy + Compose Canvas redraw overhead.
target_link_libraries(nescore log android)

# AGP's externalNativeBuild automatically picks up the .so from CMake's
# build output — no manual LIBRARY_OUTPUT_DIRECTORY needed.

# ---------------------------------------------------------------------------
# libretro-snes9x (the best-in-class SNES/SFC core) — compiled as a separate
# .so (libsnescore.so) to avoid retro_* symbol conflicts with libnescore.so.
# Driven through the standard libretro API, same pull-model as FCEUmm.
# ---------------------------------------------------------------------------
set(SNES9X_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../snes9x")
set(SNES9X_SRC "${SNES9X_DIR}")

if(EXISTS "${SNES9X_SRC}/libretro/libretro.cpp")
    set(SNES9X_CORE_SOURCES
        "${SNES9X_SRC}/filter/snes_ntsc.c"
        "${SNES9X_SRC}/apu/apu.cpp"
        "${SNES9X_SRC}/apu/bapu/dsp/sdsp.cpp"
        "${SNES9X_SRC}/apu/bapu/smp/smp.cpp"
        "${SNES9X_SRC}/apu/bapu/smp/smp_state.cpp"
        "${SNES9X_SRC}/bsx.cpp"
        "${SNES9X_SRC}/c4.cpp"
        "${SNES9X_SRC}/c4emu.cpp"
        "${SNES9X_SRC}/cheats.cpp"
        "${SNES9X_SRC}/cheats2.cpp"
        "${SNES9X_SRC}/clip.cpp"
        "${SNES9X_SRC}/conffile.cpp"
        "${SNES9X_SRC}/controls.cpp"
        "${SNES9X_SRC}/cpu.cpp"
        "${SNES9X_SRC}/cpuexec.cpp"
        "${SNES9X_SRC}/cpuops.cpp"
        "${SNES9X_SRC}/crosshairs.cpp"
        "${SNES9X_SRC}/dma.cpp"
        "${SNES9X_SRC}/dsp.cpp"
        "${SNES9X_SRC}/dsp1.cpp"
        "${SNES9X_SRC}/dsp2.cpp"
        "${SNES9X_SRC}/dsp3.cpp"
        "${SNES9X_SRC}/dsp4.cpp"
        "${SNES9X_SRC}/fxinst.cpp"
        "${SNES9X_SRC}/fxemu.cpp"
        "${SNES9X_SRC}/gfx.cpp"
        "${SNES9X_SRC}/globals.cpp"
        "${SNES9X_SRC}/memmap.cpp"
        "${SNES9X_SRC}/obc1.cpp"
        "${SNES9X_SRC}/msu1.cpp"
        "${SNES9X_SRC}/ppu.cpp"
        "${SNES9X_SRC}/stream.cpp"
        "${SNES9X_SRC}/sa1.cpp"
        "${SNES9X_SRC}/sa1cpu.cpp"
        "${SNES9X_SRC}/screenshot.cpp"
        "${SNES9X_SRC}/sdd1.cpp"
        "${SNES9X_SRC}/sdd1emu.cpp"
        "${SNES9X_SRC}/seta.cpp"
        "${SNES9X_SRC}/seta010.cpp"
        "${SNES9X_SRC}/seta011.cpp"
        "${SNES9X_SRC}/seta018.cpp"
        "${SNES9X_SRC}/snapshot.cpp"
        "${SNES9X_SRC}/snes9x.cpp"
        "${SNES9X_SRC}/spc7110.cpp"
        "${SNES9X_SRC}/srtc.cpp"
        "${SNES9X_SRC}/tile.cpp"
        "${SNES9X_SRC}/tileimpl-n1x1.cpp"
        "${SNES9X_SRC}/tileimpl-n2x1.cpp"
        "${SNES9X_SRC}/tileimpl-h2x1.cpp"
        "${SNES9X_SRC}/sha256.cpp"
        "${SNES9X_SRC}/bml.cpp"
        "${SNES9X_SRC}/movie.cpp"
        "${SNES9X_SRC}/fscompat.cpp"
        # libretro frontend (provides retro_init / retro_load_game / retro_run ...)
        "${SNES9X_SRC}/libretro/libretro.cpp"
    )

    set(SNES9X_JNI_SOURCES
        "${APP_JNI_DIR}/snes_bridge.cpp"
        "${APP_JNI_DIR}/snes_loader.cpp"
        ${HQX_SOURCES}
    )

    add_library(snescore SHARED
        ${SNES9X_CORE_SOURCES}
        ${SNES9X_JNI_SOURCES}
    )

    target_include_directories(snescore PRIVATE
        "${SNES9X_SRC}"
        "${SNES9X_SRC}/libretro"
        "${SNES9X_SRC}/apu"
        "${SNES9X_SRC}/apu/bapu"
        "${APP_JNI_DIR}"
        "${HQX_DIR}"
    )

    target_compile_definitions(snescore PRIVATE
        __LIBRETRO__
        ANDROID
        HAVE_STRINGS_H
        RIGHTSHIFT_IS_SAR
        NDEBUG
    )

    target_compile_options(snescore PRIVATE
        -O2
        -fvisibility=hidden
        -ffunction-sections -fdata-sections
        -fno-rtti
        -fno-exceptions
        -Wno-unused-parameter
        -Wno-unused-variable
        -Wno-sign-compare
        -Wno-write-strings
        -Wno-deprecated-declarations
    )

    target_link_libraries(snescore log android)
endif()

# ---------------------------------------------------------------------------
# mGBA (GB/GBC/GBA core) — built as a static library via its CMake, then
# linked with our JNI bridge + libretro frontend into libgbacore.so.
# Separate .so avoids retro_* symbol conflicts with the other cores.
# ---------------------------------------------------------------------------
set(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../mgba")

if(EXISTS "${MGBA_DIR}/CMakeLists.txt")
    # Build mGBA core as a static library (no frontends, no deps)
    set(LIBMGBA_ONLY ON CACHE BOOL "" FORCE)
    set(BINARY_NAME mgba CACHE INTERNAL "")
    add_subdirectory("${MGBA_DIR}" "${CMAKE_BINARY_DIR}/mgba-build" EXCLUDE_FROM_ALL)

    set(GBA_JNI_SOURCES
        "${APP_JNI_DIR}/gba_bridge.cpp"
        "${APP_JNI_DIR}/gba_loader.cpp"
        "${MGBA_DIR}/src/platform/libretro/libretro.c"
        ${HQX_SOURCES}
    )

    add_library(gbacore SHARED ${GBA_JNI_SOURCES})

    target_include_directories(gbacore PRIVATE
        "${MGBA_DIR}/include"
        "${MGBA_DIR}/src"
        "${MGBA_DIR}/src/platform/libretro"
        "${CMAKE_BINARY_DIR}/mgba-build/include"
        "${APP_JNI_DIR}"
        "${HQX_DIR}"
    )

    target_compile_definitions(gbacore PRIVATE
        __LIBRETRO__
        COLOR_16_BIT
        COLOR_5_6_5
        DISABLE_THREADING
        MGBA_STANDALONE
        MINIMAL_CORE=2
        ENABLE_VFS
        ANDROID
        NDEBUG
    )

    target_compile_options(gbacore PRIVATE
        -O2
        -fvisibility=hidden
        -ffunction-sections -fdata-sections
        -Wno-unused-parameter
        -Wno-unused-variable
        -Wno-sign-compare
        -Wno-deprecated-declarations
    )

    target_link_libraries(gbacore mgba log android)
endif()

# ---------------------------------------------------------------------------
# J2ME native libraries (libjavam3g.so + libmicro3d.so)
# Converted from J2ME-Loader's Android.mk to CMake
# ---------------------------------------------------------------------------
set(J2ME_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")

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
target_compile_options(javam3g PRIVATE -O3 -Wno-unused-parameter -Wno-unused-variable -Wno-int-conversion -Wno-void-pointer-to-int-cast -Wno-int-to-pointer-cast -Wno-shift-negative-value -Wno-pointer-to-int-cast)
target_link_libraries(javam3g log EGL GLESv1_CM z jnigraphics)

# libmicro3d.so — Mascot Capsule 3D helper (GLES 2.0)
add_library(micro3d SHARED
    "${J2ME_NATIVE_DIR}/micro3d/src/utils.cpp"
)
target_include_directories(micro3d PRIVATE "${J2ME_NATIVE_DIR}/micro3d/inc")
target_link_libraries(micro3d log GLESv2 jnigraphics)
