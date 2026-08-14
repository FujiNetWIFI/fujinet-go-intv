/*
 * android_stubs -- satisfies the desktop core's audio_sdl.c / gamepad_sdl.c
 * contracts (session_internal.h's audio_start/audio_stop, gamepad_sdl.h's
 * intv_gamepad_start/intv_gamepad_stop) with Android-appropriate no-ops.
 *
 * Audio: Android is pull-from-Kotlin (AudioTrack's feeder thread calls
 * EmulatorNative.nativeFillAudio -> intv_host_android.cpp's blocking fill,
 * which reads intvsession_render_audio directly), not SDL push-a-device --
 * so there is no "audio device" for audio_start/stop to open or close here.
 * audio_start still resets the ring, matching the desktop's own reason for
 * doing so (discard anything left over from a previous session so a
 * Settings-driven restart doesn't replay stale audio).
 *
 * Gamepads: Android's InputDevice/MotionEvent APIs are read entirely in
 * Kotlin (input/GameControllerMapper.kt), which calls the same
 * nativePadDisc/nativePadKey JNI entry points a touch controller uses --
 * there is no native gamepad polling thread to start/stop.
 *
 * Copyright (C) 2026 Thomas Cherryhomes
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "intv_audio.h"
#include "session_internal.h"

int audio_start(struct intvsession *s)
{
    (void)s;
    intv_audio_reset();
    return 0;
}

void audio_stop(struct intvsession *s)
{
    (void)s;
}

int intv_gamepad_start(void)
{
    return 0;
}

void intv_gamepad_stop(void)
{
}
