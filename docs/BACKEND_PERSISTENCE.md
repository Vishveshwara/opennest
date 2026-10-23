# OpenNest persistence mappings

Phase 1.6 maps all 70 application columns to six Hibernate entities. Flyway V1/V2 remain the only schema definition; this phase adds no migration, repository interface, feature service, authentication flow, or endpoint. [Schema semantics](SCHEMA_CONTRACT.md) and [migration execution](BACKEND_MIGRATIONS.md) remain authoritative.

## Ownership and types

| Module | Internal package under in.opennest | Entities / tables |
| --- | --- | --- |
| identity | `identity.internal.persistence` | UserEntity/users, AuthIdentityEntity/auth_identities |
| listing | `listing.internal.persistence` | PropertyEntity/properties, PropertyImageEntity/property_images |
| interaction | `interaction.internal.persistence` | ContactRevealEntity/contact_reveals, OwnerReviewEntity/owner_reviews |

Entities and their enum types are package-private, use field access, and have protected no-argument constructors. Their state is accessible only within their persistence package; these classes are storage mappings, not module contracts or API DTOs. There are no JPA entity associations or cascade operations. Every foreign reference is a scalar UUID, including same-module references; PostgreSQL enforces the actual foreign keys. All five Modulith modules still allow no cross-module Java dependencies.

- UUID identifiers are assigned in Java before insertion. Immutable IDs/references and listing owner/category/mode are excluded from ORM updates. Services must still reject forbidden requests; silently ignoring a field in SQL is not request validation or authorization.
- String enums use text columns. Prices/counters use integer types, totals/counts/version use long types, and numeric values use BigDecimal. SQL NULL remains distinct from zero/false for inapplicable listing inventory and capacities.
- Instants map to timestamptz with JDBC timezone UTC; Kolkata ledger dates use LocalDate. Most creation timestamps come from the database and are immutable. Listing updated_at defaults in the database on insert and is refreshed by an ORM pre-update callback on dirty updates.
- A contact reveal requires its captured server instant explicitly as createdAt. Phase 4 must capture that instant after acquiring the quota lock, as specified by the schema contract; this phase does not implement quotas or accept client timestamps.
- PostGIS geography maps to a JTS Point using Hibernate Spatial's GEOGRAPHY JDBC type. Latitude/longitude use numeric(9,6); stored geography remains database-derived.
- nearby_landmarks maps as raw JSON text with Hibernate's JSON JDBC type, backed by jsonb. Jackson 3 is the selected formatter. Tests verify an array is stored as JSON, without double encoding. Item validation/provider integration remain Phase 3 work.

## Validation and generated values

The deployment post-processor pins `spring.jpa.hibernate.ddl-auto=validate` and disables Open EntityManager in View and repository auto-configuration. It rejects direct spring.jpa, raw hibernate, and Jakarta/legacy persistence settings, including environment aliases. These inputs cannot switch schema actions to create/update/drop or override the selected persistence configuration. Flyway runs before Hibernate builds the EntityManagerFactory.

Hibernate validation checks mapped structure, including column presence and compatible SQL types. It does not prove every CHECK, partial index, foreign key, or service invariant; the real database migration tests retain that coverage. Schema drift fails startup instead of being repaired automatically.

Generated location, total_rooms_available, and revealed_date have `insertable=false, updatable=false`. Hibernate retrieves generated values after insertion. After changing listing coordinates or room counters, flush and explicitly refresh/reload the entity before using generated values in a response. An ordinary in-memory field assignment cannot change these database-generated columns.

The explicit refresh is deliberate for the pinned Hibernate 7.4.5.Final. Tests observed that automatic UPDATE-generated value retrieval uses RETURNING and raises a generic missing-generated-values exception for a stale version, before the expected optimistic-lock exception. Insert-only retrieval preserves normal affected-row checking for updates. The relevant [generated-value handling](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-core/src/main/java/org/hibernate/generator/values/internal/GeneratedValuesHelper.java) and [Generated annotation](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/annotations/Generated.html) explain these mechanisms. No framework version or database expression was changed to work around this behavior.

## Optimistic locking and feature boundaries

PropertyEntity.version uses `@Version`. Actual ORM updates include the old version predicate and advance the stored version; overlapping stale writes raise OptimisticLockException and cannot overwrite the winner. The integration suite exercises two EntityManagers with overlapping transactions and verifies the winning inventory/version/total.

This is persistence protection, not the complete Phase 3 request contract. Services must compare the caller's expected version before mutation, translate stale writes to 409, preserve PAUSED, enforce publication gates/serialized quota, and implement current-version publish/pause no-ops. Hibernate dirty checking alone does not increment a version for unchanged state; accepted inventory requests that change nothing still require the explicitly designed version increment. Reviews/reports still need historical-reveal authorization, owner serialization, idempotency, and atomic aggregate maintenance in Phase 4.

## Verification

Run the existing database profile with Java 25:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml -Pschema-integration clean verify
```

The profile runs migration checks and module-local persistence scenarios against both deployment modes. Docker starts two separate PostGIS containers by default; the [migration guide](BACKEND_MIGRATIONS.md) documents dedicated external databases. No-connection unit contexts explicitly exclude Flyway/Hibernate auto-configuration; runtime excludes neither. Phase 1.7 adds HTTP/security and starts each integration application on a random HTTP port.

Persistence scenarios cover phone-less/verified-contact users, provider identities with nullable email, numeric precision, all three listing modes and applicable NULLs, generated geography/large totals, JSONB, image reservations/final metadata, historical reveals/Kolkata dates, review/report fields, immutable ownership fields, and stale overlapping updates. Most rows roll back. The stale-write test commits its dedicated fixture to make it visible across transactions, then deletes only those fixture UUIDs in cleanup. A negative test temporarily renames an identity column, proves startup rejection without repair, and restores the column in finally.

On 22 October 2026, the clean Java 25 build passed 57 unit/context/module tests and 49 database integration tests, with zero failures/errors/skips. Both isolated targets used PostgreSQL 17.11/PostGIS 3.5.3 and the unchanged Flyway V1/V2 migrations. Final database checks confirmed zero application rows, restored identity columns, and matching migration checksums. Both temporary test servers were stopped. Packaged dependencies retain Boot 4.1.1 and matching Hibernate core/Spatial 7.4.5.Final. Logs are under ignored `target/phase-1.6/`; temporary test clusters use `%TEMP%/opennest-phase-1-5`.

No production/recruiter hosting, real contact verification, feature API authorization, quotas/review aggregate concurrency, Redis isolation, or CI is established by these checks. Docker image execution is unverified locally; the actual database-engine checks use isolated PostgreSQL 17/PostGIS 3.5 clusters.
