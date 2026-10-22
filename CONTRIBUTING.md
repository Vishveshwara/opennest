# Contributing to OpenNest

Read `AGENTS.md` and the context indexes before making changes. Work on a branch and keep each PR focused on one implementation phase or fix.

Describe the resulting behavior, relevant decisions, checks actually run, and remaining limitations. CodeRabbit is a reviewer; its approval does not replace passing tests or maintainer review.

Update schema/API/module context alongside behavior changes. Write full `/api/v1/...` REST paths in documentation, keeping the two Google OIDC framework paths unchanged. Distinguish proposed contracts from implemented endpoints and report unresolved decisions explicitly. Add meaningful integration and concurrency tests for quotas, authentication, authorization, and isolation as those features are implemented.

Do not commit `.env`, provider credentials, personal contact data, generated build outputs, or real production data. Use fabricated non-contactable demo records. Never paste secrets into an issue or PR.

For Phase 0, run `docker compose config --quiet` with both database passwords configured, then `docker compose --profile sandbox up -d --wait`. GitHub Actions additionally verifies network separation and persistence after replacing containers. Network separation is a local infrastructure check, not proof of application authorization.

Contributions to OpenNest are under GNU AGPL version 3 only (`AGPL-3.0-only`), as provided in LICENSE. Preserve third-party license notices. Deployment, merging, and external provider setup are separate decisions from opening a PR.
