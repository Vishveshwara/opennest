<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:73aa62c003973b47941d1baf6377792a788fe7287a4cf1636c237ee99a88b64c -->

<!-- coderabbit-ai-generated-section:v1:536afc8383bae8d10d25de7f87213b6767c9cccb64c8c2ccfd7d1a2651e06286 -->

<!-- coderabbit-ai-generated-section:v1:e3c2834bfc391f87efc0fc6ef4e42cc0e57085ab9a7c30403bb006f436786664 -->

<!-- coderabbit-ai-generated-section:v1:2700e6ae392db041d474d040f4e13dbe55e795a08acdc57bb3049abba615692c -->

# OpenNest implementation plan

This plan defines OpenNest's proposed India-wide launch. Phase 0 provides documentation and local infrastructure. Phase 1 supplies the Java 25/Spring foundation, guarded stores, Flyway/Hibernate validation, safe errors, and real-store verification. Phase 2 supplies identity, Redis sessions, CSRF/logout, private profiles, and approved trust rules. The 10 November 2026 maintainer decision replaces the earlier provider with sole Telegram Bot API authentication in Phase 3A. Phase 3 implements listing services; Phases 4 and 5 cover interactions and the frontend. Phase 6 covers deployment and launch readiness after Phase 5. Phase 3 verification and remaining readiness gates are recorded in [LISTINGS_HANDOFF.md](LISTINGS_HANDOFF.md); Phase 4 implementation and checks are recorded in PHASE4_HANDOFF.md; Phase 5 frontend implementation and local checks are recorded in PHASE5_HANDOFF.md; hosted deployment remains outstanding. Work proceeds one phase per prompt and one reviewable PR per phase. The AI indexes summarize implemented behavior and proposals. [DEPLOYMENT_STACK.md](DEPLOYMENT_STACK.md) records hosting assumptions.

## Confirmed product direction

- Flats and PG/hostels across India from launch, with no default city or city-specific rollout. Support any Indian city without a predefined city allowlist.
- Search without a city filter returns paginated nationwide results; city, locality, and nearby-location filters narrow the results when supplied.
- Pagination, sorting, and combined filters are launch requirements. The complete request/response contract, room semantics, UI behavior, and acceptance cases are in [Listing search plan](LISTING_SEARCH.md).
- Use the product name OpenNest consistently in UI, metadata, documentation, and generated examples; lowercase `opennest` is reserved for technical identifiers.
- The planned public website is `https://opennest.co.in`, confirmed on 22 October 2026. DNS, TLS, hosting, and API/sandbox hostnames remain unverified or undecided.
- Java 25 is the backend target, confirmed on 22 October 2026; it supersedes the earlier Java target. The supplied backend POM already requests Java 25.
- Maximum five ACTIVE/FULL_OCCUPIED listings per owner; PAUSED listings do not count.
- No chat or WebSockets. Authenticated users may reveal ten distinct listing contacts per India calendar day.
- Authentication: Telegram Bot API is the only login method by maintainer decision on 10 November 2026. A native private self-contact +91 match and OTP entered in the initiating browser establish the backend-owned session. This evidence was approved on 9 November; it does not prove fresh SIM possession or property ownership. No Google login, console provider, or webhook-only approval. See [Authentication decisions](AUTHENTICATION.md) and [Telegram setup](TELEGRAM_HANDOFF.md).
- Successful Telegram OTP completion atomically creates/resolves the account and verified contact. Browsing remains public. PAUSED drafts/uploads require authentication; publication separately enforces contact, required images, ownership, and quota. Accounts without Telegram cannot authenticate through this selected method.
- PG_HOSTEL rent is INR per person/month; FLAT supports ENTIRE_FLAT at a per-property quote and ROOM at a per-room quote. Individual flat rooms and per-room pricing were confirmed on 22 October 2026. Whole-flat availability uses a boolean; ROOM/PG inventory counts rooms, not beds or people. See [listing modes and inventory](LISTING_MODEL.md).
- Owners set the number of people allowed: maxOccupants for an entire flat, maxSharingPerRoom for each offered room. Display capacity alongside price; changing capacity must not multiply a flat-room quote or change vacancy automatically.
- License: GNU Affero General Public License version 3 only (`AGPL-3.0-only`), included in the root LICENSE file.
- `/recruiter-sandbox` will use fabricated listings across multiple Indian cities in a separate database. No public navigation link to it. Trusted sandbox seeding may create demo fixtures without real phone verification; callable contact actions stay disabled and production has no such bypass.
- Reports and poor reviews produce warnings, never automatic listing suspension or deletion.
- R2 presigned PUT uploads; client-side lossless WebP without resizing or an output-size target, by maintainer decision on 12 November 2026. Source JPEG/PNG/WebP files may be up to 40,000,000 bytes; larger encoded output is allowed. Server-side metadata/content validation remains required.
- FLAT images require ROOM, HALL, BATHROOM, KITCHEN; PG_HOSTEL requires ROOM and BATHROOM.
- Preserve the TweakCN theme at `https://tweakcn.com/r/themes/cmv1h5ahl000104kwff88a0p8` when initializing the frontend.

