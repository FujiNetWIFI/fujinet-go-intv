// session_runtime -- see session_runtime.h.
//
// PACING (read this before "fixing" the lack of a frame loop here): every
// other family member (msx, coco, msdos, ...) drives frames from a fixed-
// period thread that calls into the emulator core once per tick
// (msxhost_core_run_frame() etc), because those cores have no pacing of
// their own -- the Android host has to supply it. jzIntv is different: its
// own jzintv_entry_point() *is* the blocking simulator loop, and
// core/jzintv/intv_host.c already passes -r1 to force jzIntv's real-time
// throttle (src/speed/speed.c) on, exactly as a real windowed jzIntv build
// gets for free. There is no split "tick" entry point to call from a host
// loop (the emscripten variant, src/jzintv_em.c, is not compiled here or by
// the desktop, for the same reason).
//
// So this file's "frame loop" is a presenter, not a pacer: RenderThreadMain
// blocks on frame_cv_, which OnFramePublished (intv_frame's publish hook,
// firing on the emulator thread once per completed STIC frame) signals, and
// simply presents whatever is there. It cannot drift ahead of or fall behind
// the emulator -- there is nothing to drift against -- and needs no deadline
// arithmetic at all. This is simpler than the sibling apps' loops, not a
// reduced version of one.
//
// Copyright (C) 2026 Thomas Cherryhomes
// SPDX-License-Identifier: GPL-3.0-or-later
#include "session_runtime.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include <cstring>

#include "intv_frame.h"
#include "intv_host.h"

#define LOG_TAG "IntvSession"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ADPF (Android Dynamic Performance Framework) "performance hint" session --
// tells the SoC scheduler/governor the frame deadline and per-frame CPU work
// for a small set of threads, so vendor "game" governors (Moto/MediaTek
// GameTime, Qualcomm) keep clocks up for them instead of racing to idle
// between frames. API 33+; dlsym'd so the app still runs below 33. Same
// pattern as the rest of the family's PerfHint (fujinet-go-msx/msdos's
// session_runtime.cpp), spanning the render + emulator threads here since,
// unlike those targets, intv's audio producer runs on the *emulator* thread
// too (snd_desktop.c's snd_tick is called from inside jzintv_entry_point's
// own loop) -- there is no separate audio-producer thread to add a second
// session for, unlike fujinet-go-msdos's PC-speaker case.
class PerfHint {
public:
    void start(const int32_t* tids, size_t count, int64_t targetNs) {
        auto getManager = reinterpret_cast<void* (*)()>(
            dlsym(RTLD_DEFAULT, "APerformanceHint_getManager"));
        create_ = reinterpret_cast<void* (*)(void*, const int32_t*, size_t, int64_t)>(
            dlsym(RTLD_DEFAULT, "APerformanceHint_createSession"));
        report_ = reinterpret_cast<void (*)(void*, int64_t)>(
            dlsym(RTLD_DEFAULT, "APerformanceHint_reportActualWorkDuration"));
        close_ = reinterpret_cast<void (*)(void*)>(
            dlsym(RTLD_DEFAULT, "APerformanceHint_closeSession"));
        if (!getManager || !create_ || !report_) {
            LOGW("ADPF unavailable (APerformanceHint symbols missing; pre-API-33)");
            return;
        }
        void* mgr = getManager();
        if (!mgr) {
            LOGW("ADPF unavailable (no performance hint manager on this device)");
            return;
        }
        session_ = create_(mgr, tids, count, targetNs);
        if (session_) {
            LOGI("ADPF performance hint session active (%zu tids, target %lldns)",
                 count, static_cast<long long>(targetNs));
        } else {
            LOGW("ADPF createSession returned null");
        }
    }
    void report(int64_t actualNs) const {
        if (session_ && report_) report_(session_, actualNs);
    }
    void stop() {
        if (session_ && close_) close_(session_);
        session_ = nullptr;
    }

private:
    void* session_ = nullptr;
    void* (*create_)(void*, const int32_t*, size_t, int64_t) = nullptr;
    void (*report_)(void*, int64_t) = nullptr;
    void (*close_)(void*) = nullptr;
};

