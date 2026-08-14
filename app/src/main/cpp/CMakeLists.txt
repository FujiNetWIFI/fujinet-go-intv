# libintvcore.so -- jzIntv (20200712, FujiNet-patched) + the toolkit-agnostic
# intvsession core, staged from fujinet-go-intv-desktop by
# tools/jzintv/build-jzintv-core.sh, plus the Android-native glue that
# replaces the desktop's SDL3 audio/gamepad/paths/fujinet-runtime backends.
#
# jzIntv selects its graphics/sound/event/platform backend entirely by
# *which files* are compiled -- never #ifdef inside a shared file (see the
# desktop's core/CMakeLists.txt header comment). This mirrors that file's
# JZINTV_CORE_SOURCES list exactly, minus core/debugger/** (out of scope for
# this app -- see the plan) and the WIN32-only pad file.

cmake_minimum_required(VERSION 3.22.1)
project("intvcore")

enable_language(C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
# src/locutus/*.cpp needs >= C++14; C++17 matches the rest of the family
# (app/build.gradle.kts' externalNativeBuild.cmake.cppFlags).
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---- the -O0 trap ----------------------------------------------------------
# AGP's NDK setup leaves CMAKE_*_FLAGS_DEBUG/RELEASE empty and CACHE vars
# don't propagate the way a top-level project's own toolchain file would, so
# without this jzIntv compiles unoptimized and can't hold 60fps on a
# mid-range phone -- see fujinet-go-msdos/app/src/main/cpp/CMakeLists.txt:40-63,
# which hit exactly this. Must come before any add_library() below.
add_compile_options($<$<CONFIG:Debug>:-O2> $<$<CONFIG:Release>:-O3>)

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_ANDROID_ARM_MODE ON)
    set(CMAKE_ANDROID_ARM_NEON ON)
    # 16K page size support (NDK >= 26).
    add_link_options(-Wl,-z,max-page-size=16384)
endif()

set(GEN "${CMAKE_CURRENT_SOURCE_DIR}/../cpp-generated/intv")
if(NOT EXISTS "${GEN}/jzintv-generated/src/jzintv.c")
    message(FATAL_ERROR
        "Staged Intellivision core is missing at ${GEN}. Run "
        "tools/jzintv/build-jzintv-core.sh (set INTV_DESKTOP_SRC if the "
        "fujinet-go-intv-desktop checkout is not at ~/Workspace/fujinet-go-intv-desktop).")
endif()

set(JZ "${GEN}/jzintv-generated")

# ---- jzintv_core: the toolkit-agnostic, SDL-free jzIntv core --------------
set(JZINTV_CORE_SOURCES
    src/svn_revision.c
    src/avi/avi.c
    src/event/event.c
    src/event/event_tbl.c
    src/mem/mem.c
    src/cfg/cfg.c
    src/cfg/usage.c
    src/cfg/mapping.c
    src/file/file.c
    src/file/elfi.c
    src/fujinet/fujibus.c
    src/fujinet/fn_sock.c
    src/fujinet/fujinet.c
    src/fujinet/fujinet_config_rom.c
    src/metadata/metadata.c
    src/metadata/cfgvar_metadata.c
    src/metadata/icarttag_metadata.c
    src/metadata/print_metadata.c
    src/gif/gif_enc.c
    src/gif/lzw_enc.c
    src/gfx/palette.c
    src/gfx/gfx.c
    src/speed/speed.c
    src/cp1600/cp1600.c
    src/cp1600/op_decode.c
    src/cp1600/op_exec.c
    src/cp1600/emu_link.c
    src/cp1600/op_exec_ext.c
    src/cp1600/req_q.c
    src/cp1600/tbl/fn_cond_br.c
    src/cp1600/tbl/fn_dir_2op.c
    src/cp1600/tbl/fn_imm_2op.c
    src/cp1600/tbl/fn_impl_1op_a.c
    src/cp1600/tbl/fn_impl_1op_b.c
    src/cp1600/tbl/fn_ind_2op.c
    src/cp1600/tbl/fn_reg_1op.c
    src/cp1600/tbl/fn_reg_2op.c
    src/cp1600/tbl/fn_rot_1op.c
    src/cp1600/tbl/formats.c
    src/jlp/jlp.c
    src/periph/periph.c
    src/minilzo/minilzo.c
    src/zlib/deflate.c
    src/zlib/adler32.c
    src/zlib/trees.c
    src/zlib/zutil.c
    src/name/name.c
    src/name/name_list.c
    src/misc/crc32.c
    src/misc/crc16.c
    src/misc/avl.c
    src/misc/ll.c
    src/misc/file_crc32.c
    src/misc/types.c
    src/misc/jzprint.c
    src/misc/printer.c
    src/lzoe/lzoe.c
    src/icart/icart.c
    src/icart/icartrom.c
    src/icart/icartbin.c
    src/icart/icartfile.c
    src/icart/icarttag.c
    src/icart/icarttag_authors.c
    src/icart/icarttag_printer.c
    src/ivoice/ivoice.c
    src/locutus/locutus.cpp
    src/locutus/luigi.cpp
    src/locutus/locutus_adapt.cpp
    src/locutus/locutus_types.cpp
    src/locutus/loc_to_bin.cpp
    src/locutus/bin_to_loc.cpp
    src/locutus/rom_to_loc.cpp
    src/emscripten/web_files.c
    src/serializer/serializer.c
    src/bincfg/bincfg.c
    src/bincfg/bincfg_grmr.tab.c
    src/bincfg/bincfg_lex.c
    src/bincfg/legacy.c
    src/ecs/ecs.c
    src/ay8910/ay8910.c
    src/demo/demo.c
    src/debug/debug.c
    src/debug/debug_dasm1600.c
    src/util/symtab.c
    src/debug/source.c
    src/stic/stic.c
    src/pads/pads.c
    src/pads/pads_cgc.c
    src/cheat/cheat.c
    src/scale/scale2x.c
    src/scale/scale3x.c
    src/plat/plat_lib.c
    src/plat/gnu_getopt.c
    src/plat/plat_gen.c
    src/mvi/mvi.c
    # Non-emscripten entry point; SDL-free at the source level (only calls
    # through the gfx_t/snd_t/event_t/plat_* backend abstractions).
    src/jzintv.c
    # ---- the "null" backend combination, same trick as the desktop -------
    src/event/event_null.c
    src/joy/joy_null.c
    src/plat/plat_null.c
    src/plat/main_null.c
)
list(TRANSFORM JZINTV_CORE_SOURCES PREPEND "${JZ}/")

