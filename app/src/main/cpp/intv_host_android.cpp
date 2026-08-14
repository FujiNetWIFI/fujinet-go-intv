#include "intv_host_android.h"

#include <sys/stat.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <vector>

#include "intvsession.h"

namespace intv_host_android {
namespace {

std::mutex g_mutex;
intvsession* g_session = nullptr;
std::string g_roms_dir;

// Unblocks a fill() waiting on the audio condvar when the session is
// stopping, so AudioOutput's feeder thread (blocked in FillAudioStereo)
// never hangs past shutdown -- same contract the family's AudioOutput.stop()
// / nativeAudioSetActive(false) already assumes.
std::atomic<bool> g_audio_active{false};
std::condition_variable g_audio_cv;
std::mutex g_audio_mutex;

bool StatFile(const std::string& path, long expect_size = -1) {
    struct stat st{};
    if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) return false;
    return expect_size < 0 || st.st_size == expect_size;
}

}  // namespace

bool Start(const StartOptions& opts) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_session) {
        intvsession_paths paths{};
        paths.config_dir = opts.files_dir.c_str();
        paths.data_dir = opts.files_dir.c_str();
        g_session = intvsession_new(&paths);
        if (!g_session) return false;
    }
    g_roms_dir = intvsession_roms_path(g_session);

    intvsession_start_opts start_opts{};
    intvsession_default_opts(g_session, &start_opts);
    if (opts.ecs >= 0) start_opts.ecs = opts.ecs;
    if (opts.ivoice >= 0) start_opts.ivoice = opts.ivoice;
    if (opts.video >= 0) start_opts.video = opts.video;

    // Mitigates cfg_init()'s exit(1) on a forced --ecs=1 with no ecs.bin
    // (core/jzintv/intv_host.c's own precheck already refuses THAT case
    // before it reaches cfg_init -- this is the other half: on Android a
    // process exit() is a silent crash, so an AUTO setting must not be
    // allowed to let cart metadata drive jzIntv into requesting ECS when no
    // ROM is present either). Force AUTO down to OFF rather than leave it
    // to chance.
    if (start_opts.ecs == INTVSESSION_HW_AUTO &&
        !intvsession_has_ecs_rom(g_session)) {
        start_opts.ecs = INTVSESSION_HW_OFF;
    }

    const std::string cart = !opts.cart_path.empty()
                                  ? opts.cart_path
                                  : intvsession_cart_path(g_session);
    start_opts.cart_path = cart.empty() ? nullptr : cart.c_str();

    g_audio_active.store(true);
    const bool ok = intvsession_start(g_session, &start_opts) == 0;
    if (!ok) g_audio_active.store(false);
    return ok;
}

void Stop() {
    SetAudioActive(false);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_session) intvsession_stop(g_session);
}

bool IsRunning() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_session && intvsession_is_running(g_session);
}

std::string LastError() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_session) return "";
    const char* e = intvsession_last_error(g_session);
    return e ? e : "";
}

void PadKey(int side, int key, bool pressed) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_session)
        intvsession_pad_key(g_session, (intvsession_pad_side)side,
                            (intvsession_key)key, pressed ? 1 : 0);
}

void PadDisc(int side, int direction) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_session)
        intvsession_pad_disc(g_session, (intvsession_pad_side)side, direction);
}

void EcsKey(int key, bool pressed) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_session)
        intvsession_ecs_key_set(g_session, (intvsession_ecs_key)key,
                                pressed ? 1 : 0);
}

void EcsKeysClear() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_session) intvsession_ecs_keys_clear(g_session);
}

void FillAudioStereo(int16_t* out, int frames, int sample_rate) {
    if (!out || frames <= 0) return;

    static thread_local std::vector<int16_t> mono;
    mono.assign(static_cast<size_t>(frames), 0);

    if (g_audio_active.load()) {
        int got = 0;
        // Two frame periods' worth of budget before giving up and padding
        // with silence -- generous enough to ride out a producer burst gap
        // (snd_desktop.c publishes in ~11ms bursts, see intv_host.c's
        // -B512) without ever blocking the feeder thread indefinitely.
        const auto deadline = std::chrono::steady_clock::now() +
                              std::chrono::milliseconds(40);
        while (got < frames && g_audio_active.load()) {
            int n;
            {
                std::lock_guard<std::mutex> lock(g_mutex);
                n = g_session ? intvsession_render_audio(
                                    g_session, mono.data() + got, frames - got)
                              : 0;
            }
            got += n;
            if (got >= frames) break;
            if (std::chrono::steady_clock::now() >= deadline) break;
            if (n == 0) {
                std::unique_lock<std::mutex> alock(g_audio_mutex);
                g_audio_cv.wait_for(alock, std::chrono::milliseconds(2));
            }
        }
        // mono[got..frames) stays zero-initialized (silence pad on shortfall).
    }

    for (int i = 0; i < frames; i++) {
        out[2 * i] = mono[i];
        out[2 * i + 1] = mono[i];
    }
    (void)sample_rate; // rate is fixed (INTVSESSION_AUDIO_RATE); kept for API symmetry
}

void SetAudioActive(bool active) {
    g_audio_active.store(active);
    if (!active) {
        std::lock_guard<std::mutex> alock(g_audio_mutex);
        g_audio_cv.notify_all();
    }
}

bool HasSystemRoms(const std::string& roms_dir) {
    return StatFile(roms_dir + "/exec.bin", 8192) &&
           StatFile(roms_dir + "/grom.bin", 2048);
}

bool HasEcsRom(const std::string& roms_dir) {
    return StatFile(roms_dir + "/ecs.bin", 12 * 1024 * 2);
}

bool LoadCart(const std::string& path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_session) return false;
    g_audio_active.store(true);
    return intvsession_load_cart(g_session, path.c_str()) == 0;
}

std::string CartPath() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_session) return "";
    const char* p = intvsession_cart_path(g_session);
    return p ? p : "";
}

bool FujiNetRunning() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_session && intvsession_fujinet_running(g_session);
}

std::string FujiNetWebUiUrl() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_session) return "";
    const char* u = intvsession_fujinet_webui_url(g_session);
    return u ? u : "";
}

std::string FujiNetCopyLog() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_session) return "";
    char buf[4096] = {0};
    intvsession_fujinet_copy_log(g_session, buf, sizeof(buf));
    return buf;
}

}  // namespace intv_host_android
