# Common module

- Status: shared RequestFailure/Kind contract, internal deployment configuration, MVC errors, and baseline security; no business service or diagnostic API.
- Own shared HTTP error handling and small domain-independent types; no dependency on feature module internals.
- Allowed module dependencies: none. Common must not depend on feature modules.
- `internal.configuration` binds immutable deployment settings and validates production/demo policy, distinct datastore declarations, required secrets, and reserved Boot configuration. It runs after config-data loading and before datastore clients are constructed.
- JDBC and Redis settings derive from the selected mode once at startup. Validation errors and configuration descriptions redact credentials; no client input or header is consulted.
- Phase 1.5 pins Flyway to shared db/migration resources and selected datasource, with validation enabled and baseline/clean disabled. Direct spring.flyway inputs are rejected; no independent migration URL or credentials exist.
- Phase 1.6 pins Hibernate validate, UTC JDBC time, Jackson 3 JSON formatting, and disables Open EntityManager in View/repository auto-configuration. Direct spring.jpa and raw Hibernate/Jakarta persistence settings are rejected; no shared base entity or module dependency added.
- Phase 1.7 RequestFailure at module root accepts only a fixed Kind, never arbitrary error text. Consumers must explicitly declare common when implemented; no cross-module edge yet.
- internal.web classifies known failures and writes fixed Problem Details with UUID IDs; generic bugs stay 500, including unrecognized integrity/outage causes. Advice, outer filter, and framework /error share that writer. Response details/logs never copy exceptions or request/private data.
- internal.security denies every normal request, disables form/Basic/logout/request caching, rejects authentication, and retains CSRF. Only trusted ERROR dispatch is permitted. No Google/demo login or password user.
- Logging allowlist emits only fixed HTTP status/error-ID events; raw framework/SQL/request/stack dumps and access/debug/trace logs disabled. Error/logging policy overrides rejected. Startup diagnostics also suppressed; full contract: `docs/BACKEND_HTTP_ERRORS.md` from repository root.
