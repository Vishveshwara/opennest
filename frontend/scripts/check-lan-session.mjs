import assert from "node:assert/strict";
import { chromium } from "@playwright/test";

const origins = process.argv.slice(2);
assert(origins.length, "Supply frontend origins to check.");
const browser = await chromium.launch({ headless: true });
try {
  for (const origin of origins) {
    assert.equal(new URL(origin).origin, origin, "Use an exact frontend origin.");
    const context = await browser.newContext();
    try {
      const page = await context.newPage();
      const pageErrors = [];
      let unexpectedApiOrigins = 0;
      page.on("pageerror", (error) => pageErrors.push(error.name));
      page.on("request", (request) => {
        const url = new URL(request.url());
        if (url.pathname.startsWith("/api/v1/") && url.origin !== origin)
          unexpectedApiOrigins++;
      });
      await page.goto(origin, { waitUntil: "domcontentloaded" });
      await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("link", { name: "Sign in", exact: true })
        .waitFor();
      const statuses = await page.evaluate(async () => {
        const csrfResponse = await fetch("/api/v1/auth/csrf", {
          credentials: "include",
          cache: "no-store",
        });
        const csrf = await csrfResponse.json();
        const options = {
          method: "POST",
          credentials: "include",
          cache: "no-store",
          body: JSON.stringify({ phoneNumber: "invalid" }),
        };
        const denied = await fetch("/api/v1/auth/telegram/challenges", {
          ...options,
          headers: { "Content-Type": "application/json" },
        });
        const checked = await fetch("/api/v1/auth/telegram/challenges", {
          ...options,
          headers: {
            "Content-Type": "application/json",
            [csrf.headerName]: csrf.token,
          },
        });
        return {
          csrf: csrfResponse.status,
          missingCsrf: denied.status,
          invalidInput: checked.status,
          noStore: [csrfResponse, denied, checked].every((response) =>
            response.headers.get("Cache-Control")?.includes("no-store"),
          ),
        };
      });
      assert.deepEqual(statuses, {
        csrf: 200,
        missingCsrf: 403,
        invalidInput: 400,
        noStore: true,
      });
      const cookie = (await context.cookies(origin)).find(
        (entry) => entry.name === "OPENNEST_SESSION",
      );
      assert(cookie, "The backend session cookie must reach the browser.");
      assert.equal(cookie.domain, new URL(origin).hostname);
      assert.equal(cookie.httpOnly, true);
      assert.equal(cookie.sameSite, "Lax");
      const logout = await page.evaluate(async () => {
        const csrf = await (await fetch("/api/v1/auth/csrf")).json();
        const response = await fetch("/api/v1/auth/logout", {
          method: "POST",
          headers: { [csrf.headerName]: csrf.token },
          credentials: "include",
          cache: "no-store",
        });
        return response.status;
      });
      assert.equal(logout, 204);
      assert.equal(unexpectedApiOrigins, 0);
      assert.deepEqual(pageErrors, []);
      console.log(JSON.stringify({ origin, ...statuses, logout, sessionCookieHostOnly: true, unexpectedApiOrigins, pageErrors }));
    } finally {
      await context.close();
    }
  }
} finally {
  await browser.close();
}
