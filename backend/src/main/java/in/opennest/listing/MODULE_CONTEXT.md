# Listing module

- Status: closed module with internal property/image mappings; no public API, repository, upload, or search service implemented.
- Own future listing lifecycle, mode-aware inventory, images, publication quota, and nationwide listing queries.
- Phase 1.5 V1/V2 creates tables/indexes; Phase 1.6 adds package-private PropertyEntity/PropertyImageEntity under internal.persistence. Scalar owner/property UUIDs, no associations/cascades; Hibernate validation only. Contracts: `docs/SCHEMA_CONTRACT.md`, `docs/BACKEND_PERSISTENCE.md` from repository root.
- Geography/JTS Point and room totals are read-only, retrieved on insert; flush then refresh/reload after updates. @Version protects dirty ORM writes; caller-version checks, unchanged-inventory increments, no-op publication, and quota/publication gates remain Phase 3 service work.
- Allowed module dependencies: none; later owner/contact access must use an explicit identity contract, not another module's entities.
- Follow `docs/LISTING_MODEL.md` and `docs/LISTING_SEARCH.md` from the repository root before implementing behavior.
- Preserve PAUSED drafts, explicit publication gates, three derived rent bases, and owner-configured capacity independent of vacancy/BHK.
- No city default or city allowlist. Public responses must omit private address, owner contact, and precise coordinates.
- Listing behavior and transactional publication/inventory checks belong to Phase 3.
