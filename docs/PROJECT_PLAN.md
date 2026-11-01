# OpenNest implementation plan

This plan defines OpenNest's proposed India-wide launch. Phase 0 provides documentation and local infrastructure. Phase 1 supplies the Java 25/Spring foundation, guarded stores, Flyway/Hibernate validation, safe errors, and real-store verification. Phase 2 adds opt-in Google OIDC, Redis sessions, CSRF/logout, private profiles, and approved trust rules. Listing/interaction services, frontend, live Google smoke testing, and hosted deployment remain outstanding. Work proceeds one phase per prompt and one reviewable PR per phase. The AI indexes summarize implemented behavior and proposals. [DEPLOYMENT_STACK.md](DEPLOYMENT_STACK.md) records hosting assumptions.

## Confirmed product direction

- Flats and PG/hostels across India from launch, with no default city or city-specific rollout. Support any Indian city without a predefined city allowlist.
- Search without a city filter returns paginated nationwide results; city, locality, and nearby-location filters narrow the results when supplied.
- Pagination, sorting, and combined filters are launch requirements. The complete request/response contract, room semantics, UI behavior, and acceptance cases are in [Listing search plan](LISTING_SEARCH.md).
- Use the product name OpenNest consistently in UI, metadata, documentation, and generated examples; lowercase `opennest` is reserved for technical identifiers.
- The planned public website is `https://opennest.co.in`, confirmed on 22 October 2026. DNS, TLS, hosting, and API/sandbox hostnames remain unverified or undecided.
- Java 25 is the backend target, confirmed on 22 October 2026; it supersedes the earlier Java target. The supplied backend POM already requests Java 25.
- Maximum five ACTIVE/FULL_OCCUPIED listings per owner; PAUSED listings do not count.
- No chat or WebSockets. Authenticated users may reveal ten distinct listing contacts per India calendar day.
- Authentication: Google sign-in first through Spring Security OIDC, with backend-owned server sessions. WhatsApp verification is a later option, disabled until its flow and production readiness are reviewed. See [Authentication decisions](AUTHENTICATION.md).
- Google signup allows account creation without phone verification. Owners must separately verify a +91 contact before Phase 3 publication. Publishing remains unavailable until a verification method is ready. Planned PAUSED drafts and photo uploads require authentication but no verified phone.
- PG_HOSTEL rent is INR per person/month; FLAT supports ENTIRE_FLAT at a per-property quote and ROOM at a per-room quote. Individual flat rooms and per-room pricing were confirmed on 22 October 2026. Whole-flat availability uses a boolean; ROOM/PG inventory counts rooms, not beds or people. See [listing modes and inventory](LISTING_MODEL.md).
- Owners set the number of people allowed: maxOccupants for an entire flat, maxSharingPerRoom for each offered room. Display capacity alongside price; changing capacity must not multiply a flat-room quote or change vacancy automatically.
- License: GNU Affero General Public License version 3 only (`AGPL-3.0-only`), included in the root LICENSE file.
- `/recruiter-sandbox` will use fabricated listings across multiple Indian cities in a separate database. No public navigation link to it. Trusted sandbox seeding may create demo fixtures without real phone verification; callable contact actions stay disabled and production has no such bypass.
- Reports and poor reviews produce warnings, never automatic listing suspension or deletion.
- R2 presigned PUT uploads; client WebP compression targets <150 KB. Server-side metadata/content validation is still required.
- FLAT images require ROOM, HALL, BATHROOM, KITCHEN; PG_HOSTEL requires ROOM and BATHROOM.
- Preserve the TweakCN theme at `https://tweakcn.com/r/themes/cmv1h5ahl000104kwff88a0p8` when initializing the frontend.

## Corrections to the original constitution

