# OpenNest frontend

- Next.js App Router, React, strict TypeScript, Tailwind and shadcn-compatible Radix components. Exact releases and probes: docs/FRONTEND_DEPENDENCIES.md.
- Server configuration fixes PRIMARY or SANDBOX and API/site origins. No browser header/query selects a backend. Dedicated sandbox paths start /recruiter-sandbox; PRIMARY denies them.
- Public search/detail server reads are anonymous and no-store. Browser private calls use credentialed backend sessions and in-memory CSRF. No Next.js authentication/session store exists.
- Response schemas in src/lib/contracts.ts validate external JSON and derive frontend types. Search URLs use the complete backend allowlist and preserve nationwide omission of city.
- Session generation rejects late private responses after identity changes. Contact/owner/review components reset on expiry, logout and persona changes. Never persist private data or OTPs in browser storage.
- Owner/review effect cleanup ignores superseded responses without aborting fetches during navigation/Strict Mode. Initial session network failures show a safe notice; anonymous 401 stays quiet. Only root html attribute hydration warnings are suppressed for extension-injected attributes; descendant checks remain enabled.
- All application data and signed images remain no-store; image optimization is disabled. Cache Components and React Compiler are disabled.
- Public maps use the backend's stable 0.02-degree cell-center publicLocation with radiusMeters=2000. Exact owner coordinates stay private; precise public distances are absent.
- MapLibre uses a configured MapTiler browser key and attribution. Before constructing a map, setWorkerUrl explicitly resolves the pinned package worker through new URL with import.meta.url so Next emits a same-origin hashed .mjs asset; automatic detection inside the bundled module is unsuitable. Missing key/provider/WebGL leaves list and numeric-coordinate flows usable. No geocoder or Overpass.
- Exact TweakCN theme preserved in src/theme.json and src/app/theme.css. scripts/sync-theme.mjs reproduces them. Montserrat is locally served under OFL.
- Listing lifecycle is create PAUSED -> lossless WebP worker -> direct presigned storage PUT -> complete validation -> explicit publish. Sources <=40,000,000 bytes, 20 photos/listing, no resize/20 MP/output-size target. WASM assets and BSD notice are copied from pinned @jsquash/webp before dev/build. Encoded pixels follow browser 8-bit canvas decoding. Inventory/pause/publish use exact server versions.
- UI supports existing endpoints only. No text/price edits, image replacement/removal, deletion, account linking, chat, or reset/telemetry HTTP operations.
- Real-store browser harness lives in backend isolation-test sources. It invokes Playwright and supplies only fabricated Telegram/storage providers. Production artifacts contain neither fixture nor control endpoint.
- Setup, checks, limitations and remaining live-provider gates: docs/PHASE5_HANDOFF.md. Hosting remains Phase 6.
- Optional OPENNEST_DEV_LAN_ORIGINS in .env.development.local enables exact private-IPv4 development hosts and same-origin /api/v1/... rewrites to the fixed loopback API. Browser configuration uses a relative API base; server reads retain the configured origin. Production builds and SANDBOX reject this opt-in. npm run check:lan verifies cookies/CSRF/logout/no-store through real browser requests.

- Telegram flow is phone -> self-contact/OTP -> name only for new accounts. Existing linked accounts sign in directly. Resend/restart countdowns use server deadlines; CODE_READY retries delivery, CODE_SENT rotates up to three fresh codes after 30s. Code/name stay in component memory only.
- Authenticated header shows the caller fullName beneath My account, truncates long names visually, and keeps the full name in the accessible label/title. Account page also displays fullName.
