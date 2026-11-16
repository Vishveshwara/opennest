import encode, { init } from "@jsquash/webp/encode.js";
import { z } from "zod";
import { validateSource, WEBP_MAX_DIMENSION } from "./photo-policy";

self.addEventListener("message", async (event: MessageEvent<unknown>) => {
  try {
    const { file } = z.object({ file: z.instanceof(File) }).parse(event.data);
    validateSource(file);
    const bitmap = await createImageBitmap(file, {
      imageOrientation: "from-image",
      premultiplyAlpha: "none",
    });
    try {
      if (
        bitmap.width > WEBP_MAX_DIMENSION ||
        bitmap.height > WEBP_MAX_DIMENSION
      ) {
        self.postMessage({ kind: "failed", reason: "dimensions" });
        return;
      }
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
      const context = canvas.getContext("2d", { willReadFrequently: true });
      if (!context) throw new Error("Canvas unavailable");
      context.drawImage(bitmap, 0, 0);
      const pixels = context.getImageData(0, 0, bitmap.width, bitmap.height);
      await init({
        locateFile: (name: string) =>
          `${self.location.origin}/codecs/webp/${name}`,
      });
      const bytes = await encode(pixels, {
        lossless: 1,
        near_lossless: 100,
        exact: 1,
        quality: 100,
        method: 4,
        alpha_quality: 100,
      });
      self.postMessage({ kind: "encoded", bytes }, { transfer: [bytes] });
    } finally {
      bitmap.close();
    }
  } catch {
    self.postMessage({ kind: "failed", reason: "conversion" });
  }
});