PerfHint g_perf;
std::atomic<int64_t> g_frame_target_ns{1'000'000'000LL / 60};
// CLOCK_THREAD_CPUTIME_ID timestamp of the previous publish, so
// OnFramePublished can report real CPU work (not wall-clock, which would
// include jzIntv's own -r1 throttle sleep) per frame -- arguably more
// accurate than the wall-clock spans the sibling apps report, since those
// cores have no internal throttle of their own to exclude.
timespec g_last_publish_cpu_time{};
bool g_have_last_publish = false;

int64_t TimespecDiffNs(const timespec& a, const timespec& b) {
    return (int64_t)(a.tv_sec - b.tv_sec) * 1'000'000'000LL +
           (a.tv_nsec - b.tv_nsec);
}

}  // namespace

SessionRuntime& SessionRuntime::Get() {
    static SessionRuntime instance;
    return instance;
}

void SessionRuntime::OnEmulatorThreadStart() {
    pthread_setname_np(pthread_self(), "intv-emu");
    // THREAD_PRIORITY_URGENT_DISPLAY: keep the 60/50Hz frame schedule from
    // being preempted by UI work.
    setpriority(PRIO_PROCESS, 0, -8);

    SessionRuntime& self = Get();
    const int32_t emu_tid = static_cast<int32_t>(syscall(SYS_gettid));
    const int32_t render_tid = self.render_tid_;
    int32_t tids[2] = {emu_tid, render_tid};
    const size_t count = render_tid ? 2 : 1;
    g_have_last_publish = false;
    g_perf.start(tids, count, g_frame_target_ns.load());
}

void SessionRuntime::OnFramePublished(void* ctx) {
    auto* self = static_cast<SessionRuntime*>(ctx);

    timespec now{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &now);
    if (g_have_last_publish) {
        g_perf.report(TimespecDiffNs(now, g_last_publish_cpu_time));
    }
    g_last_publish_cpu_time = now;
    g_have_last_publish = true;

    self->SignalFrameDirty();
}

bool SessionRuntime::StartSession(const intv_host_android::StartOptions& opts) {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);

    video_standard_ = opts.video;
    g_frame_target_ns.store(video_standard_ == 1 /* INTVSESSION_VIDEO_PAL */
                                 ? 1'000'000'000LL / 50
                                 : 1'000'000'000LL / 60);

    render_running_.store(true);
    render_thread_ = std::thread(&SessionRuntime::RenderThreadMain, this);

    // Registered once per process is fine (idempotent) -- both hooks are
    // harmless when no session is running, since intv_frame_publish and
    // intv_host's thread_main only fire from a live emulator thread.
    intv_frame_set_publish_hook(&SessionRuntime::OnFramePublished, this);
    intv_host_set_thread_hook(&SessionRuntime::OnEmulatorThreadStart);

    const bool ok = intv_host_android::Start(opts);
    if (!ok) {
        LOGE("Session start failed: %s", intv_host_android::LastError().c_str());
    } else {
        LOGI("Session started");
    }
    return ok;
}

void SessionRuntime::StopSession() {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);

    intv_host_android::Stop();
    g_perf.stop();

    render_running_.store(false);
    SignalFrameDirty();
    if (render_thread_.joinable()) render_thread_.join();

    LOGI("Session stopped");
}

bool SessionRuntime::IsRunning() { return intv_host_android::IsRunning(); }

std::string SessionRuntime::LastError() { return intv_host_android::LastError(); }

void SessionRuntime::AttachSurface(JNIEnv* env, jobject surface) {
    std::lock_guard<std::mutex> lock(surface_mutex_);
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    if (surface) {
        window_ = ANativeWindow_fromSurface(env, surface);
        LOGI("AttachSurface: window=%p", static_cast<void*>(window_));
    }
    SignalFrameDirty();
}

