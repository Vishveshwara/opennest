<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:e16702f12ae2569329889ff6df6fc6845cc114cc947c963ec7b039e8b15baa77 -->

<!-- coderabbit-ai-generated-section:v1:b864089226616fd19daf68678e42331f325b53be4e73244b167a1d4e1819e138 -->

- Status: V1-V4 unchanged; V3 adds media metadata/owner projection, V4 current contact provenance, V5 expiring Telegram login receipts; V6 adds a contact sender lookup index; V7 adds sandbox reset metadata. Nine application tables/92 columns; six ORM mappings/76 columns. Identity/listing/sole Telegram authentication implemented; Phase 4 interactions implemented, no hosted migration. docs/SCHEMA_CONTRACT.md.
- Phase 1.6 maps all 70 columns inside owning modules' internal.persistence packages; six package-private entities, scalar UUID foreign references, no entity associations/public contracts. Hibernate validate only; generated fields read-only, PropertyEntity.version @Version. After listing updates flush then refresh/reload generated geography/totals; `docs/BACKEND_PERSISTENCE.md`.
- V1 conventions: UUID IDs supplied by application, text enums with CHECKs, timestamptz events, immediate RESTRICT foreign keys, no deletion cascades. Retention/erasure policy remains open.
- Separate production and sandbox databases use the same migrations and distinct runtime credentials.
- Phase 1.8 container proof passed on 30 October 2026; V1/V2 unchanged. Review-fix Java 25 build passed 130 default, 51 schema/persistence, and 23 real PostGIS/Redis isolation tests without failures/errors/skips; `docs/BACKEND_ISOLATION.md`. Container users have migration privileges; restricted production runtime roles remain a deployment prerequisite.
- users: UUID id, full_name, nullable unique +91 phone_number/phone_verified_at pair, role USER, created_at. Telegram OTP completion alone creates/resolves account and persists approved contact. No account/phone reserved by challenge creation; listing publication requires verified contact.
- Owner aggregates: nullable numeric average_rating (NULL with zero reviews), bigint review_count=0 and broker_report_count=0 initially; all review writers maintain them transactionally from owner_reviews, never independently writable by clients.
- `auth_identities`: UUID id, user_id FK, issuer, subject, email, email_verified, created_at; unique (issuer, subject). Email is not an identity key; linking requires reauthentication and proof of both identities.
- Phase 2 auth: opt-in Spring Session Redis in separate stores/namespaces; 30-minute idle/24-hour authenticated absolute lifetime. GET /api/v1/auth/csrf may create an unauthenticated session; POST /api/v1/auth/logout invalidates it; GET /api/v1/users/me reads only the caller. Phase 2 itself added no tables; current Telegram V4/V5 receipts are listed below.
- Provider tokens are not listing API credentials; expired/lost sessions require login again, and unavailable Redis fails closed with 503.
- Unreviewed users have no average rating; do not initialize them with a misleading five-star rating.
- `properties`: UUID id, immutable owner_id FK, title, description, category, flat_rental_mode, whole_flat_available, max_occupants, status, version, city, locality, private address, coordinates, geography point, rent/deposit, amenities, nullable room counts, nearby_landmarks JSONB, timestamps.
- `properties.city`: required, normalized city supplied at creation; no city default or fixed city allowlist; support listings throughout India.
- `properties.monthly_rent`: positive integer INR; PG_HOSTEL per person/month, FLAT ENTIRE_FLAT per property/month, FLAT ROOM per room/month. Derive rentBasis (`PER_PERSON | PER_PROPERTY | PER_ROOM`) from category/mode; clients cannot override it.
- `properties.security_deposit`: nonnegative integer INR with the same person/property/room basis as rent, clearly labeled.
- `properties.bedrooms`: integer >= 0 required for FLAT (0 = studio); NULL for PG_HOSTEL. This is layout/BHK, independent of available-room inventory.
- Category: `FLAT | PG_HOSTEL`; status: `ACTIVE | FULL_OCCUPIED | PAUSED`. Creation defaults to PAUSED draft, allowing its ID to exist before photo uploads; explicit publication validates contact/photos/quota.
- `properties.flat_rental_mode`: required ENTIRE_FLAT or ROOM for FLAT, NULL for PG_HOSTEL; category/mode immutable after creation initially. Enforce mode-appropriate NULL/non-NULL inventory constraints; see `docs/LISTING_MODEL.md`.
- ENTIRE_FLAT requires boolean whole_flat_available and owner-set integer max_occupants > 0; room counters, total_rooms_available, max_sharing_per_room are NULL. ROOM/PG_HOSTEL require whole_flat_available/max_occupants=NULL and non-NULL valid room counters/sharing; total is a stored generated bigint sum, sharing is owner-set per-room capacity.
- `properties.version`: nonnegative optimistic-lock version returned in owner responses; increment on state/inventory writes, reject stale requested versions. Never accept a client-supplied total or publication status through inventory updates.
- Listing ORM updatedAt uses the JVM instant clamped to createdAt when the clock precedes database creation; generated-value mappings and V1/V2 are unchanged. Real persistence regressions cover a future-created row in both deployment modes.
- ROOM/PG_HOSTEL counters >= 0; max_sharing_per_room 1..10; total derived from male + female + shared. ENTIRE_FLAT status derives from whole_flat_available, not a room count.
- Inventory transitions apply only to published listings; updating PAUSED inventory must not republish it.
- Numeric(9,6) coordinates bounded by latitude 6.5..37.5 and longitude 68..97.5 generate geography(Point,4326) location; this is a coarse box, not an India boundary or privacy coarsening.
- Precise stored coordinates support internal geo queries; public search/detail DTOs omit them unless transformed under an explicitly documented coarsening policy. Public owner phone/private address exclusions remain; no schema migration is required for this response-contract clarification.
- GiST geography index; city/status/category/locality/rent and owner/status indexes.
- V2 includes 15 indexes: public status + created_at DESC/id ASC, both monthly_rent directions/id ASC, and matching city-prefixed variants, plus geography/owner/filter/child indexes. Use EXPLAIN on representative multi-city data before additional bedroom/room/rating indexes.
- Pagination/count queries share identical filters; sort tie-breaker is property UUID. Query pagination before image/child hydration to avoid duplicate listings; search semantics in `docs/LISTING_SEARCH.md`.
- `property_images`: UUID id, property_id FK, unique staging upload_object_key, nullable unique final object_key, segment, display_order, upload_expires_at, validation_state (`PENDING | VALIDATED | REJECTED`), created_at. Only VALIDATED has a final key; completion verifies ownership/content/size and final-object immutability.
- Only VALIDATED images satisfy publication segments; staging URLs cannot mutate a validated published object. Repeated completion of the same validated row returns its existing metadata, not another image.
- Segment: `ROOM | HALL | BATHROOM | KITCHEN | EXTERIOR_AMENITY`.
- `contact_reveals`: UUID id, tenant_id FK, composite (property_id, owner_id) FK, generated Kolkata revealed_date from created_at, created_at; unique tenant/property/date, no self-reveal. Composite key targets properties(id, owner_id), preventing mismatched attribution.
- Review/report eligibility uses a historical reveal for the same tenant/property/owner on any date, not only today; no owner reassignment may transfer review history to another user.
- Quota date explicitly uses Asia/Kolkata; PostgreSQL ledger is authoritative, Redis accelerates checks.
- `owner_reviews`: UUID id, composite (property_id, owner_id) FK, reviewer_id FK, smallint rating 1..5, issue_tag, nullable comment_text, is_auto_broker_report, created_at. CHECKs forbid self-review and inconsistent brokerage tag/rating/flag combinations.
- Unique `(reviewer_id, property_id)` per database, excluding the report flag; UUID is immutable across review-to-report conversion.
- Issue tag: `GENUINE_OWNER | ASKED_BROKERAGE | MAINTENANCE_ISSUE | FAKE_PHOTOS | OTHER`.
- Prevent repeated brokerage reports per reviewer/property and self-reviews; enforce owner/property consistency.
- First report inserts or converts only that pair's review to rating=1 / ASKED_BROKERAGE / is_auto_broker_report=true; conversion preserves UUID, created_at, and comment_text. Repeat reports are no-ops.
- Ordinary review creation conflicts on an existing pair; ASKED_BROKERAGE is reserved for the report flow. A normal review cannot overwrite or clear a report.
- All review/report writers lock the owner row and atomically update the review and owner aggregates; review_count counts rows, broker_report_count counts flagged rows, average_rating averages each row once. Conversion changes no row count; retries change no aggregates.
- Deferred WhatsApp challenges: Redis, <= 5 minute TTL, hashed high-entropy token, expected phone, initiating session/action, deduped provider message IDs, atomic one-time completion; no public six-digit bearer login. Final flow requires review before enabling.
- Telegram challenges are private Redis with original-session/realm/expected-phone/chat binding, five-minute TTL, keyed OTP HMACs. Only original-session CSRF completion authenticates/persists contact. V5 id/user_id/session_digest/expires_at receipt is private, expires with challenge and is deleted hourly; no ORM entity.
- Trust badge derived, not independently writable: `STANDARD | TOP_RATED | LOW_RATINGS_BEWARE | BROKERAGE_WARNED | CRITICAL_BROKER_AND_LOW_RATING`.
- Approved/implemented trust precedence: reports >= 1 and average < 3 -> CRITICAL_BROKER_AND_LOW_RATING; else reports >= 1 -> BROKERAGE_WARNED; else reviews >= 2 and average < 3 -> LOW_RATINGS_BEWARE; else reviews >= 3 and average >= 4.5 -> TOP_RATED; else STANDARD. No reviews -> STANDARD with NULL rating.
- Proposed source schema's `is_sandbox` flags are not security boundaries; separate databases replace shared-row routing.
- Five-listing quota requires owner-level transaction serialization, including reactivation; no count-then-insert race.
- Only ACTIVE/FULL_OCCUPIED count toward the five-listing quota; PAUSED drafts do not. Inventory changes keep published listings counted even when whole_flat_available=false or room availability reaches zero.
- Trusted sandbox seeds include historical reveal rows for designated personas so normal review/report eligibility works despite disabled callable contact actions.
- Quota and review aggregate changes need transaction/concurrency tests in their implementation phases.
- V3 image metadata: nullable declared_content_type/declared_byte_size and content_type/byte_size/width/height; all-or-none groups, allowlisted types, 5 MiB and 20 MP checks. Legacy V2 rows remain valid; new service writes populate metadata. Six mappings now cover 76 columns.
- listing_owner_profiles exposes only id/full_name/average_rating/review_count/broker_report_count. Listing reads may join it for summaries and rating sorts; no contacts or provider fields. Schema comparisons require its presence and include its definition; drift/missing-view regressions run against both targets.
- Phase 3 service enforcement: owner-row lock before listing/image locks; 20 retained/5 published listings, image/rate limits, exact versions, PAUSED preservation, explicit publication/contact/photo gates. Search items/count/image hydration use one repeatable-read snapshot.
- V4 contact_verifications: seven JDBC-owned columns, id challenge UUID PK, user_id unique users FK RESTRICT, phone_number exact +91, positive bigint telegram_user_id, 64-character hex session_digest, method TELEGRAM_ACCOUNT_CONTACT, verified_at. V4-era seven application tables/83 columns; current V5 totals eight tables/87 columns with six ORM mappings/76 columns unchanged.
- Login completion advisory-locks challenge/sender then locks user; account/identity/contact/V4 provenance/V5 receipt commit atomically. Phone uniqueness never merges users. Rollback permits claim retry; durable original-session receipt retries survive Redis loss within challenge lifetime. Unambiguous legacy Telegram receipts preserve existing accounts; other legacy recovery explicit. Keep current contact provenance for verified account lifetime, no history/plaintext code/vCard/username. docs/TELEGRAM_HANDOFF.md.


- V7 adds JDBC-owned sandbox_state: singleton smallint id=1, positive seed_version/generation, nonnegative Kolkata six-hour reset_interval and reset_at timestamptz. Five columns; no rows seeded by migration. V1-V6 unchanged.
- Sandbox reset locks exclusively, deletes children before parents and atomically reseeds/increments generation; normal operations hold shared locks. Locking the metadata row rejects repeatable-read snapshots predating reset.

<!-- /coderabbit-ai-generated:v1 -->
