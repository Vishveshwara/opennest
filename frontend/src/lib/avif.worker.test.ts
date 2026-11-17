import { afterEach, beforeEach, expect, it, vi } from "vitest";

const encode = vi.hoisted(() => vi.fn());
vi.mock("@jsquash/avif/codec/enc/avif_enc.js", () => ({ default: async () => ({ encode }) }));
let listener: ((event: MessageEvent<unknown>) => Promise<void>) | undefined;
const postMessage = vi.fn();
const close = vi.fn();
const drawImage = vi.fn();
const fillRect = vi.fn();
const pixels = { width: 2048, height: 1365, data: new Uint8ClampedArray(4) };

beforeEach(async () => {
  vi.resetModules();
  encode.mockReset().mockReturnValue(new Uint8Array(100));
  close.mockClear(); drawImage.mockClear(); fillRect.mockClear(); postMessage.mockClear();
  vi.stubGlobal("self", {
    location: { origin: "https://opennest.example.invalid" },
    addEventListener: (_name: string, handler: typeof listener) => { listener = handler; },
    postMessage,
  });
  vi.stubGlobal("createImageBitmap", vi.fn(async () => ({ width: 6000, height: 4000, close })));
  vi.stubGlobal("OffscreenCanvas", class {
    width: number; height: number;
    constructor(width: number, height: number) { this.width = width; this.height = height; }
    getContext() { return { fillStyle: "", fillRect, drawImage, getImageData: () => pixels }; }
  });
  await import("./avif.worker");
});
afterEach(() => vi.unstubAllGlobals());

async function run() {
  if (!listener) throw new Error("Worker did not register");
  await listener(new MessageEvent("message", { data: { file: new File(["photo"], "source.png", { type: "image/png" }) } }));
}

it("resizes and flattens on the device, releasing the source bitmap before encoding", async () => {
  await run();
  expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 2048, 1365);
  expect(fillRect).toHaveBeenCalledWith(0, 0, 2048, 1365);
  expect(close).toHaveBeenCalledOnce();
  expect(close.mock.invocationCallOrder[0]).toBeLessThan(encode.mock.invocationCallOrder[0]);
  expect(encode).toHaveBeenCalledWith(pixels.data, 2048, 1365, expect.objectContaining({ quality: 60, speed: 8, lossless: false, bitDepth: 8 }));
  expect(postMessage).toHaveBeenCalledWith({ kind: "encoded", bytes: expect.any(ArrayBuffer) }, expect.anything());
});

it("makes at most two attempts and keeps the smaller result", async () => {
  encode.mockReturnValueOnce(new Uint8Array(1_100_000)).mockReturnValueOnce(new Uint8Array(500_000));
  await run();
  expect(encode).toHaveBeenCalledTimes(2);
  expect(encode.mock.calls[1][3].quality).toBe(45);
  expect(postMessage.mock.calls[0][0].bytes.byteLength).toBe(500_000);
});

it("rejects encoded images above the hard limit without starting an upload", async () => {
  encode.mockReturnValue(new Uint8Array(4_000_001));
  await run();
  expect(encode).toHaveBeenCalledTimes(2);
  expect(postMessage).toHaveBeenCalledWith({ kind: "failed", reason: "size" });
});
