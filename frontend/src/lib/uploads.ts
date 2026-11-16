import { presignSchema } from "./contracts";
import { z } from "zod";
import { validateSource } from "./photo-policy";

export async function convertToLosslessWebp(file: File): Promise<Blob> {
  validateSource(file);
  const worker = new Worker(new URL("./webp.worker.ts", import.meta.url), {
    type: "module",
  });
  return new Promise<Blob>((resolve, reject) => {
    worker.onmessage = (event: MessageEvent<unknown>) => {
      const result = z
        .discriminatedUnion("kind", [
          z.object({
            kind: z.literal("encoded"),
            bytes: z.instanceof(ArrayBuffer),
          }),
          z.object({
            kind: z.literal("failed"),
            reason: z.enum(["dimensions", "conversion"]),
          }),
        ])
        .safeParse(event.data);
      worker.terminate();
      if (!result.success) {
        reject(
          new Error("The photo could not be converted. Please try again."),
        );
        return;
      }
      if (result.data.kind === "encoded") {
        resolve(new Blob([result.data.bytes], { type: "image/webp" }));
        return;
      }
      const messages = {
        dimensions:
          "WebP supports images up to 16,383 pixels on each side. Choose a photo within that format limit.",
        conversion:
          "Your browser could not convert this photo. It may be damaged, animated, or exceed your device's available memory.",
      };
      reject(new Error(messages[result.data.reason]));
    };
    worker.onerror = (event) => {
      event.preventDefault();
      worker.terminate();
      reject(
        new Error(
          "The lossless image encoder could not start. Refresh the page and try again.",
        ),
      );
    };
    worker.postMessage({ file });
  });
}

export class UploadTransportError extends Error {
  constructor(readonly needsNewReservation: boolean) {
    super(
      needsNewReservation
        ? "The upload URL is unavailable. Retry this photo with a fresh reservation."
        : "The photo upload failed. Check your connection and retry.",
    );
  }
}

export function uploadDirect(
  ticket: z.infer<typeof presignSchema>,
  blob: Blob,
  onProgress: (progress: number) => void,
) {
  const url = new URL(ticket.uploadUrl);
  if (!["https:", "http:"].includes(url.protocol))
    throw new Error("The upload URL is unavailable.");
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", url.href);
    xhr.withCredentials = false;
    xhr.timeout = 540000;
    Object.entries(ticket.requiredHeaders).forEach(([key, value]) => {
      if (!["content-length", "host"].includes(key.toLowerCase()))
        xhr.setRequestHeader(key, value);
    });
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable)
        onProgress(Math.round((event.loaded / event.total) * 100));
    };
    xhr.onerror = xhr.ontimeout = () => reject(new UploadTransportError(false));
    xhr.onload = () => {
      if ((xhr.status >= 200 && xhr.status < 300) || xhr.status === 412)
        resolve();
      else
        reject(
          new UploadTransportError(
            [400, 401, 403, 404, 409, 410].includes(xhr.status),
          ),
        );
    };
    xhr.send(blob);
  });
}
