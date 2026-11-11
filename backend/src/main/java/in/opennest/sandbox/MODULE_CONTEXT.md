# Sandbox module

- Phase 4 owns deterministic multi-city fabricated fixtures, persona selection, CLI reset/telemetry and six-hour scheduled resets on the separately configured SANDBOX backend.
- Allowed dependencies: common and identity public contracts. No entity/internal-package dependencies. Trusted fixture/reset tooling deliberately writes the shared schema in the sandbox database only.
- V7 sandbox_state stores singleton seed_version/generation/reset_interval/reset_at. Migrations create no fixtures or metadata rows in PRIMARY.
- Persona switching requires DEVELOPMENT/SANDBOX plus its explicit feature flag. Identity supplies isolated Redis sessions, rotation, CSRF clearing and realm/cookie enforcement without enabling Telegram.
- Public routes: GET /api/v1/sandbox/personas, POST /api/v1/sandbox/personas/select and GET /api/v1/sandbox/images/{segment}.png. Selection uses an allowlisted personaKey and CSRF. All responses are no-store.
- Seed version 1 contains nine personas, 17 listings across Delhi/Bengaluru/Mumbai/Pune/Hyderabad, all rental modes/statuses, five trust badges, illustrative bundled PNGs, and historical reveal eligibility. Contacts/provider identities/receipts are absent.
- First initialization requires an empty dedicated database. Startup never wipes an unrecognized populated database. Existing seed versions require an explicit operator reset when incompatible.
- Shared transaction-scoped advisory locks coordinate listing/interaction/persona/profile operations; reset takes the exclusive lock and deletes children before parents in one transaction. Storage objects are never deleted by reset.
- Reset increments generation, invalidating previous persona sessions. Scheduled resets use Kolkata six-hour intervals and are idempotent per interval; forced operator resets increment each time.
- Operator commands use opennest.sandbox.command=reset or telemetry with explicit feature flags and web-application-type=none. No reset/telemetry HTTP routes exist; telemetry returns only counts/version/generation/reset time.
- Production rejects sandbox mode/secrets/demo flags; PRIMARY rejects operator commands and sandbox sessions. Headers never select a store or grant privileges.
- Callable contact actions remain disabled. Normal listing publication still requires verified contact, images and quota; trusted seeds do not create a runtime bypass.
- Configuration, manual instructions, appeal/retention gates and verification: docs/PHASE4_HANDOFF.md.
