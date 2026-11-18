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
  await expect(page.getByText("Sign-in unavailable", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Sign in", exact: true })).toHaveCount(0);
  await page.getByRole("link", { name: "List your place" }).click();
  await expect(page.getByText("Your session could not be checked.", { exact: false })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Sign in to continue" })).toHaveCount(0);
  await page.unroute("**/api/v1/users/me");
  await page.getByRole("button", { name: "Retry session check" }).click();
  await expect(page.getByRole("heading", { name: "Sign in to continue" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Sign in", exact: true })).toBeVisible();
  await expect(
    page.getByRole("alert").filter({ hasText: "OpenNest cannot reach a required service" }),
  ).toHaveCount(0);
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
    const headerLogo = page.locator(".site-header .brand-mark");
    const logoPath = dark ? /\/opennest-logo-dark\.[^/]+\.webp$/ : /\/opennest-logo\.[^/]+\.webp$/;
    await expect(headerLogo).toHaveAttribute("src", logoPath);
    const logo = await headerLogo.evaluate((image: HTMLImageElement) => image.src);
    await expect(page.locator(".site-footer .brand-mark")).toHaveAttribute("src", logoPath);
    const asset = await page.request.get(logo);
    expect(asset.ok()).toBe(true);
    expect(asset.headers()["cache-control"]).toContain("immutable");
    expect((await asset.body()).length).toBeLessThan(10_000);
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

test("public search works without JavaScript at a narrow viewport", async ({ browser }) => {
  const context = await browser.newContext({
    javaScriptEnabled: false,
    viewport: { width: 320, height: 720 },
  });
  try {
    const page = await context.newPage();
    await page.goto(`${sandboxUi}/recruiter-sandbox/`);
    await expect(page.locator(".listing-card").first()).toBeVisible();
    await page.getByText("All filters", { exact: true }).click();
    await expect(page.getByLabel("Minimum quoted rent, INR")).toBeVisible();
    await page.getByLabel("Where are you looking?").fill(" Pune ");
    await page.getByRole("button", { name: "Find my place" }).click();
    await expect(page).toHaveURL(/city=Pune/);
    expect(new URL(page.url()).searchParams.has("searchForm")).toBe(false);
    await expect(page.getByRole("heading", { name: "Places in Pune" })).toBeVisible();
    await page.getByLabel("Sort listings").selectOption("RENT_ASC");
    await page.getByRole("button", { name: "Apply", exact: true }).click();
    await expect(page).toHaveURL(/sort=RENT_ASC/);
    await expect(page).toHaveURL(/city=Pune/);

    const longCity = "W".repeat(120);
    await page.goto(`${sandboxUi}/recruiter-sandbox/?city=${longCity}`);
    await expect(page.getByRole("heading", { name: `Places in ${longCity}` })).toBeVisible();
    expect(await page.locator("html").evaluate((root) => root.scrollWidth)).toBe(320);
  } finally {
    await context.close();
  }
});

const ownerPhone = "+910000000001",
  seekerPhone = "+910000000002";
async function signIn(
  page: Page,
  request: APIRequestContext,
  phoneNumber: string,
) {
  await page.goto("/login");
  await page.getByLabel("Phone number", { exact: true }).fill(phoneNumber.slice(3));
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
  await page.goto("/login?returnTo=%2Fmy-listings");
  await expect(page.getByRole("heading", { name: "You’re signed in" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: /mobile number/i })).toHaveCount(0);
  await page.getByRole("link", { name: "Continue", exact: true }).click();
  await expect(page).toHaveURL(/\/my-listings$/);
  let reservationRequests = 0;
  page.on("request", (request) => {
    if (request.url().endsWith("/images/presign")) reservationRequests++;
  });
  const ids: string[] = [];
  const longTitle = `OpenNest ${"W".repeat(150)}`;
  for (const [index, category, mode, city] of [
    [0, "FLAT", "ENTIRE_FLAT", "Pune"],
    [1, "FLAT", "ROOM", "Delhi"],
    [2, "PG_HOSTEL", "", "Hyderabad"],
  ] satisfies [number, string, string, string][]) {
    await page.goto("/my-listings/new");
    await page
      .getByLabel("Listing title", { exact: true })
      .fill(index === 0 ? longTitle : `OpenNest fabricated place ${index}`);
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
    expect((await request.get(`/listings/${id}`)).status()).toBe(404);
    let sourcePhotoBytes = 0;
    for (const segment of category === "FLAT"
      ? ["ROOM", "HALL", "BATHROOM", "KITCHEN"]
      : ["ROOM", "BATHROOM"]) {
      await page
        .getByRole("combobox", { name: "Photo segment", exact: true })
        .selectOption(segment);
      const sourcePhoto = await image(
        page,
        index === 2 && segment === "ROOM" ? 6000 : 40,
        index === 2 && segment === "ROOM" ? 4000 : 40,
      );
      if (segment === "ROOM") sourcePhotoBytes = sourcePhoto.buffer.length;
      await page.getByLabel("Choose photos", { exact: true }).setInputFiles(sourcePhoto);
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
    expect(photos.every((photo) => photo.contentType === "image/avif")).toBe(
      true,
    );
    const first = photos.at(0);
    if (!first) throw new Error("Validated photo missing");
    expect(first.width).toBe(index === 2 ? 2048 : 40);
    expect(first.height).toBe(index === 2 ? 1365 : 40);
    const finalPhoto = await request.get(first.url);
    const photoBytes = await finalPhoto.body();
    expect(photoBytes.toString("ascii", 4, 8)).toBe("ftyp");
    expect(photoBytes.toString("ascii", 8, 12)).toBe("avif");
    expect(photoBytes.length).toBeGreaterThan(0);
    if (index === 2) expect(photoBytes.length).toBeLessThan(sourcePhotoBytes);
    const rendered = await page.evaluate(async (url) => {
      const photo = new Image();
      photo.src = url;
      await photo.decode();
      return { width: photo.naturalWidth, height: photo.naturalHeight };
    }, first.url);
    expect(rendered).toEqual({ width: first.width, height: first.height });
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
  await seekerPage.setViewportSize({ width: 320, height: 720 });
  let mapRequests = 0;
  seekerPage.on("request", (request) => {
    if (new URL(request.url()).hostname === "api.maptiler.com") mapRequests++;
  });
  await seekerPage.route("**/storage/final/**", (route) => route.fulfill({ status: 403 }));
  await seekerPage.goto(`/listings/${ids[0]}`);
  await expect(seekerPage).toHaveTitle(`${longTitle} in FABRICATED NEIGHBORHOOD, PUNE | OpenNest`);
  expect(await seekerPage.locator("html").evaluate((root) => root.scrollWidth)).toBe(320);
  const contact = seekerPage.getByRole("button", { name: "Reveal owner contact", exact: true });
  const mapToggle = seekerPage.getByRole("button", { name: "Show approximate map" });
  await expect(contact).toBeEnabled();
  await contact.focus();
  await seekerPage.keyboard.press("Tab");
  await expect(mapToggle).toBeFocused();
  expect(mapRequests).toBe(0);
  const webglAvailable = await seekerPage.evaluate(
    () => Boolean(document.createElement("canvas").getContext("webgl2")),
  );
  const mapResponse = webglAvailable ? seekerPage.waitForResponse("https://api.maptiler.com/**") : null;
  await mapToggle.click();
  await expect(seekerPage.getByRole("button", { name: "Hide approximate map" })).toHaveAttribute("aria-expanded", "true");
  if (mapResponse) expect((await mapResponse).ok()).toBe(true);

  await seekerPage.unroute("**/storage/final/**");
  const refresh = seekerPage.getByRole("button", { name: "Refresh photos", exact: true }).first();
  await expect(refresh).toBeVisible();
  const freshListing = seekerPage.waitForResponse((response) =>
    new URL(response.url()).pathname === `/api/v1/listings/${ids[0]}`,
  );
  await refresh.click();
  const freshResponse = await freshListing;
  expect(freshResponse.ok()).toBe(true);
  expect(freshResponse.headers()["cache-control"]).toContain("no-store");
  expect(await freshResponse.request().headerValue("cookie")).toBeNull();
  await expect.poll(async () =>
    seekerPage.locator(".photo-gallery img").first().evaluate((image: HTMLImageElement) => image.naturalWidth),
  ).toBeGreaterThan(0);
  await expect(seekerPage.locator(".photo-gallery img").first()).toHaveAttribute("fetchpriority", "high");
  await seekerPage.setViewportSize({ width: 1280, height: 800 });
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
  await page.getByRole("button", { name: "Delete listing", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Delete listing", exact: true }).click();
  await expect(page).toHaveURL(/\/my-listings$/);
  await expect(page.locator(`h2 a[href="/my-listings/${ids[1]}"]`)).toBeVisible();
  await expect(page.locator(`a[href="/my-listings/${ids[0]}"]`)).toHaveCount(0);
});

test("sandbox URL search, back navigation, mobile controls, personas, and isolation", async ({
  page,
  request,
}) => {
  await page.goto(`${sandboxUi}/recruiter-sandbox`);
  await expect(page.locator(".listing-card:visible")).not.toHaveCount(0);
  await page.getByText("All filters", { exact: true }).click();
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
  await page.getByText("All filters", { exact: true }).click();
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