void SessionRuntime::DetachSurface(JNIEnv* /*env*/) {
    std::lock_guard<std::mutex> lock(surface_mutex_);
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

void SessionRuntime::SignalFrameDirty() {
    {
        std::lock_guard<std::mutex> lock(frame_mutex_);
        frame_dirty_ = true;
    }
    frame_cv_.notify_one();
}

void SessionRuntime::RenderThreadMain() {
    pthread_setname_np(pthread_self(), "intv-render");
    render_tid_ = static_cast<pid_t>(syscall(SYS_gettid));

    scratch_frame_.assign(INTV_FRAME_WIDTH * INTV_FRAME_HEIGHT, 0);
    // 0 forces intv_frame_copy to report a change on the very first poll,
    // same convention the header documents ("pass *serial_inout = 0 to
    // force a copy").
    frame_serial_ = 0;

    while (render_running_.load()) {
        {
            std::unique_lock<std::mutex> lock(frame_mutex_);
            frame_cv_.wait(lock, [this] {
                return frame_dirty_ || !render_running_.load();
            });
            if (!render_running_.load()) break;
            frame_dirty_ = false;
        }

        // Ignore the return value: even when nothing changed since our last
        // copy (e.g. we were woken by AttachSurface, not a publish),
        // scratch_frame_ already holds the last real frame, and presenting
        // it again is exactly right for a newly attached surface.
        intv_frame_copy(scratch_frame_.data(), &frame_serial_);

        ANativeWindow* w_local = nullptr;
        {
            std::lock_guard<std::mutex> lock(surface_mutex_);
            if (window_) {
                w_local = window_;
                ANativeWindow_acquire(w_local);
            }
        }
        if (w_local) {
            PresentTo(w_local, scratch_frame_.data(), INTV_FRAME_WIDTH,
                     INTV_FRAME_HEIGHT);
            ANativeWindow_release(w_local);
        }
    }
}

void SessionRuntime::PresentTo(ANativeWindow* w, const uint32_t* xrgb8888,
                               int width, int height) {
    if (!w || !xrgb8888) return;

    // The 160x200 STIC buffer is presented into a 4:3 view on the Compose
    // side (EmulatorSurface.kt) -- the STIC's pixels are not square, and
    // that stretch is what makes the picture look right. The buffer
    // geometry set here is the *source* pixel grid, not the aspect ratio.
    ANativeWindow_setBuffersGeometry(w, width, height, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(w, &buffer, nullptr) != 0) return;

    const int copy_w = buffer.width < width ? buffer.width : width;
    const int copy_h = buffer.height < height ? buffer.height : height;
    auto* dst = static_cast<uint32_t*>(buffer.bits);
    for (int y = 0; y < copy_h; ++y) {
        const uint32_t* src_row = xrgb8888 + static_cast<size_t>(y) * width;
        uint32_t* dst_row = dst + static_cast<size_t>(y) * buffer.stride;
        for (int x = 0; x < copy_w; ++x) {
            // intv_frame's XRGB8888 (0x00RRGGBB) -> Android RGBA_8888 (mem
            // order R,G,B,A = word 0xAABBGGRR): swap R/B, force opaque alpha.
            const uint32_t p = src_row[x];
            dst_row[x] = 0xFF000000u | ((p & 0x000000FFu) << 16) |
                        (p & 0x0000FF00u) | ((p & 0x00FF0000u) >> 16);
        }
    }
    ANativeWindow_unlockAndPost(w);
}

void SessionRuntime::RequestReset() {
    // jzIntv has no in-place reset entry point analogous to msxhost's --
    // exposing this as stop+start on the current cart is left for a later
    // milestone (see EmulatorNative.kt's own comment); intentionally a
    // no-op for now rather than a partial implementation.
}
