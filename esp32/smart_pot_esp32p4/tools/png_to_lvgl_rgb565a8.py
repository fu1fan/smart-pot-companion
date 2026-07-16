import argparse
from pathlib import Path

from PIL import Image


def fit_image(source: Image.Image, width: int, height: int, padding: int) -> Image.Image:
    source = source.convert("RGBA")
    scale = min((width - padding * 2) / source.width, (height - padding * 2) / source.height)
    resized = source.resize(
        (max(1, round(source.width * scale)), max(1, round(source.height * scale))),
        Image.Resampling.LANCZOS,
    )
    output = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    output.alpha_composite(resized, ((width - resized.width) // 2, (height - resized.height) // 2))
    return output


def rgb565a8_bytes(image: Image.Image) -> bytes:
    color = bytearray()
    alpha = bytearray()
    for r, g, b, a in image.getdata():
        rgb565 = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
        color.extend((rgb565 & 0xFF, rgb565 >> 8))
        alpha.append(a)
    return bytes(color + alpha)


def write_c_file(output: Path, symbol: str, width: int, height: int, data: bytes) -> None:
    with output.open("w", encoding="ascii", newline="\n") as handle:
        handle.write('#include "lvgl.h"\n\n')
        handle.write(f"static const uint8_t {symbol}_map[] LV_ATTRIBUTE_MEM_ALIGN = {{\n")
        for offset in range(0, len(data), 16):
            chunk = data[offset:offset + 16]
            handle.write("    " + ", ".join(f"0x{value:02x}" for value in chunk) + ",\n")
        handle.write("};\n\n")
        handle.write(f"const lv_image_dsc_t {symbol} = {{\n")
        handle.write("    .header = {\n")
        handle.write("        .magic = LV_IMAGE_HEADER_MAGIC,\n")
        handle.write("        .cf = LV_COLOR_FORMAT_RGB565A8,\n")
        handle.write("        .flags = 0,\n")
        handle.write(f"        .w = {width},\n")
        handle.write(f"        .h = {height},\n")
        handle.write(f"        .stride = {width * 2},\n")
        handle.write("        .reserved_2 = 0,\n")
        handle.write("    },\n")
        handle.write(f"    .data_size = sizeof({symbol}_map),\n")
        handle.write(f"    .data = {symbol}_map,\n")
        handle.write("    .reserved = NULL,\n")
        handle.write("};\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert a transparent PNG to an LVGL RGB565A8 C array.")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--preview", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--symbol", required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--padding", type=int, default=4)
    args = parser.parse_args()

    fitted = fit_image(Image.open(args.input), args.width, args.height, args.padding)
    args.preview.parent.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fitted.save(args.preview)
    write_c_file(args.output, args.symbol, args.width, args.height, rgb565a8_bytes(fitted))


if __name__ == "__main__":
    main()
