# Changelog

## 1.0.2

### Fixed
- **Cartridge boots no longer stall partway through with a frozen progress
  bar.** Loading a cart from FujiNet could stop dead mid-transfer, leaving the
  console on the "BOOTING / DO NOT POWER OFF" screen with the bar stuck and the
  title line drawn as garbage. Whether a title was affected came down to its own
  data, so it looked arbitrary: roughly half the 32K Intellivision-era carts hung
  every single time while the rest loaded fine. The emulator's receive buffer was
  sized for the FujiNet mailbox's replies rather than for the much larger frames
  a cartridge push arrives in, so a block of graphics data that happened to
  encode badly overran it -- and the connection could never recover afterwards,
  which is why the bar froze instead of reporting a failure. The buffer is now
  sized for what it actually carries, and a malformed frame is discarded and
  resynchronized rather than wedging the link. Picked up from the rebuilt jzIntv
  core (`tools/jzintv/build-jzintv-core.sh --refresh`).

## 1.0.1

### Fixed
- **`.bin`/`.cfg` cartridge pairs now load correctly from the SD host slot.**
  The web UI file manager stored only the *first* file of an upload and
  reported success, so sending a cartridge and its `.cfg` memory map together
  left the card holding the `.bin` alone. The emulator then booted it against
  its size-guess map, which for most titles means a black screen, garbage or a
  hang -- with nothing anywhere saying the map was missing. Uploads now accept
  and store every selected file (drag-and-drop included) and name each one in
  the result. Requires the rebuilt FujiNet runtime
  (`tools/fujinet/build-fujinet.sh`).
- The Settings cartridge picker no longer sets a bare `.cfg` as the cartridge.
  jzIntv takes the cart path as its trailing positional argument and calls
  `exit(1)` when it is a `.cfg` with no companion ROM -- a silent process kill
  on Android. The sidecar is still imported, with a message pointing at the
  matching `.bin`.

### Changed
- FujiNet runtime logging says what the `.cfg` sibling probe did: `file_exists`
  reports found/not found rather than just the path it tried, and a ROM that
  mounts without a sibling now says so and names the path it looked for. Both
  land in `<filesDir>/intv/fujinet/fujinet-console.log`.

## 1.0.0

Google Play readiness release. No emulation changes.

### Added
- Release signing via `keystore.properties` (shared FujiNet Go upload
  keystore) and `tools/release-play.sh` to build the signed AAB for Play.
- Privacy policy (`docs/index.md`, published via GitHub Pages) and
  `docs/play-checklist.md` covering the console-side submission steps.
- Unit tests for system-ROM import classification.

### Changed
- **System ROM import now verifies CRC32**, not just file size. An 8 KB
  cartridge picked at the ROM gate previously overwrote `exec.bin`
  silently; it is now rejected with an explanatory message.
- `verify-no-roms.py` fails closed during release builds (`--require`):
  a machine where the check cannot run no longer passes it. The check now
  also runs for `bundleRelease` (it previously only guarded
  `assembleRelease`) and is ordered after asset/lib merging so it scans
  real output. `INTV_ROMS_DIR` overrides the reference-dump location.
- Cleartext HTTP is now scoped to the loopback FujiNet web UI via a
  network security config instead of `usesCleartextTraffic="true"`.
- Release builds carry full native debug symbols for Play crash reports.

## 0.2.0

Boots the larger Intellivision cartridges that previously failed with
ERR CODE 3, hangs, or corrupted graphics. All changes arrive through the
restaged desktop core and the rebuilt FujiNet runtime; see
fujinet-go-intv-desktop 0.4.0 and fujinet-firmware `fix-intv-bigger-carts`
(6577aa2c2) for the underlying work.

