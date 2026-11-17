import { copyFile, mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const output = resolve(root, "public/codecs/avif");
await mkdir(output, { recursive: true });
for (const file of ["avif_enc.wasm"])
  await copyFile(
    resolve(root, "node_modules/@jsquash/avif/codec/enc", file),
    resolve(output, file),
  );
await copyFile(
  resolve(root, "third-party/AVIF_CODEC_NOTICES.txt"),
  resolve(output, "LICENSE.codec.txt"),
);
