# Common module

- Status: shared RequestFailure/Kind contract, internal deployment configuration, MVC errors, and baseline security; no business service or diagnostic API.
- Own shared HTTP error handling and small domain-independent types; no dependency on feature module internals.
- Allowed module dependencies: none. Common must not depend on feature modules.
- `internal.configuration` implements Boot's current EnvironmentPostProcessor SPI, binds immutable deployment settings, and validates production/demo policy, distinct stores/secrets, and reserved Boot configuration after config-data loading and before client construction.
- JDBC and Redis settings derive from the selected mode once at startup. Validation errors and configuration descriptions redact credentials; no client input or header is consulted.
- Phase 1.5 pins Flyway to shared db/migration resources and selected datasource, with validation enabled and baseline/clean disabled. Direct spring.flyway inputs are rejected; no independent migration URL or credentials exist.
- Phase 1.6 pins Hibernate validate, UTC JDBC time, Jackson 3 JSON formatting, and disables Open EntityManager in View/repository auto-configuration. Direct spring.jpa and raw Hibernate/Jakarta persistence settings are rejected; no shared base entity or module dependency added.
- Phase 1.7 RequestFailure at module root accepts only a fixed Kind, never arbitrary error text. Consumers must explicitly declare common when implemented; no cross-module edge yet.
- internal.web writes fixed Problem Details with UUID IDs; return-value validation and generic bugs stay 500. Recognized 4xx statuses and 503 share safeStatus normalization across advice and /error. Advice rethrows Security exceptions unchanged for Security's 401/403 handling; response details never copy exception/request/private data.
- internal.security denies every normal request, disables form/Basic/logout/request caching, rejects authentication, and retains CSRF. Only trusted ERROR dispatch is permitted. No Google/demo login or password user.
- HTTP logs emit fixed status/error-ID events at INFO for 4xx and ERROR for 5xx. Root ERROR diagnostics use a literal redacted pattern without messages, logger/thread names, MDC, arguments, or stack traces. Raw framework/SQL/request data, access/debug/trace logs, and policy overrides remain disabled; `docs/BACKEND_HTTP_ERRORS.md`.
