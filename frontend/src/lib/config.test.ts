import { afterEach, expect, it, vi } from "vitest";
import { getConfig, getDevelopmentLanOrigins } from "./config";

afterEach(() => vi.unstubAllEnvs());

it("keeps direct browser API calls when LAN access is disabled", () => {
  vi.stubEnv("OPENNEST_DEV_LAN_ORIGINS", "");
  expect(getConfig().browserApiOrigin).toBe(getConfig().apiOrigin);
});

it("uses same-origin browser requests with a fixed loopback server API for LAN development", () => {
  vi.stubEnv("OPENNEST_DEV_LAN_ORIGINS", "http://192.168.1.9:3000");
  expect(getDevelopmentLanOrigins()).toEqual(["http://192.168.1.9:3000"]);
  expect(getConfig()).toMatchObject({
    apiOrigin: "http://localhost:8080",
    browserApiOrigin: "",
    mode: "PRIMARY",
  });
});

it.each([
  "http://8.8.8.8:3000",
  "http://192.168.1.9:3000/other",
  "http://192.168.1.9:3000/",
  "http://192.168.1.9:3000,",
  "http://*.example.com",
  "http://192.168.test.example:3000",
])("rejects unsafe development LAN configuration: %s", (value) => {
  vi.stubEnv("OPENNEST_DEV_LAN_ORIGINS", value);
  expect(getDevelopmentLanOrigins).toThrow();
});

it("rejects LAN access in production and sandbox", () => {
  vi.stubEnv("OPENNEST_DEV_LAN_ORIGINS", "http://192.168.1.9:3000");
  vi.stubEnv("NODE_ENV", "production");
  expect(getConfig).toThrow();
  vi.stubEnv("NODE_ENV", "test");
  vi.stubEnv("OPENNEST_UI_MODE", "SANDBOX");
  expect(getConfig).toThrow();
});

it("rejects forwarding LAN requests to a remote API", () => {
  vi.stubEnv("OPENNEST_DEV_LAN_ORIGINS", "http://192.168.1.9:3000");
  vi.stubEnv("OPENNEST_API_ORIGIN", "https://api.example.com");
  expect(getConfig).toThrow("loopback API");
});
