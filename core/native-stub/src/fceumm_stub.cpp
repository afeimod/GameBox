// SPDX-License-Identifier: MIT
// Fallback placeholder core — used when the FCEUmm submodule has not
// been pulled yet. Exposes the same JNI surface as the real one, so the
// app can build and the developer can iterate on UI.
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>

#define TAG "nescore-stub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static bool s_loaded = false;
static int  s_region = 0;
static uint8_t* s_xbuf = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_loadRom(JNIEnv*, jclass, jstring) {
    LOGI("loadRom (stub) — real FCEUmm not linked");
    s_loaded = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_unload(JNIEnv*, jclass) { s_loaded = false; }

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_reset(JNIEnv*, jclass, jboolean) {}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_runFrame(JNIEnv*, jclass) {}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setPad1(JNIEnv*, jclass, jint) {}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setRegion(JNIEnv*, jclass, jint) { s_region = 0; }

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setSampleRate(JNIEnv*, jclass, jint) {}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setFastForward(JNIEnv*, jclass, jboolean) {}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_saveState(JNIEnv*, jclass, jint, jstring) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_loadState(JNIEnv*, jclass, jint, jstring) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_NesNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF("");
}
