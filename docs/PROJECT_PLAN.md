# OpenNest implementation plan

This plan defines OpenNest's proposed India-wide launch. Phase 0 currently provides documentation, local infrastructure, review guidance, and infrastructure CI only; no application modules or endpoints exist. Work proceeds one phase per prompt and one reviewable PR per phase. The AI indexes summarize this plan, while AUTHENTICATION.md and LISTING_SEARCH.md specify their detailed planned contracts. [DEPLOYMENT_STACK.md](DEPLOYMENT_STACK.md) records the proposed hosting, verified provider constraints, and deployment readiness requirements.

## Confirmed product direction

- Flats and PG/hostels across India from launch, with no default city or city-specific rollout. Support any Indian city without a predefined city allowlist.
- Search without a city filter returns paginated nationwide results; city, locality, and nearby-location filters narrow the results when supplied.
- Pagination, sorting, and combined filters are launch requirements. The complete request/response contract, room semantics, UI behavior, and acceptance cases are in [Listing search plan](LISTING_SEARCH.md).
- Use the product name OpenNest consistently in UI, metadata, documentation, and generated examples; lowercase `opennest` is reserved for technical identifiers.
- Maximum five ACTIVE/FULL_OCCUPIED listings per owner; PAUSED listings do not count.
- No chat or WebSockets. Authenticated users may reveal ten distinct listing contacts per India calendar day.
- Authentication: Google sign-in first through Spring Security OIDC, with backend-owned server sessions. WhatsApp verification is a later option, disabled until its flow and production readiness are reviewed. See [Authentication decisions](AUTHENTICATION.md).
- The planned Google flow will allow account creation without phone verification. Owners must separately verify a +91 contact before publishing; publishing will remain gated until that verification method is ready. PAUSED draft creation and photo uploads need an authenticated account but no verified phone.
- PG_HOSTEL rent is quoted in INR per person per month; FLAT rent is per whole property per month. Always return/display the pricing basis. Room inventory remains rooms, not beds or people.
- License: GNU Affero General Public License version 3 only (`AGPL-3.0-only`), included in the root LICENSE file.
- `/recruiter-sandbox` will use fabricated listings across multiple Indian cities in a separate database. No public navigation link to it. Trusted sandbox seeding may create demo fixtures without real phone verification; callable contact actions stay disabled and production has no such bypass.
- Reports and poor reviews produce warnings, never automatic listing suspension or deletion.
- R2 presigned PUT uploads; client WebP compression targets <150 KB. Server-side metadata/content validation is still required.
- FLAT images require ROOM, HALL, BATHROOM, KITCHEN; PG_HOSTEL requires ROOM and BATHROOM.
- Preserve the TweakCN theme at `https://tweakcn.com/r/themes/cmv1h5ahl000104kwff88a0p8` when initializing the frontend.

## Corrections to the original constitution

1. **Sandbox security:** an untrusted header cannot select production versus demo data. Use separate backend deployments/configuration, database credentials, Redis/session stores, cookie names/hosts, storage prefixes or buckets, cache namespaces, and separate OAuth clients/signing keys where used. Production disables demo operations entirely. The local Compose profile provisions separate data stores; it is not proof of application isolation.
2. **Trust rules:** the source contains incompatible formulas. Proposed canonical precedence: >=1 report and average <3 -> CRITICAL_BROKER_AND_LOW_RATING; else >=1 report -> BROKERAGE_WARNED; else >=2 reviews and average <3 -> LOW_RATINGS_BEWARE; else >=3 reviews and average >=4.5 -> TOP_RATED; otherwise STANDARD. No reviews means STANDARD with a NULL numeric rating. Calculate the same badge for the owner profile and their listing responses. This precedence still needs maintainer confirmation; do not label an owner VERIFIED merely because nobody reported them.
3. **Reports:** require prior contact reveal and forbid self-reporting. Each reviewer/property pair has one unique review with an immutable UUID. The first report creates that review or converts it in place to one star / ASKED_BROKERAGE / report flag, preserving existing text, UUID, and creation time. Show the allegation notice separately from preserved text. Repeated reports are no-ops. Ordinary review creation conflicts on an existing pair and cannot clear a report; ASKED_BROKERAGE uses the report endpoint. Serialize all review/report writers on the owner row and update the review and owner aggregates in one transaction, counting each row and report once. Provide an appeal/correction path. Warnings should say “brokerage reported,” not assert proven misconduct.
4. **Atomic limits:** serialize owner quota changes and daily contact-ledger writes in PostgreSQL. Redis is an accelerator, not the sole quota ledger. Lost cache entries must never reset the daily allowance.
5. **Inventory:** derive the total from component counts. Preserve PAUSED status on inventory edits; require a nonnegative version for optimistic concurrency so two owner clicks cannot overwrite each other. A published listing switches between ACTIVE and FULL_OCCUPIED based on availability, and both states count toward the owner quota.
6. **Recovery:** persistent volumes and restart policies do not guarantee zero loss or recovery within three seconds. Redis `appendfsync everysec` can lose recent writes after a crash; contact quota correctness must survive that. Set measurable recovery objectives and test off-host backup restores before launch.
7. **Geography/privacy:** the coordinate box includes places outside India and is only an initial validation bound. Evaluate a boundary polygon if strict geofencing is necessary. Hide precise addresses/coordinates from public responses where appropriate.
8. **Memory:** a 300 MB backend cap and requested JVM flags are hypotheses to benchmark. The two local datastore pairs alone permit 860 MB; both applications plus OS overhead do not safely fit a 1 GB host. Start with a measured deployment budget and keep the demo independently deployable.
9. **Configuration:** verify `spring.datasource.connection-fetch=lazy` before using it; do not assume an undocumented property works. Pool size 5/minimum idle 1, virtual threads, Flyway, and Hibernate validate remain targets.
10. **Version pins:** requested Spring Boot 4.1.1, Java 21, Next.js 16.4, React 19.3 remain targets. Verify registry availability, compatible Spring Modulith/Resilience4j versions, and lock exact dependencies during bootstrap. Do not force independently selected Spring Framework/Security versions over the Boot BOM.