## Corrections to the original constitution

1. **Sandbox security:** an untrusted header cannot select production versus demo data. Use separate backend deployments/configuration, database credentials, Redis/session stores, cookie names/hosts, storage prefixes or buckets, cache namespaces, and separate bot secrets/signing keys where used. Production disables demo operations entirely. The local Compose profile provisions separate data stores; it is not proof of application isolation.
2. **Trust rules:** Phase 2 approval confirms this canonical precedence: >=1 report and average <3 -> CRITICAL_BROKER_AND_LOW_RATING; else >=1 report -> BROKERAGE_WARNED; else >=2 reviews and average <3 -> LOW_RATINGS_BEWARE; else >=3 reviews and average >=4.5 -> TOP_RATED; otherwise STANDARD. No reviews means STANDARD with a NULL numeric rating. Phase 2 calculates profile badges; Phase 3 must reuse the calculator for listing responses. Do not label an owner VERIFIED merely because nobody reported them.
3. **Reports:** require prior contact reveal and forbid self-reporting. Each reviewer/property pair has one unique review with an immutable UUID. The first report creates that review or converts it in place to one star / ASKED_BROKERAGE / report flag, preserving existing text, UUID, and creation time. Show the allegation notice separately from preserved text. Repeated reports are no-ops. Ordinary review creation conflicts on an existing pair and cannot clear a report; ASKED_BROKERAGE uses the report endpoint. Serialize all review/report writers on the owner row and update the review and owner aggregates in one transaction, counting each row and report once. Provide an appeal/correction path. Warnings should say “brokerage reported,” not assert proven misconduct.
4. **Atomic limits:** serialize owner quota changes and daily contact-ledger writes in PostgreSQL. Redis is an accelerator, not the sole quota ledger. Lost cache entries must never reset the daily allowance.
5. **Inventory:** derive room totals from component counts for ROOM/PG_HOSTEL; ENTIRE_FLAT uses wholeFlatAvailable with no room total. Preserve PAUSED on edits; require an exact nonnegative version match before writes, including repeated publish/pause requests. Stale requests return 409; current-version publish/pause no-ops do not change quota/version. Published status derives from the mode's availability, and both ACTIVE/FULL_OCCUPIED count toward the owner quota. Details: [listing model](LISTING_MODEL.md).
6. **Recovery:** persistent volumes and restart policies do not guarantee zero loss or recovery within three seconds. Redis `appendfsync everysec` can lose recent writes after a crash; contact quota correctness must survive that. Set measurable recovery objectives and test off-host backup restores before launch.
7. **Geography/privacy:** the coordinate box includes places outside India and is only a coarse validation bound. Boundary-polygon validation is an additional requirement if strict India geofencing is intended. Public search/detail responses omit owner phone, private address, and precise coordinates; public map locations require a documented coarsening policy, otherwise omit coordinates.
8. **Memory:** proposed backend caps and JVM flags are hypotheses to benchmark, not configured production limits. The two local datastore pairs alone permit 860 MiB; both applications plus OS overhead do not safely fit a 1 GiB host. Start with a measured deployment budget and keep the demo independently deployable.
9. **Configuration:** Phase 1.3 configures pool size 5/minimum idle 1 and virtual threads through documented properties. It does not use the unverified `spring.datasource.connection-fetch=lazy` property. Phase 1.5 adds Flyway migrations; Phase 1.6 adds internal mappings with forced Hibernate validation, never automatic schema updates.
10. **Version pins:** backend bootstrap uses Spring Boot 4.1.1, Java 25, and Spring Modulith 2.1.1; Next.js 16.4 and React 19.3 remain frontend targets. [Dependency findings](BACKEND_DEPENDENCIES.md) record release/BOM checks, probe resolution, and Java 25 context/module tests. Feature integration remains unverified. Keep exact dependency selections; do not force independently selected Spring Framework/Security versions over the Boot BOM.

## Delivery phases and acceptance criteria

