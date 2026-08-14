#!/usr/bin/env python3
"""Render the Android launcher icon set from the shared FujiNet Go art.

The artwork (tools/icons/src/fujinet-go-intv-foreground.png) is the exact
same transparent FujiNet mark the CoCo/ADAM/Apple II/MSX/MS-DOS apps use --
copied byte-identical from fujinet-go-intv-desktop's data/icons/src/, which
itself notes it is verified against fujinet-go-coco-desktop's own copy --
composited over this product's own background colour, so the whole family
reads as one product line while each target still gets a distinct badge
colour.

Background is dark green (#1e4912), the same colour fujinet-go-intv-desktop
uses (tools/icons/make-icons.py there), per the explicit user request that
this Android app share it and also use it as the in-app UI accent colour
(see app/src/main/java/online/fujinet/go/intv/ui/theme/Theme.kt). Composite
parameters (rounded-square mask, corner radius, foreground zoom, output
sizes) match the desktop script and the sibling Android apps' own generators.

Produces two asset families:
  - Adaptive icon layers (foreground + background PNGs) at every mipmap
    density, referenced by res/mipmap-anydpi-v26/ic_launcher{,_round}.xml.
  - Flat (non-adaptive) ic_launcher / ic_launcher_round PNGs at the same
    densities, for launchers that don't support adaptive icons.
  - Root ic_launcher/ source dir: 1024.png (master) + play_store_512.png,
    for store listings.

Re-run only when the artwork or background colour changes:

    python3 tools/icons/make-icons.py
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
FOREGROUND = ROOT / "tools/icons/src/fujinet-go-intv-foreground.png"
RES_DIR = ROOT / "app/src/main/res"
LAUNCHER_SRC_DIR = ROOT / "ic_launcher"

BACKGROUND = (0x1E, 0x49, 0x12, 0xFF)    # dark green -- see module docstring
MASTER = 1024                            # render big, downsample with LANCZOS
CORNER_RADIUS = 0.22                     # fraction of the edge (flat icon only)
FOREGROUND_ZOOM = 1.18                   # Android's mask crops; compensate a little

# density -> (flat/legacy icon px, adaptive layer px)
# Adaptive layers are drawn at 108/72 of the legacy size (the standard
# Android adaptive-icon safe-zone ratio) so the launcher's own mask can crop
# them without clipping the FujiNet mark.
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def render_flat(size: int) -> Image.Image:
    """Rounded-square background + centred foreground, non-adaptive icon."""
    scale = 4
    big = Image.new("RGBA", (size * scale, size * scale), (0, 0, 0, 0))
    ImageDraw.Draw(big).rounded_rectangle(
        (0, 0, size * scale - 1, size * scale - 1),
        radius=int(size * scale * CORNER_RADIUS),
        fill=BACKGROUND,
    )
    icon = big.resize((size, size), Image.LANCZOS)

    art = Image.open(FOREGROUND).convert("RGBA")
    art_size = int(size * FOREGROUND_ZOOM)
    art = art.resize((art_size, art_size), Image.LANCZOS)
    offset = (size - art_size) // 2
    overlay = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    overlay.paste(art, (offset, offset), art)

    composed = Image.alpha_composite(icon, overlay)
    composed.putalpha(Image.composite(composed.getchannel("A"),
                                      Image.new("L", (size, size), 0),
                                      icon.getchannel("A")))
    return composed


def render_adaptive_back(size: int) -> Image.Image:
    return Image.new("RGBA", (size, size), BACKGROUND)


def render_adaptive_fore(size: int) -> Image.Image:
    # Adaptive foreground layers are drawn edge-to-edge (no rounding -- the
    # launcher applies its own mask) with the same zoom as the flat icon so
    # both variants read identically once masked.
    art = Image.open(FOREGROUND).convert("RGBA")
    art_size = int(size * FOREGROUND_ZOOM * 0.72)   # 0.72: adaptive safe zone
    art = art.resize((art_size, art_size), Image.LANCZOS)
    offset = (size - art_size) // 2
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(art, (offset, offset), art)
    return canvas


def main() -> int:
    if not FOREGROUND.exists():
        print(f"missing artwork: {FOREGROUND}", file=sys.stderr)
        return 1

    for density, legacy_px in DENSITIES.items():
        mip_dir = RES_DIR / f"mipmap-{density}"
        mip_dir.mkdir(parents=True, exist_ok=True)

        flat = render_flat(legacy_px)
        flat.save(mip_dir / "ic_launcher.png")
        # Round variant: same composite, circular alpha mask.
        mask = Image.new("L", (legacy_px, legacy_px), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, legacy_px - 1, legacy_px - 1), fill=255)
        round_icon = Image.new("RGBA", (legacy_px, legacy_px), (0, 0, 0, 0))
        round_icon.paste(flat, (0, 0), mask)
        round_icon.save(mip_dir / "ic_launcher_round.png")

        adaptive_px = int(legacy_px * 108 / 72)
        render_adaptive_back(adaptive_px).save(mip_dir / "ic_launcher_adaptive_back.png")
        render_adaptive_fore(adaptive_px).save(mip_dir / "ic_launcher_adaptive_fore.png")
        print(f"wrote {mip_dir}")

    LAUNCHER_SRC_DIR.mkdir(parents=True, exist_ok=True)
    render_flat(MASTER).save(LAUNCHER_SRC_DIR / "1024.png")
    render_flat(512).save(LAUNCHER_SRC_DIR / "play_store_512.png")
    print(f"wrote {LAUNCHER_SRC_DIR}/1024.png, play_store_512.png")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
