# Identity module

- Status: Telegram Bot API sole authentication by maintainer decision 10 November 2026. Spring Security, private profiles, Redis sessions/CSRF/logout, transactional accounts, and trust rules implemented; disabled until explicitly configured.
- Own users, provider identities, verified private contacts, account/profile authorization, and identity-owned listing_owner_profiles view.
- Public contracts: CurrentAccount.id(), IdentityAccounts.hasVerifiedContact(UUID)/lockForListingMutation(UUID), TrustBadge.calculate. Only public common dependencies; no other module dependency. Entities/profiles/credentials remain internal.
- V1/V2 mappings UserEntity/AuthIdentityEntity remain package-private, scalar references/generated timestamps; six total ORM mappings still 76 columns. V4 contact provenance and V5 login receipts are JDBC-owned; Flyway/Hibernate validate only.
- internal.telegram creates anonymous original-session/realm/expected-contact challenges. Private Telegram self-contact checks precede six-digit HMAC-derived OTP delivery; only original-session CSRF completion signs in. No account/phone reserved at challenge creation.
- Telegram account key is issuer https://telegram.org plus validated sender ID. Never link by email/typed phone/username. Unambiguous existing verified receipt recovers its account; ambiguous/unassociated legacy accounts require explicit recovery. Preserve existing data/migrations.
- Completion advisory-locks challenge/sender then user row, atomically persists account/identity/contact/current provenance/expiring login receipt. Phone conflicts never merge accounts. Rollback keeps claim retryable; committed original-session receipts recover within challenge lifetime. Expired login receipts are ignored/deleted hourly.
- V4 current receipt retained for verified account lifetime, no history/username/vCard/plaintext OTP. Future account erasure/contact replacement must remove dependent private receipts.
- Controller rotates session ID, clears CSRF, explicitly saves minimal realm-bound principal, and privately carries retry binding across rotation. Status COMPLETED is a database receipt, not proof of browser authentication. Mutations require freshly fetched CSRF after login.
- Versioned Redis namespace invalidates old provider sessions. Cookies host-only/HttpOnly/Lax, Secure in production/HTTPS; 30-minute idle/24-hour absolute authenticated limits. Separate store/cookie/realm rejects replay; no header-based authorization.
- Telegram opt-in OPENNEST_TELEGRAM_AUTH_ENABLED, PRIMARY only. Production secret-header HTTPS webhooks; explicit development-only polling refuses registered webhook and uses Redis lease/persisted offsets. Sandbox contact actions, removed provider settings and console OTP are rejected.
- Redis Lua enforces TTL, starts/attempts/send/poll limits, chat binding and update deduplication. Codes/contacts/secrets never enter logs/browser status APIs. Fixed-host client disables redirects/paid sends and bounds body/time, honors retry_after.
- AccountStore retains typed REQUIRED/read-only profile/contact reads, preserving original failures if rollback also fails. Telegram JDBC transactions own sign-in writes; no old provider login helper remains.
- Listing publication consults verified contact and owner-row lock, then separately enforces images/quotas/versions. Login cannot bypass publication. Identity view exposes only ID/name/trust aggregates; no contact/provider fields.
- Routes/configuration/setup/current evidence: docs/AUTHENTICATION.md, docs/TELEGRAM_HANDOFF.md and docs/ai-context/API_CONTRACTS.md. No Google/OIDC login dependency/handler/routes, console/password fallback, or automatic bot-update login.


- Current proof and manual status: docs/API_VERIFICATION.md records all listing HTTP routes exercised through Telegram-created sessions with isolated image storage; maintainer confirmed real local OTP/profile. No OAuth library/wiring remains; deliberate reject guards and historical private schema are preserved.

- Phase 4 adds public InteractionAccounts and PersonaSessions. Private contact DTOs redact toString; review aggregates are assigned within the interaction-owned owner-locked transaction.
- Shared sessions/profile/CSRF/logout activate for Telegram PRIMARY or explicit DEVELOPMENT/SANDBOX personas. Telegram challenges/transport remain PRIMARY-only. Sandbox uses personas:v1 Redis namespace, allowlisted selection and generation-bound sessions.
- Sandbox profile/session reads coordinate through SandboxBoundary; schema/application failures retain safe error classification. Phase 4 does not add any PRIMARY login alternative.

- PersonaSessions requires the caller to hold the shared reset lock while reading generation and installing the session; SandboxController is the sole caller and owns that lock.
