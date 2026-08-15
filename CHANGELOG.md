# Changelog

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
