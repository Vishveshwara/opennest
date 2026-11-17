import { readFile, mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import encode, { init } from "@jsquash/avif/encode.js";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
await init({ wasmBinary: await readFile(resolve(root, "node_modules/@jsquash/avif/codec/enc/avif_enc.wasm")) });
const width = 64, height = 48;
const data = new Uint8ClampedArray(width * height * 4);
for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
  const offset = (y * width + x) * 4;
  data.set([Math.round(x * 255 / (width - 1)), Math.round(y * 255 / (height - 1)), (x * 17 + y * 13) % 256, 255], offset);
}
const bytes = new Uint8Array(await encode({ data, width, height }, { quality: 60, speed: 8 }));
const directory = resolve(root, "../backend/src/test/resources/images");
await mkdir(directory, { recursive: true });
await writeFile(resolve(directory, "browser-avif.avif"), bytes);
console.log(JSON.stringify({ width, height, bytes: bytes.length }));
