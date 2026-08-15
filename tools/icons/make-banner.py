#!/usr/bin/env python3
"""Render the Android TV banner (drawable-xhdpi/banner.png) for fujinet-go-intv.

Matches the sibling apps' banner layout (mark on the left, "FujiNet Go" /
product name stacked on the right) but uses this app's own background
colour -- the same dark green (#1E4912) as the launcher icon and in-app
theme accent -- with white FujiNet mark and text, since intv's other
obvious brand colour (the STIC-era controller yellow) reads poorly against
dark green and doesn't match the white-on-dark treatment MSX/Apple II
already use for their dark banners.

Re-run only when the artwork, background colour, or copy changes:

    python3 tools/icons/make-banner.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FOREGROUND = ROOT / "tools/icons/src/fujinet-go-intv-foreground.png"
OUT = ROOT / "app/src/main/res/drawable-xhdpi/banner.png"

BACKGROUND = (0x1E, 0x49, 0x12, 0xFF)   # dark green -- matches the launcher icon
WHITE = (255, 255, 255, 255)
SUBTITLE = (235, 235, 235, 255)

SIZE = (320, 180)
SCALE = 4  # supersample for clean edges, then downsample

TITLE_FONT = "/usr/share/fonts/noto/NotoSans-Bold.ttf"
SUBTITLE_FONT = "/usr/share/fonts/noto/NotoSans-Regular.ttf"


def main() -> int:
    w, h = SIZE[0] * SCALE, SIZE[1] * SCALE
    canvas = Image.new("RGBA", (w, h), BACKGROUND)

    # Logo mark, left-aligned, vertically centred.
    art = Image.open(FOREGROUND).convert("RGBA")
    mark_size = int(h * 0.62)
    art = art.resize((mark_size, mark_size), Image.LANCZOS)
    mark_x = int(w * 0.05)
    mark_y = (h - mark_size) // 2
    canvas.paste(art, (mark_x, mark_y), art)

    draw = ImageDraw.Draw(canvas)
    text_x = mark_x + mark_size + int(w * 0.02)

    title_font = ImageFont.truetype(TITLE_FONT, int(h * 0.155))
    subtitle_font = ImageFont.truetype(SUBTITLE_FONT, int(h * 0.115))

    title = "FujiNet Go"
    subtitle = "Intellivision"

    title_bbox = draw.textbbox((0, 0), title, font=title_font)
    subtitle_bbox = draw.textbbox((0, 0), subtitle, font=subtitle_font)
    title_h = title_bbox[3] - title_bbox[1]
    subtitle_h = subtitle_bbox[3] - subtitle_bbox[1]
    gap = int(h * 0.03)

    block_h = title_h + gap + subtitle_h
    title_y = (h - block_h) // 2 - title_bbox[1]
    subtitle_y = title_y + title_h + gap - subtitle_bbox[1]

    draw.text((text_x, title_y), title, font=title_font, fill=WHITE)
    draw.text((text_x, subtitle_y), subtitle, font=subtitle_font, fill=SUBTITLE)

    banner = canvas.resize(SIZE, Image.LANCZOS).convert("RGB")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    banner.save(OUT)
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
