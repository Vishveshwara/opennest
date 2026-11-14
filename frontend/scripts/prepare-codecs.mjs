import { copyFile, mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const output = resolve(root, "public/codecs/webp");
await mkdir(output, { recursive: true });
for (const file of ["webp_enc.wasm", "webp_enc_simd.wasm"])
  await copyFile(
    resolve(root, "node_modules/@jsquash/webp/codec/enc", file),
    resolve(output, file),
  );
await copyFile(
  resolve(root, "node_modules/@jsquash/webp/codec/LICENSE.codec.md"),
  resolve(output, "LICENSE.codec.txt"),
);
