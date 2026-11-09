# OpenNest Phase 3 listing handoff

Phase 3 implements listing drafts, owner retrieval, direct image uploads, explicit publication/pause, mode-aware inventory, and nationwide search. Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1, PostgreSQL/PostGIS, and backend-owned sessions remain the stack. Hosting and live R2 readiness are not established by this implementation.

## Routes and visibility

All listing responses use `Cache-Control: no-store`. Phase 5 retains no-store for every listing fetch and response; shared listing caching needs a separate approved policy and invalidation change. Google-disabled deployments permit public listing reads and exact-origin public-read CORS. Authentication and owner mutations still require configured Google login. Unknown routes remain denied.

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

Phone verification remains deferred. Google sign-in leaves the contact unset. Publication returns 403 with a fixed explanation that separately verified +91 contact is required and verification is not yet available. Successful publication tests use isolated test-only database fixtures; no runtime bypass or verification endpoint exists.

| Limit | Value |
| --- | --- |
| Retained listings per owner, all statuses | 20 |
| Validated or unexpired PENDING images per listing | 12 |
| Image reservations per owner, rolling 24 hours | 60 |
| Upload bytes | 1 through 5 MiB |
| Image types | JPEG, PNG, WebP; actual signature and decoding must agree |
| Decoded pixels | At most 20 million; animation rejected |
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

The browser performs the PUT directly. Apply the returned signed headers; the browser supplies Content-Length from the exact upload body. Configure bucket CORS for the exact frontend origin, PUT/GET/HEAD as needed, Content-Type and If-None-Match, and ETag exposure. CORS, scoped bucket credentials, and bucket lifecycle rules still require provider configuration and a live smoke test before exposure.

Completion checks the actual size, conditionally reads the staging object's ETag, decodes bounded bytes, rejects animation, and re-encodes fresh pixels without source metadata. Output is JPEG for opaque images and PNG for alpha images. JPEG EXIF orientation, including mirrors and rotations, is applied to the pixels with the resulting dimensions before metadata is discarded. It writes a unique final object using If-None-Match, then rechecks ownership, state, and expiry under database locks before attaching it. Validated completion retries return the existing image metadata with fresh read URLs. A later staging write cannot affect the final object.

Two processing permits and two decoder threads bound concurrent work. Storage calls have 20-second overall and 10-second attempt timeouts. Staging HEAD, GET, and actual body consumption share the 20-second total budget; expiry aborts the response stream even when bytes keep arriving. Decoding has a 20-second deadline; final attachment also checks an overall two-minute deadline. Decoder threads remain bounded even if a decoder ignores interruption. Upload/download URLs, bytes, addresses, and account/contact data are excluded from logs and DTO diagnostic strings.

Hourly cleanup deletes staging objects and unattached final objects older than 24 hours. It marks PENDING reservations rejected after expiry plus 24 hours and deletes rejected reservation rows after seven days. Attached final objects and retained drafts remain. The completion deadline prevents old in-flight work from attaching objects eligible for orphan cleanup. Failed or duplicate finalization can leave temporary private objects until cleanup. Only dedicated selected buckets and server-generated `listings/` keys are scanned.

Both buckets remain private. Public reads issue five-minute signed GET URLs only for validated images on visible listings. Pausing prevents new public URLs; an issued URL can remain usable until expiry. No public bucket, CDN, cache purge integration, or media hostname is provisioned.

AWS SDK v2 `2.57.11` and TwelveMonkeys WebP decoder `3.15.3` were resolved from Maven Central and compiled under Java 25. The adapter follows [Cloudflare's Java SDK guidance](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/), including disabled chunked encoding. The tiny positive WebP test fixture is from [TwelveMonkeys small_1x1.webp at tag 3.15.0](https://github.com/haraldk/TwelveMonkeys/blob/twelvemonkeys-3.15.0/imageio/imageio-webp/src/test/resources/webp/small_1x1.webp); its BSD notice is preserved in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Migration, verification, and remaining gates

V3 adds the public owner projection and six nullable image metadata columns with shape/size constraints. Nullable metadata preserves pre-service V2 rows. New reservations and completions always populate metadata. V1/V2 contents remain unchanged; Flyway and Hibernate validation remain mandatory. Tests cover both clean migration and a populated V2 upgrade.

Run from the repository root using Java 25 and Linux amd64 Docker:

```powershell
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pschema-integration,isolation-integration' clean verify
python .github/scripts/verify_backend.py --reports
python -m unittest discover --start-directory .github/scripts --pattern '*_test.py'
```

The required suites include listing parsing/image validation, real SDK calls against a local HTTP storage fixture, and listing lifecycle tests on separate real PostGIS/Redis pairs. Query-plan evidence is written to `backend/target/listing-query-plans.txt` using 3,000 fabricated listings across five Indian cities. Report verification rejects missing/skipped suites and packaged test fixtures. On 9 November 2026, the clean local Java 25/Linux amd64 Docker gate passed all 450 tests after review corrections: 337 default, 53 schema/persistence, 24 datastore-isolation, 19 identity, and 17 listing integration cases. There were no failures, errors, or skips. Report/artifact validation, all six Python checks, context limits, and whitespace checks passed. Query plans used the existing nationwide/city rent indexes and recorded a spatial query; no additional index was added. No CI result for this change is claimed.

Live R2 bucket/CORS/credential checks and real-Google repeated-login/denial/logout remain manual gates. No provider resources, secrets, merge, deployment, or public exposure are part of this phase. Overpass is disabled and nearbyLandmarks is empty. Text/price edits, image replacement/removal, deletion, phone verification, interactions, and recruiter personas remain later work. The 20-retained-listing cap does not delete old drafts; owners at that cap cannot create another listing until a deletion contract is implemented.

Earlier phase evidence was inspected during planning: [Backend](https://github.com/Vishveshwara/opennest/actions/runs/42574209529) and [Foundation](https://github.com/Vishveshwara/opennest/actions/runs/42574209532) passed for `7c3bba3`. Those results cover the earlier revision, not these listing changes.

Review regressions verify every documented listing path/method, reject an added diagnostic mapping, fingerprint the owner view, check exact IDs for all six sorts and UUID ties across pages, and commit a concurrent update between count and page queries during a standalone search. Legacy migrated null-metadata completion returns 409. Encoded context-relative listing paths retain body limits and CORS at a later security-filter order. Tests cover Google-disabled POST/PATCH denial with valid CSRF/authentication and origin-specific GET preflights, all eight EXIF orientations and pixel colors, and continuously trickling storage bodies sharing the HEAD/GET deadline. CI now requires the query-plan file before the unchanged report upload.
