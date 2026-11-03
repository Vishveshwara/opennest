# OpenNest database schema contract

Status: Phase 1.4 design implemented by Phase 1.5's V1 schema/V2 indexes and Phase 1.6's module-owned mappings/Hibernate validation, verified on isolated PostgreSQL 17/PostGIS 3.5 targets on 22 October 2026. See [migration execution](BACKEND_MIGRATIONS.md) and [persistence evidence](BACKEND_PERSISTENCE.md). Hosted databases are not migrated; repositories, triggers, and feature APIs remain unimplemented.

The [listing model](LISTING_MODEL.md), [search contract](LISTING_SEARCH.md), and [authentication decisions](AUTHENTICATION.md) remain authoritative for feature behavior. Production and sandbox use identical migrations in separate databases. No row-level sandbox flag or request-selected database is introduced.

## Storage conventions

- Six application tables live in `public`: `users`, `auth_identities`, `properties`, `property_images`, `contact_reveals`, and `owner_reviews`. Flyway's history and PostGIS extension objects are additional infrastructure objects.
- Each table has `id uuid PRIMARY KEY`, NOT NULL, with no database default. The application supplies a random UUID before insertion. IDs never change; fixture IDs may be deterministic only in isolated sandbox/test databases.
- In the tables below, **required** means `NOT NULL`; **nullable** means SQL NULL is allowed. **None** means no explicit default. Nullable fields omitted on insert become NULL. Generated columns have no separate default and cannot accept a caller's value.
- Enum-like values use `text` plus named `CHECK` constraints, mapped as strings later. Do not introduce PostgreSQL enum types or ordinal persistence. Every required enum also has `NOT NULL`.
- Required free text has `CHECK (length(btrim(column)) > 0)`. Nullable text, when present, has the same nonblank check. This rejects empty/ASCII-space-only values; Unicode whitespace handling and future content/length limits belong to request validation. Technical keys are opaque, case-sensitive, and never silently trimmed or case-folded.
- Use `COLLATE "C"` for `issuer`, `subject`, `phone_number`, storage object keys, and normalized `city`/`locality`. City/locality equality follows the application's shared NFKC, whitespace-collapse, trim, and locale-independent uppercase normalization. SQL nonblank checks do not prove canonical normalization. No city default, catalog, or allowlist exists.
- Event timestamps use `timestamp with time zone` (`timestamptz`), required, default `statement_timestamp()` unless noted. Application mappings use `Instant`. `updated_at` also defaults on insert; services explicitly refresh it on accepted mutations. No update trigger is planned.
- Use whole-INR `integer` prices, `integer` room counters/capacities/bedrooms, and `bigint` totals, versions, and aggregate counts. Integer storage bounds are technical limits, not new commercial quotas. Reject fractional API integers before SQL conversion.
- All foreign keys are immediate, `NOT DEFERRABLE`, `ON UPDATE RESTRICT ON DELETE RESTRICT`. No cascade, set-null action, automatic anonymization, or soft-delete field is introduced. Deletion/retention workflows require a separate design before real personal data is collected.
- Name keys/checks using `pk_<table>`, `uq_<table>_<purpose>`, `fk_<table>_<reference>`, and `ck_<table>_<purpose>`; use `ix_` for nonunique indexes. Keep each identifier within PostgreSQL's 63-byte limit.

