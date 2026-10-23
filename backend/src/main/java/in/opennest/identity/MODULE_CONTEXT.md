# Identity module

- Status: closed module with internal users/provider-identity mappings; no public API, repository, authentication, or session service implemented.
- Own future users, provider identities, verified private contacts, and account/profile behavior.
- Phase 1.5 V1/V2 creates tables/indexes; Phase 1.6 maps them as package-private UserEntity/AuthIdentityEntity under internal.persistence. Scalar user UUID, no associations. Contracts from repository root: `docs/SCHEMA_CONTRACT.md`, `docs/BACKEND_PERSISTENCE.md`.
- UUID IDs originate in Java; database creation timestamps read-only; nullable verified contact pair and unrounded BigDecimal aggregates remain private. USER accounts may both own and seek listings, with no privileged role yet.
- Allowed module dependencies: none; introduce explicit contracts alongside their consumers.
- Planned Google identity key is issuer/subject, never email. Google login does not verify a phone number.
- Keep entities, credentials, and private contact data internal; expose only authorized account data through deliberate contracts.
- Google OIDC, Redis sessions, CSRF/logout, profiles, and trust calculation belong to Phase 2.