| Phase / PR | Scope | Required evidence |
| --- | --- | --- |
| 0: foundation | Instructions, context indexes, local persistent stores, review configuration, CI | Compose parses; both store pairs start on distinct networks; PostGIS works; data survives container replacement |
| 1: backend | Java 25/Spring bootstrap, Flyway schema including rental modes, applicable inventory, flat bedrooms and search indexes, common errors, deployment modes | Migrations on real PostGIS; schema validation; production rejects demo mode; module boundaries |
| 2: identity | Backend sessions, CSRF/logout, private profile, trust calculator, transactional identity persistence | Rotation/expiry, CSRF, authorized profile, isolation, approved trust thresholds; provider login superseded by Phase 3A |
| 3: listings | PAUSED drafts, image upload/finalization, explicit publish/pause, owner quota, versioned mode-aware inventory, nationwide search, R2 URLs, optional approved Overpass | Draft-upload-publish flow works for all modes; invalid photos/contact cannot publish; LISTING_MODEL.md and LISTING_SEARCH.md cases pass; concurrent sixth publication rejected; stale writes rejected; PAUSED preserved; unauthorized edits denied; real geo queries; enrichment disabled/outage fallback |
| 3A: Telegram-only authentication | Anonymous-session challenges, private self-contact matching, original-browser OTP, stable Telegram identity, verified contact, durable retries | Rotation/CSRF/logout/expiry, removed-provider denial, original-session/realm binding, contact rejection, duplicate/replay/rate/outage handling, concurrent signup and receipt recovery; local login/profile confirmed; hosted readiness and live storage/failure/logout remain separate checks |
| 4: interaction/demo | Contact ledger, ratings/reports, multi-city demo seeding/reset/personas | Fabricated listings cover multiple Indian cities; concurrent eleventh reveal rejected; retries free; Kolkata midnight; reports deduplicated; reset cannot touch real data |
| 5: frontend | India-wide public portal, recruiter UI, URL-backed search filters/sorting/pagination, semantic theme, maps, image compression | Nationwide initial search; search links/back navigation/page reset work; accessible mobile flows; upload/reveal/report end-to-end; private responses absent from public caches |
| 6: deployment and launch readiness | Hosting, DNS/TLS, production Telegram webhooks, delivery, edge rate limits, Redis capacity/alerts, recovery | Exact routing/secrets and browser sessions, pre-session limits, hosted smoke/isolation/load/restore/rollback before approved public release |

Phase 1 should finalize schema semantics before creating V1. Each later feature updates its module context and API/schema indexes in the same PR.

Phase 1.1 is complete; see [backend versions and compatibility](BACKEND_DEPENDENCIES.md). The supplied wrapper ran Maven 3.10.0 under Temurin Java 25.0.4.1; the dependency probe and optional Resilience4j profile resolved, and the initial skeleton's build/context-load test passed without changing backend inputs at that step.

Phase 1.2 is complete. The existing skeleton now uses `in.opennest.OpenNestApplication`, OpenNest metadata/application naming, and the Modulith 2.1.1 BOM with core/test starters. The common, identity, listing, interaction, and sandbox modules are closed declarations with no allowed cross-module dependencies or public contracts yet. Each module has a local context file; [backend context](../backend/MODULE_CONTEXT.md) records the boundaries. Its clean Java 25 build passed three context/module tests without adding feature APIs, datastore configuration, migrations, or deployment modes at that step.

Phase 1.3 is complete; see [backend configuration](BACKEND_CONFIGURATION.md). Runtime environment and data mode bind separately at startup, before PostgreSQL/Hikari and Redis/Lettuce client construction. Production permits PRIMARY only, rejects development/demo flags and sandbox secrets, and requires database/Redis passwords. Sandbox selection requires explicit mode and distinct declared targets/credentials; no request selects stores. Pool size/minimum idle are 5/1, virtual threads are enabled and exercised, and SQL script initialization is disabled. A clean Java 25 build passed 46 tests with no failures/errors/skips. Tests prove configuration and client selection without querying live stores; real isolation, schema, auth, and deployment checks remain later tasks.

Phase 1.4 is complete; [schema contract](SCHEMA_CONTRACT.md) specifies all six tables' SQL types, nullability, defaults, keys, checks, generated values, deletion actions, and baseline indexes. Composite property/owner foreign keys prevent mismatched attribution; row constraints are distinguished from later transactional authorization/quota/aggregate rules. Initial USER accounts can both own and seek accommodation. Retention policy, trust thresholds, contact provider, public map coarsening, and Phase 3 upload limits remain explicit open decisions. Phase 1.4 changed documentation only and did not run SQL at that step.