PostgreSQL accepts a CHECK whose result is NULL; mode/aggregate checks must therefore use explicit NULL tests as specified below. Cross-row rules need transactions or keys, rather than CHECK subqueries. See [PostgreSQL constraints](https://www.postgresql.org/docs/17/ddl-constraints.html).

## Identity-owned tables

### users

| Column | SQL type | Nullability | Default | Database rule |
| --- | --- | --- | --- | --- |
| `id` | `uuid` | required | None | Primary key |
| `full_name` | `text` | required | None | Nonblank |
| `phone_number` | `varchar(13)` | nullable | None | Unique; when present matches `^[+]91[0-9]{10}$` |
| `phone_verified_at` | `timestamptz` | nullable | None | Paired with phone; no automatic verification timestamp |
| `role` | `text` | required | `'USER'` | Initial allowed value: `USER` |
| `average_rating` | `numeric` | nullable | None | NULL iff review_count=0; otherwise 1..5 |
| `review_count` | `bigint` | required | `0` | >= 0 |
| `broker_report_count` | `bigint` | required | `0` | 0..review_count |
| `created_at` | `timestamptz` | required | `statement_timestamp()` | Account creation instant |

- `ck_users_verified_phone`: `(phone_number IS NULL AND phone_verified_at IS NULL) OR (phone_number IS NOT NULL AND phone_verified_at IS NOT NULL)`. The service stores both only after separate successful verification. A format check and timestamp are not proof of verification; Google login must leave both NULL.
- `uq_users_phone_number` uses ordinary UNIQUE semantics, allowing multiple NULL phones and forbidding duplicate non-NULL contacts. Contact changes, releases, or identity linking by phone are not implemented or authorized by this constraint.
- `ck_users_rating_state`: `(review_count = 0 AND average_rating IS NULL) OR (review_count > 0 AND average_rating IS NOT NULL AND average_rating BETWEEN 1 AND 5)`. Count/range checks also apply. No default five-star rating.
- `average_rating` deliberately has no fixed decimal scale. Later writers set it from PostgreSQL `AVG(rating)` in the same transaction as counts; presentation rounding must not overwrite the stored aggregate. Cross-table agreement is a service invariant, not proven by these row checks.
- The previous index named `role` without defining values. `USER` is the initial account label; an account can both own listings and seek accommodation. Ownership/reviewer permissions depend on authenticated IDs and row relationships, not mutually exclusive OWNER/TENANT labels. No admin/moderator privilege or endpoint is introduced; adding privileged roles requires a separate authorization design.
- Trust badges are derived, never stored. Their proposed thresholds still require maintainer confirmation in Phase 2.

### auth_identities

| Column | SQL type | Nullability | Default | Database rule |
| --- | --- | --- | --- | --- |
| `id` | `uuid` | required | None | Primary key |
| `user_id` | `uuid` | required | None | FK to users(id) |
| `issuer` | `text` | required | None | Nonblank, case-sensitive |
| `subject` | `text` | required | None | Nonblank, case-sensitive |
| `email` | `text` | nullable | None | Nonblank when present; not unique |
| `email_verified` | `boolean` | required | `false` | Cannot be true when email is NULL |
| `created_at` | `timestamptz` | required | `statement_timestamp()` | Link creation instant |

- `uq_auth_identities_issuer_subject` is UNIQUE(issuer, subject). Store the validated provider issuer and subject without email-based substitution. Phase 2 must validate the accepted Google issuer consistently before lookup/insert; arbitrary issuer strings are not trusted because they fit this table.
- `ck_auth_identities_email_claim`: `email IS NOT NULL OR email_verified = false`. Missing verification claims remain false. Email is provider metadata, never a unique identity or automatic account-link key. [OpenID Connect claim stability](https://openid.net/specs/openid-connect-core-1_0.html#ClaimStability) defines issuer/subject as the stable identity pair.
- One user may have multiple explicitly linked identities. No uniqueness on user_id or user_id/issuer is added. Linking still requires reauthentication and proof of both identities; deleting a link is not exposed yet.
- Tokens, sessions, OAuth state, and deferred phone challenges do not belong in these tables. Planned sessions/challenges use the deployment's separate Redis instance.

## Listing-owned tables

### properties

| Column | SQL type | Nullability | Default | Database rule |
| --- | --- | --- | --- | --- |
| `id` | `uuid` | required | None | Primary key |
| `owner_id` | `uuid` | required | None | FK to users(id); immutable through services |
| `title` | `text` | required | None | Nonblank |
| `description` | `text` | required | None | Nonblank |
| `category` | `text` | required | None | FLAT or PG_HOSTEL |
| `flat_rental_mode` | `text` | nullable | None | ENTIRE_FLAT or ROOM when applicable |
| `status` | `text` | required | `'PAUSED'` | ACTIVE, FULL_OCCUPIED, or PAUSED |
| `version` | `bigint` | required | `0` | >= 0 |
| `city` | `text` | required | None | Nonblank normalized value; no default |
| `locality` | `text` | required | None | Nonblank normalized value |
| `private_address` | `text` | required | None | Nonblank; never public |
| `latitude` | `numeric(9,6)` | required | None | 6.5..37.5 inclusive |
| `longitude` | `numeric(9,6)` | required | None | 68..97.5 inclusive |
| `location` | `geography(Point,4326)` | required | Generated stored | Derived from longitude/latitude |
| `monthly_rent` | `integer` | required | None | > 0, whole INR |
| `security_deposit` | `integer` | required | None | >= 0, whole INR; explicit zero allowed |
| `bedrooms` | `integer` | nullable | None | Required >= 0 for FLAT; NULL for PG_HOSTEL |
| `has_wifi` | `boolean` | required | `false` | Explicit true/false |
| `has_gym` | `boolean` | required | `false` | Explicit true/false |
| `has_swimming_pool` | `boolean` | required | `false` | Explicit true/false |
| `whole_flat_available` | `boolean` | nullable | None | Required only for ENTIRE_FLAT |
| `max_occupants` | `integer` | nullable | None | Required > 0 only for ENTIRE_FLAT |
| `male_rooms_available` | `integer` | nullable | None | Required >= 0 only for ROOM/PG_HOSTEL |
| `female_rooms_available` | `integer` | nullable | None | Required >= 0 only for ROOM/PG_HOSTEL |
| `shared_rooms_available` | `integer` | nullable | None | Required >= 0 only for ROOM/PG_HOSTEL |
| `total_rooms_available` | `bigint` | nullable | Generated stored | Sum of applicable room counters; NULL for ENTIRE_FLAT |
| `max_sharing_per_room` | `integer` | nullable | None | Required 1..10 only for ROOM/PG_HOSTEL |
| `nearby_landmarks` | `jsonb` | required | `'[]'::jsonb` | jsonb_typeof(value) = 'array' |
| `created_at` | `timestamptz` | required | `statement_timestamp()` | Creation instant, not publication time |
| `updated_at` | `timestamptz` | required | `statement_timestamp()` | >= created_at |

`uq_properties_id_owner` is UNIQUE(id, owner_id), in addition to the primary key. Interaction tables reference that pair so stored owner attribution cannot disagree with its property. This extra unique key is intentional.

`ck_properties_mode_inventory` admits exactly these complete branches. Each required nullable-column condition must include `IS NOT NULL`, in addition to its value/range test:

| Branch | Required values | Required NULL values |
| --- | --- | --- |
| FLAT / ENTIRE_FLAT | category=FLAT; flat_rental_mode explicitly non-NULL and ENTIRE_FLAT; bedrooms non-NULL >=0; whole_flat_available non-NULL; max_occupants non-NULL >0 | male/female/shared room counters; max_sharing_per_room |
| FLAT / ROOM | category=FLAT; flat_rental_mode explicitly non-NULL and ROOM; bedrooms non-NULL >=0; each room counter non-NULL >=0; max_sharing_per_room non-NULL in 1..10 | whole_flat_available; max_occupants |
| PG_HOSTEL | category=PG_HOSTEL; each room counter non-NULL >=0; max_sharing_per_room non-NULL in 1..10 | flat_rental_mode; bedrooms; whole_flat_available; max_occupants |

The stored total expression is `CASE WHEN category = 'FLAT' AND flat_rental_mode = 'ENTIRE_FLAT' THEN NULL ELSE male_rooms_available::bigint + female_rooms_available::bigint + shared_rooms_available::bigint END`. Cast before adding to avoid integer overflow. No room-counter or capacity defaults disguise omitted input. The mode constraint makes all other branches invalid.

`ck_properties_publication_inventory` permits PAUSED with any otherwise valid inventory. For an ENTIRE_FLAT it permits ACTIVE only with whole_flat_available=true, and FULL_OCCUPIED only with false. For ROOM/PG_HOSTEL it permits ACTIVE only with total_rooms_available>0, and FULL_OCCUPIED only with total_rooms_available=0. Express this with a CASE over the already validated mode, with the resulting predicate required `IS TRUE`, so NULL cannot admit a published row.

The stored geography expression is `ST_SetSRID(ST_MakePoint(longitude::double precision, latitude::double precision), 4326)::geography`. Longitude is X, latitude is Y, as specified by [ST_MakePoint](https://postgis.net/docs/ST_MakePoint.html). Use the stored numeric values as the single coordinate source. Reject out-of-bound request coordinates before six-decimal persistence rounding. The coarse box is not strict India geofencing, and six decimal places are not a public privacy transformation.

PostgreSQL 17 supports [stored generated columns](https://www.postgresql.org/docs/17/ddl-generated-columns.html) with immutable row-local expressions. PostGIS 3.5's [geometry functions](https://github.com/postgis/postgis/blob/3.5.0/postgis/postgis.sql.in) and [geography cast](https://github.com/postgis/postgis/blob/3.5.0/postgis/geography.sql.in) declare the relevant operations immutable. Phase 1.5 proved these expressions on PostgreSQL 17.11/PostGIS 3.5.3; execution against the Docker image remains unverified locally. Phase 1.6 maps generated fields read-only with insert-time retrieval; flush then refresh/reload listing geography/totals after updates.

Currency, rent period, and rentBasis are not writable/stored columns: derive INR/MONTH and PER_PERSON, PER_PROPERTY, or PER_ROOM from category/mode. Deposits use that same basis. Capacities do not multiply prices or imply current occupancy; bedrooms never substitute for vacancy.

Landmarks are optional enrichment: an empty array is valid and an outage must not block saving. SQL enforces the array container only. Phase 3 defines/validates item projections and limits before using an approved provider; do not persist raw provider responses or embed private contact data. Public DTOs always omit private_address and precise coordinates. They may return transformed map coordinates only under a separately approved coarsening policy; until then omit map coordinates.

### property_images

| Column | SQL type | Nullability | Default | Database rule |
| --- | --- | --- | --- | --- |
| `id` | `uuid` | required | None | Primary key, reserved before presign |
| `property_id` | `uuid` | required | None | FK to properties(id) |
| `upload_object_key` | `text` | required | None | Nonblank, unique staging key |
| `object_key` | `text` | nullable | None | Nonblank when present, unique final key |
| `segment` | `text` | required | None | ROOM, HALL, BATHROOM, KITCHEN, or EXTERIOR_AMENITY |
| `display_order` | `integer` | required | `0` | >= 0 |
| `upload_expires_at` | `timestamptz` | required | None | > created_at; service chooses bounded expiry |
| `validation_state` | `text` | required | `'PENDING'` | PENDING, VALIDATED, or REJECTED |
| `created_at` | `timestamptz` | required | `statement_timestamp()` | Reservation creation instant |

- `uq_property_images_upload_key` and `uq_property_images_object_key` enforce uniqueness separately. Multiple NULL final keys are allowed. Staging/final namespace separation and storage permissions are service/deployment responsibilities; these two unique keys alone do not prove separation across the columns.
- `ck_property_images_final_key`: VALIDATED requires a non-NULL final object_key different from upload_object_key; PENDING/REJECTED require object_key=NULL. The service validates bytes/type/size/ownership/expiry and creates an immutable final object before setting VALIDATED. Database state alone cannot prove object existence or content safety.
- Never expose staging credentials or staging URLs as published images. Store object keys, not presigned URLs or tokens. Only VALIDATED records contribute publication images.
- Multiple images per segment and tied display_order values are permitted; read order is display_order ASC, id ASC. No unique segment/order constraint or invented upload-count limit is added.
- Repeated completion returns the existing validated row without another insert. Image deletion, replacement, rejection cleanup, file limits, and expiry duration require Phase 3 contracts; the schema does not grant those operations.

## Interaction-owned tables

### contact_reveals

| Column | SQL type | Nullability | Default | Database rule |
| --- | --- | --- | --- | --- |
| `id` | `uuid` | required | None | Primary key |
| `tenant_id` | `uuid` | required | None | FK to users(id); seeking account, not a global role |
| `property_id` | `uuid` | required | None | Part of composite property/owner FK |
| `owner_id` | `uuid` | required | None | (property_id, owner_id) FK to properties(id, owner_id) |
| `revealed_date` | `date` | required | Generated stored | Kolkata date of created_at |
| `created_at` | `timestamptz` | required | `statement_timestamp()` | Ledger insertion instant |

- `uq_contact_reveals_daily_pair` is UNIQUE(tenant_id, property_id, revealed_date). `ck_contact_reveals_not_self` requires tenant_id <> owner_id.
- The stored date expression is `(created_at AT TIME ZONE 'Asia/Kolkata')::date`. It never depends on the session/server timezone or a client-supplied date. Historical sandbox fixtures set created_at, not the generated date.
- Phase 4 serializes reveal writers on the seeking user's row. After acquiring that lock, capture one server `clock_timestamp()` instant, derive its Kolkata day for existing-pair/quota lookup, and use that same instant as created_at on insertion. Do not use the transaction-start timestamp after waiting on a lock across midnight. If midnight passes after capture, retain the captured instant consistently for this operation. The default covers inserts without an explicit timestamp; clients never supply either field.
- Ten distinct listings/day is a transactional count rule, not enforced by uniqueness alone. Existing-pair retries consume no additional slot. The database is authoritative; Redis loss does not reset quota. Never store a phone snapshot or contact response in this ledger.

The explicit-zone expression follows [PostgreSQL AT TIME ZONE](https://www.postgresql.org/docs/17/functions-datetime.html#FUNCTIONS-DATETIME-ZONECONVERT); the [PostgreSQL 17 function catalog](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/include/catalog/pg_proc.dat) marks the text-zone/timestamptz conversion immutable. Phase 1.5 verified generated-date behavior under different session timezones and at Kolkata midnight.

### owner_reviews

| Column | SQL type | Nullability | Default | Database rule |
| --- | --- | --- | --- | --- |
| `id` | `uuid` | required | None | Immutable primary key |
| `property_id` | `uuid` | required | None | Part of composite property/owner FK |
| `owner_id` | `uuid` | required | None | (property_id, owner_id) FK to properties(id, owner_id) |
| `reviewer_id` | `uuid` | required | None | FK to users(id) |
| `rating` | `smallint` | required | None | 1..5 |
| `issue_tag` | `text` | required | None | GENUINE_OWNER, ASKED_BROKERAGE, MAINTENANCE_ISSUE, FAKE_PHOTOS, or OTHER |
| `comment_text` | `text` | nullable | None | Nonblank when present |
| `is_auto_broker_report` | `boolean` | required | `false` | Linked to brokerage tag/rating |
| `created_at` | `timestamptz` | required | `statement_timestamp()` | Original creation instant; preserved on conversion |

- `uq_owner_reviews_reviewer_property` is UNIQUE(reviewer_id, property_id), with no report flag in the key. `ck_owner_reviews_not_self` requires reviewer_id <> owner_id.
- `ck_owner_reviews_report_shape` permits either `(is_auto_broker_report = true AND rating = 1 AND issue_tag = 'ASKED_BROKERAGE')` or `(is_auto_broker_report = false AND issue_tag <> 'ASKED_BROKERAGE')`. This rejects inconsistent stored reports, but authorization to report still belongs to the service.
- A prior contact_reveals row for the same reviewer/property/owner on any historical date is required by the service. No foreign key to a daily reveal is added: reveal eligibility is not restricted to today or to one chosen ledger UUID.
- Ordinary review creation is create-only; an existing pair conflicts. A report inserts if absent or converts that same row in place. Preserve id, created_at, reviewer/property/owner, and existing comment_text. A newly inserted report uses automated allegation text supplied by the service; no database text default is introduced.
- A repeated report changes nothing. Ordinary reviews cannot overwrite or clear a report. These transition/immutability rules are service invariants; a row CHECK cannot inspect the preceding version of a row.
- All review/report writers lock the owner row, mutate the review, and recompute review_count, broker_report_count, and average_rating from owner_reviews in one transaction. Conversions keep row count unchanged; retries keep all aggregates unchanged. No automatic listing hiding follows a rating/report.

## Keys, deletion, and indexes for V1/V2

The exact FK graph is below. Every edge uses RESTRICT for updates/deletes, without deferral. Composite FKs replace a separate property_id FK in interaction tables; ownership already reaches users through properties.owner_id.

| Referencing table/columns | Referenced key |
| --- | --- |
| auth_identities(user_id) | users(id) |
| properties(owner_id) | users(id) |
| property_images(property_id) | properties(id) |
| contact_reveals(tenant_id) | users(id) |
| contact_reveals(property_id, owner_id) | properties(id, owner_id) |
| owner_reviews(reviewer_id) | users(id) |
| owner_reviews(property_id, owner_id) | properties(id, owner_id) |

Deleting a referenced user/property must fail instead of silently erasing identities, image reservations, quota history, or review eligibility. This is a conservative initial FK action, not an approved retention policy. No deletion endpoint, purge job, or retention period is defined. Future erasure/anonymization must coordinate database history and object storage under the pending policy.

Primary/unique keys in V1 create their own indexes; do not duplicate them. Phase 1.5's V2 adds these nonunique indexes:

| Index | Columns, order, and predicate | Purpose |
| --- | --- | --- |
| `ix_auth_identities_user` | (user_id) | Account identity lookup/FK |
| `ix_properties_owner_status` | (owner_id, status) | Owner listings and published quota |
| `ix_properties_city_filters` | (city, status, category, locality, monthly_rent) | City-filtered search baseline |
| `ix_properties_location` | USING GiST(location) | Geography radius queries |
| `ix_properties_newest` | (status, created_at DESC, id ASC), public predicate | Nationwide NEWEST |
| `ix_properties_rent_asc` | (status, monthly_rent ASC, id ASC), public predicate | Nationwide RENT_ASC |
| `ix_properties_rent_desc` | (status, monthly_rent DESC, id ASC), public predicate | Nationwide RENT_DESC |
| `ix_properties_city_newest` | (city, status, created_at DESC, id ASC), public predicate | City NEWEST |
| `ix_properties_city_rent_asc` | (city, status, monthly_rent ASC, id ASC), public predicate | City RENT_ASC |
| `ix_properties_city_rent_desc` | (city, status, monthly_rent DESC, id ASC), public predicate | City RENT_DESC |
| `ix_property_images_property_order` | (property_id, display_order, id) | Ordered image hydration/FK |
| `ix_contact_reveals_tenant_day` | (tenant_id, revealed_date, property_id) | Daily distinct-listing quota |
| `ix_contact_reveals_property_owner` | (property_id, owner_id) | Composite FK/history lookup |
| `ix_owner_reviews_property_owner` | (property_id, owner_id) | Composite FK/property reviews |
| `ix_owner_reviews_owner` | (owner_id) | Owner aggregate recomputation |

The public predicate means exactly `WHERE status IN ('ACTIVE', 'FULL_OCCUPIED')`. Explicit status filters still apply to search queries. Separate rent directions preserve the final id ASC tie-breaker; reversing a B-tree reverses every key's direction. See [PostgreSQL index ordering](https://www.postgresql.org/docs/17/indexes-ordering.html). Geography [ST_DWithin](https://postgis.net/docs/ST_DWithin.html) uses metres and can use a spatial index.

Do not promise these indexes satisfy every filter/sort combination. Phase 3 records EXPLAIN plans for nationwide/city queries, paginates property IDs before image hydration, and obtains items/counts from one consistent snapshot. Add bedroom, room, sharing, rating, JSONB, or additional covering indexes only when representative measurements justify them.

## Enforcement boundary and handoff

| Invariant | V1 database protection | Later service/transaction responsibility |
| --- | --- | --- |
| Identity/contact | Unique issuer/subject and non-NULL phones; phone/timestamp shape | OIDC validation, linking proof, actual phone verification, authorized private access |
| Ownership | User FKs; composite property/owner FKs; no self-reveal/review | Authenticated owner/reviewer IDs; immutable property owner/category/mode; no client ID assertions |
| Draft/publication | Default PAUSED; valid mode/inventory; published status agrees with vacancy | Only draft creation; contact/photos gates and serialized five-listing quota at publish/reactivation |
| Inventory/version | Nonnegative version; generated totals; row consistency | Exact expected-version conditional update; accepted inventory increments; PAUSED preserved; current-version publish/pause no-op; stale request 409 |
| Images | Required keys, state/final-key shape, uniqueness, property FK | Ownership, expiry/content validation, immutable final object, required publication segments, cleanup/abuse limits |
| Reveals | Unique daily pair; generated Kolkata day; owner FK; no self-reveal | Ten/day under user-row lock, retry behavior, contact authorization, no phone logging/caching |
| Reviews/reports | Unique pair, rating/tag/report shape, owner FK, no self-review | Historical reveal; create-only ordinary reviews; preserved conversion identity/text; idempotent reports |
| Aggregates | Nonnegative consistent count/rating shape | All writers serialize on owner; recompute from reviews atomically; no client aggregate writes |
| Privacy/isolation | Separate databases share this schema; no sandbox flags | Separate credentials/stores/sessions/caches; authorized DTOs; sandbox contact actions disabled |
| Lifecycle | RESTRICT FKs prevent implicit dependency deletion | No delete/retention flow until policy is approved; never hide listings automatically |

No triggers, cross-table CHECK functions, runtime database roles, or cross-module Java contracts are added in Phase 1.4. Trusted database writes can bypass service-only rules; transaction tests and restricted deployment privileges remain necessary.

Phase 1.5's real PostgreSQL 17/PostGIS 3.5 checks cover the following schema acceptance cases; execution details and remaining infrastructure checks are in [the migration record](BACKEND_MIGRATIONS.md):

- Apply V1 to an empty database and re-run Flyway without changes; verify extension/schema metadata, all six tables, types, defaults, generated expressions, keys, checks, and indexes.
- Accept all three valid rental branches, studio FLAT, zero applicable vacancies, PAUSED drafts, all published states with matching vacancy, multiple phone-less users, and unrated owners.
- Reject every incomplete/NULL-leaking mode branch, wrong capacity/counter fields, negative inputs, inconsistent published status, and direct assignments to generated columns. Test room totals beyond integer range without overflow.
- Verify coordinate edges, longitude/latitude orientation, generated-point updates, coarse-box rejections, and metre-based spatial behavior. Do not claim strict India geofencing.
- Verify unique identity/phone/review/daily-pair keys; duplicate nullable phones/final keys remain valid. Reject mismatched property owners, self-reveals/reviews, invalid report shape, bad image finalization state, and referenced-user/property deletion.
- Verify Kolkata dates around 18:30 UTC and under changed session timezones, including historical fixtures. Verify object expiry and timestamp-order checks.
- Keep service/transaction behaviors separate from these schema tests. Phase 1.6 now proves Hibernate validation and persistence round trips; publication/reveal quota and review concurrency belong to their feature phases. Broader application isolation and backend CI gates remain later Phase 1 tasks.

Phase 2 approves and implements trust thresholds without changing the schema. Phone provider, moderation/appeals, retention/erasure policy, public map coarsening, and Phase 3 draft/upload/text limits and edit/deletion routes remain open. They do not authorize a service bypass or a public launch.
