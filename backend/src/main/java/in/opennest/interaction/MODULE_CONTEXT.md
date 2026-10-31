# Interaction module

- Status: closed module with internal reveal/review mappings; no public API, repository, ledger service, report flow, or aggregate service implemented.
- Own future contact reveals, daily quota ledger, reviews, and brokerage reports.
- Phase 1.5 V1/V2 creates tables/indexes; Phase 1.6 adds package-private ContactRevealEntity/OwnerReviewEntity under internal.persistence. All foreign references scalar UUIDs, with no entity associations. Contracts: `docs/SCHEMA_CONTRACT.md`, `docs/BACKEND_PERSISTENCE.md` from repository root.
- Reveal createdAt requires a server instant captured after the future quota lock; generated Kolkata LocalDate is read-only. Review creation identity/references are immutable in ORM; report fields mutable only for future authorized transactional writers.
- Allowed module dependencies: none; future access to listing ownership or identity aggregates requires explicit contracts and transaction boundaries.
- PostgreSQL must remain authoritative for daily quotas; Redis is an accelerator. Quota dates use Asia/Kolkata.
- Review/report writes must serialize and update owner aggregates transactionally; reports require prior reveal and remain allegations.
- Never auto-hide listings because of ratings or reports. Implement these behaviors and concurrency tests in Phase 4.
