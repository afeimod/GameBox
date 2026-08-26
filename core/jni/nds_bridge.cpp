// SPDX-License-Identifier: MIT
// JNI bridge for melonDS (Nintendo DS) core.
//
// Thin wrapper around the libretro frontend in nds_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the other engines.

#include "nds_bridge.h"
#include "nds_loader.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>

#define TAG "ndscore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace ndscore {

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
    LOGI("NDS ROM loaded OK: %s (region=%d, rate=%d, %dx%d)",
         path.c_str(), region, rom::audioSampleRate(),
         rom::videoWidth(), rom::videoHeight());
    return true;
}

void Engine::unload()       { rom::unload(); }
void Engine::reset(bool h)  { rom::resetEmulation(h); }
void Engine::runFrame()     { rom::stepFrame(); }
void Engine::shutdown()     { rom::unload(); }

void Engine::setPad1(int bits)        { rom::setControllerInput(0, (uint16_t)bits); }
void Engine::setPad2(int bits)        { rom::setControllerInput(1, (uint16_t)bits); }
void Engine::setPad3(int bits)        { rom::setControllerInput(2, (uint16_t)bits); }
void Engine::setPad4(int bits)        { rom::setControllerInput(3, (uint16_t)bits); }

void Engine::setTouchInput(int x, int y, bool pressed) {
    rom::setTouchInput(x, y, pressed);
}
void Engine::setTouchInputDirect(int x, int y, bool pressed) {
    rom::setTouchInputDirect(x, y, pressed);
}
void Engine::setRegion(int region)    { rom::applyRegion(region); }
void Engine::setSampleRate(int hz)    { rom::applySampleRate(hz); }
void Engine::setFastForward(int speed)  { rom::applySpeed(speed > 0 ? (float)speed : 1.0f); }

bool Engine::saveState(int slot, const std::string& path) {
    return rom::saveStateToPath(slot, path);
}

bool Engine::loadState(int slot, const std::string& path) {
    return rom::loadStateFromPath(slot, path);
}

bool Engine::flushSaveRam() {
    return rom::flushSaveRamToDisk();
}

bool Engine::reloadSaveRam() {
    return rom::reloadSaveRamFromDisk();
}

bool Engine::getFrameBuffer(uint32_t* out, int w, int h) {
    return rom::copyFramebufferARGB(out, w, h);
}

bool Engine::getFilteredFrameBuffer(uint32_t* out, int w, int h) {
    return rom::copyFilteredFramebufferARGB(out, w, h);
}

int Engine::filteredWidth()  { return rom::filteredWidth(); }
int Engine::filteredHeight() { return rom::filteredHeight(); }
uint64_t Engine::frameStamp() { return rom::frameStamp(); }

int Engine::readAudio(int16_t* out, int maxFrames) {
    return rom::readAudio(out, maxFrames);
}

int Engine::audioSampleRate()       { return rom::audioSampleRate(); }
int Engine::audioTargetSampleRate() { return rom::audioTargetSampleRate(); }

void Engine::setPaths(const std::string& systemDir, const std::string& saveDir) {
    rom::setPaths(systemDir, saveDir);
}

void Engine::setSaveName(const std::string& name) {
    rom::setSaveName(name);
}

void Engine::setSurface(jobject /*surface*/) {
    // Stub — JNI wrapper below performs ANativeWindow extraction.
}

void Engine::setCoreOption(const std::string& key, const std::string& value) {
    rom::setCoreOption(key, value);
}

int Engine::videoWidth()  { return rom::videoWidth(); }
int Engine::videoHeight() { return rom::videoHeight(); }

void Engine::setVideoFilter(int filter) { rom::setVideoFilter(filter); }
void Engine::setHighQualityScaling(bool enabled) { rom::setHighQualityScaling(enabled); }

} // namespace ndscore

// ---------------------------------------------------------------------------
// JNI surface — mirrors NdsNative.kt exactly
// ---------------------------------------------------------------------------

