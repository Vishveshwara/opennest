import {
  test,
  expect,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
import { z } from "zod";
import AxeBuilder from "@axe-core/playwright";

const primaryUi = process.env.E2E_PRIMARY_UI ?? "http://localhost:3000";
const sandboxUi = process.env.E2E_SANDBOX_UI ?? "http://localhost:3001";

async function controlledMap(page: Page) {
  await page.route("https://api.maptiler.com/**", (route) =>
    route.fulfill({ json: { version: 8, sources: {}, layers: [] } }),
  );
}
test.beforeEach(async ({ page }) => {
  await controlledMap(page);
});

test("extension HTML attributes and session network failure are handled", async ({
  page,
}) => {
  let hydrationWarnings = 0;
  const uncaught: string[] = [];
  page.on("console", (message) => {
    if (
      message.type() === "error" &&
      /hydrated|hydration|didn.t match/i.test(message.text())
    )
      hydrationWarnings++;
  });
  page.on("pageerror", (error) => uncaught.push(error.name));
  await page.addInitScript(() => {
    const observer = new MutationObserver(apply);
    function apply() {
      if (document.documentElement) {
        document.documentElement.setAttribute(
          "data-ctrl-f-plus-layover-theme",
          "standard-dark",
        );
        observer.disconnect();
      }
    }
    observer.observe(document, { childList: true, subtree: true });
    apply();
  });
  await page.route("**/api/v1/users/me", (route) => route.abort("failed"));
  await page.goto("/login");
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "OpenNest cannot reach a required service" }),
  ).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute(
    "data-ctrl-f-plus-layover-theme",
    "standard-dark",
  );
  expect(hydrationWarnings).toBe(0);
  expect(uncaught).toEqual([]);
});

test("public search is keyboard accessible in both themes and survives map failure", async ({
  page,
}) => {
  await page.goto(`${sandboxUi}/recruiter-sandbox/`);
  if (process.env.OPENNEST_CAPTURE_PUBLIC_PREVIEW === "1") {
    await page.screenshot({
      path: "test-results/public-search-light.png",
      fullPage: false,
    });
  }
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "Skip to content" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("#main")).toBeFocused();
  for (const dark of [false, true]) {
    if (dark)
      await page
        .getByRole("button", { name: "Use dark theme", exact: true })
        .click();
    await expect
      .poll(() =>
        page.evaluate(
          () =>
            document
              .getAnimations()
              .filter((animation) => animation.playState === "running").length,
        ),
      )
      .toBe(0);
    const result = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(
      result.violations.map((value) => ({
        id: value.id,
        nodes: value.nodes.map((node) => node.target),
      })),
    ).toEqual([]);
  }
  await page.unroute("https://api.maptiler.com/**");
  await page.route("https://api.maptiler.com/**", (route) => route.abort());
  await page.getByRole("button", { name: "Map view", exact: true }).click();
  await expect(
    page.getByText(
      "Map tiles could not load. Listings and coordinate fields remain available.",
    ),
  ).toBeVisible();
  await expect(page.locator(".listing-card:visible")).not.toHaveCount(0);
});

const ownerPhone = "+910000000001",
  seekerPhone = "+910000000002";
async function signIn(
  page: Page,
  request: APIRequestContext,
  phoneNumber: string,
) {
  await page.goto("/login");
  await page.getByLabel("Phone number", { exact: true }).fill(phoneNumber);
  await page.getByRole("button", { name: "Continue with Telegram" }).click();
  const link = page.getByRole("link", { name: "Open Telegram" });
  await expect(link).toBeVisible();
  const botUrl = await link.getAttribute("href");
  const response = await request.post(
    `${process.env.E2E_CONTROL_ORIGIN}/telegram`,
    {
      headers: { "X-Control-Secret": process.env.E2E_CONTROL_SECRET ?? "" },
      data: { botUrl, phoneNumber },
    },
  );
  expect(response.ok()).toBe(true);
  const { code } = z.object({ code: z.string() }).parse(await response.json());
  await page.getByLabel("Telegram code", { exact: true }).fill(code);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  const name = page.getByLabel("Full name", { exact: true });
  const signedIn = page.getByRole("button", { name: "Sign out" });
  await expect(name.or(signedIn)).toBeVisible();
  if (await name.isVisible()) {
    await name.fill("Fabricated OpenNest member");
    await page.getByRole("button", { name: "Create account", exact: true }).click();
  }
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}

