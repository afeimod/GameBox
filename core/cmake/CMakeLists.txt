cmake_minimum_required(VERSION 3.22.1)
project("nesstation-core" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- libretro-fceumm sources ---
# We use the upstream libretro-fceumm as a source-only dependency: drop the
# libretro_* frontend, keep the core, and use our own JNI/IO/Audio glue.
set(FCEUMM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../fceumm")

if(NOT EXISTS "${FCEUMM_DIR}/src/fceu.c")
    message(FATAL_ERROR
        "FCEUmm sources not found at ${FCEUMM_DIR}. "
        "Either `git submodule update --init --recursive` or set "
        "`-PuseStubCore=true` in gradle.properties to use the built-in stub core.")
endif()

file(GLOB FCEUMM_C_SOURCES CONFIGURE_DEPENDS
    "${FCEUMM_DIR}/src/*.c"
)
file(GLOB FCEUMM_CPP_SOURCES CONFIGURE_DEPENDS
    "${FCEUMM_DIR}/src/*.cpp"
)
file(GLOB FCEUMM_BOARD_SOURCES CONFIGURE_DEPENDS
    "${FCEUMM_DIR}/src/boards/*.c"
)

# Exclude the libretro frontend (we have our own).
list(REMOVE_ITEM FCEUMM_C_SOURCES
    "${FCEUMM_DIR}/src/libretro.c"
    "${FCEUMM_DIR}/src/libretro-common.c"
    "${FCEUMM_DIR}/src/libretro_callbacks.c"
)

set(APP_JNI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jni")
set(APP_JNI_SOURCES
    ${APP_JNI_DIR}/bridge.cpp
    ${APP_JNI_DIR}/audio_backend.cpp
    ${APP_JNI_DIR}/input_backend.cpp
    ${APP_JNI_DIR}/video_backend.cpp
    ${APP_JNI_DIR}/rom_loader.cpp
)

add_library(nescore SHARED
    ${FCEUMM_C_SOURCES}
    ${FCEUMM_CPP_SOURCES}
    ${FCEUMM_BOARD_SOURCES}
    ${APP_JNI_SOURCES}
)

target_include_directories(nescore PRIVATE
    ${FCEUMM_DIR}/src
    ${FCEUMM_DIR}/src/boards
    ${APP_JNI_DIR}
)

target_compile_definitions(nescore PRIVATE
    FCEU_USE_LIBRETRO=0
    FCEU_NO_LIBRESSL=1
    HAVE_STDINT_H=1
    HAVE_INTTYPES_H=1
    _GNU_SOURCE=1
    NDEBUG=1
)

# Strip logging
target_compile_options(nescore PRIVATE
    -O2 -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -Wno-unused-parameter -Wno-unused-variable
    -Wno-unused-but-set-variable
)

target_link_libraries(nescore
    log
    android
    OpenSLES
    GLESv2
)

# Output to app's jniLibs
set_target_properties(nescore PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../app/src/main/jniLibs/${ANDROID_ABI}"
)