1. **Sandbox security:** an untrusted header cannot select production versus demo data. Use separate backend deployments/configuration, database credentials, Redis/session stores, cookie names/hosts, storage prefixes or buckets, cache namespaces, and separate OAuth clients/signing keys where used. Production disables demo operations entirely. The local Compose profile provisions separate data stores; it is not proof of application isolation.
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
| 2: identity | Google OIDC, backend sessions, CSRF/logout, user profile, trust calculator | State/nonce/PKCE and ID-token checks; session rotation/expiry; CSRF; safe account linking; production rejects demo auth; trust thresholds; phone-unverified owners cannot publish |
| 3: listings | PAUSED drafts, image upload/finalization, explicit publish/pause, owner quota, versioned mode-aware inventory, nationwide search, R2 URLs, optional approved Overpass | Draft-upload-publish flow works for all modes; invalid photos/contact cannot publish; LISTING_MODEL.md and LISTING_SEARCH.md cases pass; concurrent sixth publication rejected; stale writes rejected; PAUSED preserved; unauthorized edits denied; real geo queries; enrichment disabled/outage fallback |
| 4: interaction/demo | Contact ledger, ratings/reports, multi-city demo seeding/reset/personas | Fabricated listings cover multiple Indian cities; concurrent eleventh reveal rejected; retries free; Kolkata midnight; reports deduplicated; reset cannot touch real data |
| 5: frontend | India-wide public portal, recruiter UI, URL-backed search filters/sorting/pagination, semantic theme, maps, image compression | Nationwide initial search; search links/back navigation/page reset work; accessible mobile flows; upload/reveal/report end-to-end; private responses absent from public caches |

Phase 1 should finalize schema semantics before creating V1. Each later feature updates its module context and API/schema indexes in the same PR.

Phase 1.1 is complete; see [backend versions and compatibility](BACKEND_DEPENDENCIES.md). The supplied wrapper ran Maven 3.10.0 under Temurin Java 25.0.4.1; the dependency probe and optional Resilience4j profile resolved, and the initial skeleton's build/context-load test passed without changing backend inputs at that step.

Phase 1.2 is complete. The existing skeleton now uses `in.opennest.OpenNestApplication`, OpenNest metadata/application naming, and the Modulith 2.1.1 BOM with core/test starters. The common, identity, listing, interaction, and sandbox modules are closed declarations with no allowed cross-module dependencies or public contracts yet. Each module has a local context file; [backend context](../backend/MODULE_CONTEXT.md) records the boundaries. Its clean Java 25 build passed three context/module tests without adding feature APIs, datastore configuration, migrations, or deployment modes at that step.

Phase 1.3 is complete; see [backend configuration](BACKEND_CONFIGURATION.md). Runtime environment and data mode bind separately at startup, before PostgreSQL/Hikari and Redis/Lettuce client construction. Production permits PRIMARY only, rejects development/demo flags and sandbox secrets, and requires database/Redis passwords. Sandbox selection requires explicit mode and distinct declared targets/credentials; no request selects stores. Pool size/minimum idle are 5/1, virtual threads are enabled and exercised, and SQL script initialization is disabled. A clean Java 25 build passed 46 tests with no failures/errors/skips. Tests prove configuration and client selection without querying live stores; real isolation, schema, auth, and deployment checks remain later tasks.

Phase 1.4 is complete; [schema contract](SCHEMA_CONTRACT.md) specifies all six tables' SQL types, nullability, defaults, keys, checks, generated values, deletion actions, and baseline indexes. Composite property/owner foreign keys prevent mismatched attribution; row constraints are distinguished from later transactional authorization/quota/aggregate rules. Initial USER accounts can both own and seek accommodation. Retention policy, trust thresholds, contact provider, public map coarsening, and Phase 3 upload limits remain explicit open decisions. Phase 1.4 changed documentation only and did not run SQL at that step.

