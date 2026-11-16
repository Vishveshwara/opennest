import { beforeEach, expect, it, vi } from "vitest";
import { convertToLosslessWebp, uploadDirect } from "./uploads";
import { MAX_SOURCE_BYTES, validateSource } from "./photo-policy";

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
  await expect(convertToLosslessWebp(oversized)).rejects.toThrow("40 MB");
  expect(CodecWorker.instances).toHaveLength(0);
});

it("does not impose the source-size limit on lossless output", async () => {
  encodedBytes = new ArrayBuffer(MAX_SOURCE_BYTES + 1);
  const source = new File(["photo"], "source.jpeg", { type: "image/jpeg" });
  const result = await convertToLosslessWebp(source);
  expect(result.size).toBe(MAX_SOURCE_BYTES + 1);
  expect(result.type).toBe("image/webp");
  expect(CodecWorker.instances[0].postMessage).toHaveBeenCalledWith({
    file: source,
  });
  expect(CodecWorker.instances[0].terminate).toHaveBeenCalledOnce();
});

it("rejects unsupported formats before allocating an encoder", async () => {
  await expect(
    convertToLosslessWebp(
      new File(["file"], "image.svg", { type: "image/svg+xml" }),
    ),
  ).rejects.toThrow("JPEG, PNG, or WebP");
  expect(CodecWorker.instances).toHaveLength(0);
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
