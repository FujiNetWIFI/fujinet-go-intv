#!/usr/bin/env bash
# Stages the toolkit-agnostic Intellivision core (jzIntv 20200712 + the
# FujiNet fujibus/BoIP patch + the intvsession glue) from a local
# fujinet-go-intv-desktop checkout into app/src/main/cpp-generated/intv/, for
# app/src/main/cpp/CMakeLists.txt to compile into libintvcore.so.
#
# This deliberately does NOT re-implement jzIntv patching: the desktop repo
# is the one source of truth for jzintv-fujinet.patch and how it's applied
# (tools/jzintv/patch-staged-tree.py there). If the desktop checkout already
# has a staged core/jzintv-generated/ tree (e.g. from its own cmake
# configure), we just copy it; otherwise we drive its own staging script
# against a jzIntv source checkout.
#
# Env overrides:
#   INTV_DESKTOP_SRC=/path   fujinet-go-intv-desktop checkout
#                             (default: ~/Workspace/fujinet-go-intv-desktop)
#   JZINTV_SRC=/path         unpacked jzIntv 20200712 source, used only as a
#                             fallback if the desktop repo has no staged tree
#                             yet (default: ~/Workspace/jzintv-20200712-src)
#
# Usage: build-jzintv-core.sh [--abi <abi>]... [--all-abis] [--with-roms] [--refresh]
#
# --abi/--all-abis are accepted (and echoed in the .source-info stamp) for
# symmetry with the family's other staging scripts, but change nothing here:
# unlike openMSX's per-ABI static archives, this core is plain C/C++ compiled
# directly by AGP's externalNativeBuild per ABI -- there is no per-ABI staging
# step, only per-ABI *compilation*, which CMakeLists.txt handles.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

INTV_DESKTOP_SRC="${INTV_DESKTOP_SRC:-${HOME}/Workspace/fujinet-go-intv-desktop}"
JZINTV_SRC="${JZINTV_SRC:-${HOME}/Workspace/jzintv-20200712-src}"

STAGE="${PROJECT_ROOT}/app/src/main/cpp-generated/intv"
ROMS_OUT="${PROJECT_ROOT}/app/src/main/assets-generated/intv-roms"

WITH_ROMS=0
REFRESH=0
ABIS=()
ALL_ABIS=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) ABIS+=("$2"); shift 2 ;;
        --all-abis) ALL_ABIS=1; shift ;;
        --with-roms) WITH_ROMS=1; shift ;;
        --refresh) REFRESH=1; shift ;;
        *) echo "build-jzintv-core.sh: unknown argument: $1" >&2; exit 1 ;;
    esac
done

fail() { echo "build-jzintv-core.sh: $*" >&2; exit 1; }

[[ -f "${INTV_DESKTOP_SRC}/core/include/intvsession.h" ]] || fail \
    "fujinet-go-intv-desktop checkout not found at ${INTV_DESKTOP_SRC}
       set INTV_DESKTOP_SRC=/path/to/fujinet-go-intv-desktop"

if [[ -d "${STAGE}" && "${REFRESH}" -eq 0 && -f "${STAGE}/jzintv-generated/src/jzintv.c" ]]; then
    echo "build-jzintv-core.sh: ${STAGE} already staged (pass --refresh to restage)"
