import { presignSchema } from "./contracts";
import { z } from "zod";
import { MAX_AVIF_BYTES, validateSource } from "./photo-policy";

export async function convertToAvif(file: File): Promise<Blob> {
  validateSource(file);
  const worker = new Worker(new URL("./avif.worker.ts", import.meta.url), {
    type: "module",
  });
  return new Promise<Blob>((resolve, reject) => {
    const deadline = setTimeout(() => {
      worker.terminate();
      reject(new Error("Photo preparation took too long. Try a smaller photo."));
    }, 120_000);
    worker.onmessage = (event: MessageEvent<unknown>) => {
      clearTimeout(deadline);
      const result = z
        .discriminatedUnion("kind", [
          z.object({
            kind: z.literal("encoded"),
            bytes: z.instanceof(ArrayBuffer),
          }),
          z.object({
            kind: z.literal("failed"),
            reason: z.enum(["size", "conversion"]),
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
        if (!result.data.bytes.byteLength || result.data.bytes.byteLength > MAX_AVIF_BYTES) {
          reject(new Error("The converted photo exceeds the 4 MB upload limit."));
          return;
        }
        resolve(new Blob([result.data.bytes], { type: "image/avif" }));
        return;
      }
      const messages = {
        size: "The converted photo exceeds 4 MB. Choose a smaller photo.",
        conversion:
          "Your browser could not convert this photo. It may be damaged, animated, or exceed your device's available memory.",
      };
      reject(new Error(messages[result.data.reason]));
    };
    worker.onerror = (event) => {
      clearTimeout(deadline);
      event.preventDefault();
      worker.terminate();
      reject(
        new Error(
          "The photo encoder could not start. Refresh the page and try again.",
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
