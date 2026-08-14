// session_runtime -- surface lifecycle, the frame-presenter thread, and ADPF
// performance hints for the Android port. Deliberately does NOT drive a
// frame loop the way the family's other targets do (msxhost_core_run_frame()
// on a fixed-period thread): jzIntv paces itself (intv_host.c's -r1 real-time
// throttle), so this thread is a pure presenter -- it blocks on a condvar
// signalled once per frame by intv_frame's publish hook and presents
// whatever arrived, never driving or throttling the emulator itself. See the
// .cpp file's header comment for the full reasoning.
//
// Copyright (C) 2026 Thomas Cherryhomes
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <android/native_window.h>
#include <jni.h>
#include <sys/types.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "intv_host_android.h"

class SessionRuntime {
public:
    static SessionRuntime& Get();

    // opts.video selects the ADPF frame-deadline target (60Hz NTSC / 50Hz
    // PAL) as well as being forwarded to intvsession_start.
    bool StartSession(const intv_host_android::StartOptions& opts);
    void StopSession();
    bool IsRunning();
    std::string LastError();

    void AttachSurface(JNIEnv* env, jobject surface);
    void DetachSurface(JNIEnv* env);

    void RequestReset();

private:
    SessionRuntime() = default;

    // Called from intv_frame's publish hook, on the emulator thread, once
    // per completed STIC frame.
    static void OnFramePublished(void* ctx);
    void SignalFrameDirty();

    // Called from intv_host's thread hook, at the very top of the emulator
    // thread, before jzintv_entry_point() runs.
    static void OnEmulatorThreadStart();

    void RenderThreadMain();
    void PresentTo(ANativeWindow* w, const uint32_t* xrgb8888, int width,
                   int height);

    std::mutex lifecycle_mutex_;
    std::atomic<bool> render_running_{false};
    std::thread render_thread_;

    std::mutex frame_mutex_;
    std::condition_variable frame_cv_;
    bool frame_dirty_ = false;
    std::vector<uint32_t> scratch_frame_;
    uint64_t frame_serial_ = 0;

    std::mutex surface_mutex_;
    ANativeWindow* window_ = nullptr;

    int video_standard_ = 0; // INTVSESSION_VIDEO_NTSC
    pid_t render_tid_ = 0;
};