Phase 1.5 is complete; [migration record](BACKEND_MIGRATIONS.md) documents shared V1 schema/PostGIS and V2 indexes, startup application through the selected datasource, reserved Flyway settings, and repeatable integration checks. A clean Java 25 build passed 52 unit/context/module tests and 25 database tests with no failures/errors/skips. Both isolated PostgreSQL 17.11/PostGIS 3.5.3 targets received identical migrations/schema/checksums with no application fixtures; repeat migration executed nothing. Docker is unavailable, so tests used portable database clusters rather than the Compose image. No hosted/Compose database, Redis isolation, or CI was exercised at that step.

Phase 1.6 is complete; [persistence contract and evidence](BACKEND_PERSISTENCE.md) records six package-private entities inside their owning modules, scalar UUID foreign references, read-only generated columns, and listing @Version. Hibernate validate runs after Flyway and rejects schema drift; direct ORM schema-action overrides are rejected. Generated listing values require flush then refresh/reload after updates to preserve proper stale-write exceptions on the pinned Hibernate release. Its clean Java 25 build passed 57 unit/context/module and 49 real database tests. V1/V2 remained unchanged; no business service/API, Docker/hosted execution, Redis isolation, or CI was claimed at that step.

Phase 1.7 is complete; [HTTP errors/security contract](BACKEND_HTTP_ERRORS.md) records fixed Problem Details for expected 400/401/403/404/409/429/503 failures and generic unexpected 500 responses. Known uniqueness/stale conflicts and connection/timeouts remain distinct from generic integrity/schema/application bugs. Responses reset uncommitted data and never copy exception/validation/request/private values. Logging emits fixed status/error-ID events only; framework diagnostics, SQL/request dumps, and stack traces are suppressed, including at startup. Every normal request is denied; form/Basic/logout/default-password/demo access is absent and CSRF remains enabled. The clean Java 25 build passed 114 unit/context/HTTP and 49 database tests, zero failures/errors/skips, including 50 HTTP tests through test-only controllers/live container. No diagnostic business endpoint is packaged, and migrations/mappings are unchanged. Docker, Redis isolation, CI, and hosted deployment remain unverified. This paragraph records the Phase 1.7 checkpoint. The completed Phase 1.8/1.9 evidence is recorded below.

### Phase 1 task boundaries

One requested subtask is implemented at a time within the scoped Phase 1 change. Completion below does not imply the entire phase or CI has passed.

| Task | Status | Deliverable and completion gate |
| --- | --- | --- |
| 1.1: versions | Complete | Exact releases/BOM compatibility recorded; dependency probes and Java 25 wrapper build verified |
| 1.2: bootstrap/modules | Complete | in.opennest root, five closed modules/context files; startup and Modulith boundary tests |
| 1.3: deployment configuration | Complete | Fixed startup store selection, production guards, Hikari 5/1, virtual threads; 46 tests and packaged rejection smoke check |
| 1.4: schema contract | Complete | Six-table specification, constraints/deletion/indexes and enforcement ownership; documentation/link/consistency checks, no migration |
| 1.5: Flyway schema | Complete | V1/PostGIS and V2 contract indexes; 25 real database checks across two modes, matching checksums/schema and no fixtures; Docker/hosted execution not claimed |
| 1.6: persistence mappings | Complete | Six internal mappings with read-only generated values/@Version; forced Hibernate validate; 57 unit/module and 49 database checks, including stale writes and schema drift |
| 1.7: HTTP/errors/security baseline | Complete | Safe Problem Details/logs, known versus unexpected classification, deny-all security without login/password access; 114 unit/HTTP and 49 database checks; identity follows Phase 2 |
| 1.8: datastore isolation | Complete locally | Review-fix Java 25/Linux amd64 Docker build passed 130 default, 51 schema/persistence, and 23 isolation tests on 30 October 2026, with no failures/errors/skips. See [isolation proof](BACKEND_ISOLATION.md) |
| 1.9: CI/handoff | Implemented; inspect PR checks | Java 25 backend CI runs both real-store profiles and verifies wrapper/reports/artifact boundaries; the local review-fix suite totals 204 passing tests and Foundation is unchanged. [Handoff commands and results](BACKEND_HANDOFF.md); PR #2 contains review corrections; no merge/deploy |

The 30 October verification closes the local Docker gate. The return-value ConstraintViolationException finding is fixed and covered by a real validation regression. Review corrections also cover Security delegation, HTTP status preservation, redacted diagnostic levels, Boot SPI registration, and timestamp clamping. The [handoff](BACKEND_HANDOFF.md) records the 204-test result; inspect completed PR CI separately before declaring the phase complete.

### Phase 2 identity foundation

