# Identity module

- Status: Phase 2 Google OIDC, Redis sessions, CSRF/logout, private profile, transactional account persistence, and trust calculator implemented; Google is explicitly disabled until configured.
- Own users, provider identities, verified private contacts, and account/profile behavior.
- Phase 1.5 V1/V2 creates tables/indexes; Phase 1.6 maps them as package-private UserEntity/AuthIdentityEntity under internal.persistence. Scalar user UUID, no associations. Contracts from repository root: `docs/SCHEMA_CONTRACT.md`, `docs/BACKEND_PERSISTENCE.md`.
- UUID IDs originate in Java; database creation timestamps read-only; nullable verified contact pair and unrounded BigDecimal aggregates remain private. USER accounts may both own and seek listings, with no privileged role yet.
- Allowed module dependencies: common, through public RequestFailure and HttpFailureResponse only. No dependency on other modules or common internals.
- Google identity key is validated issuer/subject, never email. Account and identity creation share a transaction; a losing unique-key race rolls back before reading the winner. Google login does not verify a phone number.
- Keep entities, credentials, and private contact data internal; expose only authorized account data through deliberate contracts.
- Public contracts: IdentityAccounts.hasVerifiedContact(UUID), and TrustBadge.calculate with approved aggregate thresholds. Entities and AccountProfile remain in internal packages.
- Identity owns enabled security, cookie serialization, and session policy. Common retains deny-all security when Google is disabled. No Basic/form/password/demo fallback exists.
- Redis sessions use 30-minute idle and 24-hour authenticated absolute limits; only a minimal deployment-bound principal survives successful login. Provider credentials are discarded.
- Routes: GET /oauth2/authorization/google, GET /login/oauth2/code/google, GET /api/v1/auth/csrf, POST /api/v1/auth/logout, GET /api/v1/users/me. All require explicit Google enablement; REST profile requires authentication.
- Configuration, contracts, verification, and outstanding Google smoke test: docs/IDENTITY_HANDOFF.md and docs/AUTHENTICATION.md from repository root.
