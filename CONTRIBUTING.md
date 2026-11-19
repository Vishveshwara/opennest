# Contributing to OpenNest

Read `AGENTS.md` and the context indexes before making changes. Work on a branch and keep each PR focused on one implementation phase or fix.

Describe the resulting behavior, relevant decisions, checks actually run, and remaining limitations. CodeRabbit is a reviewer; its approval does not replace passing tests or maintainer review.

Update schema/API/module context alongside behavior changes. Write full `/api/v1/...` REST paths in documentation. Telegram Bot API is the sole login provider. Distinguish proposed contracts from implemented endpoints and report unresolved decisions explicitly. Add meaningful integration and concurrency tests for quotas, authentication, authorization, and isolation as those features are implemented.

Do not commit `.env`, provider credentials, personal contact data, generated build outputs, or real production data. Use fabricated non-contactable demo records. Never paste secrets into an issue or PR.

Use the [Docker Desktop guide](docs/LOCAL_DOCKER_TESTING.md) for the datastore, backend, frontend and browser checks relevant to your change. Verify deployment-facing changes against the [Render, Supabase and Vercel setup](docs/DEPLOYMENT_SETUP.md). Record which checks ran locally and which require hosted verification.

Contributions to OpenNest are under GNU AGPL version 3 only (`AGPL-3.0-only`), as provided in LICENSE. Preserve third-party license notices. Deployment, merging, and external provider setup are separate decisions from opening a PR.
