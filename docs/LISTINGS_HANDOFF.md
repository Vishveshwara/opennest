<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:b811d06fd7613c4b65533f220e42a2ded5df74b0a632c2083fdd6f31254c7d0b -->

<!-- coderabbit-ai-generated-section:v1:b0606461f1e73389535d36a358a41492243f551ace7676033aec9a29822fb87e -->

<!-- coderabbit-ai-generated-section:v1:248c0c97374831f2326ae9b6156ee4318f1b4156f74348e73339412575ff36fb -->

# OpenNest Phase 3 listing handoff

Phase 3 implements listing drafts, owner retrieval, direct image uploads, explicit publication/pause, mode-aware inventory, and nationwide search. Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1, PostgreSQL/PostGIS, and backend-owned sessions remain the stack. Hosting and live R2 readiness are not established by this implementation.

## Routes and visibility

All listing responses use `Cache-Control: no-store`. Phase 5 retains no-store for every listing fetch and response; shared listing caching needs a separate approved policy and invalidation change. Authentication-disabled deployments permit public listing reads and exact-origin public-read CORS. Authentication and owner mutations still require configured Telegram login. Unknown routes remain denied.

| Route | Behavior |
| --- | --- |
| `POST /api/v1/listings` | Authenticated creation of a PAUSED draft, returning 201, Location, listing ID, and version. |
| `GET /api/v1/listings/mine` | Caller-only collection of all statuses; optional status, page, and size. Fixed NEWEST ordering with UUID ties. |
| `GET /api/v1/listings/{id}` | Public ACTIVE/FULL_OCCUPIED detail. PAUSED is owner-only and otherwise 404. Owners receive their private address, coordinates, and version. |
| `GET /api/v1/listings/search` | Public nationwide search with the complete [search contract](LISTING_SEARCH.md). No city default or allowlist. |
| `POST /api/v1/listings/{id}/publish` | Owner-only `{version}`. Requires verified +91 contact, required validated images, and a serialized five-listing quota. |
| `POST /api/v1/listings/{id}/pause` | Owner-only `{version}`; releases the publication slot without deleting content. |
| `PATCH /api/v1/listings/{id}/inventory` | Owner-only mode-specific inventory and version, as defined in [listing modes](LISTING_MODEL.md). |
| `POST /api/v1/listings/{id}/images/presign` | Owner-only `{segment, contentType, byteSize}` reservation. Returns imageId, objectKey, uploadUrl, expiresAt, and requiredHeaders. |
| `POST /api/v1/listings/{id}/images/complete` | Owner-only `{imageId}`. Validates uploaded bytes and attaches a separate immutable final object. |
| `DELETE /api/v1/listings/{id}/images/{imageId}` | Owner-only, CSRF-protected photo removal; returns deletedImageId/listingPaused. |

Draft input requires title, description, category, city, locality, privateAddress, latitude, longitude, monthlyRent, securityDeposit, all three amenity booleans, and applicable mode/inventory fields. FLAT also requires flatRentalMode and bedrooms; PG_HOSTEL leaves these absent or null. Entire flats require wholeFlatAvailable/maxOccupants. Room listings require all three room counters and maxSharingPerRoom. Client ownership, publication status, totals, and enrichment are rejected.

Public responses contain no owner phone, private address, precise coordinates, or precise distance. Owner summaries use the existing trust calculator. Prices always return currency=INR, rentPeriod=MONTH, and the category/mode-derived rentBasis. Capacity never changes the price basis or implies occupied beds.

Search/count/image hydration share a read-only REPEATABLE_READ transaction. Every filter is parameterized, sort expressions are allowlisted, and property rows are paginated before image hydration. The identity-owned `listing_owner_profiles` view exposes only ID, name, and aggregate trust fields for search joins. It contains no contacts or provider identities.

## Transaction and input rules

Owner mutations lock the account row before the listing and image rows. Published quota and retained-listing checks use PostgreSQL, with no Redis quota dependency. Both ACTIVE and FULL_OCCUPIED count toward the five-listing limit. A sixth publication returns 409.

Photo removal holds the owner/listing/image locks and transitions the reservation to REJECTED, clearing the final key and validated metadata. Reservation identity, declared metadata and created_at stay intact so deletion cannot refund the 60-reservation rolling-day budget. Existing cleanup removes rejected rows after seven days and private final objects after the orphan grace period. Previously issued read URLs can live until their five-minute expiry. Concurrent deletion of a category's last required validated segment pauses a published listing exactly once and increments its version. Whole-listing deletion is not part of this change.

Exact nonnegative versions are mandatory before inventory, publication, and pause writes. Stale retries return 409. Current-version publication/pause no-ops preserve version and quota. Every accepted inventory update increments version, even if values are unchanged. Inventory edits preserve PAUSED. SQL updates read generated inventory totals from the database before returning.

