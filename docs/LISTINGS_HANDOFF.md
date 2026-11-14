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

Draft input requires title, description, category, city, locality, privateAddress, latitude, longitude, monthlyRent, securityDeposit, all three amenity booleans, and applicable mode/inventory fields. FLAT also requires flatRentalMode and bedrooms; PG_HOSTEL leaves these absent or null. Entire flats require wholeFlatAvailable/maxOccupants. Room listings require all three room counters and maxSharingPerRoom. Client ownership, publication status, totals, and enrichment are rejected.

Public responses contain no owner phone, private address, precise coordinates, or precise distance. Owner summaries use the existing trust calculator. Prices always return currency=INR, rentPeriod=MONTH, and the category/mode-derived rentBasis. Capacity never changes the price basis or implies occupied beds.

Search/count/image hydration share a read-only REPEATABLE_READ transaction. Every filter is parameterized, sort expressions are allowlisted, and property rows are paginated before image hydration. The identity-owned `listing_owner_profiles` view exposes only ID, name, and aggregate trust fields for search joins. It contains no contacts or provider identities.

## Transaction and input rules

Owner mutations lock the account row before the listing and image rows. Published quota and retained-listing checks use PostgreSQL, with no Redis quota dependency. Both ACTIVE and FULL_OCCUPIED count toward the five-listing limit. A sixth publication returns 409.

Exact nonnegative versions are mandatory before inventory, publication, and pause writes. Stale retries return 409. Current-version publication/pause no-ops preserve version and quota. Every accepted inventory update increments version, even if values are unchanged. Inventory edits preserve PAUSED. SQL updates read generated inventory totals from the database before returning.

Telegram-only login now establishes approved +91 contact evidence through private self-contact matching and original-session OTP completion. Publication still independently checks that evidence, validated images and owner quotas. Legacy/fabricated unverified accounts stay blocked with fixed CONTACT_VERIFICATION_REQUIRED guidance. See [Telegram login setup](TELEGRAM_HANDOFF.md).

| Limit | Value |
| --- | --- |
| Retained listings per owner, all statuses | 20 |
| Validated or unexpired PENDING images per listing | 20 |
| Image reservations per owner, rolling 24 hours | 60 |
| Browser source bytes | 1 through 40,000,000; converted output may be larger |
| Browser source types / current upload | JPEG, PNG, WebP / lossless WebP |
| Dimensions and capacity | No 20 MP cap; browser WebP encoder supports sides through 16,383; device and backend heap admission apply; animation rejected |
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

The browser uses a dedicated @jsquash/webp WASM worker to encode browser-decoded 8-bit canvas pixels as lossless WebP. It applies source orientation without resizing or a target output size. Lossless output may exceed the original JPEG; previously discarded JPEG detail cannot be recovered. Browser color conversion still applies. The 40 MB source-only check is client-side because the backend receives converted bytes.

Completion checks the actual encoded size, conditionally reads the staging object's ETag, validates the RIFF/chunk structure and one VP8L decode, and strips container metadata while preserving the lossless payload. Lossy WebP, EXIF-bearing WebP and animation are rejected. Existing saved images stay readable. Legacy API JPEG/PNG inputs retain fresh-pixel sanitization and JPEG EXIF orientation handling; current UI uploads use WebP. It writes a unique final object using If-None-Match, then rechecks ownership, state, and expiry under database locks before attaching it. Validated completion retries return the existing image metadata with fresh read URLs. A later staging write cannot affect the final object.

One processing permit and one decoder thread bound concurrent work. Encoded transport is bounded by the Java byte-array limit, 2,147,483,639 bytes, rather than the source-file policy. Before download, declared encoded bytes times four must fit one quarter of maximum JVM heap. Before WebP decode, eight bytes per pixel plus three encoded buffers must fit that budget; insufficient capacity returns retryable 503. This avoids a fixed megapixel cap but still requires suitable server memory. Storage calls have 120-second overall and 60-second attempt timeouts. Staging HEAD, GET, and actual body consumption share the 120-second total budget; expiry aborts the response stream even when bytes keep arriving. Decoding has a 60-second deadline; final attachment also checks an overall five-minute deadline. Decoder threads remain bounded even if a decoder ignores interruption. Upload/download URLs, bytes, addresses, and account/contact data are excluded from logs and DTO diagnostic strings.

Hourly cleanup deletes staging objects and unattached final objects older than 24 hours. It marks PENDING reservations rejected after expiry plus 24 hours and deletes rejected reservation rows after seven days. Attached final objects and retained drafts remain. The completion deadline prevents old in-flight work from attaching objects eligible for orphan cleanup. Failed or duplicate finalization can leave temporary private objects until cleanup. Only dedicated selected buckets and server-generated `listings/` keys are scanned.

Both buckets remain private. Public reads issue five-minute signed GET URLs only for validated images on visible listings. Pausing prevents new public URLs; an issued URL can remain usable until expiry. No public bucket, CDN, cache purge integration, or media hostname is provisioned.