static JavaVM* s_jvm = nullptr;

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_loadRom(JNIEnv* env, jclass, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    // Copy into a std::string so the data outlives ReleaseStringUTFChars.
    std::string pathStr(cpath ? cpath : "");
    env->ReleaseStringUTFChars(path, cpath);
    bool ok = ndscore::Engine::instance().loadRom(pathStr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_unload(JNIEnv*, jclass) {
    ndscore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_reset(JNIEnv*, jclass, jboolean hard) {
    ndscore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_runFrame(JNIEnv*, jclass) {
    ndscore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setPad1(JNIEnv*, jclass, jint bits) {
    ndscore::Engine::instance().setPad1(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setPad2(JNIEnv*, jclass, jint bits) {
    ndscore::Engine::instance().setPad2(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setPad3(JNIEnv*, jclass, jint bits) {
    ndscore::Engine::instance().setPad3(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setPad4(JNIEnv*, jclass, jint bits) {
    ndscore::Engine::instance().setPad4(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setTouchInput(JNIEnv*, jclass, jint x, jint y, jboolean pressed) {
    ndscore::Engine::instance().setTouchInput(x, y, pressed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setTouchInputDirect(JNIEnv*, jclass, jint x, jint y, jboolean pressed) {
    ndscore::Engine::instance().setTouchInputDirect(x, y, pressed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setRegion(JNIEnv*, jclass, jint region) {
    ndscore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setSampleRate(JNIEnv*, jclass, jint rate) {
    ndscore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setFastForward(JNIEnv*, jclass, jint speed) {
    ndscore::Engine::instance().setFastForward(speed);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_saveState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = ndscore::Engine::instance().saveState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_loadState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = ndscore::Engine::instance().loadState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Manual in-game .sav (battery save) flush — used by the "核心sav存档"
// save mechanism in the global settings. Flushes the core's SAVE_RAM
// buffer atomically to <saveDir>/<saveName>.srm via temp+rename, so a
// crash mid-flush leaves the previous .srm intact.
JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_flushSaveRam(JNIEnv*, jclass) {
    return ndscore::Engine::instance().flushSaveRam() ? JNI_TRUE : JNI_FALSE;
}

// Reload the per-game .srm into the core's SAVE_RAM buffer — discards
// any unsaved in-game progress and resets the cartridge RAM to whatever
// was last persisted to disk.
JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_reloadSaveRam(JNIEnv*, jclass) {
    return ndscore::Engine::instance().reloadSaveRam() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    int vw = ndscore::Engine::instance().videoWidth();
    int vh = ndscore::Engine::instance().videoHeight();
    if (vw <= 0 || vh <= 0) { vw = 256; vh = 384; }
    jsize len = env->GetArrayLength(out);
    if (len < vw * vh) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool fresh = ndscore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(px), vw, vh);
    env->ReleaseIntArrayElements(out, px, 0);
    return fresh ? JNI_TRUE : JNI_FALSE;
}

// Filtered frame for the custom dual-screen layout: the upscaled
// (HQ2X/HQ4X/XBR) frame when active, the raw frame otherwise.
JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_getFilteredFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    int vw = ndscore::Engine::instance().filteredWidth();
    int vh = ndscore::Engine::instance().filteredHeight();
    if (vw <= 0 || vh <= 0) { vw = 256; vh = 384; }
    jsize len = env->GetArrayLength(out);
    if (len < vw * vh) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool ok = ndscore::Engine::instance().getFilteredFrameBuffer(
        reinterpret_cast<uint32_t*>(px), vw, vh);
    env->ReleaseIntArrayElements(out, px, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_filteredVideoWidth(JNIEnv*, jclass) {
    return ndscore::Engine::instance().filteredWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_filteredVideoHeight(JNIEnv*, jclass) {
    return ndscore::Engine::instance().filteredHeight();
}

JNIEXPORT jlong JNICALL
Java_com_nesstation_app_core_jni_NdsNative_frameStamp(JNIEnv*, jclass) {
    return (jlong)ndscore::Engine::instance().frameStamp();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    int maxFrames = len / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int n = ndscore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), maxFrames);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_audioSampleRate(JNIEnv*, jclass) {
    return ndscore::Engine::instance().audioSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_audioTargetSampleRate(JNIEnv*, jclass) {
    return ndscore::Engine::instance().audioTargetSampleRate();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setPaths(JNIEnv* env, jclass, jstring systemDir, jstring saveDir) {
    const char* sys = systemDir ? env->GetStringUTFChars(systemDir, nullptr) : nullptr;
    const char* sav = saveDir   ? env->GetStringUTFChars(saveDir,   nullptr) : nullptr;
    ndscore::Engine::instance().setPaths(sys ? sys : "", sav ? sav : "");
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setSaveName(JNIEnv* env, jclass, jstring name) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    ndscore::Engine::instance().setSaveName(n ? n : "");
    if (n) env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_NdsNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(ndscore::Engine::instance().lastError().c_str());
}

// --- Hardware-accelerated rendering ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setSurface(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = nullptr;
    if (surface) {
        win = ANativeWindow_fromSurface(env, surface);
    }
    ndscore::rom::setSurface(win);
    if (win) ANativeWindow_release(win);
}

// --- Core options ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setCoreOption(JNIEnv* env, jclass, jstring key, jstring value) {
    const char* ckey = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char* cval = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    ndscore::Engine::instance().setCoreOption(ckey ? ckey : "", cval ? cval : "");
    if (ckey) env->ReleaseStringUTFChars(key, ckey);
    if (cval) env->ReleaseStringUTFChars(value, cval);
}

// --- Video geometry ---

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_videoWidth(JNIEnv*, jclass) {
    return ndscore::Engine::instance().videoWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_videoHeight(JNIEnv*, jclass) {
    return ndscore::Engine::instance().videoHeight();
}

// --- Video filter ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setVideoFilter(JNIEnv*, jclass, jint filter) {
    ndscore::Engine::instance().setVideoFilter(filter);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setHighQualityScaling(JNIEnv*, jclass, jboolean enabled) {
    ndscore::Engine::instance().setHighQualityScaling(enabled);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_isCoreLibLoaded(JNIEnv*, jclass) {
    return ndscore::rom::isCoreLoaded() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
