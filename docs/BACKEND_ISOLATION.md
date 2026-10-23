# OpenNest backend isolation verification

Phase 1.8 implementation is present. Its completion gate is **pending container execution**. On 22 October 2026 the Java 25 build compiled the new suite, passed all 114 existing tests, and packaged the application without test controllers. The integration gate failed during setup because this machine has no Docker engine. No PostGIS/Redis container assertion has passed yet. CI and hosted deployment were not run.

## Run the required proof

Select Java 25 and start a Linux Docker engine accessible to Testcontainers. The existing PostGIS image requires amd64 support. From the repository root:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml -Pisolation-integration clean verify
```

The Unix equivalent uses `./backend/mvnw`. Maven resolves Testcontainers 2.0.5 from Spring Boot's dependency management. The suite starts two `postgis/postgis:17-3.5` containers and two `redis:7.4-alpine` containers with different database names, users, and passwords. Redis authentication is enabled for these tests even though the development Compose Redis services are unauthenticated. Containers use ephemeral published ports and are removed after the suite. No installed or hosted database is targeted.

`BackendIsolationIT` contains 23 test invocations. It requires Docker and fails setup if Docker is unavailable. It has no external-store fallback, disabled-without-Docker option, or mocked datastore. The earlier schema suite's `OPENNEST_SCHEMA_TEST_*` fallback variables do not affect this suite.

To run both integration suites after Docker is available:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml '-Pschema-integration,isolation-integration' clean verify
```

JUnit reports appear in `backend/target/surefire-reports` and `backend/target/failsafe-reports`. Phase 1.8 is complete only after the container suite passes with no skipped tests. A successful default build or `test-compile` alone does not satisfy this gate.

## What the suite checks

- Boot independently configured PRODUCTION/PRIMARY and DEVELOPMENT/SANDBOX contexts. Neither receives the other deployment's secrets. Flyway and Hibernate run normally, without auto-configuration exclusions.
- Apply V1/V2 to both databases and verify their checksums match. Use actual PostGIS and actual Redis reads/writes through each context's configured clients.
- Store the same UUID/key with different values in each deployment. Read and write through test-only HTTP handlers with absent, true, false, PRIMARY, SANDBOX, and invalid `X-Sandbox-Mode` values. Verify the other stores never receive those writes.
- Reject sandbox PostgreSQL credentials at the primary server, including when the primary username is supplied with the sandbox password. Reject startup with that wrong database password. Reject the sandbox Redis password at primary Redis and return safe 503 from the affected test request without fallback.
- Verify headers cannot authenticate, elevate roles, bypass CSRF, or enable normal/demo/login routes. MockMvc supplies trusted test principals; no HTTP login substitute is enabled.
- Reject every production development/demo flag, sandbox mode, sandbox secrets, and missing required production database/Redis inputs.
- Start an additional production context without test configuration and inspect its handler registry. Only the framework error controller may exist; no demo, login, probe, or default user handler is allowed.
- Corrupt each database's Flyway checksum temporarily and require startup failure without repair. Restore the checksum afterward. Cause a real V1 DDL failure with an isolated database owner lacking PostGIS installation privileges; verify no application table survives the failed migration.
- Stop primary Redis, then primary PostgreSQL. Require safe 503 responses and database startup failure. Verify sandbox still reads and writes independently after both outages.

Probe handlers, their security chain, test principals, and shortened client timeouts exist only under `src/isolation-test/java`. They are absent from the packaged application. Probe writes are test plumbing across the two stores, not a claim of a distributed transaction or business-service implementation.

## Boundaries and outstanding proof

The suite checks application store selection, real server authentication, and applicable failure handling. It does not establish deployed network firewalls, restricted runtime-role privileges, DNS/TLS, session/cookie/OIDC isolation, storage buckets, or future demo operations. Container PostgreSQL users are migration-capable development users, not the restricted production runtime roles required before deployment.

PostgreSQL migration/schema failures prevent startup. Redis remains a lazy dependency: valid configuration can boot while Redis is unavailable or its password is wrong; the first Redis operation fails closed. These tests exercise that operation through the existing common error handler. Test-only timeouts bound failures without changing production client settings.

The 22 October attempt recorded 114 passing default tests and one failed integration setup, with no skipped tests. It did not execute the 23 isolation invocations. The build log is ignored at `target/phase-1.8/verify.log`; the [verification record](verification/backend-isolation/result.json) preserves the result without machine credentials. Enable Docker and rerun the command above before marking Phase 1.8 complete. Phase 1.9 CI wiring remains separate.
