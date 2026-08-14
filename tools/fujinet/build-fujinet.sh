#!/usr/bin/env bash
#
# Build the FujiNet Intellivision runtime as a shared library (libfujinet.so)
# plus its default runtime assets (fnconfig.ini / data / SD) for the Android
# app.
#
# Adapted from fujinet-go-msx's tools/fujinet/build-fujinet.sh (itself
# adapted from fujinet-go-apple2's). Differences:
#   * Source is the user's LOCAL fujinet-firmware checkout (not a target-
#     specific fork) -- fujinet-go-intv-desktop's own FujiNet build
#     (cmake/FujiNetRuntime.cmake) also builds from third_party/fujinet-
#     firmware, pinned at commit a0991d7bd176ba8aa833b869571e328749d964be --
#     so this is the same source tree, not a different one.
#   * The PC build targets RS232 (build.sh -cp RS232), the same target the
#     desktop app uses: jzIntv's fujibus device (src/fujinet/fn_sock.c in the
#     staged jzIntv tree, patched in by jzintv-fujinet.patch) rides a
#     BoIP TCP client, and RS232's Becker-style BoIP channel *listens* by
#     default -- exactly the direction the emulator needs.
#   * fnconfig.ini is forced to [BOIP] enabled=1 host=127.0.0.1 port=65503,
#     matching INTVSESSION_BOIP_PORT (core/include/intvsession.h) so the
#     Android build agrees with the desktop app's own port, and collides
#     with nothing else in the family's port table (see README.md).
#   * The web admin UI binds 0.0.0.0:8057 (support/fujinet_android_entry.cpp),
#     which the app's FujiNet tab (FujiNetWebViewActivity) opens on loopback.
#
# In-process Android adaptations (SHARED target, the
# fujinet_android_entry.cpp wrapper, fnSystem.clear_shutdown_request(), a
# reboot()/exit() guard, mbedTLS for Android, expat-from-source, termios/
# libssh fixes) are applied as text transforms to the staged copy -- the same
# fujinet-pc codebase every family member's build-fujinet.sh patches, so the
# transforms are shared verbatim.

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
PROJECT_ROOT=$(cd -- "${SCRIPT_DIR}/../.." &>/dev/null && pwd)
SUPPORT_DIR="${SCRIPT_DIR}/support"
WORK_ROOT="${SCRIPT_DIR}/work"
CLONE_DIR="${WORK_ROOT}/fujinet-firmware"
GENERATED_ASSET_ROOT="${PROJECT_ROOT}/app/src/main/assets-generated/fujinet"
GENERATED_JNI_ROOT="${PROJECT_ROOT}/app/src/main/jniLibs-generated"

# Local fujinet-firmware checkout (override with FUJINET_SRC=/path ...).
FUJINET_SRC="${FUJINET_SRC:-${HOME}/Workspace/fujinet-firmware}"
PC_TARGET="RS232"
BOIP_PORT=65503
WEBUI_PORT=8057

MBEDTLS_URL="https://github.com/Mbed-TLS/mbedtls.git"
MBEDTLS_TAG="mbedtls-3.6.5"
MBEDTLS_COMMIT="e185d7fd85499c8ce5ca2a54f5cf8fe7dbe3f8df"
MBEDTLS_SOURCE_DIR="${WORK_ROOT}/mbedtls-src"
MBEDTLS_BUILD_ROOT="${WORK_ROOT}/mbedtls-build"
MBEDTLS_INSTALL_ROOT="${WORK_ROOT}/mbedtls-install"
# x86 (32-bit) excluded, matching the packaged ABIs (see app/build.gradle.kts).
DEFAULT_ANDROID_ABIS=("armeabi-v7a" "arm64-v8a" "x86_64")

REQUESTED_ABIS=()
BUILD_ALL_ABIS=0
REFRESH_CLONE=0
ANDROID_ABI_VALUE=""

usage() {
    cat <<'EOF'
Usage: bash tools/fujinet/build-fujinet.sh (--all-abis | --abi <abi> [--abi <abi> ...]) [--refresh]

Builds the FujiNet Intv Android runtime from the local fujinet-firmware checkout.
EOF
}

fail() {
    echo "build-fujinet.sh: $*" >&2
    exit 1
}

abi_supported() {
    local abi="$1" candidate
    for candidate in "${DEFAULT_ANDROID_ABIS[@]}"; do
        [[ "${candidate}" == "${abi}" ]] && return 0
    done
    return 1
}