Phase 2 established account persistence, Redis sessions, session rotation/expiry, CSRF/logout/CORS, private profiles, and trust thresholds. Its original provider integration and credentials were superseded on 10 November 2026. Current authentication is entirely defined by Phase 3A below and [AUTHENTICATION.md](AUTHENTICATION.md). The earlier 298-test evidence is historical and does not establish the current change's result.

Preserve the foundation's 30-minute idle/24-hour authenticated lifetime, exact origin policy, realm/store isolation, transactional private profile access, and generic safe errors. Provider identity writes now belong to atomic Telegram completion. No phone/email account auto-linking, anonymous migration, or production demo fallback is permitted.

### Phase 3 task boundaries

Phase 3 implementation is described in [the listing handoff](LISTINGS_HANDOFF.md). It adds PAUSED drafts, caller-only listing pagination, validated private R2 images, explicit publish/pause, transactional quotas, versioned inventory, and the complete nationwide search contract. Phone verification stays deferred, coordinates stay out of public responses, Overpass stays disabled, and every listing response uses no-store.

| Task | Deliverable and completion gate |
| --- | --- |
| 3.1: contracts/schema | Public current-account/contact/owner-lock contracts, safe owner SQL projection and V3 image metadata; clean and populated-V2 migration checks |
| 3.2: drafts/owner APIs | PAUSED creation, owner collection/detail, numeric/text/mode limits; ownership and draft privacy tests |
| 3.3: storage | Opt-in R2 adapter, isolated credentials/private buckets, signed direct PUT/GET; real SDK/local HTTP contract tests |
| 3.4: image lifecycle | Reservation limits, content validation/fresh-pixel finalization, retries, cleanup; malformed/expired/concurrent/storage failure tests |
| 3.5: lifecycle | Owner-row serialization, five published/20 retained caps, exact versions, PAUSED preservation; concurrency tests |
| 3.6: search | All filters/sorts/pagination, matching snapshot counts, real geo queries, safe batched DTOs; representative multi-city query plans |
| 3.7: integration | authentication-enabled/disabled security and exact CORS, no-store/private media, sandbox isolation; HTTP/session/privacy regressions |
| 3.8: handoff | Required suite/artifact gates, aligned context/docs/configuration; complete Java 25/Docker local gate and separately inspected CI |

The clean local Java 25/Linux amd64 Docker gate passed 450 tests after review corrections on 9 November 2026, with no failures/errors/skips. Report/artifact checks, six Python checks, context limits and whitespace checks passed. Current-change CI, live R2/CORS, and manual real-provider login/logout remain unverified.

The current agreed limits are 20 retained listings per owner, 20 images per listing, 60 image reservations per owner per rolling 24 hours, ten-minute upload URLs, and five-minute private read URLs. The 12 November 2026 media follow-up replaces 5 MiB/20 MP with a browser source-only 40,000,000-byte cap and on-device lossless WebP. No resizing or output-size target applies; format dimensions, transport bounds and dynamic backend capacity admission remain. Cleanup runs hourly with a 24-hour object grace period and seven-day rejected-reservation retention. No draft is automatically deleted. Text/price edits, image replacement/removal, listing deletion, phone verification, frontend work, interactions, and recruiter personas remain outside Phase 3.

### Phase 3A Telegram-only authentication

The maintainer selected Telegram Bot API as the only authentication system on 10 November 2026. The private local bot token is configured; getMe/getWebhookInfo checks passed, explicit local polling is enabled, and old provider settings were removed. No live message, OTP login, webhook registration, tunnel, or hosted deployment was performed.

An anonymous browser fetches CSRF, starts a session-bound +91 challenge, opens the bot and shares its own contact, then enters the OTP in the original browser. Only successful CSRF-protected completion authenticates, rotates the session, clears the old CSRF token, and saves the realm-bound principal. The frontend must fetch a fresh CSRF token. Status polling alone never authenticates. Telegram Gateway, WhatsApp/SMS, and console providers remain unselected.

Stable account identity uses the Telegram sender, never typed phone/email. Existing users/listings and V1-V4 remain intact. An unambiguous prior verified Telegram receipt can recover its existing account; other legacy accounts require explicit recovery. Changing the Redis session namespace invalidates old provider sessions. No automatic linking/merging is introduced.

V5 adds four-column expiring login receipts. Challenge/identity advisory locks and user-row locking atomically commit account/identity/contact/current provenance/login receipt. Database rollback leaves the Redis claim retryable; committed receipts recover same-session retries while the original challenge remains valid. Expired login receipts are ignored and deleted hourly. Current contact provenance remains one receipt for the verified account's lifetime; future replacement/erasure must remove dependent private data.

