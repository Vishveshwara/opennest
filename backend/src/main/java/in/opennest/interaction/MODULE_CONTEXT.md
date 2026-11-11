# Interaction module

- Phase 4 implements contact reveal ledger, create-only reviews, idempotent brokerage reports, public review pagination and caller-only interaction state.
- Public HTTP routes are under /api/v1/interactions/listings/{id}; details: docs/PHASE4_HANDOFF.md. No cross-module public service is exposed.
- Allowed dependencies: common, identity, listing, using only public contracts. Entities stay package-private; service persistence uses JDBC inside explicit transactions.
- ListingInteractions supplies owner/visibility; InteractionAccounts owns account locks, verified contact retrieval and aggregate assignment. Reviewer/owner IDs are never accepted from clients.
- Reveals lock the seeking user with FOR NO KEY UPDATE, then capture clock_timestamp for both Kolkata date and created_at. Same-day retries precede the ten-listing quota; PostgreSQL is authoritative without a Redis quota cache.
- NO KEY UPDATE serializes writers while remaining compatible with foreign-key KEY SHARE locks, preventing reciprocal-account deadlocks. Published listing FOR SHARE protects reveal visibility.
- Reviews/reports require a same-user/property/owner reveal on any date, including when subsequently paused. Self-interactions are forbidden.
- Owner-row lock serializes all review/report writes. Review and recomputed aggregates commit together; conversion preserves UUID/time/comment; repeated reports mutate nothing.
- Public review reads require published listings; state for paused listings requires ownership or reveal history. Responses exclude account IDs/contact data and use no-store.
- Brokerage notices describe allegations. No ratings/reports change listing status. Editing/deletion/moderation APIs are absent.
- SandboxBoundary guards transactions against reset and stale session generations. Repeatable-read sandbox reads permit metadata row locking to reject snapshots predating reset.
- Validation allows ratings 1..5, four ordinary issue tags, nullable nonblank comments up to 2000 characters, and bounded page/size only. JSON bodies are limited to 8 KiB with duplicate keys rejected.
- Verification: InteractionInputTests, Phase4IT and controlled Telegram-session interaction coverage in TelegramContactIT. Current execution evidence is recorded in docs/PHASE4_HANDOFF.md.