### Changed
- **jzIntv FujiNet peripheral** (staged from the desktop repo): `.cfg`
  sidecar mappings clamp at end-of-file (SDK-1600 bin2rom semantics)
  instead of dropping oversized lines; a mapping that covers the FujiNet
  mailbox window ($9C00–$9F3F) boots with the mailbox disabled for the
  session instead of being rejected; `[memattr]` cartridge RAM is honored
  (8/16-bit, capped at the hardware's 0x2800-word budget); 40KB/48KB bare
  `.bin` images get sensible default maps; abort-CLOSE from the runtime
  discards a failed push instead of booting partial data.
- **FujiNet runtime** (`libfujinet.so`): finds `.cfg` sidecars on hosts
  with a path prefix set (the sibling probe was double-prefixing), verifies
  the pushed byte count, and aborts cleanly on short transfers.
- **Embedded FujiNet config ROM**: boot failures now name their reason on
  screen (NO MAPPING, TRUNCATED XFER, JLP CONFLICT, ...).

## 0.1.0

Initial Android port of fujinet-go-intv-desktop, matching the shape of the
other FujiNet Go family members (800, Apple2, Adam, CoCo, MSX, MS-DOS).

### App shell (Jetpack Compose)
- `MainActivity` (edge-to-edge, `FLAG_KEEP_SCREEN_ON`,
  `setSustainedPerformanceMode(true)`), `EmulatorSessionService` (foreground
  service keeping the session alive), `SettingsActivity` (translucent Compose
  dialog host), `FujiNetWebViewActivity`.
- Packaged as an Android **game** (`appCategory="game"`, `isGame="true"`),
  with ADPF performance hints (`APerformanceHint`) on the emulator thread so
  vendor game governors (Moto/MediaTek GameTime) engage, and
  `Surface.setFrameRate(50|60, FIXED_SOURCE)` matched to NTSC/PAL.

### Controller (default view)
- `ControllerPad.kt`: the combined Intellivision hand controller — 16-way disc
  snapped to the 8 compass positions (transcribed from the desktop's
  `keypad_window.c:direction_from_point`), 12-key keypad, 3 side action
  buttons, P1/P2(/ECS) side selector. Haptics on disc-direction change and
  key press.
- ECS keyboard overlay (`EcsKeyboard.kt`), reachable when ECS is enabled,
  transcribed from the desktop's `ecskbd_window.c`.

### Native (`libintvcore.so`)
- Stages jzIntv 20200712 + the FujiNet patch from the desktop repo's already-
  patched `core/jzintv-generated/` tree (`tools/jzintv/build-jzintv-core.sh`),
  reusing `core/{session.c,settings.c,intv_keymap.c}` and
  `core/jzintv/{intv_host,intv_frame,intv_audio}.c` + `desktop/{gfx_desktop,
  snd_desktop}.c` unchanged.
- Android-specific replacements for the desktop's SDL3 backends:
  `paths_android.c`, `android_stubs.c` (audio/gamepad no-ops — Android pulls
  audio and reads `InputDevice` from Kotlin), `fujinet_runtime_android.c`
  (binds `fujinet_android_*` via the family's `fujinet_android.cpp` shim).
- `intv_frame_set_publish_hook()` added to `intv_frame.c` so the render thread
  presents on publish instead of polling; jzIntv's own `-r1` real-time
  throttle remains the frame pacer (no host-driven frame loop — see the
  session_runtime.cpp header comment).
- Cartridge loading added to the shared core (`intv_host.{c,h}`,
  `intvsession.h`, `session.c`): a trailing positional argv now reaches
  jzIntv's `cfg->fn_game`, so an imported `.rom`/`.bin`/`.int` boots instead
  of only the embedded FujiNet config ROM. Written as a clean, additive diff
  intended to cherry-pick back into fujinet-go-intv-desktop.

### System ROM / cartridge import
- Storage Access Framework import for `exec.bin`/`grom.bin`/`ecs.bin`
  (classified by exact byte size, not filename) and cartridges (`.rom/.bin/
  .int` + optional `.cfg` memory-map sidecar).
- "System ROMs required" gate shown until `exec.bin`+`grom.bin` are present;
  release builds ship no ROMs (see COMPLIANCE.md, `verifyNoEmbeddedRoms`).

### FujiNet
- `libfujinet.so` built from `fujinet-firmware` via `tools/fujinet/build-
  fujinet.sh` (forked from fujinet-go-msx's, RS232/listener direction, which
  matches Intellivision's `fujibus`-connects-out shape). BoIP on TCP 65503,
  web admin on TCP 8057.

### Settings
- ECS / Intellivoice: tri-state Auto/Off/On (kept tri-state rather than
  collapsed to a switch — Auto is jzIntv's own cart-metadata default and
  either two-state aliasing breaks a real use case).
- NTSC/PAL: radio toggle.
- Haptics: applied live; everything else applies on restart (jzIntv is a
  process-wide singleton — see `session_runtime.cpp`).

### Debugger
- Not ported (out of scope for this app, matching the rest of the family).
