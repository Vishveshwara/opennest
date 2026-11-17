import { beforeEach, expect, it, vi } from "vitest";
import { convertToAvif, uploadDirect } from "./uploads";
import { MAX_AVIF_BYTES, MAX_SOURCE_BYTES, photoDimensions, validateSource } from "./photo-policy";

let encodedBytes = new ArrayBuffer(32);
class CodecWorker {
  static instances: CodecWorker[] = [];
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null;
  onerror: ((event: ErrorEvent) => void) | null = null;
  terminate = vi.fn();
  postMessage = vi.fn(() =>
    queueMicrotask(() =>
      this.onmessage?.(
        new MessageEvent("message", {
          data: { kind: "encoded", bytes: encodedBytes },
        }),
      ),
    ),
  );
  constructor() {
    CodecWorker.instances.push(this);
  }
}

beforeEach(() => {
  CodecWorker.instances = [];
  encodedBytes = new ArrayBuffer(32);
  vi.stubGlobal("Worker", CodecWorker);
});

it("accepts source files at 40 MB and rejects larger files before starting a worker", async () => {
  const source = new File(["photo"], "source.png", { type: "image/png" });
  Object.defineProperty(source, "size", { value: MAX_SOURCE_BYTES });
  expect(() => validateSource(source)).not.toThrow();
  const oversized = new File(["photo"], "large.png", { type: "image/png" });
  Object.defineProperty(oversized, "size", { value: MAX_SOURCE_BYTES + 1 });
  await expect(convertToAvif(oversized)).rejects.toThrow("40 MB");
  expect(CodecWorker.instances).toHaveLength(0);
});

it("returns an AVIF blob and releases its worker", async () => {
  const source = new File(["photo"], "source.jpeg", { type: "image/jpeg" });
  const result = await convertToAvif(source);
  expect(result.size).toBe(32);
  expect(result.type).toBe("image/avif");
  expect(CodecWorker.instances[0].postMessage).toHaveBeenCalledWith({
    file: source,
  });
  expect(CodecWorker.instances[0].terminate).toHaveBeenCalledOnce();
});

it("rejects unsupported formats before allocating an encoder", async () => {
  await expect(
    convertToAvif(
      new File(["file"], "image.svg", { type: "image/svg+xml" }),
    ),
  ).rejects.toThrow("JPEG, PNG, WebP, or AVIF");
  expect(CodecWorker.instances).toHaveLength(0);
});

it("bounds encoded output independently of the source limit", async () => {
  encodedBytes = new ArrayBuffer(MAX_AVIF_BYTES + 1);
  await expect(convertToAvif(new File(["photo"], "source.png", { type: "image/png" }))).rejects.toThrow("4 MB");
  expect(CodecWorker.instances[0].terminate).toHaveBeenCalledOnce();
});

it("resizes large photos proportionally without upscaling small photos", () => {
  expect(photoDimensions(6000, 4000)).toEqual({ width: 2048, height: 1365 });
  expect(photoDimensions(4000, 6000)).toEqual({ width: 1365, height: 2048 });
  expect(photoDimensions(40, 40)).toEqual({ width: 40, height: 40 });
  expect(photoDimensions(1, 10000)).toEqual({ width: 1, height: 2048 });
  expect(() => photoDimensions(0, 100)).toThrow("dimensions");
});

it("uses browser-owned content length and can complete a PUT whose previous acknowledgement was lost", async () => {
  const headers = vi.fn();
  class Request {
    status = 412;
    withCredentials = true;
    timeout = 0;
    upload = { onprogress: null };
    onload: (() => void) | null = null;
    onerror: (() => void) | null = null;
    ontimeout: (() => void) | null = null;
    open = vi.fn();
    setRequestHeader = headers;
    send() {
      this.onload?.();
    }
  }
  vi.stubGlobal("XMLHttpRequest", Request);
  await expect(
    uploadDirect(
      {
        imageId: "00000000-0000-4000-8000-000000000001",
        objectKey: "fixture",
        uploadUrl: "https://storage.example.invalid/staging",
        expiresAt: "2030-01-01T00:00:00Z",
        requiredHeaders: {
          "content-length": "3",
          host: "storage.example.invalid",
          "content-type": "image/webp",
          "if-none-match": "*",
        },
      },
      new Blob(["123"]),
      vi.fn(),
    ),
  ).resolves.toBeUndefined();
  expect(headers.mock.calls).toEqual([
    ["content-type", "image/webp"],
    ["if-none-match", "*"],
  ]);
});
