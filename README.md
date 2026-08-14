# FujiNet Go Intv

Android Intellivision emulation with integrated FujiNet, in the spirit of
[FujiNet Go 800](https://github.com/mozzwald/fujinet-go-800) (Atari 8-bit),
FujiNet Go Apple2 (Apple ][), FujiNet Go Adam (Coleco ADAM), FujiNet Go CoCo
(TRS-80 Color Computer), FujiNet Go MSX and FujiNet Go MS-DOS.

This repository fuses two desktop programs into one cohesive mobile app,
mirroring [fujinet-go-intv-desktop](https://github.com/FujiNetWIFI/fujinet-go-intv-desktop):

- **jzIntv** (20200712, Joe Zbiciak) — the Intellivision emulator, patched with
  the FujiNet `fujibus`/BoIP mailbox peripheral and an embedded FujiNet config
  ROM (`jzintv-fujinet.patch`). It is embedded as a static library and driven
  through a toolkit-agnostic session core (`intvsession.h`) shared with the
  desktop app, wired into an Android `Surface`/`AudioTrack` instead of SDL.
- **fujinet-pc firmware** — built as `libfujinet.so` and run in-process as a
  background runtime.

The two halves talk over **FujiBusPacket-over-BoIP on loopback TCP 65503**
(the same port the desktop app uses). Direction mirrors CoCo's Becker link:
the FujiNet runtime runs the **listener**, and jzIntv's `fujibus` device is
the **client** that connects out to `127.0.0.1:65503`. To the user it is
transparent — boot and the FujiNet config ROM is just there.

## System ROMs, ECS and Intellivoice

- **No system ROMs are bundled** in release builds. `exec.bin` (8192 B),
  `grom.bin` (2048 B) and, optionally, `ecs.bin` (24576 B) must be imported
  (Storage Access Framework) before the machine can boot — see
  [COMPLIANCE.md](./COMPLIANCE.md).
- **Cartridges** (`.rom` / `.bin` / `.int`, optionally with a same-name `.cfg`
  memory-map sidecar) are imported the same way. Without a cartridge, the
  machine boots the embedded FujiNet config ROM.
- **ECS** (Intellivision Computer Keyboard, tri-state Auto/Off/On) and
  **Intellivoice** (tri-state Auto/Off/On) are Settings toggles; Intellivoice
  needs no ROM (its SP0256 mask ROM is compiled into jzIntv). **NTSC/PAL** is
  a Settings radio toggle.

## Controller

The default view is the combined Intellivision hand controller: the 16-way
disc (snapped to the 8 compass positions, matching the desktop's own keypad
window), the 12-key keypad, and the three side action buttons. A third
overlay, reachable when ECS is enabled, is an on-screen ECS keyboard.

## Architecture

| Concern | Component |
|---|---|
| Emulator core | jzIntv 20200712 + FujiNet patch, staged from `fujinet-go-intv-desktop`'s `core/` and `core/jzintv-generated/` |
| App native lib | `libintvcore.so` (jzIntv static core + Android host + `intvsession` + JNI) |
| Android host | `app/src/main/cpp/session_runtime.cpp` (presenter thread over `ANativeWindow`, `AudioTrack`-pulled audio, ADPF performance hints) |
| FujiNet runtime | `libfujinet.so`, `dlopen`'d in-process |
| Transport | FujiBusPacket-over-BoIP, TCP 65503 (FujiNet listens, jzIntv connects) |
| FujiNet web UI | served on `0.0.0.0:8057`; the **FujiNet** tab opens `http://127.0.0.1:8057/` |
| UI | Jetpack Compose (emulator surface, controller pad, ECS keyboard, settings, FujiNet WebUI) |

## Sources

The native components are built from local checkouts (not pinned GitHub
tarballs), so unpushed changes are used as-is:

- jzIntv core + FujiNet patch: `~/Workspace/fujinet-go-intv-desktop` (override with `INTV_DESKTOP_SRC=`)
- FujiNet firmware: `~/Workspace/fujinet-firmware` (override with `FUJINET_SRC=`)

## Build requirements

- JDK 21+ for the Gradle daemon, Android SDK (compile SDK 36) + an installed NDK
- `bash`, `git`, `python3`, `cmake`, `rsync`
- The FujiNet build clones and cross-compiles Mbed TLS

`local.properties` records `sdk.dir` and `ndk.dir`.

## Build

```bash
# Stage the jzIntv/FujiNet core from the desktop checkout:
bash tools/jzintv/build-jzintv-core.sh --abi arm64-v8a

# FujiNet runtime (libfujinet.so + assets):
bash tools/fujinet/build-fujinet.sh --abi arm64-v8a

# Full app (all packaged ABIs: arm64-v8a, armeabi-v7a, x86_64 -- x86-32 is
# excluded, matching the rest of the family):
./gradlew assembleDebug
./gradlew assembleDebug -PintvAbi=arm64-v8a              # fast single-ABI dev build
./gradlew assembleDebug -PintvAbi=arm64-v8a,x86_64        # comma list for a subset

# Dev-only convenience: stage exec.bin/grom.bin/ecs.bin into assets so a
# local debug build boots without an import step. NEVER use for a release
# build (see COMPLIANCE.md); the release build type refuses it outright.
./gradlew assembleDebug -PintvRoms=true
```

The application id / package is `online.fujinet.go.intv`.

## Ports

| Purpose | Port |
|---|---|
| BoIP (FujiBusPacket) | TCP 65503 (matches `INTVSESSION_BOIP_PORT` in the desktop's `intvsession.h`) |
| FujiNet web admin | TCP 8057 |

## Generated (uncommitted) directories

- `app/src/main/cpp-generated/intv/` — staged jzIntv + `intvsession` core sources
- `app/src/main/assets-generated/` — FujiNet runtime assets, and (dev-only, `-PintvRoms=true`) system ROMs
- `app/src/main/jniLibs-generated/` — `libfujinet.so` per ABI
- `tools/jzintv/work/`, `tools/fujinet/work/`

## Licensing

This is a copyleft project — see [COMPLIANCE.md](./COMPLIANCE.md) and
[THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md). **jzIntv is GPLv2-or-later**
and FujiNet is GPLv3, so the combined work is distributed under **GPLv3**
([LICENSE](./LICENSE)). System ROMs and cartridges are the user's own and are
never bundled in release builds.
