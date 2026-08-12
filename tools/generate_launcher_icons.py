#!/usr/bin/env python3
"""
Regenerates every launcher icon from one square source image.

Android wants fifteen files across five densities, in three flavours, and gets
them subtly wrong in different ways if you hand-cut them. This does the whole
set from a single artwork so they can never drift apart.

    python3 tools/generate_launcher_icons.py path/to/mangaki-icon.png

What the source has to be
-------------------------
Square, and the mascot alone — no wordmark. A launcher icon is rendered at
about 4mm across; text baked into it is unreadable and only steals room from
the thing people actually recognise.

Transparent background is best. A flat one is fine and gets composited onto
`launcher_background` for the legacy icons; anything busy will fight the
adaptive mask.

At least 432x432, ideally much larger or an SVG rendered to PNG first, since
everything here is downscaled and nothing is invented.

What it produces
----------------
`ic_launcher_foreground` is the adaptive-icon layer. Android crops it hard:
the outer ~25% on each side can be shaved off by the launcher's mask, and on
some devices it is also scaled up during animations. So the artwork is drawn
into the middle 66% and the rest left transparent — that margin is why a
correctly built adaptive icon looks smaller than you expect at first.

`ic_launcher` and `ic_launcher_round` are the legacy pre-Android-8 icons,
which are shown whole with no mask. They get a much smaller margin, and are
flattened onto the background colour because transparency there reads as a
hole rather than a shape.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

# dp sizes Android expects, per density bucket.
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

FOREGROUND_DP = 108
LEGACY_DP = 48

# Fraction of the canvas the artwork occupies. The adaptive figure is the
# conservative end of Google's safe zone; the legacy one only avoids the
# artwork touching the bezel.
ADAPTIVE_SCALE = 0.66
LEGACY_SCALE = 0.92

# Matches @color/launcher_background in res/values/colors.xml.
LEGACY_BACKGROUND = (255, 255, 255, 255)

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"


def fit(source: Image.Image, canvas: int, scale: float) -> Image.Image:
    """The source centred on a transparent square, occupying `scale` of it."""
    target = max(1, round(canvas * scale))
    art = source.copy()
    art.thumbnail((target, target), Image.LANCZOS)

    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.paste(art, ((canvas - art.width) // 2, (canvas - art.height) // 2), art)
    return out


def flatten(image: Image.Image) -> Image.Image:
    base = Image.new("RGBA", image.size, LEGACY_BACKGROUND)
    return Image.alpha_composite(base, image)


def circular(image: Image.Image) -> Image.Image:
    """The round legacy icon, masked to a circle with an antialiased edge."""
    from PIL import ImageDraw

    supersample = 4
    big = image.size[0] * supersample
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big - 1, big - 1), fill=255)
    mask = mask.resize(image.size, Image.LANCZOS)

    out = image.copy()
    out.putalpha(mask)
    return out


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2

    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"No such file: {path}")
        return 1

    source = Image.open(path).convert("RGBA")
    if source.width != source.height:
        print(f"warning: source is {source.width}x{source.height}, not square — it will be letterboxed")
    if min(source.size) < 432:
        print(f"warning: source is only {source.width}px; xxxhdpi needs 432px and will be upscaled")

    written = 0
    for density, factor in DENSITIES.items():
        out_dir = RES / f"mipmap-{density}"
        out_dir.mkdir(parents=True, exist_ok=True)

        foreground = fit(source, round(FOREGROUND_DP * factor), ADAPTIVE_SCALE)
        legacy = flatten(fit(source, round(LEGACY_DP * factor), LEGACY_SCALE))

        for name, image in (
            ("ic_launcher_foreground", foreground),
            ("ic_launcher", legacy),
            ("ic_launcher_round", circular(legacy)),
        ):
            target = out_dir / f"{name}.webp"
            image.save(target, "WEBP", lossless=True, quality=100, method=6)
            print(f"  {target.relative_to(RES.parent.parent.parent)}  {image.width}x{image.height}")
            written += 1

    print(f"\n{written} files written. Check them on a device: the adaptive icon is masked "
          f"differently by every launcher, and that is only visible in situ.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