resolve_requested_abis() {
    local -a resolved=()
    local abi
    if [[ "${BUILD_ALL_ABIS}" -eq 1 ]]; then
        printf '%s\n' "${DEFAULT_ANDROID_ABIS[@]}"
        return
    fi
    [[ "${#REQUESTED_ABIS[@]}" -gt 0 ]] || fail "Specify --all-abis or at least one --abi"
    for abi in "${REQUESTED_ABIS[@]}"; do
        abi_supported "${abi}" || fail "Unsupported ABI: ${abi}"
        if [[ " ${resolved[*]} " != *" ${abi} "* ]]; then
            resolved+=("${abi}")
        fi
    done
    printf '%s\n' "${resolved[@]}"
}

read_sdk_dir() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        printf '%s\n' "${ANDROID_SDK_ROOT}"
        return
    fi
    local local_properties="${PROJECT_ROOT}/local.properties"
    if [[ -f "${local_properties}" ]]; then
        local sdk_dir
        sdk_dir=$(sed -n 's/^sdk.dir=//p' "${local_properties}" | tail -n 1)
        [[ -n "${sdk_dir}" ]] && { printf '%s\n' "${sdk_dir}"; return; }
    fi
    fail "ANDROID_SDK_ROOT is not set and sdk.dir is missing from local.properties"
}

