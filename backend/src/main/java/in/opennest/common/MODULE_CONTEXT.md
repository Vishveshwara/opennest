# Common module

- Status: shared RequestFailure/Kind and HttpFailureResponse contracts, internal deployment configuration, MVC errors, and conditional baseline security; no business service or diagnostic API.
- Own shared HTTP error handling and small domain-independent types; no dependency on feature module internals.
- Allowed module dependencies: none. Common must not depend on feature modules.
- `internal.configuration` implements Boot's current EnvironmentPostProcessor SPI, binds immutable deployment settings, and validates production/demo policy, distinct stores/secrets, and reserved Boot configuration after config-data loading and before client construction.
- JDBC and Redis settings derive from the selected mode once at startup. Validation errors and configuration descriptions redact credentials; no client input or header is consulted.
- Phase 1.5 pins Flyway to shared db/migration resources and selected datasource, with validation enabled and baseline/clean disabled. Direct spring.flyway inputs are rejected; no independent migration URL or credentials exist.
- Phase 1.6 pins Hibernate validate, UTC JDBC time, Jackson 3 JSON formatting, and disables Open EntityManager in View/repository auto-configuration. Direct spring.jpa and raw Hibernate/Jakarta persistence settings are rejected; no shared base entity or module dependency added.
- RequestFailure accepts only a fixed Kind. HttpFailureResponse exposes fixed-status HTTP error rendering without granting access to internal adapters. Identity declares a dependency on common.
- internal.web writes fixed Problem Details with UUID IDs; return-value validation and generic bugs stay 500. Recognized 4xx statuses and 503 share safeStatus normalization across advice and /error. Advice rethrows Security exceptions unchanged for Security's 401/403 handling; response details never copy exception/request/private data.
- internal.security denies normal requests when Google is disabled, without form/Basic/logout/default-password access. Enabled authentication is owned by identity. Both retain safe errors and trusted ERROR dispatch.
- The deployment postprocessor publishes canonical environment/mode values before identity guards run. Fixed error rendering preserves validated CORS headers across response reset.
- HTTP logs emit fixed status/error-ID events at INFO for 4xx and ERROR for 5xx. Root ERROR diagnostics use a literal redacted pattern without messages, logger/thread names, MDC, arguments, or stack traces. Raw framework/SQL/request data, access/debug/trace logs, and policy overrides remain disabled; `docs/BACKEND_HTTP_ERRORS.md`.
