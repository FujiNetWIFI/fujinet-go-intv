#!/usr/bin/env python3
"""Assert that a release build really carries no Mattel Intellivision ROMs.

Ported from fujinet-go-intv-desktop's core/tests/no_embedded_roms.py, which
makes that repo's COMPLIANCE.md claim checkable rather than aspirational:
take a distinctive slice out of each system ROM image and grep every file in
the release output for it. Anything found is a ROM that got shipped despite
the policy, which would make the artifact non-redistributable.

Unlike no_embedded_roms.py (which scans a single desktop binary), a release
APK's ROM bytes could show up in two independent places: the compiled-in
roms_embedded.c table inside libintvcore.so (should be empty --
INTV_WITH_ROMS=OFF), or a stray copy under assets (should never exist in a
release build -- -PintvRoms is refused by the release build type before this
even runs, but this check doesn't trust that alone). So this walks every
regular file under the given directories, not just one binary.

usage: verify-no-roms.py [--require] <dir>...

With --require, the inability to run the check (no scan dirs, no local ROM
images to probe with) is itself a failure -- release builds must not pass on
a machine where the check silently could not run. Without it, those cases
skip with a warning, so dev machines without the reference dumps aren't
blocked. INTV_ROMS_DIR overrides where the reference ROM images are found.
"""

import os
import sys
from pathlib import Path

PROBE = 64

# The desktop repo's tools/jzintv/roms/ (or a locally staged
# assets-generated/intv-roms/, if this ever runs against a dev tree by
# mistake) is the source of the ROM images to probe for. INTV_ROMS_DIR, when
# set, is searched first.
ROM_SEARCH_DIRS = [
    *([Path(os.environ["INTV_ROMS_DIR"])] if os.environ.get("INTV_ROMS_DIR") else []),
    Path.home() / "Workspace" / "fujinet-go-intv-desktop" / "tools" / "jzintv" / "roms",
]


def probe_bytes(data):
    """A slice distinctive enough that finding it means something.

    Reject any chunk dominated by a single byte value and require real
    variety, so a run of 0x00 or 0xFF padding -- which occurs in almost any
    binary of a reasonable size -- can never be mistaken for a match. Scan the
    whole file rather than a few fixed offsets.
    """
    max_run = PROBE // 2      # no single byte value may cover half the slice
    min_distinct = 12

    for start in range(0, max(1, len(data) - PROBE), PROBE):
        chunk = data[start:start + PROBE]
        if len(chunk) != PROBE:
            continue
        if max(chunk.count(b) for b in set(chunk)) > max_run:
            continue
        if len(set(chunk)) < min_distinct:
            continue
        return chunk
    return None


def find_rom_dir():
    for d in ROM_SEARCH_DIRS:
        if (d / "exec.bin").is_file() and (d / "grom.bin").is_file():
            return d
    return None


def main():
    args = sys.argv[1:]
    require = "--require" in args
    args = [a for a in args if a != "--require"]
    if not args:
        sys.exit(__doc__)

    scan_dirs = [Path(p) for p in args if Path(p).is_dir()]
    if not scan_dirs:
        if require:
            print("verify-no-roms: FAIL: none of the scan directories exist "
                  f"({args}); with --require the check must actually run")
            return 1
        # Nothing to scan (e.g. the release build hasn't produced merged
        # assets/libs under the expected paths yet) -- not a failure, since
        # the release build type already refuses -PintvRoms outright; this
        # script is the second, belt-and-braces check.
        print("verify-no-roms: no scan directories found; skipping "
              "(release build type's -PintvRoms guard is the primary check)")
        return 0

    romdir = find_rom_dir()
    if romdir is None:
        msg = ("verify-no-roms: no local ROM images found to probe with "
               f"(checked {[str(d) for d in ROM_SEARCH_DIRS]}; "
               "set INTV_ROMS_DIR to override)")
        if require:
            print(f"{msg} -- FAIL under --require")
            return 1
        print(f"{msg}; skipping -- "
              "this only means we couldn't test, not that the build is clean")
        return 0

    files = []
    for scan_dir in scan_dirs:
        files.extend(f for f in scan_dir.rglob("*") if f.is_file())

    checked = 0
    leaked = []

    for rom in sorted(romdir.glob("*.bin")):
        data = rom.read_bytes()
        if len(data) < 128:
            continue
        chunk = probe_bytes(data)
        if chunk is None:
            continue
        checked += 1
        for f in files:
            try:
                if chunk in f.read_bytes():
                    leaked.append((rom.name, str(f)))
            except OSError:
                continue

    if checked == 0:
        if require:
            print("verify-no-roms: FAIL: no ROM images had a distinctive "
                  "slice to probe with; with --require the check must "
                  "actually run")
            return 1
        print("verify-no-roms: no ROM images had a distinctive slice to "
              "probe with; skipping")
        return 0

    for rom_name, path in leaked:
        print(f"FAIL: {rom_name} bytes found embedded in {path}")

    if leaked:
        return 1

    print(f"verify-no-roms: {checked} ROM images checked across "
          f"{len(files)} files; none embedded, as intended")
    return 0


if __name__ == "__main__":
    sys.exit(main())
