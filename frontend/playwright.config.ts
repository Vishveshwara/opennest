import { defineConfig, devices } from "@playwright/test";

process.env.PLAYWRIGHT_NO_COPY_PROMPT = "1";

const primaryUi = process.env.E2E_PRIMARY_UI ?? "http://localhost:3000";
const sandboxUi = process.env.E2E_SANDBOX_UI ?? "http://localhost:3001";
const real = process.env.OPENNEST_E2E_REAL === "1";
if (!real)
  throw new Error(
    "Run browser acceptance through the test-only FrontendBrowserHarness. See docs/PHASE5_HANDOFF.md.",
  );
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 120000,
  expect: { timeout: 15000 },
  retries: 0,
  grep: process.env.OPENNEST_E2E_GREP
    ? new RegExp(process.env.OPENNEST_E2E_GREP)
    : undefined,
  reporter: "list",
  use: {
    baseURL: primaryUi,
    trace: "off",
    screenshot: "off",
    video: "off",
    launchOptions: { args: ["--enable-unsafe-swiftshader"] },
    actionTimeout: 15000,
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: `npm run start -- --port ${new URL(primaryUi).port}`,
      url: primaryUi,
      reuseExistingServer: false,
      timeout: 90000,
      env: {
        OPENNEST_UI_MODE: "PRIMARY",
        OPENNEST_API_ORIGIN: process.env.E2E_PRIMARY_API ?? "",
        OPENNEST_SITE_ORIGIN: primaryUi,
        OPENNEST_MAPTILER_KEY: "fabricated-browser-map-key",
      },
    },
    {
      command: `npm run start -- --port ${new URL(sandboxUi).port}`,
      url: `${sandboxUi}/recruiter-sandbox`,
      reuseExistingServer: false,
      timeout: 90000,
      env: {
        OPENNEST_UI_MODE: "SANDBOX",
        OPENNEST_API_ORIGIN: process.env.E2E_SANDBOX_API ?? "",
        OPENNEST_SITE_ORIGIN: sandboxUi,
        OPENNEST_MAPTILER_KEY: "fabricated-browser-map-key",
      },
    },
  ],
});
