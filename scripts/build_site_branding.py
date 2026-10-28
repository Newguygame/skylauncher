#!/usr/bin/env python3
"""Create browser and app icons for the SkyLauncher GitHub Pages website."""
from io import BytesIO
from pathlib import Path

import cairosvg
from PIL import Image

SITE = Path(__file__).resolve().parents[1] / "docs"
ICON = SITE / "favicon.svg"
LOGO = SITE / "fathom-studios-logo.svg"


def render(source: Path, size: int) -> Image.Image:
    data = cairosvg.svg2png(url=str(source), output_width=size)
    return Image.open(BytesIO(data)).convert("RGBA")


def main() -> None:
    icon = render(ICON, 512)
    for size, filename in (
        (16, "favicon-16x16.png"),
        (32, "favicon-32x32.png"),
        (180, "apple-touch-icon.png"),
        (192, "android-chrome-192x192.png"),
        (512, "android-chrome-512x512.png"),
    ):
        icon.resize((size, size), Image.Resampling.LANCZOS).save(SITE / filename, optimize=True)

    icon.save(
        SITE / "favicon.ico",
        format="ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )
    logo = render(LOGO, 1200)
    logo.save(SITE / "fathom-studios-logo.png", optimize=True)

    required = ("favicon.ico", "favicon-32x32.png", "apple-touch-icon.png",
                "android-chrome-192x192.png", "android-chrome-512x512.png",
                "fathom-studios-logo.png")
    for name in required:
        path = SITE / name
        if not path.is_file() or path.stat().st_size == 0:
            raise SystemExit(f"Missing branding asset: {path}")
        print(f"Built {name}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
