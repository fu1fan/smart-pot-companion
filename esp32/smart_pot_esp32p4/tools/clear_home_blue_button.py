from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "main" / "assets" / "generated" / "home_static_bg.png"


def main() -> None:
    image = Image.open(SOURCE).convert("RGBA")
    # The live Long button owns this area. Remove its older baked-in background.
    ImageDraw.Draw(image).rectangle((414, 386, 604, 446), fill=(5, 7, 6, 255))
    image.save(SOURCE)


if __name__ == "__main__":
    main()
