# OpenNest Phase 5 frontend

Phase 5 adds the Next.js frontend and the approved approximate public map projection. Database migrations V1-V7 are unchanged. The image follow-up adds V8 to expand media constraints without altering tables or existing photos. Hosting, DNS/TLS, purchases, merge and deployment are not included.

## Run locally

Use Node 24 and Java 25. Start the existing PRIMARY backend and its isolated stores using the backend handoff. Telegram authentication still requires explicit backend enablement and private bot configuration. Never copy the root private environment file into the frontend.

```powershell
cd frontend
npm ci
npm run dev
```

The defaults are PRIMARY, frontend http://localhost:3000 and backend http://localhost:8080. Override OPENNEST_API_ORIGIN and OPENNEST_SITE_ORIGIN through the process environment or a private frontend/.env.local. They are fixed server configuration, not request inputs. Before releasing a revision, set OPENNEST_SOURCE_REVISION to its published 40-character Git commit so the footer links to corresponding source and its license. Local uncommitted work defaults to the repository link; that is not release evidence.

Run a dedicated sandbox backend on port 8081 with its separate stores and explicit persona configuration. In a second frontend process:

```powershell
$env:OPENNEST_DEV_LAN_ORIGINS = ''
$env:OPENNEST_UI_MODE = 'SANDBOX'
$env:OPENNEST_API_ORIGIN = 'http://localhost:8081'
$env:OPENNEST_SITE_ORIGIN = 'http://localhost:3001'
npm run dev -- --port 3001
```

Open http://localhost:3001/recruiter-sandbox. PRIMARY returns 404 for that path. The sandbox is unlisted, contains fabricated multi-city data and offers personas through /recruiter-sandbox/personas. Callable contacts stay disabled; normal ownership/publication rules remain. Resets invalidate sessions and require persona selection again. Reset and telemetry remain operator CLI operations.

The shared dev command cannot run two Next.js dev servers against the same .next directory simultaneously. For concurrent local PRIMARY/SANDBOX verification, run npm run build once, then npm run start on ports 3000 and 3001 with their separate process environments. Do not build while those servers are serving that output.

## Test from another device on the local network

Local development normally uses http://localhost:3000 and direct requests to http://localhost:8080. On another device, localhost refers to that device, so the unconfigured network URL cannot use this API address. The original Account label indicated an unfinished session probe rather than an authenticated account; it now reads Checking sign-in until that probe completes.

For explicit DEVELOPMENT/PRIMARY LAN access, add this setting to both the private root .env and frontend/.env.development.local:

```dotenv
OPENNEST_DEV_LAN_ORIGINS=http://192.168.1.9:3000
```

Use the development-only frontend environment file so production builds do not inherit the LAN opt-in. Restart the backend and npm run dev after changing these settings. Keep the backend and frontend primary origins configured for localhost. The browser sends /api/v1/... requests to the frontend address it opened; Next rewrites them to the fixed loopback backend. Localhost continues to work. Next development assets allow only the configured extra hosts, and backend CORS accepts only its existing origin plus the explicit LAN origins. Production and sandbox reject this opt-in. Host-only HttpOnly/Lax cookies, original Origin, CSRF checks, realm isolation and no-store stay enforced. Server reads still use the configured API origin. No image bytes pass through this proxy; R2 uploads remain direct.

The list accepts at most eight exact HTTP origins with RFC1918 IPv4 addresses, separated by commas. Paths, wildcards, public IPs and remote backend targets are rejected. Update the setting if the computer's LAN address changes. R2 bucket CORS must separately include the LAN frontend origin before direct photo uploads can work from that address.

With the development frontend and backend running, verify both addresses from frontend:

```powershell
npm run check:lan -- http://localhost:3000 http://192.168.1.9:3000
```

This uses a fresh browser session for each address and checks the Sign in link, same-origin requests, host-only HttpOnly/Lax cookies, anonymous CSRF issuance, rejection without CSRF, invalid login-input rejection with CSRF, logout, no-store headers and uncaught errors. It submits only an invalid phone string and creates no Telegram challenge or bot message. It does not sign in a real account.

## Use the application

