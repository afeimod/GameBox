// SPDX-License-Identifier: MIT
// JNI bridge for FCEUmm core
//
// Thin wrapper around the libretro frontend in rom_loader.cpp. Kotlin owns
// the emulation thread and pulls frames / audio on demand — no native-side
// threads or callbacks, which keeps the lifecycle simple and crash-free.

#include "bridge.h"
#include "rom_loader.h"

#include <jni.h>
#include <android/log.h>
#include <cstring>

#define TAG "nescore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace nescore {

Engine& Engine::instance() {
    static Engine e;
    return e;
}

bool Engine::loadRom(const std::string& path) {
    int region = 0;
    auto err = rom::loadFromFile(path, region);
    if (!err.empty()) {
        lastError_ = err;
        LOGE("loadRom failed: %s", err.c_str());
        return false;
    }
    lastError_.clear();
    LOGI("ROM loaded OK: %s (region=%d, rate=%d)",
         path.c_str(), region, rom::audioSampleRate());
    return true;
}

void Engine::unload()       { rom::unload(); }
void Engine::reset(bool h)  { rom::resetEmulation(h); }
void Engine::runFrame()     { rom::stepFrame(); }
void Engine::shutdown()     { rom::unload(); }

void Engine::setPad1(int bits)        { rom::setControllerInput(0, (uint8_t)bits); }
void Engine::setRegion(int region)    { rom::applyRegion(region); }
void Engine::setSampleRate(int hz)    { rom::applySampleRate(hz); }
void Engine::setFastForward(bool on)  { rom::applySpeed(on ? 4.0f : 1.0f); }

void Engine::saveState(int slot, const std::string& path) {
    rom::saveStateToPath(slot, path);
}

bool Engine::loadState(int slot, const std::string& path) {
    return rom::loadStateFromPath(slot, path);
}

bool Engine::getFrameBuffer(uint32_t* out, int w, int h) {
    return rom::copyFramebufferARGB(out, w, h);
}

int Engine::readAudio(int16_t* out, int maxFrames) {
    return rom::readAudio(out, maxFrames);
}

int Engine::audioSampleRate() {
    return rom::audioSampleRate();
}

void Engine::setPaths(const std::string& systemDir, const std::string& saveDir) {
    rom::setPaths(systemDir, saveDir);
}

} // namespace nescore

// ---------------------------------------------------------------------------
// JNI surface — mirrors NesNative.kt exactly
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_loadRom(JNIEnv* env, jclass, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = nescore::Engine::instance().loadRom(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_unload(JNIEnv*, jclass) {
    nescore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_reset(JNIEnv*, jclass, jboolean hard) {
    nescore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_runFrame(JNIEnv*, jclass) {
    nescore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setPad1(JNIEnv*, jclass, jint bits) {
    nescore::Engine::instance().setPad1(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setRegion(JNIEnv*, jclass, jint region) {
    nescore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setSampleRate(JNIEnv*, jclass, jint rate) {
    nescore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setFastForward(JNIEnv*, jclass, jboolean on) {
    nescore::Engine::instance().setFastForward(on == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_saveState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    nescore::Engine::instance().saveState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_loadState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = nescore::Engine::instance().loadState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    jsize len = env->GetArrayLength(out);
    if (len < 256 * 240) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool fresh = nescore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(px), 256, 240);
    env->ReleaseIntArrayElements(out, px, 0); // commit back
    return fresh ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NesNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    int maxFrames = len / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int n = nescore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), maxFrames);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NesNative_audioSampleRate(JNIEnv*, jclass) {
    return nescore::Engine::instance().audioSampleRate();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setPaths(JNIEnv* env, jclass, jstring systemDir, jstring saveDir) {
    const char* sys = systemDir ? env->GetStringUTFChars(systemDir, nullptr) : nullptr;
    const char* sav = saveDir   ? env->GetStringUTFChars(saveDir,   nullptr) : nullptr;
    nescore::Engine::instance().setPaths(sys ? sys : "", sav ? sav : "");
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_NesNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(nescore::Engine::instance().lastError().c_str());
}

} // extern "C"