## Delivery phases and acceptance criteria

| Phase / PR | Scope | Required evidence |
| --- | --- | --- |
| 0: foundation | Instructions, context indexes, local persistent stores, review configuration, CI | Compose parses; both store pairs start; PostGIS works; data survives container replacement |
| 1: backend | Java 21/Spring bootstrap, Flyway schema including flat bedrooms and search indexes, common errors, deployment modes | Migrations on real PostGIS; schema validation; production rejects demo mode; module boundaries |
| 2: identity | Google OIDC, backend sessions, CSRF/logout, user profile, trust calculator | State/nonce/PKCE and ID-token checks; session rotation/expiry; CSRF; safe account linking; production rejects demo auth; trust thresholds; phone-unverified owners cannot publish |
| 3: listings | PAUSED drafts, image upload/finalization, explicit publish/pause, owner quota, versioned inventory, nationwide search, R2 URLs, Overpass | Draft-upload-publish flow works; invalid photos/contact cannot publish; search contract cases in LISTING_SEARCH.md pass; concurrent sixth publication rejected; stale writes rejected; PAUSED preserved; unauthorized edits denied; real geo queries; timeout fallback |
| 4: interaction/demo | Contact ledger, ratings/reports, multi-city demo seeding/reset/personas | Fabricated listings cover multiple Indian cities; concurrent eleventh reveal rejected; retries free; Kolkata midnight; reports deduplicated; reset cannot touch real data |
| 5: frontend | India-wide public portal, recruiter UI, URL-backed search filters/sorting/pagination, semantic theme, maps, image compression | Nationwide initial search; search links/back navigation/page reset work; accessible mobile flows; upload/reveal/report end-to-end; private responses absent from public caches |

Phase 1 should finalize schema semantics before creating V1. Each later feature updates its module context and API/schema indexes in the same PR.

## Technical implementation notes for later phases

- Backend modules: common, identity, listing, interaction, sandbox under `in.opennest`. Keep module internals private and verify boundaries with Modulith tests.
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
- Six-hour demo resets must serialize with writes, be idempotent, and use sandbox-only credentials. Fabricated contact actions should be disabled; never seed callable real-looking phone numbers.
- Build application images in GitHub Actions and publish to GHCR after Dockerfiles exist. Deploy immutable digests with protected environment approval; never compile on the small EC2 host.
- Add a restricted runtime database role distinct from the migration/admin role before deployment. Local Compose users are development-only.
- Add scheduled backup/restore verification, bounded logs, dependency/security checks, and provider spend limits before launch.
- Include the OpenNest license and a corresponding-source link for the running revision in the deployed app; preserve third-party license notices.

## Open decisions and setup dependencies

- Hosting starts personal/non-commercial with minimal cost; no AWS account, credits, or domain exist yet. Confirm budget, image architecture, same-site session routing, map providers, and Redis version/license before public deployment; see [deployment decisions](DEPLOYMENT_STACK.md#decisions-before-public-deployment).
- Set up the production Google OAuth client/redirect URI. If Google is also enabled in the sandbox, use a separate client and redirect origin; demo personas do not require Google accounts. Use deployment secrets, never chat or Git, for credentials.
- Choose an approved owner-phone verification method before publishing becomes available; WhatsApp production eligibility, flow approval, provider selection, and any spend limit remain pending.
- Confirm the proposed trust precedence before Phase 2 implementation; review/report lifecycle is specified in the API and schema indexes.
- Decide a manual moderation/appeal policy and retention/deletion requirements before collecting real personal data.
- Decide public map/address precision before Phase 3 response DTOs; public responses must never expose the private address or owner phone. Geo queries may use exact stored coordinates without returning those raw coordinates to callers.

These items remain open deliberately. Do not silently choose a phone provider, enable the deferred WhatsApp flow, claim the trust thresholds are approved, or declare a public launch ready while owner contact verification is unavailable.

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