- Search starts nationwide. Filters, allowlisted sorting and pagination live in the URL. Page numbers are one-based in the UI and zero-based in the API. Filter/sort/size changes reset page zero.
- Telegram login uses the original browser, native private self-contact and OTP. Status polling does not authenticate. CSRF refresh follows session rotation. Only the account holder can see their private account contact.
- Owners use /my-listings to create PAUSED drafts, add segmented photos, validate them and explicitly publish. Direct uploads never pass through Next.js or Spring as a byte proxy. Version conflicts reload current data before another user submission. Transient PUT retries reuse an unexpired reservation and its converted WebP bytes; terminal/expired uploads renew the reservation. Completion retries retain the existing image ID when retryable.
- Prices/deposits show their person, room or property basis. Capacity remains distinct from vacancy/BHK and never multiplies rent.
- Reveal is an explicit authenticated action with a ten-distinct-listing Kolkata daily allowance. Reviews/reports require historical reveal eligibility. Reports remain allegations and preserve prior review text.
- No text/price edits, image removal/replacement, listing deletion, contact changes, messaging or moderation endpoint is exposed.

Original TweakCN variables remain unchanged. Component text uses derived semantic color mixes where necessary to meet contrast in both themes.

Every application-data request and response remains no-store. Public server reads omit credentials; private browser calls use backend-owned cookies. Signed image URLs bypass Next.js image optimization. Phone numbers, codes and tokens are not put into metadata, logs, browser storage or browser-test recordings.

## Configure photo uploads

The current owner flow accepts JPEG, PNG or WebP sources through 40,000,000 bytes and converts them to lossless WebP on the device in a WASM worker. It keeps dimensions without a 20 MP cap or target output size. Converted files may exceed 40 MB. WebP encoder sides are limited to 16,383 pixels and device memory still matters. Lossless refers to browser-decoded 8-bit pixels, including browser color conversion, rather than recovering original JPEG information. No backend encoding is needed for these WebP uploads; finalization still validates one decoded image and strips metadata. Twenty photos are allowed per listing, with 60 reservations per rolling 24 hours. Existing validated images remain unchanged.

The local service was disabled and R2 credentials were unconfigured when the reported 503 was investigated. The maintainer chose to keep direct R2 uploads and configure credentials separately. This change does not provision buckets or enable storage with missing credentials.

In the private root environment, supply R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_KEY, R2_STAGING_BUCKET and R2_FINAL_BUCKET, then set OPENNEST_STORAGE_ENABLED=true. Use distinct private staging/final buckets and exact-origin CORS for the frontend. Allow PUT/GET/HEAD, Content-Type and If-None-Match as applicable, and expose ETag. Browser-owned Content-Length and Host must not be set by JavaScript. A repeated conditional PUT returning 412 proceeds to completion, where backend size/signature/decode checks still apply.

Restart the backend with `.\scripts\start-local.ps1 -Build` so Flyway applies V8, then restart `npm run dev` in frontend. Dev/build copies the WASM codecs and their BSD license locally; no external codec host is used. Full configuration, timeout and capacity rules are in [LISTINGS_HANDOFF.md](LISTINGS_HANDOFF.md). Live R2 credentials, bucket CORS and provider uploads still require a maintainer smoke test.

## Configure maps

Set OPENNEST_MAPTILER_KEY to a maintainer-owned MapTiler browser key restricted to the intended origins. This key is intentionally sent to the browser. Keep account credentials and provider management secrets out of the frontend. Supply separate restricted keys for separate deployments where appropriate, monitor usage and approve provider terms before exposure. No account or paid plan is provisioned.