else
    rm -rf "${STAGE}"
    mkdir -p "${STAGE}"

    # ---- 1. Obtain an already-patched jzintv-generated/ tree ---------------
    DESKTOP_STAGED="${INTV_DESKTOP_SRC}/core/jzintv-generated"
    if [[ -f "${DESKTOP_STAGED}/src/jzintv.c" ]]; then
        echo "build-jzintv-core.sh: reusing desktop's already-staged jzintv-generated/"
        cp -a "${DESKTOP_STAGED}" "${STAGE}/jzintv-generated"
    else
        echo "build-jzintv-core.sh: desktop has no staged tree yet; staging from JZINTV_SRC"
        [[ -f "${JZINTV_SRC}/src/jzintv.c" ]] || fail \
            "no staged jzIntv tree at ${DESKTOP_STAGED} and no source at ${JZINTV_SRC}
       either run fujinet-go-intv-desktop's own cmake configure once (which
       stages it), or set JZINTV_SRC=/path/to/jzintv-20200712-src"
        mkdir -p "${STAGE}/jzintv-generated"
        cp -a "${JZINTV_SRC}/src" "${STAGE}/jzintv-generated/src"
        python3 "${INTV_DESKTOP_SRC}/tools/jzintv/patch-staged-tree.py" \
            "${STAGE}/jzintv-generated" \
            "${INTV_DESKTOP_SRC}/tools/jzintv/jzintv-fujinet.patch"
    fi

    # ---- 2. Copy the toolkit-agnostic glue, minus the SDL3-backed pieces ---
    # (paths.c, audio_sdl.c, gamepad_sdl.c, fujinet_runtime.c are replaced by
    # Android-native files in app/src/main/cpp/ -- see CMakeLists.txt.)
    mkdir -p "${STAGE}/include" "${STAGE}/src" "${STAGE}/jzintv/desktop"
    cp -a "${INTV_DESKTOP_SRC}/core/include/." "${STAGE}/include/"
    cp "${INTV_DESKTOP_SRC}/core/jzintv/intv_frame.c" \
       "${INTV_DESKTOP_SRC}/core/jzintv/intv_frame.h" \
       "${INTV_DESKTOP_SRC}/core/jzintv/intv_audio.c" \
       "${INTV_DESKTOP_SRC}/core/jzintv/intv_audio.h" \
       "${INTV_DESKTOP_SRC}/core/jzintv/intv_host.c" \
       "${INTV_DESKTOP_SRC}/core/jzintv/intv_host.h" \
       "${STAGE}/jzintv/"
    cp "${INTV_DESKTOP_SRC}/core/jzintv/desktop/gfx_desktop.c" \
       "${INTV_DESKTOP_SRC}/core/jzintv/desktop/snd_desktop.c" \
       "${STAGE}/jzintv/desktop/"
    cp "${INTV_DESKTOP_SRC}/core/src/session.c" \
       "${INTV_DESKTOP_SRC}/core/src/settings.c" \
       "${INTV_DESKTOP_SRC}/core/src/intv_keymap.c" \
       "${INTV_DESKTOP_SRC}/core/src/session_internal.h" \
       "${INTV_DESKTOP_SRC}/core/src/compat.h" \
       "${INTV_DESKTOP_SRC}/core/src/roms_embedded.h" \
       "${INTV_DESKTOP_SRC}/core/src/gamepad_sdl.h" \
       "${STAGE}/src/"
    mkdir -p "${STAGE}/embed-roms"
    cp "${INTV_DESKTOP_SRC}/tools/jzintv/embed-roms.py" "${STAGE}/embed-roms/"

    # ---- Android-only compatibility patch: zlib's Z_SOLO ptrdiff_t guess --
    # jzIntv's bundled zlib (src/zlib/zutil.h) builds with Z_SOLO defined
    # (zconf.h), which skips <stddef.h> and instead *guesses* ptrdiff_t is
    # `long`. That guess is right on every desktop target (all LP64) and on
    # arm64-v8a/x86_64 (also LP64), but wrong on the ILP32 armeabi-v7a ABI,
    # where bionic's ptrdiff_t is `int` -- "typedef redefinition with
    # different types ('long' vs 'int')". Upstream already special-cases
    # __TERMUX__ (Android's other userland) for exactly this reason; add
    # __ANDROID__ (defined by the NDK toolchain on every Android ABI, so
    # this is a no-op everywhere else, including the desktop build this was
    # staged from) rather than patch it out only for the 32-bit ABI.
    sed -i \
        's/#if !defined(WIN32) \&\& !defined(__TERMUX__)/#if !defined(WIN32) \&\& !defined(__TERMUX__) \&\& !defined(__ANDROID__)/' \
        "${STAGE}/jzintv-generated/src/zlib/zutil.h"

    {
        echo "desktop_src=${INTV_DESKTOP_SRC}"
        echo "desktop_commit=$(git -C "${INTV_DESKTOP_SRC}" rev-parse HEAD 2>/dev/null || echo local)"
        if [[ -n "$(git -C "${INTV_DESKTOP_SRC}" status --porcelain 2>/dev/null)" ]]; then
            echo "desktop_dirty=1"
        fi
        echo "jzintv_version=20200712"
        echo "abis=${ABIS[*]:-${ALL_ABIS:+all}}"
    } > "${STAGE}/.source-info"

    echo "build-jzintv-core.sh: staged $(find "${STAGE}" -name '*.c' -o -name '*.cpp' | wc -l) source files into ${STAGE}"
fi

if [[ "${WITH_ROMS}" -eq 1 ]]; then
    ROM_SRC="${INTV_DESKTOP_SRC}/tools/jzintv/roms"
    [[ -f "${ROM_SRC}/exec.bin" && -f "${ROM_SRC}/grom.bin" ]] || fail \
        "--with-roms requested but ${ROM_SRC}/{exec,grom}.bin not found"
    mkdir -p "${ROMS_OUT}"
    cp "${ROM_SRC}/exec.bin" "${ROM_SRC}/grom.bin" "${ROMS_OUT}/"
    [[ -f "${ROM_SRC}/ecs.bin" ]] && cp "${ROM_SRC}/ecs.bin" "${ROMS_OUT}/"
    cat >&2 <<'BANNER'

  ================================================================
   DEV BUILD -- system ROMs staged into assets-generated/intv-roms.
   DO NOT DISTRIBUTE this build. Release builds must NOT pass
   -PintvRoms=true (the release build type refuses it outright).
  ================================================================

BANNER
else
    # Without --with-roms this must be a distributable tree: remove any ROMs
    # staged by an earlier dev build, or they'd ride along into the release
    # merged assets (verifyNoEmbeddedRoms catches this, but fix the cause).
    rm -rf "${ROMS_OUT}"
fi