find_latest_ndk() {
    local sdk_dir="$1" ndk_root="${sdk_dir}/ndk" candidate
    [[ -d "${ndk_root}" ]] || return 1
    while IFS= read -r candidate; do
        if [[ -f "${ndk_root}/${candidate}/build/cmake/android.toolchain.cmake" ]]; then
            printf '%s\n' "${ndk_root}/${candidate}"
            return 0
        fi
    done < <(find "${ndk_root}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -Vr)
    return 1
}

resolve_ndk_dir() {
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then printf '%s\n' "${ANDROID_NDK_HOME}"; return; fi
    if [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then printf '%s\n' "${ANDROID_NDK_ROOT}"; return; fi
    local local_properties="${PROJECT_ROOT}/local.properties" ndk_dir
    if [[ -f "${local_properties}" ]]; then
        ndk_dir=$(sed -n 's/^ndk.dir=//p' "${local_properties}" | tail -n 1)
        [[ -n "${ndk_dir}" ]] && { printf '%s\n' "${ndk_dir}"; return; }
    fi
    if ndk_dir=$(find_latest_ndk "$(read_sdk_dir)"); then printf '%s\n' "${ndk_dir}"; return; fi
    [[ -f "/opt/android-ndk/build/cmake/android.toolchain.cmake" ]] && { printf '%s\n' "/opt/android-ndk"; return; }
    fail "Unable to locate an Android NDK (set ANDROID_NDK_HOME or ndk.dir)"
}

stage_local_source() {
    [[ -d "${FUJINET_SRC}" ]] || fail "fujinet-firmware source not found at ${FUJINET_SRC} (set FUJINET_SRC)"
    [[ -f "${FUJINET_SRC}/build.sh" ]] || fail "build.sh missing under ${FUJINET_SRC}"

    rm -rf "${CLONE_DIR}"
    mkdir -p "${WORK_ROOT}"
    rsync -a --delete \
        --exclude '.git' --exclude 'build/' --exclude 'dist/' \
        "${FUJINET_SRC}/" "${CLONE_DIR}/"
}

apply_android_patches() {
    python3 - "${CLONE_DIR}" "${SUPPORT_DIR}" "${BOIP_PORT}" <<'PY'
from pathlib import Path
import sys

clone_dir = Path(sys.argv[1])
support_dir = Path(sys.argv[2])
boip_port = sys.argv[3]

def patch(rel, transforms, required=True):
    p = clone_dir / rel
    if not p.exists():
        if required:
            sys.exit(f"build-fujinet.sh: expected file missing: {rel}")
        return
    text = p.read_text()
    for old, new, *opt in transforms:
        count = opt[0] if opt else 1
        if old not in text:
            if new in text:
                continue
            sys.exit(f"build-fujinet.sh: patch anchor not found in {rel}:\n---\n{old[:200]}\n---")
        text = text.replace(old, new, count)
    p.write_text(text)

# --- lib/config/fnConfig.h: BOIP default port -> INTVSESSION_BOIP_PORT ----
# The generic "#else" default (1985, the Apple dev-relay port) is not what
# jzIntv's fujibus device connects to -- move it to 65503 so the default
# used when fnconfig.ini has no/empty [BOIP] port agrees with the desktop
# app's own INTVSESSION_BOIP_PORT (core/include/intvsession.h).
patch("lib/config/fnConfig.h", [
    (
        "// Dev relay over network, used by Apple\n"
        "#  define CONFIG_DEFAULT_BOIP_PORT 1985",
        f"// Intv (FujiNet Go): matches INTVSESSION_BOIP_PORT, so jzIntv's\n"
        f"// --fujinet default agrees with this runtime's default.\n"
        f"#  define CONFIG_DEFAULT_BOIP_PORT {boip_port}",
    ),
])

# --- build.sh: Android cross-compile wiring -------------------------------
patch("build.sh", [
    (
        '  export PROJECT_CONFIG=$INI_FILE\n  GEN_CMD=""\n',
        '  export PROJECT_CONFIG=$INI_FILE\n'
        '  CMAKE_EXTRA_ARGS=()\n'
        '  if [ -n "${ANDROID_NDK_HOME}" ] ; then\n'
        '    if [ -z "${ANDROID_ABI}" ] ; then\n'
        '      echo "ANDROID_ABI must be set for Android PC builds"\n'
        '      exit 1\n'
        '    fi\n'
        '    ANDROID_PLATFORM=${ANDROID_PLATFORM:-android-26}\n'
        '    CMAKE_EXTRA_ARGS+=(\n'
        '      "-DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake"\n'
        '      "-DANDROID_ABI=${ANDROID_ABI}"\n'
        '      "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"\n'
        '      "-DANDROID_STL=c++_static"\n'
        '      "-DFUJINET_ANDROID=ON"\n'
        '      "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"\n'
        '      "-DWITH_MBEDTLS=ON"\n'
        '      "-DWITH_GSSAPI=OFF"\n'
        '      "-DWITH_EXAMPLES=OFF"\n'
        '    )\n'
        '    if [ -n "${MBEDTLS_ROOT_DIR}" ] ; then\n'
        '      CMAKE_EXTRA_ARGS+=("-DMBEDTLS_ROOT_DIR=${MBEDTLS_ROOT_DIR}")\n'
        '    fi\n'
        '  fi\n'
        '  GEN_CMD=""\n',
    ),
    (
        '    cmake .. -DCMAKE_EXPORT_COMPILE_COMMANDS=1 -DFUJINET_TARGET=$PC_TARGET "$@"\n',
        '    cmake .. "${CMAKE_EXTRA_ARGS[@]}" -DCMAKE_EXPORT_COMPILE_COMMANDS=1 -DFUJINET_TARGET=$PC_TARGET "$@"\n',
    ),
    (
        '    cmake "$GEN_CMD" .. -DCMAKE_EXPORT_COMPILE_COMMANDS=1 -DFUJINET_TARGET=$PC_TARGET "$@"\n',
        '    cmake "$GEN_CMD" .. "${CMAKE_EXTRA_ARGS[@]}" -DCMAKE_EXPORT_COMPILE_COMMANDS=1 -DFUJINET_TARGET=$PC_TARGET "$@"\n',
    ),
    (
        '    cmake .. -DFUJINET_TARGET=$PC_TARGET -DCMAKE_BUILD_TYPE=$BUILD_TYPE "$@"\n',
        '    cmake .. "${CMAKE_EXTRA_ARGS[@]}" -DFUJINET_TARGET=$PC_TARGET -DCMAKE_BUILD_TYPE=$BUILD_TYPE "$@"\n',
    ),
    (
        '    cmake "$GEN_CMD" .. -DFUJINET_TARGET=$PC_TARGET -DCMAKE_BUILD_TYPE=$BUILD_TYPE "$@"\n',
        '    cmake "$GEN_CMD" .. "${CMAKE_EXTRA_ARGS[@]}" -DFUJINET_TARGET=$PC_TARGET -DCMAKE_BUILD_TYPE=$BUILD_TYPE "$@"\n',
    ),
    (
        '        pip install platformio || exit 1\n',
        '        pip install platformio pyyaml jinja2 || exit 1\n',
    ),
    (
        '  # run unit tests\n  ctest -V --progress\n',
        '  # run unit tests (skip for Android cross-builds: target-arch binaries)\n'
        '  if [ -z "${ANDROID_ABI}" ] ; then ctest -V --progress ; else echo "Skipping unit tests (Android cross-build)" ; true ; fi\n',
    ),
])

# --- fujinet_pc.cmake: SHARED target, mbedTLS, expat, dist ----------------
patch("fujinet_pc.cmake", [
    (
        'add_executable(fujinet ${SOURCES})\n',
        'if(FUJINET_ANDROID)\n'
        '    add_library(fujinet SHARED ${SOURCES} android/fujinet_android_entry.cpp)\n'
        '    set_target_properties(fujinet PROPERTIES OUTPUT_NAME "fujinet")\n'
        '    target_compile_definitions(fujinet PRIVATE FUJINET_ANDROID=1)\n'
        'else()\n'
        '    add_executable(fujinet ${SOURCES})\n'
        'endif()\n',
    ),
    (
        'set(_MBEDTLS_ROOT_HINTS $ENV{MBEDTLS_ROOT_DIR} ${MBEDTLS_ROOT_DIR})\n'
        'set(_MBEDTLS_ROOT_PATHS "$ENV{PROGRAMFILES}/libmbedtls")\n'
        'set(_MBEDTLS_ROOT_HINTS_AND_PATHS HINTS ${_MBEDTLS_ROOT_HINTS} PATHS ${_MBEDTLS_ROOT_PATHS})\n'
        'find_library(MBEDTLS_STATIC_LIB libmbedtls.a HINTS ${_MBEDTLS_ROOT_HINTS_AND_PATHS})\n'
        'find_library(MBEDX509_STATIC_LIB libmbedx509.a HINTS ${_MBEDTLS_ROOT_HINTS_AND_PATHS})\n'
        'find_library(MBEDCRYPTO_STATIC_LIB libmbedcrypto.a HINTS ${_MBEDTLS_ROOT_HINTS_AND_PATHS})\n'
        'find_path(MBEDTLS_INCLUDE_DIR mbedtls/ssl.h HINTS ${_MBEDTLS_ROOT_HINTS_AND_PATHS} PATH_SUFFIXES include)\n',
        'if(FUJINET_ANDROID AND DEFINED MBEDTLS_ROOT_DIR)\n'
        '    set(MBEDTLS_STATIC_LIB "${MBEDTLS_ROOT_DIR}/lib/libmbedtls.a")\n'
        '    set(MBEDX509_STATIC_LIB "${MBEDTLS_ROOT_DIR}/lib/libmbedx509.a")\n'
        '    set(MBEDCRYPTO_STATIC_LIB "${MBEDTLS_ROOT_DIR}/lib/libmbedcrypto.a")\n'
        '    set(MBEDTLS_INCLUDE_DIR "${MBEDTLS_ROOT_DIR}/include")\n'
        'else()\n'
        '    set(_MBEDTLS_ROOT_HINTS $ENV{MBEDTLS_ROOT_DIR} ${MBEDTLS_ROOT_DIR})\n'
        '    set(_MBEDTLS_ROOT_PATHS "$ENV{PROGRAMFILES}/libmbedtls")\n'
        '    set(_MBEDTLS_ROOT_HINTS_AND_PATHS HINTS ${_MBEDTLS_ROOT_HINTS} PATHS ${_MBEDTLS_ROOT_PATHS})\n'
        '    find_library(MBEDTLS_STATIC_LIB libmbedtls.a HINTS ${_MBEDTLS_ROOT_HINTS_AND_PATHS})\n'
        '    find_library(MBEDX509_STATIC_LIB libmbedx509.a HINTS ${_MBEDTLS_ROOT_HINTS_AND_PATHS})\n'
        '    find_library(MBEDCRYPTO_STATIC_LIB libmbedcrypto.a HINTS ${_MBEDTLS_ROOT_HINTS_AND_PATHS})\n'
        '    find_path(MBEDTLS_INCLUDE_DIR mbedtls/ssl.h HINTS ${_MBEDTLS_ROOT_HINTS_AND_PATHS} PATH_SUFFIXES include)\n'
        'endif()\n',
    ),
    (
        'target_link_libraries(fujinet pthread expat cjson cjson_utils smb2 ssh nfs gumbo_fn)\n',
        'if(FUJINET_ANDROID)\n'
        '    set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)\n'
        '    set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)\n'
        '    add_subdirectory(components/expat/expat/expat)\n'
        '    find_library(ANDROID_LOG_LIB log)\n'
        '    target_link_libraries(fujinet expat cjson cjson_utils smb2 ssh nfs gumbo_fn ${ANDROID_LOG_LIB})\n'
        'else()\n'
        '    target_link_libraries(fujinet pthread expat cjson cjson_utils smb2 ssh nfs gumbo_fn)\n'
        'endif()\n',
    ),
    (
        '        COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:fujinet> dist\n',
        '        COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:fujinet> dist/libfujinet.so\n',
        2,
    ),
])

# --- fnSystem: clear_shutdown_request() + Android reboot guard ------------
patch("lib/hardware/fnSystem.h", [
    (
        '    int request_for_shutdown();\n'
        '    int check_for_shutdown();\n',
        '    int request_for_shutdown();\n'
        '    int check_for_shutdown();\n'
        '    void clear_shutdown_request();\n',
    ),
])
patch("lib/hardware/fnSystem.cpp", [
    (
        'int SystemManager::check_for_shutdown()\n'
        '{\n'
        '    return _shutdown_requests;\n'
        '}\n',
        'int SystemManager::check_for_shutdown()\n'
        '{\n'
        '    return _shutdown_requests;\n'
        '}\n'
        'void SystemManager::clear_shutdown_request()\n'
        '{\n'
        '    _shutdown_requests = 0;\n'
        '}\n',
    ),
    (
        '        // do cleanup and exit\n'
        '        Debug_println("SystemManager::reboot - exiting ...");\n'
        '        // FN will be restarted if ended with EXIT_AND_RESTART (75)\n'
        '        exit(_reboot_code);\n',
        '        // do cleanup and exit\n'
        '        Debug_println("SystemManager::reboot - exiting ...");\n'
        '#if defined(FUJINET_ANDROID)\n'
        '        // Android embeds FujiNet in-process; exit() would kill the app.\n'
        '        // Ask the service loop to stop so the app can restart the thread.\n'
        '        request_for_shutdown();\n'
        '        return;\n'
        '#else\n'
        '        // FN will be restarted if ended with EXIT_AND_RESTART (75)\n'
        '        exit(_reboot_code);\n'
        '#endif\n',
    ),
])

# --- libssh FindMbedTLS.cmake: use explicit Android static-lib paths ------
patch("components_pc/libssh/cmake/Modules/FindMbedTLS.cmake", [
    (
        'find_path(MBEDTLS_INCLUDE_DIR\n',
        '# [fujinet-go-intv] Resolve the cross-compiled Android mbedTLS directly;\n'
        '# CMake find_library/find_path are sysroot-restricted under the NDK.\n'
        'if(DEFINED MBEDTLS_ROOT_DIR AND EXISTS "${MBEDTLS_ROOT_DIR}/include/mbedtls/ssl.h")\n'
        '    set(MBEDTLS_INCLUDE_DIR "${MBEDTLS_ROOT_DIR}/include" CACHE PATH "" FORCE)\n'
        '    set(MBEDTLS_SSL_LIBRARY "${MBEDTLS_ROOT_DIR}/lib/libmbedtls.a" CACHE FILEPATH "" FORCE)\n'
        '    set(MBEDTLS_CRYPTO_LIBRARY "${MBEDTLS_ROOT_DIR}/lib/libmbedcrypto.a" CACHE FILEPATH "" FORCE)\n'
        '    set(MBEDTLS_X509_LIBRARY "${MBEDTLS_ROOT_DIR}/lib/libmbedx509.a" CACHE FILEPATH "" FORCE)\n'
        'endif()\n'
        'find_path(MBEDTLS_INCLUDE_DIR\n',
    ),
])

# --- linux_termios2.h: bionic already defines struct termios2 -------------
patch("lib/compat/linux_termios2.h", [
    (
        'struct termios2 {\n'
        '        tcflag_t c_iflag;               /* input mode flags */\n'
        '        tcflag_t c_oflag;               /* output mode flags */\n'
        '        tcflag_t c_cflag;               /* control mode flags */\n'
        '        tcflag_t c_lflag;               /* local mode flags */\n'
        '        cc_t c_line;                    /* line discipline */\n'
        '        cc_t c_cc[LINUX_NCCS];          /* control characters */\n'
        '        speed_t c_ispeed;               /* input speed */\n'
        '        speed_t c_ospeed;               /* output speed */\n'
        '};\n',
        '#ifndef __ANDROID__\n'
        'struct termios2 {\n'
        '        tcflag_t c_iflag;               /* input mode flags */\n'
        '        tcflag_t c_oflag;               /* output mode flags */\n'
        '        tcflag_t c_cflag;               /* control mode flags */\n'
        '        tcflag_t c_lflag;               /* local mode flags */\n'
        '        cc_t c_line;                    /* line discipline */\n'
        '        cc_t c_cc[LINUX_NCCS];          /* control characters */\n'
        '        speed_t c_ispeed;               /* input speed */\n'
        '        speed_t c_ospeed;               /* output speed */\n'
        '};\n'
        '#endif\n',
    ),
], required=False)

# --- libssh misc.c: S_IWRITE not defined on Android ----------------------
patch("components_pc/libssh/src/misc.c", [
    (
        '#include <sys/stat.h>\n'
        '#include <sys/types.h>\n',
        '#include <sys/stat.h>\n'
        '#include <sys/types.h>\n'
        '#ifndef S_IWRITE\n'
        '#define S_IWRITE S_IWUSR\n'
        '#endif\n',
    ),
], required=False)

# --- fnFsSPIFFS: absolute flash "data" root (Android in-process) -----------
patch("lib/FileSystem/fnFsSPIFFS.cpp", [
    (
        '    strlcpy(_basepath, "data", sizeof(_basepath));',
        '    {\n'
        '        const char *fn_root = getenv("FUJINET_RUNTIME_ROOT");\n'
        '        if (fn_root && *fn_root) {\n'
        '            strlcpy(_basepath, fn_root, sizeof(_basepath));\n'
        '            strlcat(_basepath, "/data", sizeof(_basepath));\n'
        '        } else {\n'
        '            strlcpy(_basepath, "data", sizeof(_basepath));\n'
        '        }\n'
        '    }',
    ),
])

# --- mgHttpClient: absolute CA bundle path (Android in-process) ------------
patch("lib/http/mgHttpClient.cpp", [
    (
        '        tempCa = mg_file_read(&mg_fs_posix, "data/ca.pem");',
        '        const char *fn_root = getenv("FUJINET_RUNTIME_ROOT");\n'
        '        std::string ca_path = (fn_root && *fn_root)\n'
        '            ? std::string(fn_root) + "/data/ca.pem"\n'
        '            : std::string("data/ca.pem");\n'
        '        tempCa = mg_file_read(&mg_fs_posix, ca_path.c_str());',
    ),
])

# --- pc_rtos task shim: name worker threads after their FreeRTOS task ------
patch("lib/compat/pc_rtos/pc_rtos.cpp", [
    (
        '#include <mutex>\n'
        '#include <thread>\n',
        '#include <mutex>\n'
        '#include <pthread.h>\n'
        '#include <thread>\n',
    ),
    (
        'static BaseType_t pc_task_create(TaskFunction_t fn, void *arg, TaskHandle_t *out_handle)\n'
        '{\n'
        '    std::thread t([fn, arg] { fn(arg); });\n'
        '    t.detach();\n',
        'static BaseType_t pc_task_create(TaskFunction_t fn, const char *name, void *arg, TaskHandle_t *out_handle)\n'
        '{\n'
        '    std::thread t([fn, arg, name] {\n'
        '        if (name && *name) {\n'
        '            char tn[16];\n'
        '            strncpy(tn, name, sizeof(tn) - 1);\n'
        '            tn[sizeof(tn) - 1] = 0;\n'
        '            pthread_setname_np(pthread_self(), tn);\n'
        '        }\n'
        '        fn(arg);\n'
        '    });\n'
        '    t.detach();\n',
    ),
    (
        'extern "C" BaseType_t xTaskCreate(TaskFunction_t fn, const char *, uint32_t, void *arg,\n'
        '                                  UBaseType_t, TaskHandle_t *out_handle)\n'
        '{\n'
        '    return pc_task_create(fn, arg, out_handle);\n'
        '}\n',
        'extern "C" BaseType_t xTaskCreate(TaskFunction_t fn, const char *name, uint32_t, void *arg,\n'
        '                                  UBaseType_t, TaskHandle_t *out_handle)\n'
        '{\n'
        '    return pc_task_create(fn, name, arg, out_handle);\n'
        '}\n',
    ),
    (
        'extern "C" BaseType_t xTaskCreatePinnedToCore(TaskFunction_t fn, const char *, uint32_t, void *arg,\n'
        '                                              UBaseType_t, TaskHandle_t *out_handle, BaseType_t)\n'
        '{\n'
        '    return pc_task_create(fn, arg, out_handle);\n'
        '}\n',
        'extern "C" BaseType_t xTaskCreatePinnedToCore(TaskFunction_t fn, const char *name, uint32_t, void *arg,\n'
        '                                              UBaseType_t, TaskHandle_t *out_handle, BaseType_t)\n'
        '{\n'
        '    return pc_task_create(fn, name, arg, out_handle);\n'
        '}\n',
    ),
], required=False)

# --- Drop in the Android entry-point wrapper ------------------------------
android_dir = clone_dir / "android"
android_dir.mkdir(exist_ok=True)
(android_dir / "fujinet_android_entry.cpp").write_text(
    (support_dir / "fujinet_android_entry.cpp").read_text()
)
PY
}

apply_local_patch_files() {
    local patch_dir="${SCRIPT_DIR}/patches"
    [[ -d "${patch_dir}" ]] || return 0
    local patch_file
    while IFS= read -r patch_file; do
        patch -d "${CLONE_DIR}" -p1 < "${patch_file}"
    done < <(find "${patch_dir}" -maxdepth 1 -type f -name '*.patch' | sort)
}

configure_mbedtls_for_android() {
    python3 - "${MBEDTLS_SOURCE_DIR}/include/mbedtls/mbedtls_config.h" <<'PY'
from pathlib import Path
import sys
config_h = Path(sys.argv[1])
text = config_h.read_text()
for old, new in (
    ('//#define MBEDTLS_THREADING_C\n', '#define MBEDTLS_THREADING_C\n'),
    ('//#define MBEDTLS_THREADING_PTHREAD\n', '#define MBEDTLS_THREADING_PTHREAD\n'),
):
    if old in text:
        text = text.replace(old, new)
config_h.write_text(text)
PY
}

prepare_mbedtls_source() {
    if [[ "${REFRESH_CLONE}" -eq 1 ]]; then
        rm -rf "${MBEDTLS_SOURCE_DIR}"
    fi
    if [[ ! -d "${MBEDTLS_SOURCE_DIR}/.git" ]]; then
        rm -rf "${MBEDTLS_SOURCE_DIR}"
        mkdir -p "${WORK_ROOT}"
        git clone --depth 1 --branch "${MBEDTLS_TAG}" "${MBEDTLS_URL}" "${MBEDTLS_SOURCE_DIR}"
    fi
    (
        cd "${MBEDTLS_SOURCE_DIR}"
        git fetch --depth 1 origin "${MBEDTLS_COMMIT}"
        git checkout --detach "${MBEDTLS_COMMIT}"
        git submodule update --init --recursive --depth 1
    )
}

build_mbedtls() {
    local mbedtls_build_dir="${MBEDTLS_BUILD_ROOT}/${ANDROID_ABI_VALUE}"
    local mbedtls_install_dir="${MBEDTLS_INSTALL_ROOT}/${ANDROID_ABI_VALUE}"

    if [[ "${REFRESH_CLONE}" -eq 0 ]] \
        && [[ -f "${mbedtls_install_dir}/lib/libmbedtls.a" ]] \
        && [[ -f "${mbedtls_install_dir}/lib/libmbedx509.a" ]] \
        && [[ -f "${mbedtls_install_dir}/lib/libmbedcrypto.a" ]]; then
        export MBEDTLS_ROOT_DIR="${mbedtls_install_dir}"
        return
    fi

    prepare_mbedtls_source
    configure_mbedtls_for_android

    rm -rf "${mbedtls_build_dir}" "${mbedtls_install_dir}"
    mkdir -p "${MBEDTLS_BUILD_ROOT}" "${MBEDTLS_INSTALL_ROOT}"

    cmake \
        -S "${MBEDTLS_SOURCE_DIR}" \
        -B "${mbedtls_build_dir}" \
        -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN_FILE}" \
        -DANDROID_ABI="${ANDROID_ABI_VALUE}" \
        -DANDROID_PLATFORM="${ANDROID_PLATFORM:-android-26}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="${mbedtls_install_dir}" \
        -DENABLE_PROGRAMS=OFF \
        -DENABLE_TESTING=OFF \
        -DMBEDTLS_FATAL_WARNINGS=OFF \
        -DUSE_STATIC_MBEDTLS_LIBRARY=ON \
        -DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
        -DLINK_WITH_PTHREAD=ON \
        -DDISABLE_PACKAGE_CONFIG_AND_INSTALL=OFF

    cmake --build "${mbedtls_build_dir}" --target install --parallel
    [[ -f "${mbedtls_install_dir}/lib/libmbedtls.a" ]] || fail "Expected libmbedtls.a under ${mbedtls_install_dir}/lib"
    export MBEDTLS_ROOT_DIR="${mbedtls_install_dir}"
}

force_boip_config() {
    # Ensure the runtime's FujiBusPacket-over-BoIP endpoint is the loopback
    # listener jzIntv's --fujinet connects out to.
    python3 - "${GENERATED_ASSET_ROOT}/fnconfig.ini" "${BOIP_PORT}" <<'PY'
from pathlib import Path
import sys, re
ini = Path(sys.argv[1])
port = sys.argv[2]
text = ini.read_text() if ini.exists() else ""
section = f"[BOIP]\nenabled=1\nhost=127.0.0.1\nport={port}\n"
if re.search(r'(?im)^\[BOIP\]', text):
    text = re.sub(r'(?ims)^\[BOIP\].*?(?=^\[|\Z)', section, text)
else:
    if text and not text.endswith("\n"):
        text += "\n"
    text += "\n" + section
ini.write_text(text)
PY
}

copy_shared_outputs() {
    local dist_dir="$1"
    [[ -d "${dist_dir}/data" ]] || fail "Expected FujiNet data directory at ${dist_dir}/data"
    [[ -d "${dist_dir}/SD" ]] || fail "Expected FujiNet SD directory at ${dist_dir}/SD"
    [[ -f "${dist_dir}/fnconfig.ini" ]] || fail "Expected FujiNet config at ${dist_dir}/fnconfig.ini"

    mkdir -p "${GENERATED_ASSET_ROOT}"
    cp -R "${dist_dir}/data" "${GENERATED_ASSET_ROOT}/data"
    cp -R "${dist_dir}/SD" "${GENERATED_ASSET_ROOT}/SD"
    cp "${dist_dir}/fnconfig.ini" "${GENERATED_ASSET_ROOT}/fnconfig.ini"
    force_boip_config
    printf '%s (%s)\n' "${PC_TARGET}" "$(git -C "${FUJINET_SRC}" rev-parse --short HEAD 2>/dev/null || echo local)" \
        > "${GENERATED_ASSET_ROOT}/upstream-commit.txt"
}

copy_abi_output() {
    local abi="$1" dist_dir="$2"
    local lib_output="${dist_dir}/libfujinet.so"
    [[ -f "${lib_output}" ]] || fail "Expected shared library at ${lib_output}"
    mkdir -p "${GENERATED_JNI_ROOT}/${abi}"
    cp "${lib_output}" "${GENERATED_JNI_ROOT}/${abi}/libfujinet.so"
}

build_fujinet_for_abi() {
    local abi="$1"
    rm -rf "${CLONE_DIR}/build"
    export ANDROID_NDK_HOME="${NDK_DIR}"
    export ANDROID_ABI="${abi}"
    export ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-26}"
    ANDROID_ABI_VALUE="${abi}"

    build_mbedtls

    (
        cd "${CLONE_DIR}"
        bash ./build.sh -cp "${PC_TARGET}"
    )
}

