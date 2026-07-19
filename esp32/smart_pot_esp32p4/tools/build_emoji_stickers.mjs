import fs from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";

const runtimeModules = process.env.NODE_PATH?.split(path.delimiter)[0];
if (!runtimeModules) throw new Error("NODE_PATH must point to a node_modules directory containing sharp");
const require = createRequire(path.join(runtimeModules, "package.json"));
const sharp = require("sharp");

const projectRoot = path.resolve(import.meta.dirname, "..");
const sourceDir = path.join(projectRoot, "main", "assets", "emoji_twemoji");
const previewDir = path.join(projectRoot, "main", "assets", "generated", "emoji_stickers");
const androidDir = path.resolve(projectRoot, "..", "..", "kotlin", "androidApp", "src", "main", "res", "drawable-nodpi");
const outputFile = path.join(projectRoot, "main", "emoji_stickers_img.c");
const names = ["heart", "smile", "happy", "thirsty", "dark", "weak", "wave", "star", "flower", "water", "sun", "sleep"];
const size = 168;

await fs.mkdir(previewDir, { recursive: true });
await fs.mkdir(androidDir, { recursive: true });

let cSource = '#include "lvgl.h"\n\n';
for (const name of names) {
  const svg = await fs.readFile(path.join(sourceDir, `${name}.svg`));
  const pipeline = sharp(svg, { density: 192 })
    .resize(size, size, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } });
  const png = await pipeline.clone().png().toBuffer();
  await fs.writeFile(path.join(previewDir, `${name}.png`), png);
  await fs.writeFile(path.join(androidDir, `emoji_sticker_${name}.png`), png);

  const { data, info } = await pipeline.clone().ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const color = Buffer.alloc(info.width * info.height * 2);
  const alpha = Buffer.alloc(info.width * info.height);
  for (let pixel = 0; pixel < info.width * info.height; pixel++) {
    const offset = pixel * 4;
    const rgb565 = ((data[offset] >> 3) << 11) | ((data[offset + 1] >> 2) << 5) | (data[offset + 2] >> 3);
    color[pixel * 2] = rgb565 & 0xff;
    color[pixel * 2 + 1] = rgb565 >> 8;
    alpha[pixel] = data[offset + 3];
  }
  const bytes = Buffer.concat([color, alpha]);
  const symbol = `emoji_${name}_img`;
  cSource += `static const uint8_t ${symbol}_map[] LV_ATTRIBUTE_MEM_ALIGN = {\n`;
  for (let offset = 0; offset < bytes.length; offset += 16) {
    cSource += `    ${[...bytes.subarray(offset, offset + 16)].map(value => `0x${value.toString(16).padStart(2, "0")}`).join(", ")},\n`;
  }
  cSource += `};\n\nconst lv_image_dsc_t ${symbol} = {\n`;
  cSource += `    .header = { .magic = LV_IMAGE_HEADER_MAGIC, .cf = LV_COLOR_FORMAT_RGB565A8, .flags = 0, .w = ${size}, .h = ${size}, .stride = ${size * 2}, .reserved_2 = 0 },\n`;
  cSource += `    .data_size = sizeof(${symbol}_map),\n    .data = ${symbol}_map,\n    .reserved = NULL,\n};\n\n`;
}

await fs.writeFile(outputFile, cSource, "ascii");