Production requires secret-authenticated HTTPS webhooks; explicit development polling is leased and persists acknowledged offsets. Recruiter contact actions stay disabled. Existing image, publication and listing quota gates remain. [Setup/Postman flow](TELEGRAM_HANDOFF.md) and [verification contract](TELEGRAM_CONTACT_VERIFICATION.md) record limits and current evidence. The revised clean Java 25/Linux amd64 Docker gate passed 553 tests on 10 November 2026, with no failures/errors/skips. Six Python checks, report/artifact validation, wrapper/context limits and removed-OAuth-library checks passed. The maintainer confirmed real local Telegram contact/OTP login and the private verified profile on 10 November. CI, hosted readiness and live listing/storage/failure/logout checks remain unverified. [API_VERIFICATION.md](API_VERIFICATION.md) records the expanded cross-API gate; [POSTMAN_API_GUIDE.md](POSTMAN_API_GUIDE.md) covers manual testing.

### Phase 4 task boundaries

Phase 4 is implemented locally. Final validated reports contain 596 passing Java tests and six passing Python checks on 11 November 2026. [The interaction and sandbox handoff](PHASE4_HANDOFF.md) records the combined run, corrected isolation assertion/recheck and remaining gates; CI/live storage/hosting remain unverified. Frontend and deployment remain in Phases 5 and 6.

| Task | Deliverable |
| --- | --- |
| 4.1 | Public listing/identity contracts, module dependencies, shared sessions with PRIMARY-only Telegram |
| 4.2 | Daily contact ledger, capture-after-lock Kolkata date, free retries and ten-listing quota |
| 4.3 | Historical-reveal reviews, idempotent report conversion and atomic owner aggregates |
| 4.4 | Public paginated reviews and caller-only interaction state |
| 4.5 | Multi-city fabricated listings/images/history and all trust badges |
| 4.6 | Allowlisted personas, CSRF/session rotation and realm/store/generation separation |
| 4.7 | Operator CLI reset/telemetry, atomic six-hour resets and stale-session rejection |
| 4.8 | Validation/concurrency/HTTP/isolation tests, contexts, Postman examples and handoff |

### Phase 5 task boundaries

Phase 5 implements the local frontend and the approved additive public map projection. See [frontend handoff](PHASE5_HANDOFF.md) and [dependency selection](FRONTEND_DEPENDENCIES.md). Hosting remains Phase 6; no merge or deployment is authorized.

| Task | Deliverable |
| --- | --- |
| 5.1 | Verify/pin Next.js 16.4.6, React 19.3.1, Node 24 and compatible tooling; initialize frontend |
| 5.2 | Original TweakCN theme, responsive application shell and accessible components |
| 5.3 | Typed API boundaries, backend sessions/CSRF, no-store and private-state cleanup |
| 5.4 | Nationwide URL-backed filters, sorting, pagination and navigation |
| 5.5 | Approximate publicLocation, MapLibre/MapTiler, private owner coordinate input |
| 5.6 | Original-browser Telegram self-contact/OTP, account and logout |
| 5.7 | Owner drafts, direct compressed uploads, validation, publication/pause and versioned inventory |
| 5.8 | Authorized reveal, ordinary reviews and brokerage allegations |
| 5.9 | Dedicated unlisted recruiter frontend, personas and disabled callable contacts |
| 5.10 | Frontend CI, controlled-provider real-store browser harness, documentation and evidence |

The projection uses decimal floor(coordinate/0.02)*0.02+0.01 and radiusMeters=2000. Exact coordinates remain owner-only. Maps show the current page and do not introduce an all-listings endpoint, geocoder, Overpass or shared cache. MapTiler requires the maintainer's restricted browser key and separate live-provider verification. Retention/erasure and appeal policies remain prerequisites before collecting real public interactions.

### Phase 6 task boundaries

Phase 6 is planned after Phase 5. It owns deployment and launch readiness under [DEPLOYMENT_STACK.md](DEPLOYMENT_STACK.md). No hosting, production Telegram webhook registration, edge configuration, or monitoring is implemented by this plan update.

| Task | Deliverable and completion gate |
| --- | --- |
| 6.1: deployment decisions | Confirm budget, providers, target architecture, domain control, production/sandbox hostnames, and remaining launch decisions before provisioning |
| 6.2: hosting and delivery | Create tested images and release workflows, DNS/TLS and browser routing, isolated stores, restricted database roles, and deployment secrets; verify readiness and prevent public access to backend/store ports around the edge |
| 6.3: production Telegram setup | Configure distinct bots/secrets and exact HTTPS webhook/origins, allowed message updates, verify provider header, reject development polling and sandbox credentials |
| 6.4: edge rate limits | Limit unauthenticated GET /api/v1/auth/csrf and POST /api/v1/auth/telegram/challenges with measured per-client/aggregate budgets; verify 429 before Redis session creation and prevent edge bypass |
| 6.5: Redis capacity and alerts | Set measured maxmemory below container and host budgets with persistence/allocator headroom and noeviction; exercise memory-pressure, rejected-write/OOM alerts, and alert delivery |
| 6.6: launch verification and recovery | Hosted Telegram login/repeated-login/failure/CSRF logout, browser/session isolation and smoke tests; load, restore and compatible rollback before release approval |

