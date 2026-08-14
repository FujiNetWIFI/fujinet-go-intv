// intv_core -- the JNI bridge. Every native method Kotlin's
// online.fujinet.go.intv.core.EmulatorNative declares is implemented here,
// as a thin translation to session_runtime.h / intv_host_android.h -- no
// logic lives in this file beyond JNI string/array marshalling.
//
// Copyright (C) 2026 Thomas Cherryhomes
// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>

#include <string>

#include "intv_host_android.h"
#include "session_runtime.h"

namespace {

std::string JStr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return result;
}

jstring ToJString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeStartSession(
    JNIEnv* env, jobject /*thiz*/, jstring filesDir, jstring cartPath,
    jint ecs, jint ivoice, jint video) {
    intv_host_android::StartOptions opts;
    opts.files_dir = JStr(env, filesDir);
    opts.cart_path = JStr(env, cartPath);
    opts.ecs = ecs;
    opts.ivoice = ivoice;
    opts.video = video;
    return SessionRuntime::Get().StartSession(opts) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeStopSession(
    JNIEnv*, jobject) {
    SessionRuntime::Get().StopSession();
}

JNIEXPORT jboolean JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeIsRunning(JNIEnv*,
                                                                 jobject) {
    return SessionRuntime::Get().IsRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeLastError(JNIEnv* env,
                                                                 jobject) {
    return ToJString(env, SessionRuntime::Get().LastError());
}

JNIEXPORT void JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeAttachSurface(
    JNIEnv* env, jobject, jobject surface) {
    SessionRuntime::Get().AttachSurface(env, surface);
}

JNIEXPORT void JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeDetachSurface(
    JNIEnv* env, jobject) {
    SessionRuntime::Get().DetachSurface(env);
}

JNIEXPORT void JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeRequestReset(JNIEnv*,
                                                                    jobject) {
    SessionRuntime::Get().RequestReset();
}

JNIEXPORT jboolean JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeHasSystemRoms(
    JNIEnv* env, jobject, jstring romsDir) {
    return intv_host_android::HasSystemRoms(JStr(env, romsDir)) ? JNI_TRUE
                                                                 : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeHasEcsRom(
    JNIEnv* env, jobject, jstring romsDir) {
    return intv_host_android::HasEcsRom(JStr(env, romsDir)) ? JNI_TRUE
                                                             : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativePadKey(
    JNIEnv*, jobject, jint side, jint key, jboolean pressed) {
    intv_host_android::PadKey(side, key, pressed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativePadDisc(
    JNIEnv*, jobject, jint side, jint direction) {
    intv_host_android::PadDisc(side, direction);
}

JNIEXPORT void JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeEcsKey(
    JNIEnv*, jobject, jint key, jboolean pressed) {
    intv_host_android::EcsKey(key, pressed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeEcsKeysClear(JNIEnv*,
                                                                    jobject) {
    intv_host_android::EcsKeysClear();
}

// out is interleaved stereo int16 (out.size / 2 frames). Returns the number
// of shorts written (always out.size -- shortfalls are silence-padded, see
// intv_host_android::FillAudioStereo).
JNIEXPORT jint JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeFillAudio(
    JNIEnv* env, jobject, jshortArray out) {
    const jsize len = env->GetArrayLength(out);
    const int frames = len / 2;
    if (frames <= 0) return 0;

    jshort* buf = env->GetShortArrayElements(out, nullptr);
    intv_host_android::FillAudioStereo(reinterpret_cast<int16_t*>(buf),
                                       frames, 48000);
    env->ReleaseShortArrayElements(out, buf, 0);
    return frames * 2;
}

JNIEXPORT void JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeAudioSetActive(
    JNIEnv*, jobject, jboolean active) {
    intv_host_android::SetAudioActive(active == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeFujiNetRunning(
    JNIEnv*, jobject) {
    return intv_host_android::FujiNetRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeFujiNetWebUiUrl(
    JNIEnv* env, jobject) {
    return ToJString(env, intv_host_android::FujiNetWebUiUrl());
}

JNIEXPORT jstring JNICALL
Java_online_fujinet_go_intv_core_EmulatorNative_nativeFujiNetCopyLog(
    JNIEnv* env, jobject) {
    return ToJString(env, intv_host_android::FujiNetCopyLog());
}

}  // extern "C"
