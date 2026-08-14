/*
 * paths_android -- Android replacement for the desktop's core/src/paths.c.
 *
 * Satisfies exactly the prototypes core/src/session_internal.h declares, so
 * session.c (staged unmodified) links against this instead of the desktop's
 * SDL/XDG-aware implementation. The differences from the desktop version:
 *
 *   - No XDG/Windows directory discovery: Kotlin's RuntimeInstaller always
 *     supplies explicit config_dir/data_dir (both under the app's private
 *     filesDir, which is already sandboxed -- there is nothing analogous to
 *     "the user's home directory" to fall back to on Android).
 *   - No exe_dir()/install-dir search for the FujiNet runtime: Kotlin stages
 *     assets/fujinet -> <filesDir>/fujinet ahead of calling
 *     nativeStartSession, and libfujinet.so is packaged as a jniLibs
 *     artifact the dynamic linker resolves by bare soname (see
 *     fujinet_android.cpp's dlopen("libfujinet.so"), same as every other
 *     family member) -- so paths_provision_fujinet here only has to verify
 *     what Kotlin already put in place, not search for it.
 *
 * Copyright (C) 2026 Thomas Cherryhomes
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "intv_host.h"
#include "session_internal.h"

static int make_dir(const char *path)
{
    return mkdir(path, 0755);
}

static int mkdir_p(const char *path)
{
    char buf[INTV_PATH_MAX];
    char *p;
    if (!path || !*path) return -1;
    snprintf(buf, sizeof(buf), "%s", path);
    for (p = buf + 1; *p; p++) {
        if (*p == '/') {
            char save = *p;
            *p = '\0';
            if (make_dir(buf) != 0 && errno != EEXIST) return -1;
            *p = save;
        }
    }
    if (make_dir(buf) != 0 && errno != EEXIST) return -1;
    return 0;
}

static int is_file(const char *path)
{
    struct stat st;
    return path && *path && stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

int paths_init(struct intvsession *s, const char *config_dir,
              const char *data_dir)
{
    /* Kotlin always supplies both (see EmulatorNative.nativeStartSession /
     * RuntimeInstaller); "." fallbacks below only guard against a caller
     * (e.g. a future test) that omits them, matching the desktop's own
     * defensive shape rather than crashing. */
    snprintf(s->config_dir, sizeof(s->config_dir), "%s",
             (config_dir && *config_dir) ? config_dir : ".");
    snprintf(s->data_dir, sizeof(s->data_dir), "%s",
             (data_dir && *data_dir) ? data_dir : ".");

    if (mkdir_p(s->config_dir) != 0 || mkdir_p(s->data_dir) != 0)
        return -1;

    snprintf(s->settings_file, sizeof(s->settings_file), "%s/settings.ini",
             s->config_dir);

    snprintf(s->roms_dir, sizeof(s->roms_dir), "%s/roms", s->data_dir);
    if (mkdir_p(s->roms_dir) != 0)
        return -1;

    intv_host_provision_roms(s->roms_dir);

    snprintf(s->fujinet_root, sizeof(s->fujinet_root), "%s/fujinet",
             s->data_dir);
    snprintf(s->fujinet_config, sizeof(s->fujinet_config), "%s/fnconfig.ini",
             s->fujinet_root);
    snprintf(s->fujinet_sd, sizeof(s->fujinet_sd), "%s/SD", s->fujinet_root);
    snprintf(s->fujinet_data, sizeof(s->fujinet_data), "%s/data",
             s->fujinet_root);
    s->fujinet_lib[0] = '\0'; /* resolved in paths_provision_fujinet */

    snprintf(s->webui_url, sizeof(s->webui_url), "http://127.0.0.1:%d/",
             INTVSESSION_WEBUI_PORT);

    return 0;
}

/* Kotlin's RuntimeInstaller (app/src/main/java/online/fujinet/go/intv/
 * RuntimeInstaller.kt) copies assets/fujinet/{fnconfig.ini,data,SD} into
 * <data_dir>/fujinet before the session is ever started -- so unlike the
 * desktop, there is nothing to search for or copy here, only to verify.
 * libfujinet.so itself is not looked up by path at all: fujinet_android.cpp
 * dlopen()s it by bare soname, which the dynamic linker resolves against the
 * APK's packaged jniLibs for this ABI (same as every other family member).
 * fujinet_lib is set to a non-empty placeholder purely so the
 * "!s->fujinet_lib[0]" no-runtime-available checks elsewhere behave the same
 * as on desktop. */
int paths_provision_fujinet(struct intvsession *s)
{
    if (!is_file(s->fujinet_config)) {
        session_set_error(s, "FujiNet runtime assets not staged at %s "
                          "(RuntimeInstaller should have copied them before "
                          "the session started)", s->fujinet_root);
        return -1;
    }
    mkdir_p(s->fujinet_sd);
    mkdir_p(s->fujinet_data);
    snprintf(s->fujinet_lib, sizeof(s->fujinet_lib), "libfujinet.so");
    return 0;
}

int intvsession_has_system_roms(const intvsession *s)
{
    char exec_path[INTV_PATH_MAX], grom_path[INTV_PATH_MAX];
    snprintf(exec_path, sizeof(exec_path), "%s/exec.bin", s->roms_dir);
    snprintf(grom_path, sizeof(grom_path), "%s/grom.bin", s->roms_dir);
    return is_file(exec_path) && is_file(grom_path);
}

const char *intvsession_roms_path(const intvsession *s)
{
    return s->roms_dir;
}

const char *intvsession_config_path(const intvsession *s)
{
    return s->config_dir;
}

const char *intvsession_data_path(const intvsession *s)
{
    return s->data_dir;
}

void session_set_error(struct intvsession *s, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(s->last_error, sizeof(s->last_error), fmt, ap);
    va_end(ap);
}