list(APPEND JZINTV_CORE_SOURCES
    "${GEN}/jzintv/desktop/gfx_desktop.c"
    "${GEN}/jzintv/desktop/snd_desktop.c"
    "${GEN}/jzintv/intv_frame.c"
    "${GEN}/jzintv/intv_audio.c"
    "${GEN}/jzintv/intv_host.c"
)

add_library(jzintv_core STATIC ${JZINTV_CORE_SOURCES})
target_include_directories(jzintv_core PUBLIC
    "${JZ}/src" "${GEN}/jzintv" "${GEN}/include")
target_include_directories(jzintv_core PRIVATE "${GEN}/src")
target_compile_definitions(jzintv_core PUBLIC
    JZINTV_VERSION_MAJOR=0x00
    JZINTV_VERSION_MINOR=0x01
    USE_AS_BACKEND
    # bionic (like glibc) needs sys/ioctl.h spelled out explicitly for
    # plat_lib.c's terminal-width probe -- same fix the desktop's
    # non-WIN32/non-Apple/non-FreeBSD branch applies for Linux.
    USE_SYS_IOCTL
)
target_compile_options(jzintv_core PRIVATE
    -Wno-unused-parameter -Wno-sign-compare -Wno-unused-but-set-variable)
target_link_libraries(jzintv_core PUBLIC m)

# ---- system ROMs: empty table unless -DINTV_WITH_ROMS=ON ------------------
# Gradle sets INTV_WITH_ROMS from the dev-only -PintvRoms=true property (see
# app/build.gradle.kts); the release build type refuses that property
# outright, and verifyNoEmbeddedRoms audits the merged release output as a
# second, independent check. See COMPLIANCE.md.
option(INTV_WITH_ROMS "Embed exec.bin/grom.bin/ecs.bin (dev builds only)" OFF)
set(INTV_ROM_DIR_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../assets-generated/intv-roms")
if(INTV_WITH_ROMS AND EXISTS "${INTV_ROM_DIR_SRC}/exec.bin")
    file(GLOB INTV_EMBED_ROMS "${INTV_ROM_DIR_SRC}/*.bin")
else()
    set(INTV_EMBED_ROMS "")
endif()

set(INTV_ROMS_C "${CMAKE_CURRENT_BINARY_DIR}/roms_embedded.c")
find_package(Python3 COMPONENTS Interpreter REQUIRED)
add_custom_command(
    OUTPUT "${INTV_ROMS_C}"
    COMMAND "${Python3_EXECUTABLE}"
            "${GEN}/embed-roms/embed-roms.py"
            "${INTV_ROMS_C}"
            --roms ${INTV_EMBED_ROMS}
    DEPENDS "${GEN}/embed-roms/embed-roms.py" ${INTV_EMBED_ROMS}
    COMMENT "Embedding Intellivision ROMs (INTV_WITH_ROMS=${INTV_WITH_ROMS})"
    VERBATIM)
target_sources(jzintv_core PRIVATE "${INTV_ROMS_C}")
target_include_directories(jzintv_core PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")

# ---- intvcore: the Android-native session core + JNI bridge ---------------
add_library(intvcore SHARED
    intv_core.cpp
    session_runtime.cpp
    intv_host_android.cpp
    paths_android.c
    android_stubs.c
    fujinet_runtime_android.c
    fujinet_android.cpp
    "${GEN}/src/session.c"
    "${GEN}/src/settings.c"
    "${GEN}/src/intv_keymap.c"
)
target_include_directories(intvcore PRIVATE "${GEN}/src" "${GEN}/include" "${GEN}/jzintv")
target_compile_options(intvcore PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(intvcore PRIVATE jzintv_core log android dl m)
