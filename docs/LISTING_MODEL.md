# OpenNest listing modes and pricing

Status: proposed feature implementation contract. On 22 October 2026, the maintainer confirmed individual vacant rooms in flats priced per room per month, plus owner-configured numbers of people allowed. PG accommodation remains per person per month. Phase 1.5/1.6 implements the schema and internal mappings; listing services/endpoints remain unimplemented. See [persistence scope](BACKEND_PERSISTENCE.md).

## Modes

| Category | flatRentalMode | Monthly rent basis | Availability | Bedrooms/BHK |
| --- | --- | --- | --- | --- |
| FLAT | ENTIRE_FLAT | PER_PROPERTY: one entire flat | wholeFlatAvailable boolean | Layout of the entire flat; 0 means studio |
| FLAT | ROOM | PER_ROOM: one offered room | male + female + shared available-room counters | Layout of the containing flat, not the number of offered rooms |
| PG_HOSTEL | NULL | PER_PERSON: one person's stay | male + female + shared available-room counters | NULL |

Every price includes currency=INR, rentPeriod=MONTH, and the derived rentBasis. Security deposits use the same basis as rent. Never divide a whole-flat quote by bedrooms, multiply a PG quote by sharing, or infer a room quote from either.

An ENTIRE_FLAT listing has wholeFlatAvailable=true or false, owner-set maxOccupants (a required positive integer), and NULL room counters, totalRoomsAvailable, and maxSharingPerRoom. A ROOM or PG_HOSTEL listing has wholeFlatAvailable=NULL, maxOccupants=NULL, nonnegative room counters, totalRoomsAvailable equal to their sum, and owner-set maxSharingPerRoom in 1..10. NULL means not applicable; it must not be presented as zero vacant rooms. Gender counters classify offered rooms, not the gender of a logged-in user.

For ENTIRE_FLAT, maxOccupants is the maximum number of people allowed in the entire flat. For ROOM, maxSharingPerRoom is the maximum people allowed in each offered room; the quote remains per room regardless of that capacity. Owners supply these limits at draft creation and may change them through versioned inventory updates. Capacity is not current occupancy or available beds and never changes status by itself. Do not infer it from BHK, available-room counts, or rent. Reject missing, zero, negative, fractional, or out-of-range integers; the existing 1..10 per-room limit does not cap an entire flat at ten people.

ROOM listings may group equivalent vacant rooms with the same quoted rent, deposit, sharing limit, and listing terms. Different room prices/terms need separate listings; every published listing counts toward the owner's five-listing quota. This grouping is a proposed implementation convention, not a booking or bed-allocation system. PG prices are per person while PG inventory remains rooms; no bed inventory or room booking is implied.

Category and flatRentalMode are fixed at draft creation for the initial implementation. Changing mode later would need a separately designed conversion flow; inventory requests cannot change price basis or mode. Required photos remain category-based as specified in PROJECT_PLAN.md.

## Publication and updates

- Create every listing as a PAUSED draft. Creation requires an authenticated account, valid city, category/mode, pricing, and mode-appropriate inventory. Photo validation and verified owner contact are publication gates.
- For ENTIRE_FLAT, publishing derives ACTIVE when wholeFlatAvailable=true and FULL_OCCUPIED otherwise. For ROOM/PG_HOSTEL, publishing derives ACTIVE when totalRoomsAvailable > 0 and FULL_OCCUPIED otherwise.
- Both published states count toward the five-listing quota. PAUSED listings do not. Inventory edits preserve PAUSED; they cannot publish or reactivate a draft.
- `PATCH /api/v1/listings/{id}/inventory` accepts exactly one mode-appropriate payload below. Reject inapplicable fields, client totals, status, category, or mode with 400. Resolve the mode from the stored listing, not a caller assertion.

```json
{"wholeFlatAvailable": true, "maxOccupants": 4, "version": 3}
```

```json
{"maleRoomsAvailable": 1, "femaleRoomsAvailable": 0, "sharedRoomsAvailable": 2, "maxSharingPerRoom": 2, "version": 3}
```

The first payload applies only to ENTIRE_FLAT; the second applies to ROOM/PG_HOSTEL. Owner responses return the resulting status, applicable inventory, and current version. Require ownership, CSRF, and an exact version match before every inventory/publication/pause mutation. An outdated version returns 409 even on a repeated request; the client must refetch. A publish/pause request with the current version that is already in the requested publication state is a no-op and does not increment the version or consume/release another quota slot. Inventory edits increment the version when accepted. This version rule does not change brokerage-report idempotency.

## Search and display

- Add optional `flatRentalMode=ENTIRE_FLAT|ROOM`; it restricts results to FLAT. Combining it with category=PG_HOSTEL is invalid.
- Bedroom filters cover both flat modes and always describe the containing flat's layout. Use available-room filters for vacancy, never BHK.
- Room-count, gender-room, and sharing filters apply only to ROOM/PG_HOSTEL and exclude ENTIRE_FLAT. Explicit ENTIRE_FLAT combined with any of those filters is invalid. With no explicit mode, those filters narrow the eligible modes automatically.
- ROOMS_DESC sorts applicable totals descending with NULLS LAST, then id ASC. Whole-flat availability is represented through status and wholeFlatAvailable, never a fabricated room total.
- Rent filters/sorts use quoted values. Mixed results must retain their three bases; offer category/mode filters for comparisons on one basis.
- Example labels: "INR 25000 / property / month; up to 4 people", "INR 8000 / room / month; up to 2 people per room", "INR 6000 / person / month". A ROOM card can say "2 rooms available in a 3 BHK flat"; it must not imply the entire flat costs INR 8000. Show owner-set capacity on cards/details and provide a clearly labeled owner input in creation/edit forms.

Full pagination and filter behavior remains in [LISTING_SEARCH.md](LISTING_SEARCH.md).

## Acceptance criteria and remaining work

- Verify all three price bases and deposit labels across draft creation, search, detail, owner updates, and fabricated recruiter fixtures.
- Verify owner-set capacity creation/updates, validation, unauthorized/stale writes, and consistent response/display fields. Changing capacity alone must not multiply rent, change vacancy/status, or create a bed count.
- Reject mismatched category/mode/inventory fields at the API and database constraint layers. Assert stored mode cannot be changed through inventory updates.
- Verify ACTIVE/FULL_OCCUPIED/PAUSED transitions for the boolean and room-count modes, quota concurrency, stale-version conflicts, and current-version no-ops.
- Verify mode-filter combinations, whole-flat NULL placement in room sorts, and bedroom versus available-room controls.
- Before implementing Phase 3, specify limits for drafts, image reservations, file size/count, and orphan cleanup; the five published-listing quota alone does not bound storage abuse. Set numeric limits in the implementation PR.
- Listing text/price edits, image replacement/removal, contact changes, and deletion require explicit contracts before they are exposed. The current endpoint inventory must not be interpreted as implementing these flows.
