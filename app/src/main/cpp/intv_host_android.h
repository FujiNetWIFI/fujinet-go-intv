// intv_host_android -- the C++ wrapper around intvsession.h for the Android
// port. Owns the single mutex-guarded intvsession* (jzIntv is a process-wide
// singleton -- see core/jzintv/intv_host.h -- so there is exactly one of
// these per process, matching every other family member's SessionController
// shape), and everything that talks to intvsession_* directly: session
// start/stop, pad/disc/ECS-key injection, the blocking stereo audio fill,
// system-ROM probes, and cartridge load/path. session_runtime.h (surface,
// render thread, ADPF) is a separate concern layered on top of this.
//
// Copyright (C) 2026 Thomas Cherryhomes
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <string>

namespace intv_host_android {

struct StartOptions {
    // <filesDir>/intv -- passed as both config_dir and data_dir to
    // intvsession_new(). paths_init (paths_android.c) derives roms_dir,
    // fujinet_root, fujinet_config, fujinet_sd and fujinet_data from this
    // single root, so Kotlin's RuntimeInstaller must stage FujiNet's assets
    // into exactly <files_dir>/fujinet to match (see paths_android.c's
    // paths_init for the exact derivation).
    std::string files_dir;
    int ecs = -1;      // INTVSESSION_HW_* ; -1 selects the settings-store default
    int ivoice = -1;   // ditto
    int video = -1;    // INTVSESSION_VIDEO_* ; -1 selects the settings-store default
    std::string cart_path; // "" -> boot the embedded FujiNet config ROM
};

// Creates the intvsession (if not already created) and starts it. Returns
// true on success; on failure LastError() explains why (missing system
// ROMs, ECS forced on with no ecs.bin, etc -- see intvsession_start).
bool Start(const StartOptions& opts);

// Stops the running session (safe if not running). Does NOT free the
// intvsession -- Start() reuses it, matching intvsession_new/free being a
// process-lifetime pair everywhere else in the family.
void Stop();

bool IsRunning();
std::string LastError();

// Pad/disc/ECS injection -- pure pass-through to intvsession_pad_key /
// intvsession_pad_disc / intvsession_ecs_key_set / intvsession_ecs_keys_clear.
// Safe to call whether or not a session is running (same guarantee
// intv_host_pad_key etc. make).
void PadKey(int side, int key, bool pressed);
void PadDisc(int side, int direction); // -1 centers the disc
void EcsKey(int key, bool pressed);
void EcsKeysClear();

// Blocking fill: writes `frames` interleaved STEREO int16 samples (i.e.
// 2*frames shorts) into `out`, duplicating jzIntv's mono PSG+SAM mix to both
// channels (see intv_audio.h's own comment on why the ring stays mono --
// there is no stereo signal to fake). Blocks until `frames` worth of mono
// samples have been produced or a short deadline passes, silence-padding
// any shortfall so the caller (AudioOutput's feeder thread) never receives
// a torn buffer. Returns immediately with silence if SetAudioActive(false)
// was called (session stopping) or no session exists yet.
void FillAudioStereo(int16_t* out, int frames, int sample_rate);
void SetAudioActive(bool active);

// Pre-session probes -- do not require Start() to have been called.
bool HasSystemRoms(const std::string& roms_dir);
bool HasEcsRom(const std::string& roms_dir);

// Cartridge: stop-then-start with the given path persisted to the "cart"
// settings key (see intvsession_load_cart's own contract). path == "" boots
// the embedded FujiNet config ROM.
bool LoadCart(const std::string& path);
std::string CartPath();

// FujiNet accessors, pass-through to intvsession_fujinet_*.
bool FujiNetRunning();
std::string FujiNetWebUiUrl();
std::string FujiNetCopyLog();

}  // namespace intv_host_android