# --------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) [[ $# -ge 2 ]] || fail "--abi requires a value"; REQUESTED_ABIS+=("$2"); shift 2 ;;
        --all-abis) BUILD_ALL_ABIS=1; shift ;;
        --refresh) REFRESH_CLONE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail "Unknown argument: $1" ;;
    esac
done

if [[ "${BUILD_ALL_ABIS}" -eq 1 && "${#REQUESTED_ABIS[@]}" -gt 0 ]]; then
    fail "Use --all-abis or --abi, not both"
fi

mapfile -t ANDROID_ABIS_TO_BUILD < <(resolve_requested_abis)

NDK_DIR="$(resolve_ndk_dir)"
TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake"
[[ -f "${TOOLCHAIN_FILE}" ]] || fail "Android toolchain file not found at ${TOOLCHAIN_FILE}"

mkdir -p "${WORK_ROOT}"
stage_local_source
rm -rf "${CLONE_DIR}/build"
apply_android_patches
apply_local_patch_files

# Dev affordance: stage + patch only, to validate the source transforms
# without running the (slow) cross-compile.
if [[ "${FN_PATCH_ONLY:-0}" -eq 1 ]]; then
    echo "FN_PATCH_ONLY: staged and patched ${CLONE_DIR}; skipping build."
    exit 0
fi

rm -rf "${GENERATED_ASSET_ROOT}" "${GENERATED_JNI_ROOT}"
mkdir -p "${GENERATED_JNI_ROOT}"

for index in "${!ANDROID_ABIS_TO_BUILD[@]}"; do
    abi="${ANDROID_ABIS_TO_BUILD[${index}]}"
    build_fujinet_for_abi "${abi}"
    DIST_DIR="${CLONE_DIR}/build/dist"
    if [[ "${index}" == "0" ]]; then
        copy_shared_outputs "${DIST_DIR}"
    fi
    copy_abi_output "${abi}" "${DIST_DIR}"
done

printf '%s\n' "${ANDROID_ABIS_TO_BUILD[@]}" > "${GENERATED_ASSET_ROOT}/android-abis.txt"

echo "FujiNet Intv Android runtime outputs updated:"
echo "  assets:  ${GENERATED_ASSET_ROOT}"
for abi in "${ANDROID_ABIS_TO_BUILD[@]}"; do
    echo "  jniLibs: ${GENERATED_JNI_ROOT}/${abi}/libfujinet.so"
done