Run the real local Telegram contact/OTP login and CSRF-protected logout with the configured bot. Keep manual evidence separate from controlled-provider tests; repeat through Phase 5 UI and each enabled hosted deployment in Phase 6.

Any earlier public preview must satisfy the applicable provider, routing, edge rate-limit, Redis capacity/alert, and hosted session checks before exposure. Phase 6's position after Phase 5 does not postpone those controls for an earlier preview. Purchases, provisioning, and deployment require separate authorization.

## Technical implementation notes for later phases

- Backend module declarations now exist: common, identity, listing, interaction, sandbox under `in.opennest`. Keep nested implementation packages internal and types package-private where possible. Introduce explicit public contracts and allowed dependencies together; no cross-module edges are allowed at bootstrap. Modulith tests verify module discovery and boundaries.
- Application routes use full /api/v1 paths. There are no provider redirect/callback exceptions. Align API indexes and the Telegram authentication contract.
- Planned listing lifecycle: `POST /api/v1/listings` creates a PAUSED draft; presign uploads against that existing ID, complete/validate each image, then `POST /api/v1/listings/{id}/publish` checks verified contact, required image segments, and owner quota. `POST /api/v1/listings/{id}/pause` preserves content and releases the slot. Inventory cannot reactivate a paused draft.
- Presign will reserve a PENDING image and return imageId/objectKey/uploadUrl/expiresAt. `POST /api/v1/listings/{id}/images/complete` will validate it and finalize an object that a still-valid staging PUT URL cannot overwrite. Publication counts only VALIDATED images. Test completion retries and post-validation overwrite attempts before enabling uploads.
- Authentication follows docs/AUTHENTICATION.md. Spring Security and Spring Session Redis own browser authentication/session persistence; Next.js renders the Telegram start/contact/OTP flow without a parallel identity system.
- WhatsApp is an unselected alternative, not a pending implementation phase; Telegram remains the sole selected provider: the proposed user-sent code flow needs signed webhook validation, session/phone binding, high-entropy single-use challenges, replay/rate limits, and an explicit defense against forwarded-link approval of another browser. No claim of free-forever pricing, universal test-number access, or zero onboarding is established.
- Provider-delivered WhatsApp authentication templates remain an unselected alternative for phone verification. Check official policy/eligibility/current rates before implementing either option. Do not send messages, enroll accounts, or incur provider charges during foundation setup; secrets stay in deployment configuration.
- Search follows `docs/LISTING_SEARCH.md`: database-side filters/order/pagination, bounded page sizes/offsets, matching counts, and deterministic ties. City is optional for search and explicitly required for listing creation, with no database default or fixed city allowlist. Normalize cities consistently; do not assume every locality fits a fixed catalog. Cache keys include every normalized filter, sort, page, size, and deployment; nationwide queries remain distinct.
- Overpass lookup uses an HTTP interface, a 2500 ms timeout, circuit breaker, and empty-result fallback for HOSPITAL/SCHOOL/SHOP within 2.5 km. A remote outage must not prevent saving a listing. Public deployment needs an operator-approved provider or separately budgeted self-hosted service; otherwise enrichment stays disabled. Map tiles/styles and geocoding are separate provider decisions; see the [deployment plan](DEPLOYMENT_STACK.md).
- Phase 5 retains Phase 3's no-store policy for every listing response and request, including anonymous production browsing. Next.js must not override it with force-cache, revalidation, or shared listing caches. Listing/status/inventory/review writes are observed through fresh reads; no shared listing cache exists to invalidate. A future explicitly approved API/cache-policy change must define eligible anonymous production responses, deployment/query keys, signed-image URL handling, and tested invalidation for every affected listing/owner/search before enabling caching. Authenticated, sandbox, contact, and auth responses remain no-store. Enable cacheComponents/reactCompiler only after compatibility checks and without weakening this policy.
- Frontend image compression is a usability feature, not an upload security boundary. Validate uploaded objects before attaching them to a published listing.
- Six-hour demo resets must serialize with writes, be idempotent, and use sandbox-only credentials. Fabricated contact actions stay disabled; never seed callable real-looking phone numbers. Seed historical contact_reveals for designated demo personas/listings so review/report eligibility can be exercised through the normal rule without enabling live contact reveals or bypassing authorization.
- Build application images in GitHub Actions and publish to GHCR after Dockerfiles exist. Deploy immutable digests with protected environment approval; never compile on the small EC2 host.
- Add a restricted runtime database role distinct from the migration/admin role before deployment. Local Compose users are development-only.
- Add scheduled backup/restore verification, bounded logs, dependency/security checks, and provider spend limits before launch.
- Include the OpenNest license and a corresponding-source link for the running revision in the deployed app; preserve third-party license notices.