MapLibre loads MapTiler streets-v2 style and retains provider attribution. Its module worker must be explicitly configured before map construction with setWorkerUrl(new URL("maplibre-gl/dist/maplibre-gl-worker.mjs", import.meta.url).toString()). Next emits that pinned dependency as a hashed same-origin JavaScript asset in development and production. No worker CDN, copied public asset, provider-key change or backend configuration is needed. [MapLibre bundler setup](https://maplibre.org/maplibre-gl-js/docs/#esm). Missing key, network failure or unavailable WebGL leaves the list and numeric-coordinate forms usable. Geolocation and map-point selection require explicit user actions. Nearby-search coordinates are included in the shareable URL. No geocoding or Overpass calls occur.

Public search/detail add publicLocation with latitude, longitude and radiusMeters=2000. Each coordinate uses decimal floor(value/0.02)*0.02+0.01. Cells are stable and roughly 2 km across, with longitude width varying by latitude. The UI labels them approximate and draws a 2 km area, not a building location. Search/detail use the same projection. Owner responses retain exact coordinates separately; public precise distances and addresses stay absent. Cell centers at validation-bound edges can lie slightly beyond the coarse input box. Coarsening does not guarantee anonymity against repeated geo-filter queries.

## Run checks

```powershell
cd frontend
npm ci
npm run typecheck
npm run lint
npm test
npm run build
npm audit
npx playwright install chromium
```

From the repository root, run the browser harness against fresh real PostGIS/Redis stores:

```powershell
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pisolation-integration' '-Dtest=OpenNestApplicationTests' '-Dit.test=FrontendBrowserHarness' verify
```

The explicitly selected test-scope harness starts two applications and store pairs, an in-memory controlled Telegram bot and a loopback object fixture. It drives fabricated private contacts through the actual secret-protected webhook. Browser PUTs use test presigned URLs and real content validation. The control server uses a random secret, loopback binding and an invalid test-only phone range. It is not a packaged API login route. Playwright traces, video and screenshots are off; automatic page snapshots are suppressed where supported. Error diagnostics may still contain fabricated test-page content. Never point this suite at a real bot or shared database.

Browser map requests are intercepted with a controlled empty MapTiler-compatible style. With WebGL2 available, the suite now requires Location map ready and a same-origin hashed MapLibre worker response with HTTP 200 and a JavaScript content type; worker-load console errors fail the check. Without WebGL2 it checks the supported fallback. Listing links remain usable in either case. This does not verify live MapTiler tiles, attribution or usage. Automated accessibility checks cover the public search in both themes, plus keyboard skip navigation and mobile layout.

The test control server also invokes the existing authorized sandbox reset service. The browser then observes 401, clears its persona state and offers selection again. This test-only control is absent from application artifacts; no visitor reset endpoint was added.

The harness calls npm run test:e2e with its generated API/control configuration and starts production frontend servers on ports 3000/3001 by default. Those ports must be free. To preserve running local frontend output, build with OPENNEST_VERIFY_BUILD=true, then run the harness with the same environment and -Dopennest.frontend.primary-port=3100 -Dopennest.frontend.sandbox-port=3101. This uses .next-verification instead of .next; verification ports must also be free. Ordinary backend discovery excludes the harness by name; the frontend workflow selects it explicitly. Run the existing complete backend gate separately after backend changes.

## Evidence and remaining gates

The 14 November 2026 LAN follow-up reproduced the unfinished Account label and localhost API target at http://192.168.1.9:3000. The fix uses an explicit development-only LAN origin and same-origin /api/v1/... forwarding to the fixed loopback API. Both live local URLs passed npm run check:lan with fresh browser contexts: Sign in became visible, CSRF issuance returned 200, missing CSRF returned 403, invalid phone input with valid CSRF returned 400, logout returned 204, cookies were host-only/HttpOnly/Lax, responses stayed no-store, and no cross-origin API calls or uncaught page errors were observed. No real contact was submitted and no live Telegram OTP completion was performed by this check. The maintainer should start a fresh original-browser Telegram challenge on the LAN URL.

The complete clean backend gate passed 639 tests, and all four existing controlled-provider browser scenarios passed separately, giving 640 passing Java reports including the browser wrapper. Frontend production build, types, lint and 57 tests passed, including rejected production/sandbox/unsafe LAN configuration and the pending-session label. Six Python checks, package/context validation and whitespace checks passed. The updated local backend and development frontend were restarted with the explicit private LAN configuration. R2 staging preflight for the LAN origin returned 403; the maintainer must add that exact frontend origin to bucket CORS separately for direct LAN uploads. Hosted routing, remote CI and physical-device OTP completion remain unverified.

The 12 November 2026 lossless-photo follow-up passed the complete clean Java gate with 629 tests: 479 default and 150 integration, with no failures, errors or skips. The initial run found a stale seven-migration lifecycle assertion; after updating it for V8, the full gate passed. The separately selected browser wrapper brings the final report set to 630 Java tests. All four real-store Playwright scenarios passed, including an actual 6000x4000, 24 MP source converted by the browser WASM worker, direct storage upload, backend decode/validation, publication, retained image/webp type, original dimensions and VP8L final bytes. The run used isolated .next-verification output and frontend ports 3100/3101. It also covered extension-added HTML attributes and forced session-network failure.

Frontend production build, TypeScript, lint and 45 tests passed. Source-boundary tests accept exactly 40,000,000 bytes, reject larger source files before encoder allocation, and allow mocked encoder output above that boundary. Genuine encoder fixtures verify lossless RGB/RGBA payload retention and decoded pixels. Schema checks allow larger encoded metadata and 24 MP images while rejecting malformed groups, invalid types and impossible WebP dimensions. A 25-way reservation race admits exactly 20 images. All six Python report-gate tests, package/context checks and npm audit passed; audit reported zero vulnerabilities. Live R2 configuration and current-branch remote CI remain unverified. No real credentials, provider upload, shared-database migration or deployment was performed.

The 11 November frontend follow-up fixes extension-added root HTML hydration warnings and ignores superseded owner/review reads without native aborts during cleanup. Session-check network failures render a safe notice, while an anonymous 401 remains quiet. All 41 frontend tests, type checks and lint passed. A browser check against the running development server injected the reported extension attribute and forced the session fetch to fail; it observed the notice with zero hydration warnings or uncaught errors. The same scenario is now included in Playwright for the next full CI run. The full browser harness and production build were not rerun for this follow-up, preserving the running local servers. Browser extensions can still produce failures in their own fetch wrappers; disable the identified extension on localhost if those continue.

Local verification on 11 November 2026 used Windows Node 24.14.0/npm 11.9.0, Java 25.0.5.0.2, and Linux Docker PostGIS/Redis stores. Frontend type checks, lint, 37 unit/component tests, production build and npm audit passed, with zero reported dependency vulnerabilities. All three real-store Playwright scenarios passed without retries or skips, including three rental modes, Telegram OTP, direct uploads with real validation and reservation-preserving transport retry, publication/pause, contact/review/report conversion, nationwide URL navigation, persona/reset recovery, mobile layout, both-theme axe checks and map failure fallback. The complete Java 25 schema/isolation clean verify gate passed 605 tests with zero failures, errors or skips, including nine new public-location tests. Six Python checks and wrapper/context/package gates passed. The final combined report set contains 606 passing Java tests when the separately selected browser harness wrapper is included. Current-branch remote CI must be inspected separately and is not implied by local checks.

Earlier Windows headless checks accepted the map fallback even when a worker failed to load; this was too permissive. The 14 November worker follow-up reproduces that failure and verifies live MapTiler rendering on localhost and LAN using software WebGL, with an HTTP 200 same-origin JavaScript worker and no worker errors. Physical-device/hardware rendering remains a separate check. Live Telegram repeated login/failure/logout through the UI, real R2 bucket CORS/direct upload/final image access, and MapTiler rendering/attribution/key/usage checks require maintainer-configured providers. Controlled tests do not establish live-provider readiness. Phase 6 still owns hosted routing, production webhook setup, edge limits, capacity/alerts, recovery, isolation and release approval. Approve retention/erasure and a staffed appeal policy before collecting real public interaction data.

The 14 November Telegram follow-up adds a Full name step only after a new user verifies the current OTP. Existing linked accounts sign in directly. Resend code stays on the same challenge, shows its 30-second countdown and three-code allowance, invalidates prior codes and preserves original expiry/failed attempts. Pending delivery can be retried immediately. Start again shows the separate 60-second restart countdown. Backend and frontend must be updated together; reload the login page and use the current challenge link. Detailed API/recovery rules: TELEGRAM_HANDOFF.md.

Telegram signup/retry local proof on 14 November: 650 clean-gate Java tests plus the separately selected browser wrapper = 651; all four real-store browser scenarios and 64 frontend tests passed. Production build/types/lint and localhost/LAN session checks passed. Detailed cases and live-provider limits are recorded in TELEGRAM_HANDOFF.md.

Map worker local proof on 14 November: before the fix, MapLibre created a worker for the frontend root URL and reported Worker failed to load. After explicit package-worker URL resolution, the live localhost and LAN pages loaded a hashed same-origin .mjs with HTTP 200/application-javascript and reached Location map ready with no worker errors. Production build, types, lint and 64 frontend tests passed. All four real-store browser scenarios passed, including the strengthened production worker-response/map-ready assertion. No backend source changed and the full backend suite was not rerun for this frontend-only patch. Real-store browser verification still uses controlled providers; separate live MapTiler checks used the maintainer-configured browser key without logging it. Remote CI and physical-device rendering remain unverified.

The maintainer explicitly requested a local account/listing reset on 14 November to test named signup. The DEVELOPMENT/PRIMARY database reset removed three accounts, one listing, and dependent image/identity/contact/login-receipt/reveal/review records in one transaction, preserving all eight migrations. Development Redis sessions/challenges/cooldowns were cleared while bot polling offsets and update deduplication were retained. No deletion API or schema change was introduced. The local backend was restarted. R2 objects remain subject to the existing orphan-cleanup grace period. The authenticated header now includes the caller name beneath My account; a browser check verified the header/account page and no overflow with a long fabricated name at 390px width. Refresh the browser before starting the next real registration.