Telegram-only login now establishes approved +91 contact evidence through private self-contact matching and original-session OTP completion. Publication still independently checks that evidence, validated images and owner quotas. Legacy/fabricated unverified accounts stay blocked with fixed CONTACT_VERIFICATION_REQUIRED guidance. See [Telegram login setup](TELEGRAM_HANDOFF.md).

| Limit | Value |
| --- | --- |
| Retained listings per owner, all statuses | 20 |
| Validated or unexpired PENDING images per listing | 20 |
| Image reservations per owner, rolling 24 hours | 60 |
| Browser source bytes | 1 through 40,000,000 |
| Browser source types / current upload | JPEG, PNG, WebP, AVIF / AVIF |
| Current output | Longest side at most 2,048 pixels, no upscaling; soft target 1,000,000 bytes; no product limit on converted bytes |
| Dimensions and capacity | Browser decoding still needs device memory; server heap admission applies; uploaded AVIF must contain one still image |
| Upload URL lifetime | 10 minutes |
| Image read URL lifetime | 5 minutes |
| Title / description | 160 / 5,000 characters |
| City and locality / private address | 120 each / 500 characters |
| Listing JSON body | 32 KiB |

Unknown JSON fields, duplicate keys, trailing JSON values, invalid numeric types, and inapplicable inventory fields return 400. Oversized request bodies return 413. City/locality normalize with NFKC, Unicode whitespace collapse, trimming, and locale-independent uppercase; normalization must remain nonblank and within the length bound.

## R2 configuration and image lifecycle

Set `OPENNEST_STORAGE_ENABLED=true` only after configuring private buckets and credentials. The flag accepts only canonical `true` or `false`. Disabled storage has no production filesystem or fake-storage fallback; upload operations return 503. Existing validated image metadata can be read without a URL while storage is disabled.

| Setting | Primary | Sandbox |
| --- | --- | --- |
| Account | `R2_ACCOUNT_ID` | `SANDBOX_R2_ACCOUNT_ID` |
| Access key | `R2_ACCESS_KEY_ID` | `SANDBOX_R2_ACCESS_KEY_ID` |
| Secret | `R2_SECRET_KEY` | `SANDBOX_R2_SECRET_KEY` |
| Private staging bucket | `R2_STAGING_BUCKET` | `SANDBOX_R2_STAGING_BUCKET` |
| Private final bucket | `R2_FINAL_BUCKET` | `SANDBOX_R2_FINAL_BUCKET` |

Bucket names must be disjoint across deployments, including crossed staging/final names. Primary and sandbox credentials must differ when both are supplied. Production rejects sandbox storage credentials even when storage is disabled. Clients use the fixed HTTPS R2 S3 endpoint derived from the selected account; no user-supplied endpoint or object key is accepted.

The browser performs the PUT directly with a nine-minute transport timeout inside the ten-minute reservation lifetime. Apply the returned signed headers; the browser supplies Content-Length from the exact upload body. Configure bucket CORS for the exact frontend origin, PUT/GET/HEAD as needed, Content-Type and If-None-Match, and ETag exposure. CORS, scoped bucket credentials, and bucket lifecycle rules still require provider configuration and a live smoke test before exposure.

The 17 November 2026 follow-up replaces the full-resolution lossless WebP writer with a dedicated single-threaded @jsquash/avif 2.1.1 WASM worker. The browser applies orientation, fits the longest side within 2,048 pixels while preserving aspect ratio, and never upscales. It flattens transparency onto white and releases the source bitmap before encoding opaque 8-bit AVIF. Quality 60/speed 8 is the first attempt; output above 1,000,000 bytes gets one quality-45 attempt, retaining the smaller result. The target is soft; converted output is uploaded even when larger, with no 4 MB product limit. Preparation times out after two minutes. Encoding is lossy and browser color conversion still applies. The 40 MB source-only check is client-side because the backend receives converted bytes. Device memory can still limit source decoding.

Completion checks the actual encoded size and conditionally reads the staging object's ETag. AVIF admission accepts only the pinned browser writer's normalized container: one opaque 8-bit AV1 item, one in-file extent, bounded dimensions and no EXIF, XMP, animation, thumbnails, grids, external references or extra boxes. Other valid AVIF layouts must first pass through the browser writer. The server decodes one AV1 frame and verifies its dimensions/depth; it preserves the admitted AVIF bytes without re-encoding or making an RGBA copy. Browser checks are not trusted as server validation. Legacy API JPEG/PNG sanitization, JPEG EXIF orientation and lossless WebP RIFF/VP8L validation remain supported; saved images stay unchanged. A unique final object is written using If-None-Match, then ownership, state, and expiry are rechecked under database locks before attachment. Validated completion retries return existing metadata with fresh read URLs. A later staging write cannot affect the final object.

