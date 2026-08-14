# Licensing & Compliance

FujiNet Go Intv is a **copyleft** project. The shipped app is built from
original glue code plus two third-party emulation/runtime components. Read
this before distributing any build.

## Components and their licenses

### jzIntv (the emulator core) — GPLv2-or-later
- jzIntv © Joe Zbiciak and contributors, version 20200712.
- License: **GNU GPL v2 or later.**
- Built from the same source `fujinet-go-intv-desktop` uses: a pinned jzIntv
  20200712 source archive, patched with `jzintv-fujinet.patch` (adds the
  `fujibus`/BoIP mailbox peripheral and the embedded FujiNet config ROM). This
  Android app stages that already-patched tree from the desktop checkout
  (`tools/jzintv/build-jzintv-core.sh`, `INTV_DESKTOP_SRC=`) rather than
  re-patching it, so there is one source of truth for the modification.
- The Android-specific glue (`app/src/main/cpp/{paths_android.c,
  android_stubs.c, fujinet_runtime_android.c, session_runtime.cpp,
  intv_core.cpp, intv_host_android.cpp}`) replaces the desktop's SDL3 audio/
  gamepad/path backends with Android `AudioTrack`-pulled audio and Kotlin
  `InputDevice` handling. These modifications are GPL and reproducible from
  the staging script.

### FujiNet firmware / fujinet-pc — GPLv3
- `libfujinet.so` is built from the FujiNet firmware
  (`FujiNetWIFI/fujinet-firmware`), which is GPLv3.
- The Android build applies source transforms (SHARED library target, an
  in-process entry wrapper, a `reboot()`/`exit()` guard, mbedTLS-for-Android
  wiring, web admin bound to `0.0.0.0:8057`, `[BOIP]` listener on
  `127.0.0.1:65503`). These modifications are GPLv3 and reproducible from
  `tools/fujinet/build-fujinet.sh`.

### Bundled libraries (pulled in by the FujiNet build)
- **Mbed TLS** — Apache-2.0 (or GPL-2.0); cross-compiled from source.
- **libssh** — LGPL-2.1.
- **libsmb2** — LGPL-2.1.
- **libnfs** — LGPL-2.1.
- **expat** — MIT.
- **cJSON** — MIT.

## System ROM policy — NOT bundled in release builds

Intellivision's EXEC (8192 B) and GROM (2048 B) system ROMs, and the optional
ECS ROM (24576 B), are Mattel-copyrighted firmware. **They are never embedded
in a release build.**

- The `roms_embedded.c` table compiled into `libintvcore.so` is empty unless
  the build explicitly opts in with `-DINTV_WITH_ROMS=ON`, which only happens
  when Gradle is invoked with the dev-only `-PintvRoms=true` property. The
  `release` build type in `app/build.gradle.kts` **throws** if that property
  is set, refusing to produce a release artifact that carries ROMs.
- `assembleRelease` additionally depends on `verifyNoEmbeddedRoms`
  (`tools/jzintv/verify-no-roms.py`), which scans the merged release assets
  and native libraries for the three ROMs' byte signatures and fails the
  build if any are found. This is a mechanical check, not a documentation
  promise.
- End users import their own dumped `exec.bin` / `grom.bin` / `ecs.bin` (and
  any cartridges) via Settings → System ROMs / Cartridge, using the Storage
  Access Framework. Nothing is fetched from the network or bundled.
- `-PintvRoms=true` exists purely so a local **debug** build can boot without
  a manual import step during development; it stages the desktop repo's
  `tools/jzintv/roms/*.bin` into app assets and is loudly logged as a dev-only
  build by the staging script.

## Net effect

A combined, distributed binary is bound by jzIntv's **GPLv2-or-later** and
FujiNet's **GPLv3** copyleft — the combined work is distributed under
**GPLv3** (offer corresponding source). No copyrighted Intellivision firmware
or cartridge software is embedded in a release build.

The original FujiNet Go Intv glue code (build scripts, `session_runtime.cpp`,
`intv_core.cpp`, `intv_host_android.cpp`, `paths_android.c`,
`android_stubs.c`, `fujinet_runtime_android.c`, the Kotlin app) is offered
under the terms in [LICENSE](./LICENSE), within the GPL obligations of the
combined work.

See [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md) for attribution details.