Phase 1.5 is complete; [migration record](BACKEND_MIGRATIONS.md) documents shared V1 schema/PostGIS and V2 indexes, startup application through the selected datasource, reserved Flyway settings, and repeatable integration checks. A clean Java 25 build passed 52 unit/context/module tests and 25 database tests with no failures/errors/skips. Both isolated PostgreSQL 17.11/PostGIS 3.5.3 targets received identical migrations/schema/checksums with no application fixtures; repeat migration executed nothing. Docker is unavailable, so tests used portable database clusters rather than the Compose image. No hosted/Compose database, Redis isolation, or CI was exercised at that step.

Phase 1.6 is complete; [persistence contract and evidence](BACKEND_PERSISTENCE.md) records six package-private entities inside their owning modules, scalar UUID foreign references, read-only generated columns, and listing @Version. Hibernate validate runs after Flyway and rejects schema drift; direct ORM schema-action overrides are rejected. Generated listing values require flush then refresh/reload after updates to preserve proper stale-write exceptions on the pinned Hibernate release. Its clean Java 25 build passed 57 unit/context/module and 49 real database tests. V1/V2 remained unchanged; no business service/API, Docker/hosted execution, Redis isolation, or CI was claimed at that step.

Phase 1.7 is complete; [HTTP errors/security contract](BACKEND_HTTP_ERRORS.md) records fixed Problem Details for expected 400/401/403/404/409/429/503 failures and generic unexpected 500 responses. Known uniqueness/stale conflicts and connection/timeouts remain distinct from generic integrity/schema/application bugs. Responses reset uncommitted data and never copy exception/validation/request/private values. Logging emits fixed status/error-ID events only; framework diagnostics, SQL/request dumps, and stack traces are suppressed, including at startup. Every normal request is denied; form/Basic/logout/default-password/demo access is absent and CSRF remains enabled. The clean Java 25 build passed 114 unit/context/HTTP and 49 database tests, zero failures/errors/skips, including 50 HTTP tests through test-only controllers/live container. No diagnostic business endpoint is packaged, and migrations/mappings are unchanged. Docker, Redis isolation, CI, and hosted deployment remain unverified. Phase 1.8 datastore isolation is next; Phase 1 overall remains incomplete.

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
| 1.7: HTTP/errors/security baseline | Complete | Safe Problem Details/logs, known versus unexpected classification, deny-all security without login/password access; 114 unit/HTTP and 49 database checks; Google login remains Phase 2 |
| 1.8: datastore isolation | Complete locally | Review-fix Java 25/Linux amd64 Docker build passed 130 default, 51 schema/persistence, and 23 isolation tests on 30 October 2026, with no failures/errors/skips. See [isolation proof](BACKEND_ISOLATION.md) |
| 1.9: CI/handoff | Implemented; inspect PR checks | Java 25 backend CI runs both real-store profiles and verifies wrapper/reports/artifact boundaries; the local review-fix suite totals 204 passing tests and Foundation is unchanged. [Handoff commands and results](BACKEND_HANDOFF.md); PR #2 contains review corrections; no merge/deploy |

The 30 October verification closes the local Docker gate. The return-value ConstraintViolationException finding is fixed and covered by a real validation regression. Review corrections also cover Security delegation, HTTP status preservation, redacted diagnostic levels, Boot SPI registration, and timestamp clamping. The [handoff](BACKEND_HANDOFF.md) records the 204-test result; inspect completed PR CI separately before declaring the phase complete.

### Phase 2 task boundaries

Implementation and verification are documented in the [Phase 2 handoff](IDENTITY_HANDOFF.md). Google is disabled until explicitly configured. These tasks do not enable contact verification, account linking, sandbox personas, or listing publication.