One processing permit and one decoder thread bound concurrent work. AVIF dimensions are limited to 2,048 pixels per side before decoding; FFmpeg uses one decoder thread and a 4,194,304-pixel codec limit. Before AVIF decode, sixteen bytes per pixel plus three encoded buffers must fit one quarter of maximum JVM heap; insufficient capacity returns retryable 503. This is admission budgeting, not an operating-system memory sandbox. Legacy JPEG/PNG/WebP retain their existing transport and heap limits, with the shared 2,147,483,639-byte transport ceiling applying to AVIF too and WebP's eight-bytes-per-pixel budget. Before download, declared encoded bytes times four must fit that quarter-heap budget; the read buffer is bounded by the reserved size plus one byte. Storage calls have 120-second overall and 60-second attempt timeouts. Staging HEAD, GET, and actual body consumption share the 120-second total budget; expiry aborts the response stream even when bytes keep arriving. Decoding has a 60-second deadline; final attachment checks an overall five-minute deadline. Decoder threads remain bounded even if native decoding ignores interruption. Upload/download URLs, bytes, addresses, and account/contact data are excluded from logs and DTO diagnostic strings.

Hourly cleanup deletes staging objects and unattached final objects older than 24 hours. It marks PENDING reservations rejected after expiry plus 24 hours and deletes rejected reservation rows after seven days. Attached final objects and retained drafts remain. The completion deadline prevents old in-flight work from attaching objects eligible for orphan cleanup. Failed or duplicate finalization can leave temporary private objects until cleanup. Only dedicated selected buckets and server-generated `listings/` keys are scanned.

Both buckets remain private. Public reads issue five-minute signed GET URLs only for validated images on visible listings. Pausing prevents new public URLs; an issued URL can remain usable until expiry. No public bucket, CDN, cache purge integration, or media hostname is provisioned.

