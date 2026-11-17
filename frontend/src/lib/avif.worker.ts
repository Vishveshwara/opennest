import createEncoder from "@jsquash/avif/codec/enc/avif_enc.js";
import { defaultOptions } from "@jsquash/avif/meta.js";
import { z } from "zod";
import { MAX_AVIF_BYTES, TARGET_PHOTO_BYTES, photoDimensions, validateSource } from "./photo-policy";

self.addEventListener("message", async (event: MessageEvent<unknown>) => {
  try {
    const { file } = z.object({ file: z.instanceof(File) }).parse(event.data);
    validateSource(file);
    const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    let pixels: ImageData;
    try {
      const { width, height } = photoDimensions(bitmap.width, bitmap.height);
      const canvas = new OffscreenCanvas(width, height);
      const context = canvas.getContext("2d", { alpha: false, willReadFrequently: true });
      if (!context) throw new Error("Canvas unavailable");
      context.fillStyle = "white";
      context.fillRect(0, 0, width, height);
      context.imageSmoothingEnabled = true;
      context.imageSmoothingQuality = "high";
      context.drawImage(bitmap, 0, 0, width, height);
      pixels = context.getImageData(0, 0, width, height);
      canvas.width = 0;
      canvas.height = 0;
    } finally {
      bitmap.close();
    }
    const encoder = await createEncoder({
      locateFile: (name: string) => `${self.location.origin}/codecs/avif/${name}`,
    });
    let output = encoder.encode(pixels.data, pixels.width, pixels.height, {
      ...defaultOptions, quality: 60, speed: 8,
    });
    if (!output) throw new Error("Encoding failed");
    let bytes = output.slice().buffer;
    if (bytes.byteLength > TARGET_PHOTO_BYTES) {
      output = encoder.encode(pixels.data, pixels.width, pixels.height, {
        ...defaultOptions, quality: 45, speed: 8,
      });
      if (output && output.byteLength < bytes.byteLength) bytes = output.slice().buffer;
    }
    if (bytes.byteLength > MAX_AVIF_BYTES) {
      self.postMessage({ kind: "failed", reason: "size" });
      return;
    }
    self.postMessage({ kind: "encoded", bytes }, { transfer: [bytes] });
  } catch {
    self.postMessage({ kind: "failed", reason: "conversion" });
  }
});
