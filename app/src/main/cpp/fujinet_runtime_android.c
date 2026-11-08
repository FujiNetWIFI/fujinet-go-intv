/*
 * fujinet_runtime_android -- Android replacement for the desktop's
 * core/src/fujinet_runtime.c. Same session_internal.h contract
 * (fujinet_start/stop/wait_for_boip/copy_log/mix_audio), but drives the
 * FujiNetAndroid_* entry points fujinet_android.cpp exposes (which in turn
 * dlopen "libfujinet.so" and bind fujinet_android_* -- the Android build's
 * own runtime contract, distinct from the desktop's fujinet_desktop_*) --
 * rather than dlopen'ing a runtime library directly.
 *
 * The BoIP direction and timing are unchanged from the desktop: FujiNet
 * listens, jzIntv's --fujinet connects out (core/jzintv/intv_host.h), so
 * fujinet_start() has to bring the runtime up and fujinet_wait_for_boip()
 * has to see its listener actually accepting before intv_host_start() is
 * called -- see session.c's intvsession_start, which already sequences this
 * correctly and needed no change for Android.
 *
 * Copyright (C) 2026 Thomas Cherryhomes
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "compat.h"
#include "session_internal.h"

/* Implemented in fujinet_android.cpp (extern "C", so plain-C-callable). */
extern bool FujiNetAndroid_StartRuntime(const char *runtimeRootPath,
                                        const char *configPath,
                                        const char *sdPath,
                                        const char *dataPath, int listenPort);
extern void FujiNetAndroid_StopRuntime(void);
extern const char *FujiNetAndroid_LastErrorMessage(void);
extern bool FujiNetAndroid_IsRuntimeRunning(void);
extern int FujiNetAndroid_CopyRecentLog(char *output, int maxBytes);
extern void FujiNetAndroid_MixAudio(int16_t *output, int sampleCount,
                                    int outputSampleRate);

int fujinet_start(struct intvsession *s)
{
    if (s->fujinet_running)
        return 0;

    if (paths_provision_fujinet(s) != 0) {
        fprintf(stderr, "intvsession: FujiNet runtime unavailable; "
                        "continuing without it\n");
        return -1;
    }

    /* Same rationale as the desktop's fujinet_start: fnFsSPIFFS/mgHttpClient
     * root themselves from this env var rather than the CWD. */
    intv_setenv("FUJINET_RUNTIME_ROOT", s->fujinet_root);

    if (!FujiNetAndroid_StartRuntime(s->fujinet_root, s->fujinet_config,
                                     s->fujinet_sd, s->fujinet_data,
                                     INTVSESSION_BOIP_PORT)) {
        const char *err = FujiNetAndroid_LastErrorMessage();
        session_set_error(s, "FujiNet runtime failed to start: %s",
                          err && *err ? err : "(unknown)");
        return -1;
    }
    s->fujinet_running = 1;
    return 0;
}

/* See core/src/fujinet_runtime.c's own comment on why fujinet_start()
 * returning isn't enough by itself: the BoIP channel logs "No WiFi!" and
 * suspends briefly before it starts listening. fn_sock.c's client retries
 * non-blockingly, so missing this window is not fatal, just a needless wait
 * for the first exchange. */
int fujinet_wait_for_boip(struct intvsession *s, int timeout_ms)
{
    int waited = 0;

    if (!s->fujinet_running)
        return -1;

    for (;;) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd >= 0) {
            struct sockaddr_in addr;
            int ok;
            memset(&addr, 0, sizeof(addr));
            addr.sin_family = AF_INET;
            addr.sin_port = htons(INTVSESSION_BOIP_PORT);
            addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            ok = connect(fd, (struct sockaddr *)&addr, sizeof(addr)) == 0;
            intv_closesocket(fd);
            if (ok)
                return 0;
        }
        if (waited >= timeout_ms) {
            fprintf(stderr, "intvsession: the FujiNet BoIP listener did not "
                            "come up within %d ms; --fujinet will keep "
                            "retrying on its own\n", timeout_ms);
            return -1;
        }
        intv_sleep_ms(25);
        waited += 25;
    }
}

void fujinet_stop(struct intvsession *s)
{
    if (!s->fujinet_running)
        return;
    s->fujinet_running = 0;
    FujiNetAndroid_StopRuntime();
}

int fujinet_copy_log(struct intvsession *s, char *dst, int max)
{
    (void)s;
    if (!dst || max <= 0)
        return 0;
    return FujiNetAndroid_CopyRecentLog(dst, max);
}

int intvsession_fujinet_running(const intvsession *s)
{
    return s->fujinet_running;
}

const char *intvsession_fujinet_webui_url(const intvsession *s)
{
    return s->webui_url;
}

int intvsession_fujinet_copy_log(intvsession *s, char *dst, int max)
{
    return fujinet_copy_log(s, dst, max);
}

/* Both buf and the runtime's SAM speech output are mono (see
 * core/jzintv/intv_audio.h) -- no channel widening needed, same as desktop. */
void fujinet_mix_audio(struct intvsession *s, int16_t *buf, int nsamples,
                       int rate)
{
    if (!s->fujinet_running || nsamples <= 0)
        return;
    FujiNetAndroid_MixAudio(buf, nsamples, rate);
}

/* intvsession_parse_hostport and its helper, copied from the desktop's
 * core/src/fujinet_runtime.c (which this file replaces): session.c calls it
 * for an external FujiNet address. */
static int host_valid(const char *host)
{
    const size_t len = strlen(host);
    int numeric = 1;
    size_t pos;

    if (len == 0 || len > 63 || host[0] == '.' || host[0] == '-' || host[len - 1] == '.')
        return 0;
    for (pos = 0; pos < len; pos++) {
        const char chr = host[pos];
        const int digit = chr >= '0' && chr <= '9';
        if (!digit && chr != '.')
            numeric = 0;
        if (!digit && chr != '.' && chr != '-' && chr != '_' && !(chr >= 'a' && chr <= 'z') &&
            !(chr >= 'A' && chr <= 'Z'))
            return 0;
        if (chr == '.' && host[pos + 1] == '.')
            return 0;
    }
    if (numeric) {
        int oct[4], used = 0, idx;
        if (sscanf(host, "%d.%d.%d.%d%n", &oct[0], &oct[1], &oct[2], &oct[3], &used) != 4 ||
            host[used])
            return 0;
        for (idx = 0; idx < 4; idx++)
            if (oct[idx] < 0 || oct[idx] > 255)
                return 0;
    }
    return 1;
}

int intvsession_parse_hostport(const char *text, char *host, int hostsz, int *port)
{
    char buf[80];
    const char *name = "127.0.0.1";
    long num = 65503;
    size_t len;

    if (!text)
        text = "";
    while (*text == ' ' || *text == '\t')
        text++;
    len = strlen(text);
    while (len && (text[len - 1] == ' ' || text[len - 1] == '\t' || text[len - 1] == '\n' ||
                   text[len - 1] == '\r'))
        len--;
    if (len >= sizeof buf)
        return -1;
    memcpy(buf, text, len);
    buf[len] = '\0';

    if (len) {
        char *colon = strrchr(buf, ':');
        const char *digits = buf;
        char *end;
        if (colon) {
            *colon = '\0';
            name = buf;
            digits = colon + 1;
            if (!host_valid(name))
                return -1;
        }
        if (!*digits)
            return -1;
        num = strtol(digits, &end, 10);
        if (*end || num < 1 || num > 65535)
            return -1;
    }
    if ((int)strlen(name) >= hostsz)
        return -1;
    memcpy(host, name, strlen(name) + 1);
    *port = (int)num;
    return 0;
}