AWS SDK v2 `2.57.11`, TwelveMonkeys WebP decoder `3.15.3`, JavaCV `1.5.14` and FFmpeg `8.1.2-1.5.14` are pinned Maven dependencies for Java 25. Only JavaCV's frame-grabber bridge and the selected platform's FFmpeg/JavaCPP runtime are bundled; unrelated JavaCV engines are excluded. Maven validate rejects hosts outside Linux/Windows x86-64 and classifiers outside linux-x86_64/windows-x86_64 before packaging. Supported builds default to Linux x86_64 or select Windows x86_64 through the Windows profile. An explicit `-Dopennest.media.platform=...` override selects one of those targets; cross-built artifacts need testing on that target runtime. ARM, macOS and 32-bit build hosts are unsupported. Java 25 emits a native-access warning unless launched with `--enable-native-access=ALL-UNNAMED`. Native decoder load failures return 503. The adapter follows [Cloudflare's Java SDK guidance](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/). The AVIF fixture is generated from fabricated pixels with `node frontend/scripts/avif-fixtures.mjs`; legacy RGB/RGBA fixtures continue to verify exact lossless pixels and VP8L bytes. Notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), plus the generated frontend codec notices.

Minimal Linux images also need FFmpeg's system-library dependencies. On Ubuntu 24.04/26.04 install `libxcb1 libxcb-shm0 libasound2t64 libpulse0` before starting the backend; these provide `libxcb.so.1`, `libxcb-shm.so.0`, `libasound.so.2` and `libpulse.so.0`. The JavaCV bridge loads FFmpeg's device library even for file decoding; no audio/display server is required. Other distributions need equivalent packages. `unzip` is additionally needed when invoking the pinned Maven ZIP wrapper in a minimal build image. Neither a Linux deployment image nor an ARM runtime is provisioned by this change.

## Migration, verification, and remaining gates

V3 adds the public owner projection and six nullable image metadata columns with shape/size constraints. Nullable metadata preserves pre-service V2 rows. New reservations and completions always populate metadata. V8 expanded legacy media bounds. V9 adds AVIF to the same all-or-none checks with 4,000,000-byte and 2,048-pixel limits; no tables, columns or photos change. Like V8, it commits the atomic NOT VALID replacement before two separate validation transactions. V10 removes the AVIF-specific byte cap at maintainer request, retaining the shared transport/heap limits and 2,048-pixel bound. V1-V9 remain unchanged; Flyway and Hibernate validation remain mandatory. Tests cover clean migration, populated V2/V9 upgrades, saved-photo retention and old migration checksums.

Roll out the AVIF-capable backend and V10 before the frontend writer. To roll back the writer, restore the previous frontend while retaining V10 and an AVIF-capable backend so new and existing photos remain readable. Do not downgrade to a backend that cannot validate AVIF while AVIF reservations or photos exist. Failed nontransactional constraint validation needs operator reconciliation; do not repair checksums or delete photos automatically. Hosted migration and provider smoke tests are still pending.

Run from the repository root using Java 25 and Linux amd64 Docker:

```powershell
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pschema-integration,isolation-integration' clean verify
python .github/scripts/verify_backend.py --reports
python -m unittest discover --start-directory .github/scripts --pattern '*_test.py'
```

The required suites include listing parsing/image validation, real SDK calls against a local HTTP storage fixture, and listing lifecycle tests on separate real PostGIS/Redis pairs. Query-plan evidence is written to `backend/target/listing-query-plans.txt` using 3,000 fabricated listings across five Indian cities. Report verification rejects missing/skipped suites and packaged test fixtures. On 9 November 2026, the clean local Java 25/Linux amd64 Docker gate passed all 450 tests after review corrections: 337 default, 53 schema/persistence, 24 datastore-isolation, 19 identity, and 17 listing integration cases. There were no failures, errors, or skips. Report/artifact validation, all six Python checks, context limits, and whitespace checks passed. Query plans used the existing nationwide/city rent indexes and recorded a spatial query; no additional index was added. No CI result for this change is claimed.

The maintainer confirmed real local Telegram OTP login and the verified profile on 10 November. Live R2 bucket/CORS/credential checks and live failure/logout/repeated-login remain manual gates. No provider resources, secrets, merge, deployment, or public exposure are part of this phase. Overpass is disabled and nearbyLandmarks is empty. Later follow-ups implement interactions, the frontend, recruiter personas and photo removal. Text/price edits, image replacement and whole-listing deletion remain outside this change. The 20-retained-listing cap does not delete old drafts.

The 17 November photo-removal follow-up passed 89 frontend tests, TypeScript, lint and the production build. A real PostGIS/Redis regression passed concurrent last-required-segment removal, ownership, CSRF and retained rolling-day reservation accounting. Production route-registration verification also passed. Local photo DELETE preflight returned 200 after the backend restart; no real user image was removed during verification. Full combined backend/browser suites and updated CI remain separate checks.

Earlier phase evidence was inspected during planning: [Backend](https://github.com/Vishveshwara/opennest/actions/runs/42574209529) and [Foundation](https://github.com/Vishveshwara/opennest/actions/runs/42574209532) passed for `7c3bba3`. Those results cover the earlier revision, not these listing changes.

Review regressions verify every documented listing path/method, reject an added diagnostic mapping, fingerprint the owner view, check exact IDs for all six sorts and UUID ties across pages, and commit a concurrent update between count and page queries during a standalone search. Legacy migrated null-metadata completion returns 409. Encoded context-relative listing paths retain body limits and CORS at a later security-filter order. Tests cover authentication-disabled POST/PATCH denial with valid CSRF/authentication and origin-specific GET preflights, all eight EXIF orientations and pixel colors, and continuously trickling storage bodies sharing the HEAD/GET deadline. CI now requires the query-plan file before the unchanged report upload.

A further test-only review update on 9 November 2026 passed 75 focused cases: 21 image, 29 storage, and 25 datastore-isolation tests, with no failures/errors/skips. The route allowlist now inspects listing paths from every handler and rejects a SafeErrorController diagnostic mapping. EXIF fixtures cover orientation after a valid preceding IFD0 entry in both byte orders while retaining first-entry coverage. Delayed GET headers must fail within the remaining total storage budget and before the fixture client timeout. The full local suite was not rerun for this test-only update; inspect its PR checks separately.

Phase 3A now implements sole Telegram authentication with approved self-contact matching and original-session OTP entry. The publication contact/image/quota gates remain in force. [Telegram setup](TELEGRAM_HANDOFF.md) records configuration and confirmed local login/profile. [API_VERIFICATION.md](API_VERIFICATION.md) covers every listing route through a Telegram-created session, and [POSTMAN_API_GUIDE.md](POSTMAN_API_GUIDE.md) provides listing/image steps. Live R2/hosted checks remain separate.

On 10 November, the expanded Telegram/session gate passed 553 Java tests, including 31 Telegram cases with three complete mode-specific listing HTTP lifecycles. Six Python checks and 20 isolated launcher regressions also passed. OAuth/OIDC application classes/libraries and test fixtures are absent from the packaged JAR. This supersedes the earlier local counts for the current revision; CI and live R2/hosted checks remain unverified. The extra live anonymous smoke could not run because the local backend was stopped; no private session/account was used.


<!-- /coderabbit-ai-generated:v1 -->
