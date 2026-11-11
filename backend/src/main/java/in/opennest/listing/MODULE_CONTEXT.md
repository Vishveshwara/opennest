# Listing module

- Status: Phase 3 draft/owner/public APIs, mode-aware inventory, publication quotas, nationwide search and private R2 uploads implemented. Verification/configuration: docs/LISTINGS_HANDOFF.md.
- Own future listing lifecycle, mode-aware inventory, images, publication quota, and nationwide listing queries.
- Phase 1.5 V1/V2 creates tables/indexes; Phase 1.6 adds package-private PropertyEntity/PropertyImageEntity under internal.persistence. Scalar owner/property UUIDs, no associations/cascades; Hibernate validation only. Contracts: `docs/SCHEMA_CONTRACT.md`, `docs/BACKEND_PERSISTENCE.md` from repository root.
- Geography/JTS Point and room totals are read-only, retrieved on insert; flush then refresh/reload after updates. @Version protects dirty ORM writes; caller-version checks, unchanged-inventory increments, no-op publication, and quota/publication gates remain Phase 3 service work.
- Allowed module dependencies: common and identity public contracts only. CurrentAccount resolves caller UUID; IdentityAccounts supplies verified-contact eligibility and transactional owner locks; TrustBadge derives warnings. No cross-module entity access.
- Follow `docs/LISTING_MODEL.md` and `docs/LISTING_SEARCH.md` from the repository root before implementing behavior.
- Preserve PAUSED drafts, explicit publication gates, three derived rent bases, and owner-configured capacity independent of vacancy/BHK.
- No city default or city allowlist. Public responses must omit private address, owner contact, and precise coordinates.
- Native JDBC listing writes use owner -> listing -> image lock order, explicit version increments/CAS, and reload generated totals. Existing internal ORM mappings remain Hibernate-validated; V3 adds six mapped image metadata columns and the identity-owned safe owner SQL view.
- Search filters/count/page/image hydration share REPEATABLE_READ; pagination precedes child hydration. Public DTOs exclude contacts/address/coordinates/distances. Owner-only views include version and private listing fields. Every listing response is no-store.
- Limits: 20 retained/5 published listings per owner; 12 images/listing; 60 reservations/rolling 24h; 5 MiB and 20 MP; 10-minute PUT/5-minute GET URLs. Completion conditionally reads staging, strips metadata through fresh pixels, and attaches a distinct final object idempotently.
- Separate private primary/sandbox R2 buckets/credentials; disabled storage fails upload operations with 503, no runtime fake fallback. Hourly cleanup removes old staging/orphan finals, preserves attached objects/drafts, and retains rate-accounting rows seven days.
- Overpass disabled; no text/price/image edit or listing deletion routes. Telegram-only login supplies approved verified contact; legacy unverified accounts cannot publish. Test fixtures use fabricated contacts and do not bypass runtime publication gates.
- Review corrections: body limits use decoded context-relative paths and configured CORS before validation regardless of security-filter order; JPEG finalization applies all EXIF orientations before stripping metadata; staging reads abort the stream at the total deadline.


- TelegramContactIT now exercises every listing HTTP route using actual Telegram-flow session cookies in all three rental modes, with controlled test-only storage and genuine image validation. Ownership, CSRF, privacy, stale versions and logout remain enforced; docs/API_VERIFICATION.md. Live R2 remains unverified.

- Phase 4 exposes public ListingInteractions owner/publication lookup. SandboxBoundary guards transactions and rejects old reset generations/snapshots. Bundled seeded fixture images use sandbox-only no-store image routes; normal R2 validation/publication gates remain.
