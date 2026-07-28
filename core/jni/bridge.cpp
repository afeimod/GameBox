// SPDX-License-Identifier: MIT
// JNI bridge for FCEUmm core
//
// Wraps FCEUmm (FCEUX core) behind a minimal stable C++ interface, exposing
// it to the JVM via JNI. We deliberately hide the libretro-specific bits so
// the Kotlin side can stay clean.

#include "bridge.h"
#include "video_backend.h"
#include "audio_backend.h"
#include "rom_loader.h"

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <memory>
#include <mutex>

#define TAG "nescore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace nescore {

Engine& Engine::instance() {
    static Engine e;
    return e;
}

static std::mutex g_stateMtx;

bool Engine::loadRom(const std::string& path) {
    std::lock_guard<std::mutex> lk(g_stateMtx);
    if (running_.exchange(true)) {
        // already running -> reset
        running_ = false;
    }

    auto err = rom::loadFromFile(path, region_);
    if (!err.empty()) {
        lastError_ = err;
        running_ = false;
        return false;
    }
    lastError_.clear();

    if (!frameBuffer_) {
        frameBuffer_ = new uint8_t[256 * 240 * 4]; // RGBA
    }
    std::memset(frameBuffer_, 0, 256 * 240 * 4);

    if (audioCb_) {
        audioThread_ = std::thread([this] { audioThread(); });
    }
    LOGI("ROM loaded: %s region=%d", path.c_str(), region_);
    return true;
}

void Engine::unload() {
    {
        std::lock_guard<std::mutex> lk(g_stateMtx);
        running_ = false;
    }
    if (audioThread_.joinable()) audioThread_.join();
    rom::unload();
    lastError_.clear();
}

void Engine::reset(bool hard) {
    std::lock_guard<std::mutex> lk(g_stateMtx);
    rom::resetEmulation(hard);
}

void Engine::shutdown() {
    unload();
    if (frameBuffer_) {
        delete[] frameBuffer_;
        frameBuffer_ = nullptr;
    }
}

void Engine::setPad1(const NesPad& pad) {
    pad1_ = pad;
    rom::setControllerInput(0, *reinterpret_cast<const uint8_t*>(&pad));
}

void Engine::setRegion(int region) {
    std::lock_guard<std::mutex> lk(g_stateMtx);
    region_ = region;
    rom::applyRegion(region);
}

void Engine::setSampleRate(int hz) {
    sampleRate_ = hz;
    rom::applySampleRate(hz);
}

void Engine::setFastForward(bool on) {
    fastForward_ = on;
    rom::applySpeed(on ? 4.0f : 1.0f);
}

void Engine::runFrame() {
    if (!running_.load()) return;
    std::lock_guard<std::mutex> lk(g_stateMtx);
    rom::stepFrame();
    renderFrameToBuffer();
    if (videoCb_) {
        videoCb_(frameBuffer_, 256, 240, 256 * 4, /*pts*/0);
    }
}

void Engine::renderFrameToBuffer() {
    // In a real integration we'd pull a BGRA buffer from FCEUmm's
    // PPU emulation (XBuf). For now we keep a placeholder so the
    // JNI surface stays compilable; the Kotlin side blits a stub frame
    // when the engine signals "not yet rendering".
    rom::copyFramebufferBGRA(frameBuffer_, 256, 240);
}

void Engine::saveState(int slot, const std::string& dstPath) {
    std::lock_guard<std::mutex> lk(g_stateMtx);
    rom::saveStateToPath(slot, dstPath);
}

bool Engine::loadState(int slot, const std::string& srcPath) {
    std::lock_guard<std::mutex> lk(g_stateMtx);
    return rom::loadStateFromPath(slot, srcPath);
}

void Engine::audioThread() {
    constexpr int kBufFrames = 1024;
    int16_t buffer[kBufFrames * 2];
    while (running_.load()) {
        fillAudioBuffer(buffer, kBufFrames);
        if (audioCb_) {
            audioCb_(buffer, kBufFrames, sampleRate_, /*pts*/0);
        }
    }
}

void Engine::fillAudioBuffer(int16_t* out, int frames) {
    rom::mixAudio(out, frames);
}

} // namespace nescore

// ---------------------------------------------------------------------------
// JNI surface
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_loadRom(JNIEnv* env, jclass /*clazz*/, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = nescore::Engine::instance().loadRom(cpath);
    env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_unload(JNIEnv* /*env*/, jclass /*clazz*/) {
    nescore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_reset(JNIEnv* /*env*/, jclass /*clazz*/, jboolean hard) {
    nescore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_runFrame(JNIEnv* /*env*/, jclass /*clazz*/) {
    nescore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setPad1(JNIEnv* /*env*/, jclass /*clazz*/, jint bits) {
    nescore::NesPad pad{};
    pad.a      = (bits >> 0) & 1;
    pad.b      = (bits >> 1) & 1;
    pad.select = (bits >> 2) & 1;
    pad.start  = (bits >> 3) & 1;
    pad.up     = (bits >> 4) & 1;
    pad.down   = (bits >> 5) & 1;
    pad.left   = (bits >> 6) & 1;
    pad.right  = (bits >> 7) & 1;
    nescore::Engine::instance().setPad1(pad);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setRegion(JNIEnv* /*env*/, jclass /*clazz*/, jint region) {
    nescore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setSampleRate(JNIEnv* /*env*/, jclass /*clazz*/, jint rate) {
    nescore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setFastForward(JNIEnv* /*env*/, jclass /*clazz*/, jboolean on) {
    nescore::Engine::instance().setFastForward(on == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_saveState(JNIEnv* env, jclass /*clazz*/, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    nescore::Engine::instance().saveState(slot, cpath);
    env->ReleaseStringUTFChars(path, cpath);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_loadState(JNIEnv* env, jclass /*clazz*/, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = nescore::Engine::instance().loadState(slot, cpath);
    env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_NesNative_lastError(JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(nescore::Engine::instance().lastError().c_str());
}

} // extern "C"