## Open decisions and setup dependencies

- Phase 6.1 owns hosting decisions: personal/non-commercial start with minimal cost; no AWS account or credits exist yet. The planned website is `https://opennest.co.in`; confirm domain control, DNS/TLS, budget, image architecture, same-site session routing, map providers, and Redis version/license before public deployment; see [deployment decisions](DEPLOYMENT_STACK.md#decisions-before-public-deployment).
- Phase 6.3 owns production Telegram HTTPS webhook readiness and separate deployment bot/secrets. Recruiter contact actions stay disabled; credentials remain private deployment configuration.
- Phase 3A implements approved Telegram self-contact plus original-session OTP contact evidence. An arbitrary Telegram-chat OTP never verifies a typed phone. Local bot setup and maintainer-reported OTP/profile verification passed. Privacy/erasure operations, live repeated-login/failure/logout and production HTTPS/webhook readiness remain deployment gates. WhatsApp and paid Gateway/SMS alternatives remain unselected; owners without Telegram need a separately approved option. See TELEGRAM_HANDOFF.md.
- Trust precedence is approved and implemented in Phase 2. Phase 3 listing responses must reuse it; Phase 4 review/report writers maintain aggregates transactionally.
- Decide a manual moderation/appeal policy and retention/deletion requirements before collecting real personal data.
- Phase 5 approval selects stable 0.02-degree grid-cell centers and radiusMeters=2000 for publicLocation. Public search/detail never expose precise coordinates, private address, owner phone or precise distances. MapLibre uses maintainer-configured MapTiler; no provisioning is authorized. Geo queries retain exact internal coordinates, so coarsening does not guarantee anonymity.
- Phase 3 draft/upload limits and cleanup are selected and implemented in LISTINGS_HANDOFF.md. Text/price/image edit and deletion routes still require explicit contracts before exposure; the 20-retained-listing cap does not delete old drafts. The proposed rental-mode field and inventory constraints are defined in [LISTING_MODEL.md](LISTING_MODEL.md).

Telegram is already the approved sole provider and local OTP/profile behavior is confirmed. Keep the remaining hosted/privacy/operations decisions explicit; do not enable an alternative provider or declare a public launch ready from local evidence. Trust thresholds remain approved.

## Source checks

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Next.js 16 architecture and caching](https://nextjs.org/blog/next-16)
- [PostGIS Docker image and supported tags](https://github.com/postgis/docker-postgis)
- [Redis persistence and durability tradeoffs](https://redis.io/docs/latest/management/persistence/)
- [Unselected alternative: Meta WhatsApp authentication template example](https://www.postman.com/meta/whatsapp-business-platform/request/6vkv46u/create-authentication-template-w-otp-copy-code-button)
- [WhatsApp official pricing overview](https://business.whatsapp.com/products/platform-pricing)
- [GNU AGPL version 3](https://www.gnu.org/licenses/agpl-3.0.html)
- [CodeRabbit path instructions](https://kb.coderabbit.ai/articles/1134523355-configure-path-instructions)


<!-- /coderabbit-ai-generated:v1 -->
The 14 November 2026 frontend follow-up adds explicit DEVELOPMENT/PRIMARY LAN access for configured private IPv4 origins. Next development proxies existing /api/v1/... calls to the fixed loopback backend; browser session/CSRF/no-store behavior is verified independently from hosted readiness. Production/sandbox reject the LAN opt-in. Setup and evidence: [Phase 5 handoff](PHASE5_HANDOFF.md).

The 14 November 2026 Telegram follow-up uses one phone-first flow with automatic existing Telegram-account sign-in after OTP and a required new-user name before account creation. Original-session resends allow three fresh generations with 30-second cooldowns; prepared-delivery retries reuse the current generation. Plain /start/reopened links resume the active bound flow or explain browser recovery. Original-session/CSRF/contact proof, fixed challenge expiry, five bad attempts, transactional identity and no phone-based linking remain required. Details: TELEGRAM_HANDOFF.md.