async function image(page: Page, width = 40, height = 40) {
  const base64 = await page.evaluate(
    ({ width, height }) => {
      const canvas = document.createElement("canvas");
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext("2d");
      if (!ctx) throw new Error("Canvas unavailable");
      ctx.fillStyle = getComputedStyle(
        document.documentElement,
      ).getPropertyValue("--primary");
      ctx.fillRect(0, 0, width, height);
      return canvas.toDataURL("image/png").split(",")[1];
    },
    { width, height },
  );
  return {
    name: "fabricated-room.png",
    mimeType: "image/png",
    buffer: Buffer.from(base64, "base64"),
  };
}

test("Telegram, all rental modes, direct uploads, publication, reveals, reviews, and pause", async ({
  page,
  request,
  browser,
}) => {
  test.setTimeout(240000);
  await signIn(page, request, ownerPhone);
  let reservationRequests = 0;
  page.on("request", (request) => {
    if (request.url().endsWith("/images/presign")) reservationRequests++;
  });
  const ids: string[] = [];
  for (const [index, category, mode, city] of [
    [0, "FLAT", "ENTIRE_FLAT", "Pune"],
    [1, "FLAT", "ROOM", "Delhi"],
    [2, "PG_HOSTEL", "", "Hyderabad"],
  ] satisfies [number, string, string, string][]) {
    await page.goto("/my-listings/new");
    await page
      .getByLabel("Listing title", { exact: true })
      .fill(`OpenNest fabricated place ${index}`);
    await page
      .getByRole("combobox", { name: "Category", exact: true })
      .selectOption(category);
    if (category === "FLAT")
      await page
        .getByRole("combobox", { name: "Rental mode", exact: true })
        .selectOption(mode);
    await page
      .getByLabel("Description", { exact: true })
      .fill(
        "Fabricated browser acceptance listing. No real accommodation or contact data.",
      );
    await page.getByLabel("City", { exact: true }).fill(city);
    await page
      .getByLabel("Locality", { exact: true })
      .fill("Fabricated neighborhood");
    await page
      .getByLabel("Private street address", { exact: true })
      .fill("Fabricated private test address");
    await page.getByLabel("Latitude", { exact: true }).fill("18.520400");
    await page.getByLabel("Longitude", { exact: true }).fill("73.856700");
    await page.getByLabel(/Monthly rent, INR/).fill(String(12000 + index));
    if (mode !== "ENTIRE_FLAT")
      await page.getByLabel("Male rooms available", { exact: true }).fill("2");
    await page.getByRole("button", { name: "Save private draft" }).click();
    await expect(
      page.getByRole("heading", { name: "Publication checklist" }),
    ).toBeVisible();
    const id = page.url().split("/").at(-1);
    if (!id) throw new Error("Draft URL missing");
    ids.push(id);
    await expect(
      page.getByRole("button", { name: "Publish listing" }),
    ).toBeDisabled();
    const publicDraft = await request.get(
      `${process.env.E2E_PRIMARY_API}/api/v1/listings/${id}`,
    );
    expect(publicDraft.status()).toBe(404);
    for (const segment of category === "FLAT"
      ? ["ROOM", "HALL", "BATHROOM", "KITCHEN"]
      : ["ROOM", "BATHROOM"]) {
      await page
        .getByRole("combobox", { name: "Photo segment", exact: true })
        .selectOption(segment);
      await page
        .getByLabel("Choose photos", { exact: true })
        .setInputFiles(
          await image(
            page,
            index === 2 && segment === "ROOM" ? 6000 : 40,
            index === 2 && segment === "ROOM" ? 4000 : 40,
          ),
        );
      const retryTransport = index === 0 && segment === "ROOM";
      const priorReservations = reservationRequests;
      if (retryTransport)
        await page.route(
          "**/storage/staging/**",
          (route) => route.abort("failed"),
          { times: 1 },
        );
      await page
        .getByRole("button", { name: "Upload photo", exact: true })
        .click();
      if (retryTransport) {
        await page
          .getByRole("button", { name: "Retry photo", exact: true })
          .click();
      }
      await expect(
        page.getByRole("button", { name: "Upload photo", exact: true }),
      ).toHaveCount(0);
      await expect(
        page.getByRole("status").filter({ hasText: /^Validated$/ }),
      ).toHaveCount(
        (category === "FLAT"
          ? ["ROOM", "HALL", "BATHROOM", "KITCHEN"]
          : ["ROOM", "BATHROOM"]
        ).indexOf(segment) + 1,
      );
      if (retryTransport)
        expect(reservationRequests - priorReservations).toBe(1);
    }
    await page
      .getByRole("button", { name: "Publish listing", exact: true })
      .click();
    await expect(
      page.getByRole("button", { name: "Pause listing", exact: true }),
    ).toBeVisible();
    const publicResponse = await request.get(
      `${process.env.E2E_PRIMARY_API}/api/v1/listings/${id}`,
    );
    expect(publicResponse.headers()["cache-control"]).toContain("no-store");
    const body = z
      .record(z.string(), z.unknown())
      .parse(await publicResponse.json());
    const photos = z
      .array(
        z.object({
          contentType: z.string(),
          width: z.number(),
          height: z.number(),
          url: z.string(),
        }),
      )
      .parse(body.images);
    expect(photos.every((photo) => photo.contentType === "image/webp")).toBe(
      true,
    );
    const first = photos.at(0);
    if (!first) throw new Error("Validated photo missing");
    expect(first.width).toBe(index === 2 ? 6000 : 40);
    expect(first.height).toBe(index === 2 ? 4000 : 40);
    const finalPhoto = await request.get(first.url);
    const photoBytes = await finalPhoto.body();
    expect(photoBytes.toString("ascii", 8, 12)).toBe("WEBP");
    expect(photoBytes.toString("ascii", 12, 16)).toBe("VP8L");
    expect(body).not.toHaveProperty("privateAddress");
    expect(body).not.toHaveProperty("latitude");
    expect(body).not.toHaveProperty("phoneNumber");
    expect(body.publicLocation).toEqual({
      latitude: 18.53,
      longitude: 73.85,
      radiusMeters: 2000,
    });
  }
  await page.goto("/");
  await expect(page.locator(".listing-card:visible")).toHaveCount(3);
  const htmlResponse = await request.get("/");
  expect(htmlResponse.headers()["cache-control"]).toContain("no-store");
  await expect(
    page
      .getByText("INR 12,000 / property / month", { exact: true })
      .filter({ visible: true }),
  ).toBeVisible();
  await expect(
    page
      .getByText("INR 12,001 / room / month", { exact: true })
      .filter({ visible: true }),
  ).toBeVisible();
  await expect(
    page
      .getByText("INR 12,002 / person / month", { exact: true })
      .filter({ visible: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  const seeker = await browser.newContext({ baseURL: primaryUi });
  const seekerPage = await seeker.newPage();
  await controlledMap(seekerPage);
  await signIn(seekerPage, request, seekerPhone);
  await seekerPage.goto(`/listings/${ids[0]}`);
  await seekerPage
    .getByRole("button", { name: "Reveal owner contact", exact: true })
    .click();
  await expect(
    seekerPage.getByRole("link", { name: "Open WhatsApp" }),
  ).toBeVisible();
  await expect(
    seekerPage.getByText(/9 distinct contacts remaining/),
  ).toBeVisible();
  await seekerPage
    .getByLabel("Comment, optional", { exact: true })
    .fill("Fabricated experience to check report conversion.");
  await seekerPage
    .getByRole("button", { name: "Submit review", exact: true })
    .click();
  await expect(
    seekerPage.getByText("Your review is saved.", { exact: false }),
  ).toBeVisible();
  await seekerPage
    .getByRole("button", { name: "Report brokerage request", exact: true })
    .click();
  await expect(seekerPage.getByRole("dialog")).toBeVisible();
  await seekerPage
    .getByRole("button", { name: "Confirm report", exact: true })
    .click();
  await expect(
    seekerPage.getByText("Fabricated experience to check report conversion.", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    seekerPage.getByText(
      "Brokerage reported. This is an allegation, not a finding of misconduct.",
    ),
  ).toBeVisible();
  await seekerPage
    .getByRole("button", { name: "Sign out", exact: true })
    .click();
  await expect(
    seekerPage.getByRole("link", { name: "Open WhatsApp" }),
  ).toHaveCount(0);
  await seeker.close();
  await signIn(page, request, ownerPhone);
  await page.goto(`/my-listings/${ids[0]}`);
  await page
    .getByRole("button", { name: "Pause listing", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Publish listing", exact: true }),
  ).toBeVisible();
  const paused = await request.get(
    `${process.env.E2E_PRIMARY_API}/api/v1/listings/${ids[0]}`,
  );
  expect(paused.status()).toBe(404);
});

test("sandbox URL search, back navigation, mobile controls, personas, and isolation", async ({
  page,
  request,
}) => {
  await page.goto(`${sandboxUi}/recruiter-sandbox`);
  await expect(page.locator(".listing-card:visible")).not.toHaveCount(0);
  await page.getByRole("button", { name: "All filters", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Where are you looking?", exact: true })
    .fill("Pune");
  await page
    .getByRole("button", { name: "Find my place", exact: true })
    .click();
  await expect(page).toHaveURL(/city=Pune/);
  await page
    .getByRole("combobox", { name: "Sort listings", exact: true })
    .selectOption("RENT_ASC");
  await expect(page).toHaveURL(/sort=RENT_ASC/);
  await expect(page).toHaveURL(/page=0/);
  await page.goBack();
  await expect(
    page.getByRole("combobox", { name: "Sort listings", exact: true }),
  ).toHaveValue("NEWEST");
  await page.goto(`${sandboxUi}/recruiter-sandbox/?page=1&size=1`);
  await expect(
    page.getByRole("link", { name: "Page 2", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await page
    .getByRole("combobox", { name: "Results per page", exact: true })
    .selectOption("20");
  await expect(page).toHaveURL(/page=0/);
  const webglAvailable = await page.evaluate(
    () => Boolean(document.createElement("canvas").getContext("webgl2")),
  );
  const workerErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error" && /Worker failed to load/i.test(message.text()))
      workerErrors.push("Map worker failed to load");
  });
  const workerResponse = webglAvailable
    ? page.waitForResponse((response) => {
        const url = new URL(response.url());
        return url.origin === sandboxUi && /maplibre-gl-worker\.[^/]+\.mjs$/.test(url.pathname);
      })
    : null;
  await page.getByRole("button", { name: "Map view", exact: true }).click();
  await expect(
    page
      .getByRole("status")
      .filter({ hasText: webglAvailable ? "Location map ready" : "Map unavailable" }),
  ).toBeVisible();
  if (workerResponse) {
    const response = await workerResponse;
    expect(response.status()).toBe(200);
    expect(response.headers()["content-type"]).toMatch(/javascript/);
  }
  expect(workerErrors).toEqual([]);
  await expect(page.locator(".listing-card").first()).toBeVisible();
  await page.goto(`${sandboxUi}/recruiter-sandbox/personas`);
  await page
    .getByRole("button")
    .filter({
      has: page.getByRole("heading", {
        name: "Demo seeker ready to review",
        exact: true,
      }),
    })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await page.locator(".listing-card h3 a").first().click();
  await expect(
    page.getByText("Contact actions are disabled in the recruiter sandbox.", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Reveal owner contact", exact: true }),
  ).toHaveCount(0);
  const primaryDenied = await request.get(`${primaryUi}/recruiter-sandbox`);
  expect(primaryDenied.status()).toBe(404);
  const reset = await request.post(`${process.env.E2E_CONTROL_ORIGIN}/reset`, {
    headers: { "X-Control-Secret": process.env.E2E_CONTROL_SECRET ?? "" },
  });
  expect(reset.ok()).toBe(true);
  await page
    .getByRole("link", { name: "List your place", exact: false })
    .click();
  await expect(
    page.getByRole("heading", { name: "Sign in to continue", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${sandboxUi}/recruiter-sandbox/`);
  await page.getByRole("button", { name: "All filters", exact: true }).click();
  await page
    .getByRole("combobox", { name: "Place type", exact: true })
    .selectOption("PG_HOSTEL");
  await expect(
    page.getByRole("spinbutton", {
      name: "Minimum bedrooms / BHK",
      exact: true,
    }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "Find my place", exact: true })
    .click();
  await expect(page).toHaveURL(/category=PG_HOSTEL/);
  await expect(
    page.getByRole("button", { name: "Use dark theme", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Use dark theme", exact: true })
    .click();
  await expect(page.locator("html")).toHaveClass("dark");
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  );
  expect(overflow).toBe(false);
  await page.goto(`${sandboxUi}/recruiter-sandbox/?sort=unknown`);
  await expect(
    page.getByRole("alert").filter({ hasText: "unsupported" }),
  ).toBeVisible();
});