| Task | Deliverable and completion gate |
| --- | --- |
| 2.1: dependencies/configuration | Boot-managed OAuth2/Session dependencies, exact origins, selected secrets, canonical flags, production guards; Java 25 and unsafe-config tests |
| 2.2: account persistence | Transactional issuer/subject lookup/signup, unique-race recovery, no email merging or phone reservation; repeated/concurrent signup tests |
| 2.3: Redis sessions | Login rotation, 30-minute idle/24-hour authenticated absolute expiry, isolated cookies/stores/realms; expiry/replay/outage tests |
| 2.4: Google OIDC | Framework code flow with PKCE/state/nonce and signed-token validation; controlled-provider success/failure/replay tests |
| 2.5: CSRF/logout/CORS | Session synchronizer token, protected POST logout, fixed REST errors, exact origins; browser HTTP and CORS tests |
| 2.6: trust | Approved pure badge calculator; threshold/count/precedence tests, no listing visibility mutation |
| 2.7: private profile | Caller-only no-store profile, verified-contact boolean contract for Phase 3; anonymous/contact-state tests |
| 2.8: integrated security | Controlled OIDC server and two real PostGIS/Redis pairs; concurrency, isolation, dependency outages, and leak checks |
| 2.9: CI/handoff | Required-suite discovery, packaged fixture exclusion, context/doc alignment; full local gate and separately inspected PR checks |

Tasks 2.1 through 2.9 are implemented and locally verified on 1 November 2026. The complete Java 25/Docker gate passed 298 tests: 205 default, 51 schema/persistence, 23 datastore-isolation, and 19 identity integration cases. Six Python report-gate tests, context/wrapper limits, and packaged fixture checks passed. Inspect current PR checks separately for CI results. Live Google smoke testing requires a real OAuth registration and is not claimed.

## Technical implementation notes for later phases

- Backend module declarations now exist: common, identity, listing, interaction, sandbox under `in.opennest`. Keep nested implementation packages internal and types package-private where possible. Introduce explicit public contracts and allowed dependencies together; no cross-module edges are allowed at bootstrap. Modulith tests verify module discovery and boundaries.
- Route references are fully qualified: application REST under `/api/v1`, with only `/oauth2/authorization/google` and `/login/oauth2/code/google` using Spring Security framework paths. Align the route table and API index; a documentation prefix correction alone does not introduce a database migration.
- Planned listing lifecycle: `POST /api/v1/listings` creates a PAUSED draft; presign uploads against that existing ID, complete/validate each image, then `POST /api/v1/listings/{id}/publish` checks verified contact, required image segments, and owner quota. `POST /api/v1/listings/{id}/pause` preserves content and releases the slot. Inventory cannot reactivate a paused draft.
- Presign will reserve a PENDING image and return imageId/objectKey/uploadUrl/expiresAt. `POST /api/v1/listings/{id}/images/complete` will validate it and finalize an object that a still-valid staging PUT URL cannot overwrite. Publication counts only VALIDATED images. Test completion retries and post-validation overwrite attempts before enabling uploads.
- Authentication follows `docs/AUTHENTICATION.md`. Spring Security owns Google OIDC and Spring Session Redis owns browser sessions; Next.js renders the login UI without running a second NextAuth identity/session system. This deliberately replaces the original stateless-JWT target for the initial browser app.
- WhatsApp is deferred: the proposed user-sent code flow needs signed webhook validation, session/phone binding, high-entropy single-use challenges, replay/rate limits, and an explicit defense against forwarded-link approval of another browser. No claim of free-forever pricing, universal test-number access, or zero onboarding is established.
- Provider-delivered WhatsApp authentication templates remain an alternative to evaluate for phone verification. Check official policy/eligibility/current rates before implementing either option. Do not send messages, enroll accounts, or incur provider charges during foundation setup; secrets stay in deployment configuration.
- Search follows `docs/LISTING_SEARCH.md`: database-side filters/order/pagination, bounded page sizes/offsets, matching counts, and deterministic ties. City is optional for search and explicitly required for listing creation, with no database default or fixed city allowlist. Normalize cities consistently; do not assume every locality fits a fixed catalog. Cache keys include every normalized filter, sort, page, size, and deployment; nationwide queries remain distinct.
- Overpass lookup uses an HTTP interface, a 2500 ms timeout, circuit breaker, and empty-result fallback for HOSPITAL/SCHOOL/SHOP within 2.5 km. A remote outage must not prevent saving a listing. Public deployment needs an operator-approved provider or separately budgeted self-hosted service; otherwise enrichment stays disabled. Map tiles/styles and geocoding are separate provider decisions; see the [deployment plan](DEPLOYMENT_STACK.md).
- Only unauthenticated production listing responses may enter shared Next.js caches; all sandbox, authenticated, contact, and auth responses use no-store. Invalidate affected public searches/details after listing/status/inventory/review writes. A report must update trust data and invalidate affected views as part of the completed write flow. Enable cacheComponents/reactCompiler only after compatibility checks.
- Frontend image compression is a usability feature, not an upload security boundary. Validate uploaded objects before attaching them to a published listing.
- Six-hour demo resets must serialize with writes, be idempotent, and use sandbox-only credentials. Fabricated contact actions stay disabled; never seed callable real-looking phone numbers. Seed historical contact_reveals for designated demo personas/listings so review/report eligibility can be exercised through the normal rule without enabling live contact reveals or bypassing authorization.
- Build application images in GitHub Actions and publish to GHCR after Dockerfiles exist. Deploy immutable digests with protected environment approval; never compile on the small EC2 host.
- Add a restricted runtime database role distinct from the migration/admin role before deployment. Local Compose users are development-only.
- Add scheduled backup/restore verification, bounded logs, dependency/security checks, and provider spend limits before launch.
- Include the OpenNest license and a corresponding-source link for the running revision in the deployed app; preserve third-party license notices.

