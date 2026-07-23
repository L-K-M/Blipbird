#!/usr/bin/env python3
"""Generate Android launcher assets from media-sources/ (PLAN.md §10.1).

Two switchable icon variants (Settings → Appearance → App icon), each used
FULL-BLEED and unaltered — launchers apply their own shape mask:

  icon-regular.png → ic_launcher_bg / ic_launcher / ic_launcher_round
  icon-fun.png     → ic_launcher_fun_bg / ic_launcher_fun / ic_launcher_fun_round

Per density: *_bg is the artwork on the 108dp adaptive canvas; the plain and
_round outputs are the pre-8.0 legacy square (rounded corners) and circle
masks. The Play Store image (512px) comes from the regular variant. Adaptive
foregrounds are an empty vector (all art lives in the background layer); the
monochrome/notification silhouette is a hand-authored vector drawable.
"""
from PIL import Image, ImageDraw
import os

MEDIA = os.path.join(os.path.dirname(__file__), "..", "media-sources")
RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

VARIANTS = {"": "icon-regular.png", "_fun": "icon-fun.png"}   # name suffix → source
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
ADAPTIVE_DP = 108   # adaptive icon canvas
LEGACY_DP = 48


def circle_crop(img: Image.Image, size: int) -> Image.Image:
    """Scale to size and mask to a circle with transparent corners."""
    scaled = img.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(scaled, (0, 0), mask)
    return out


def rounded_square(img: Image.Image, size: int, radius_frac: float = 0.18) -> Image.Image:
    scaled = img.resize((size, size), Image.LANCZOS)
    r = int(size * radius_frac)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=r * 4, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(scaled, (0, 0), mask)
    return out


for suffix, source in VARIANTS.items():
    src = Image.open(os.path.join(MEDIA, source)).convert("RGBA")
    for density, scale in DENSITIES.items():
        d = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(d, exist_ok=True)

        canvas_px = round(ADAPTIVE_DP * scale)
        src.resize((canvas_px, canvas_px), Image.LANCZOS).save(
            os.path.join(d, f"ic_launcher{suffix}_bg.png"))

        legacy_px = round(LEGACY_DP * scale)
        rounded_square(src, legacy_px).save(os.path.join(d, f"ic_launcher{suffix}.png"))
        circle_crop(src, legacy_px).save(os.path.join(d, f"ic_launcher{suffix}_round.png"))

    if suffix == "":
        src.resize((512, 512), Image.LANCZOS).convert("RGB").save(
            os.path.join(RES, "..", "ic_launcher-playstore.png"))

print("icons generated")
