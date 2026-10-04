#!/usr/bin/env python3
"""Compose the TV banner from the supplied icon without redrawing its artwork."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parent.parent
ICON = ROOT / "app/src/main/res/drawable-nodpi/astra_icon.png"
BANNER = ROOT / "app/src/main/res/drawable-xhdpi/astra_banner.png"
MASTER = ROOT / "branding/astra-banner-master.png"
REPORT = ROOT / "branding/provenance.json"
BACKGROUND = "#DCEAF4"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def image_details(filename):
    with Image.open(filename) as source:
        rgba = source.convert("RGBA")
        return {
            "file": filename.relative_to(ROOT).as_posix(),
            "size": list(source.size),
            "mode": source.mode,
            "file_sha256": sha256(filename.read_bytes()),
            "rgba_sha256": sha256(rgba.tobytes()),
            "alpha_extrema": list(rgba.getchannel("A").getextrema()),
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--icon", type=Path, default=ICON,
                        help="Original PNG to copy unchanged into the app")
    args = parser.parse_args()
    original = args.icon.expanduser().resolve()
    with Image.open(original) as source:
        if source.format != "PNG":
            raise SystemExit("The icon must be an original PNG")
        rgba = source.convert("RGBA")
    bounds = rgba.getchannel("A").getbbox()
    if bounds is None:
        raise SystemExit("The icon is entirely transparent")
    for filename in (ICON, BANNER, MASTER, REPORT):
        filename.parent.mkdir(parents=True, exist_ok=True)
    if original != ICON.resolve():
        shutil.copyfile(original, ICON)

    master = Image.new("RGBA", (1280, 720), BACKGROUND)
    artwork = rgba.crop(bounds)
    artwork_height = 480
    artwork_width = round(artwork.width * artwork_height / artwork.height)
    artwork = artwork.resize((artwork_width, artwork_height), Image.Resampling.LANCZOS)
    artwork_position = ((master.width - artwork_width) // 2,
                        (master.height - artwork_height) // 2)
    master.alpha_composite(artwork, artwork_position)
    master.convert("RGB").save(MASTER, optimize=True)
    master.convert("RGB").resize((320, 180), Image.Resampling.LANCZOS).save(BANNER, optimize=True)

    report = {
        "method": "Deterministic Pillow alpha composition; no AI generation or artwork redrawing",
        "artwork_source_name": original.name if original != ICON.resolve() else "青色电视与简约卫星天线.png",
        "icon_copied_byte_for_byte": sha256(original.read_bytes()) == sha256(ICON.read_bytes()),
        "original_file_sha256": sha256(original.read_bytes()),
        "icon": image_details(ICON),
        "banner": image_details(BANNER),
        "master": image_details(MASTER),
        "composition": {
            "background": BACKGROUND,
            "icon_alpha_crop": list(bounds),
            "icon_destination_xy": list(artwork_position),
            "icon_destination_size": list(artwork.size),
            "resampling": "LANCZOS, proportional",
            "layout": "centered artwork, two thirds of canvas height, matching previous banner margins",
            "title": None,
        },
    }
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Icon: {ICON}\nBanner: {BANNER}\nPreview: {MASTER}")


if __name__ == "__main__":
    main()