## Open decisions and setup dependencies

- Hosting starts personal/non-commercial with minimal cost; no AWS account or credits exist yet. The planned website is `https://opennest.co.in`; confirm domain control, DNS/TLS, budget, image architecture, same-site session routing, map providers, and Redis version/license before public deployment; see [deployment decisions](DEPLOYMENT_STACK.md#decisions-before-public-deployment).
- Set up the production Google OAuth client/redirect URI. If Google is also enabled in the sandbox, use a separate client and redirect origin; demo personas do not require Google accounts. Use deployment secrets, never chat or Git, for credentials.
- Choose an approved owner-phone verification method before publishing becomes available; WhatsApp production eligibility, flow approval, provider selection, and any spend limit remain pending.
- Trust precedence is approved and implemented in Phase 2. Phase 3 listing responses must reuse it; Phase 4 review/report writers maintain aggregates transactionally.
- Decide a manual moderation/appeal policy and retention/deletion requirements before collecting real personal data.
- Decide a documented public map coarsening/precision policy before Phase 3 response DTOs; until then omit coordinates. Public search/detail responses must never expose precise coordinates, private address, or owner phone. Geo queries may use exact stored coordinates internally.
- Finalize Phase 3 draft/upload limits, cleanup, and any text/price/image edit or deletion routes before exposing those operations; the published-listing quota does not cap draft storage. The proposed rental-mode field and inventory constraints are defined in [LISTING_MODEL.md](LISTING_MODEL.md).

These remaining items stay open deliberately. Do not silently choose a phone provider, enable deferred WhatsApp, or declare a public launch ready while owner contact verification is unavailable. Trust thresholds are approved for Phase 2.

## Source checks

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Next.js 16 architecture and caching](https://nextjs.org/blog/next-16)
- [PostGIS Docker image and supported tags](https://github.com/postgis/docker-postgis)
- [Redis persistence and durability tradeoffs](https://redis.io/docs/latest/management/persistence/)
- [Meta WhatsApp authentication template example](https://www.postman.com/meta/whatsapp-business-platform/request/6vkv46u/create-authentication-template-w-otp-copy-code-button)
- [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)
- [Spring Security Google login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)
- [WhatsApp official pricing overview](https://business.whatsapp.com/products/platform-pricing)
- [GNU AGPL version 3](https://www.gnu.org/licenses/agpl-3.0.html)
- [CodeRabbit path instructions](https://kb.coderabbit.ai/articles/1134523355-configure-path-instructions)
