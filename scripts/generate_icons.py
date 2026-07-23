#!/usr/bin/env python3
"""Generate Android launcher assets from media-sources/icon.png (PLAN.md §10.1).

The master artwork is used FULL-BLEED and unaltered: it fills the entire
adaptive-icon canvas as the background layer, and launchers apply their own
shape mask over it (circle/squircle/rounded square — imposed by the platform,
not by this script). Outputs, per density:

  mipmap-*/ic_launcher_bg.png     full-bleed artwork on the 108dp adaptive canvas
  mipmap-*/ic_launcher.png        legacy square (full-bleed, rounded corners)
  mipmap-*/ic_launcher_round.png  legacy round (pre-8.0 launchers require the mask)

plus app/src/main/ic_launcher-playstore.png (512px) for the Play listing.
The adaptive foreground is an empty vector (all art lives in the background);
the monochrome/notification silhouette is a hand-authored vector drawable.
"""
from PIL import Image, ImageDraw
import os

SRC = os.path.join(os.path.dirname(__file__), "..", "media-sources", "icon.png")
RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
ADAPTIVE_DP = 108   # adaptive icon canvas
LEGACY_DP = 48

src = Image.open(SRC).convert("RGBA")


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


for density, scale in DENSITIES.items():
    d = os.path.join(RES, f"mipmap-{density}")
    os.makedirs(d, exist_ok=True)

    # Adaptive background: the artwork itself, full-bleed on the 108dp canvas.
    canvas_px = round(ADAPTIVE_DP * scale)
    src.resize((canvas_px, canvas_px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_bg.png"))

    legacy_px = round(LEGACY_DP * scale)
    rounded_square(src, legacy_px).save(os.path.join(d, "ic_launcher.png"))
    circle_crop(src, legacy_px).save(os.path.join(d, "ic_launcher_round.png"))

src.resize((512, 512), Image.LANCZOS).convert("RGB").save(
    os.path.join(RES, "..", "ic_launcher-playstore.png"))
print("icons generated")