AWS SDK v2 `2.57.11` and TwelveMonkeys WebP decoder `3.15.3` were resolved from Maven Central and compiled under Java 25. The adapter follows [Cloudflare's Java SDK guidance](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/), including disabled chunked encoding. The current 2x2 RGB/RGBA lossless fixtures were generated with the pinned jSquash encoder and verify exact pixels and retained VP8L bytes. The historical TwelveMonkeys fixture notice remains in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); browser codec licenses are included in the generated frontend notices.

## Migration, verification, and remaining gates

V3 adds the public owner projection and six nullable image metadata columns with shape/size constraints. Nullable metadata preserves pre-service V2 rows. New reservations and completions always populate metadata. V8 replaces the old 5 MiB/20 MP constraints, allows WebP final metadata, and preserves legacy null groups and saved rows. V1-V7 contents remain unchanged; Flyway and Hibernate validation remain mandatory. Tests cover both clean migration and a populated V2 upgrade.

Run from the repository root using Java 25 and Linux amd64 Docker:

```powershell
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pschema-integration,isolation-integration' clean verify
python .github/scripts/verify_backend.py --reports
python -m unittest discover --start-directory .github/scripts --pattern '*_test.py'
```

The required suites include listing parsing/image validation, real SDK calls against a local HTTP storage fixture, and listing lifecycle tests on separate real PostGIS/Redis pairs. Query-plan evidence is written to `backend/target/listing-query-plans.txt` using 3,000 fabricated listings across five Indian cities. Report verification rejects missing/skipped suites and packaged test fixtures. On 9 November 2026, the clean local Java 25/Linux amd64 Docker gate passed all 450 tests after review corrections: 337 default, 53 schema/persistence, 24 datastore-isolation, 19 identity, and 17 listing integration cases. There were no failures, errors, or skips. Report/artifact validation, all six Python checks, context limits, and whitespace checks passed. Query plans used the existing nationwide/city rent indexes and recorded a spatial query; no additional index was added. No CI result for this change is claimed.

The maintainer confirmed real local Telegram OTP login and the verified profile on 10 November. Live R2 bucket/CORS/credential checks and live failure/logout/repeated-login remain manual gates. No provider resources, secrets, merge, deployment, or public exposure are part of this phase. Overpass is disabled and nearbyLandmarks is empty. Text/price edits, image replacement/removal, deletion, interactions, and recruiter personas remain later work. The 20-retained-listing cap does not delete old drafts; owners at that cap cannot create another listing until a deletion contract is implemented.

Earlier phase evidence was inspected during planning: [Backend](https://github.com/Vishveshwara/opennest/actions/runs/42574209529) and [Foundation](https://github.com/Vishveshwara/opennest/actions/runs/42574209532) passed for `7c3bba3`. Those results cover the earlier revision, not these listing changes.

Review regressions verify every documented listing path/method, reject an added diagnostic mapping, fingerprint the owner view, check exact IDs for all six sorts and UUID ties across pages, and commit a concurrent update between count and page queries during a standalone search. Legacy migrated null-metadata completion returns 409. Encoded context-relative listing paths retain body limits and CORS at a later security-filter order. Tests cover authentication-disabled POST/PATCH denial with valid CSRF/authentication and origin-specific GET preflights, all eight EXIF orientations and pixel colors, and continuously trickling storage bodies sharing the HEAD/GET deadline. CI now requires the query-plan file before the unchanged report upload.

A further test-only review update on 9 November 2026 passed 75 focused cases: 21 image, 29 storage, and 25 datastore-isolation tests, with no failures/errors/skips. The route allowlist now inspects listing paths from every handler and rejects a SafeErrorController diagnostic mapping. EXIF fixtures cover orientation after a valid preceding IFD0 entry in both byte orders while retaining first-entry coverage. Delayed GET headers must fail within the remaining total storage budget and before the fixture client timeout. The full local suite was not rerun for this test-only update; inspect its PR checks separately.

Phase 3A now implements sole Telegram authentication with approved self-contact matching and original-session OTP entry. The publication contact/image/quota gates remain in force. [Telegram setup](TELEGRAM_HANDOFF.md) records configuration and confirmed local login/profile. [API_VERIFICATION.md](API_VERIFICATION.md) covers every listing route through a Telegram-created session, and [POSTMAN_API_GUIDE.md](POSTMAN_API_GUIDE.md) provides listing/image steps. Live R2/hosted checks remain separate.

On 10 November, the expanded Telegram/session gate passed 553 Java tests, including 31 Telegram cases with three complete mode-specific listing HTTP lifecycles. Six Python checks and 20 isolated launcher regressions also passed. OAuth/OIDC application classes/libraries and test fixtures are absent from the packaged JAR. This supersedes the earlier local counts for the current revision; CI and live R2/hosted checks remain unverified. The extra live anonymous smoke could not run because the local backend was stopped; no private session/account was used.


<!-- /coderabbit-ai-generated:v1 -->