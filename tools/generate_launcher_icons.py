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
only the middle 72dp of its 108dp is ever shown, and every launcher masks that
to a different shape — circle, squircle, teardrop. So the artwork is measured
and scaled to stay inside the 33dp circle all of those contain, rather than to
a fixed fraction of the canvas, which cannot account for its shape. That
margin is why a correctly built adaptive icon looks smaller than expected.

`ic_launcher` and `ic_launcher_round` are the legacy pre-Android-8 icons,
which are shown whole with no mask. They get a much smaller margin, and are
flattened onto the background colour because transparency there reads as a
hole rather than a shape.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw

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

# The square legacy icon is shown whole, so a plain margin is all it needs.
LEGACY_SCALE = 0.86

# The other two are masked to circles, and a fraction-of-the-canvas figure
# cannot express that. What matters is how far the artwork reaches from the
# centre, and that depends on its shape: a circular mark and a tall one with a
# crest fit very differently inside the same bounding box. Picking the fraction
# by eye is how this first came out 36.1dp against a 36dp mask — invisible in a
# preview, clipped on a real launcher.
#
# So these are radii, in dp of the 108dp adaptive canvas, and the scale needed
# to respect them is measured from the artwork itself.
ADAPTIVE_SAFE_RADIUS_DP = 33.0   # the circle Google designs adaptive icons against
ROUND_SAFE_FRACTION = 0.90       # of the legacy icon's own radius

# Matches @color/launcher_background in res/values/colors.xml.
LEGACY_BACKGROUND = (255, 255, 255, 255)

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"


def isolate(source: Image.Image, tolerance: int = 32) -> Image.Image:
    """Drops a flat background and crops to the artwork.

    Logos usually arrive as a shape floating in a white square. Fed in as-is
    the margin counts as artwork, so every icon comes out a pale box with a
    tiny mascot in the middle — and on the adaptive layer the white square is
    visible as a hard edge against the launcher's own background.

    The fill starts from the four corners rather than keying out white
    everywhere, which is what keeps the white *inside* the drawing — this
    mascot's eyes and beak — from being punched into holes.
    """
    if source.getextrema()[3][0] < 255:
        # Already cut out; only the crop is needed.
        bbox = source.getbbox()
        return source.crop(bbox) if bbox else source

    width, height = source.size
    probe = source.convert("RGB")
    sentinel = (255, 0, 255)
    for seed in ((0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1)):
        ImageDraw.floodfill(probe, seed, sentinel, thresh=tolerance)

    alpha = Image.new("L", source.size)
    alpha.putdata([0 if pixel == sentinel else 255 for pixel in probe.getdata()])

    out = source.copy()
    out.putalpha(alpha)

    bbox = out.getbbox()
    if bbox is None:
        raise SystemExit("The source is entirely background — nothing to render.")
    return out.crop(bbox)


def radius_ratio(image: Image.Image) -> float:
    """How far the artwork reaches from its centre, per half of its longest side.

    1.0 for a shape that exactly fills a circle; higher for anything that
    reaches into the corners of its bounding box. This is what decides whether
    a mask cuts into it, and it cannot be guessed from the dimensions.
    """
    alpha = image.split()[-1].load()
    width, height = image.size
    cx, cy = width / 2, height / 2
    unit = max(width, height) / 2
    worst = 0.0
    for y in range(height):
        for x in range(width):
            if alpha[x, y] > 8:
                distance = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
                if distance > worst:
                    worst = distance
    return worst / unit


def fit_radius(source: Image.Image, canvas: int, radius_px: float, ratio: float) -> Image.Image:
    """Centred, scaled so the artwork reaches no further than `radius_px`."""
    longest = 2 * radius_px / max(ratio, 1e-6)
    return fit(source, canvas, min(1.0, longest / canvas))


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


def circular(source: Image.Image, canvas: int, ratio: float) -> Image.Image:
    """The round legacy icon: its own fit, then a circular mask.

    Built from the artwork rather than by masking the square icon, so the
    smaller margin a circle needs is applied before anything is cut.
    """
    image = flatten(fit_radius(source, canvas, canvas / 2 * ROUND_SAFE_FRACTION, ratio))
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

    source = isolate(Image.open(path).convert("RGBA"))
    print(f"artwork after trimming: {source.width}x{source.height}")
    if source.width != source.height:
        print(f"warning: source is {source.width}x{source.height}, not square — it will be letterboxed")
    if min(source.size) < 432:
        print(f"warning: source is only {source.width}px; xxxhdpi needs 432px and will be upscaled")

    ratio = radius_ratio(source)
    print(f"artwork reach: {ratio:.2f}x its half-width — scaling to fit the mask")

    written = 0
    for density, factor in DENSITIES.items():
        out_dir = RES / f"mipmap-{density}"
        out_dir.mkdir(parents=True, exist_ok=True)

        canvas = round(FOREGROUND_DP * factor)
        foreground = fit_radius(source, canvas, ADAPTIVE_SAFE_RADIUS_DP * factor, ratio)
        legacy = flatten(fit(source, round(LEGACY_DP * factor), LEGACY_SCALE))

        for name, image in (
            ("ic_launcher_foreground", foreground),
            ("ic_launcher", legacy),
            ("ic_launcher_round", circular(source, round(LEGACY_DP * factor), ratio)),
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
