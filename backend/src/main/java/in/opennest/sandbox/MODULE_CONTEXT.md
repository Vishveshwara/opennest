# Sandbox module

- Status: closed module declaration only; no persona, seed/reset, telemetry, or contact operation implemented.
- Own future fabricated multi-city recruiter fixtures and bounded demo operations on a separately configured backend.
- Allowed module dependencies: none; this package alone does not enforce deployment or data isolation.
- Production must disable demo operations and reject sandbox authentication. Request headers never select a store or grant authorization.
- Phase 1.3 provides explicit SANDBOX mode and startup-only routing to separate declared database/Redis targets through common's internal configuration; no cross-module dependency is needed here.
- Production plus SANDBOX mode, production demo flags, and sandbox secrets in production are rejected. Real-store isolation tests are complete; session separation, feature authorization tests, and deployed network isolation remain later work.
- Fixtures, personas, resets, and restricted telemetry belong to Phase 4; callable sandbox contacts stay disabled.
