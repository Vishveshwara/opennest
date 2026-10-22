# OpenNest listing search plan

Status: proposed contract for the first listing-search implementation in Phase 3 and the frontend in Phase 5. No search endpoint exists yet. Pagination, sorting, and filters will be implemented together, with the same search semantics in the public portal and recruiter sandbox on their separate backends. Sandbox responses will bypass shared/public caches.

## Request and filters

The planned `GET /api/v1/listings/search` will return nationwide results when `city` is omitted. Supplied filters combine with AND and run in the database before pagination. Do not fetch all listings for browser-side filtering or sorting.

| Parameter | Planned behavior |
| --- | --- |
| `city`, `locality` | Optional exact matches after shared Unicode NFKC normalization, trimming, whitespace collapsing, and locale-independent uppercase on writes and queries. No city default or fixed city allowlist. Locality may be used without city and then matches nationwide; alternate spellings/transliterations are not silently treated as aliases. |
| `category` | Optional `FLAT` or `PG_HOSTEL`. |
| `flatRentalMode` | Optional `ENTIRE_FLAT` or `ROOM`; restricts results to FLAT. Explicit PG_HOSTEL with this filter is invalid. See [listing modes](LISTING_MODEL.md). |
| `status` | `ACTIVE` by default; explicit `FULL_OCCUPIED` also supported. `PAUSED` drafts are never included in public search, even for their owner. Published ACTIVE/FULL_OCCUPIED listings remain accessible by public detail URL; a PAUSED detail is owner-only. Reports/ratings cannot change publication status. |
| `minRent`, `maxRent` | Inclusive integer monthly quoted rent bounds in INR: PG per person, entire flat per property, flat room per room. Each >= 0 and minimum <= maximum. No implicit conversion between bases. |
| `minBedrooms`, `maxBedrooms` | Inclusive integer FLAT bedroom/BHK bounds, each >= 0; 0 means studio. Excludes PG_HOSTEL; explicit PG_HOSTEL combined with bedroom filters is invalid. Set both to the same value for an exact bedroom count. |
| `minTotalRooms`, `maxTotalRooms` | Inclusive nonnegative integer bounds on currently available rooms for ROOM/PG_HOSTEL: male + female + shared. Set both to the same value for an exact count. Excludes ENTIRE_FLAT; these are rooms, not beds or BHK. |
| `genderRoomAvailable` | Optional `MALE`, `FEMALE`, or `SHARED`; requires the corresponding available-room counter > 0 in ROOM/PG_HOSTEL. Excludes ENTIRE_FLAT. |
| `maxSharingPerRoom` | Integer 1..10; matches ROOM/PG_HOSTEL listings whose maximum occupants per room is at most this value. Excludes ENTIRE_FLAT. |
| `hasWifi`, `hasGym`, `hasSwimmingPool` | Omitted = unrestricted, true = amenity required, false = amenity absent. |
| `lat`, `lng`, `radiusKm` | All three required together. Radius > 0 and <= 100 km; validate coordinates against the documented coarse bounds (latitude 6.5..37.5, longitude 68..97.5). These are not a strict India boundary; boundary-polygon validation is an additional requirement only if strict India geofencing is intended. Use geography ST_DWithin in metres. |

Blank text, duplicate scalar parameters, unknown parameters, invalid types/enums, incomplete geo queries, and reversed ranges return 400 Problem Details. Unsupported values must not silently fall back to defaults. Valid filters that happen to match no listings return an empty result, not an error.

Explicit ENTIRE_FLAT combined with available-room, gender-room, or sharing filters is invalid. Without an explicit mode, those filters simply exclude ENTIRE_FLAT. Bedrooms/BHK apply to both flat modes and describe the entire flat's layout. For ENTIRE_FLAT, use status/wholeFlatAvailable for vacancy; totalRoomsAvailable is NULL, not zero or a bedroom count.

## Pagination and ordering

- `page` is zero-based, default 0; `size` defaults to 20 and must be 1..50. `page` must be an integer >= 0; `page * size` must be <= 10000. Reject larger offsets with 400 and ask the user to narrow filters.
- Response: `{items: ListingResponseDto[], page, size, totalElements, totalPages, hasNext, hasPrevious, sort}`. Totals reflect every matching listing in the selected deployment, not just the current page. totalPages is ceil(totalElements / size); with no matches, totalPages is 0.
- A valid page past the end returns empty items and the actual totals. `hasNext` is `page + 1 < totalPages`; `hasPrevious` is `page > 0 && totalPages > 0`.
- `sort` accepts only the enum below; default NEWEST. Every order ends with property `id ASC` so equal values do not randomly shuffle. Do not interpolate user-provided SQL field names or directions.

| Sort | Database order before the UUID tie-breaker |
| --- | --- |
| `NEWEST` | created_at DESC |
| `RENT_ASC` | monthly_rent ASC |
| `RENT_DESC` | monthly_rent DESC |
| `ROOMS_DESC` | total_rooms_available DESC NULLS LAST; ENTIRE_FLAT has no room-inventory total |
| `RATING_DESC` | owner's average_rating DESC NULLS LAST; unrated owners remain unrated |
| `DISTANCE_ASC` | distance to supplied lat/lng ASC; requires the complete geo query |

Every item includes category, flatRentalMode (NULL for PG), applicable inventory, `currency=INR`, `rentPeriod=MONTH`, and derived rentBasis: PER_PERSON for PG_HOSTEL, PER_PROPERTY for ENTIRE_FLAT, PER_ROOM for flat ROOM. RENT_ASC/RENT_DESC sort the quoted monthly_rent. Mixed results retain these different bases; label the control "Quoted monthly rent" and offer category/mode filters for comparisons on one basis. Never present a per-person quote as the price for a room or property, or a room quote as the whole-flat price. Security deposits use the same basis and an explicit label.

Return/display owner-set capacity: maxOccupants for ENTIRE_FLAT, maxSharingPerRoom for ROOM/PG_HOSTEL; the inapplicable field is NULL. Whole-flat capacity is independent of BHK and vacancy. The maxSharingPerRoom filter remains a per-room sharing filter; it must not silently filter whole-flat maxOccupants.

Items and count queries must use identical filters and visibility rules. Obtain the page and totals from a consistent database snapshot for that request. Paginate distinct property IDs before hydrating image collections; joins must not duplicate cards or inflate counts. Use projections/batched relations to avoid a query per card. Inspect query plans for nationwide and city-filtered cases before tuning indexes.

Image reservations and pending uploads must never appear as validated listing photos. Public cards use only VALIDATED image records. Exact stored coordinates may support geo queries internally. Public search and listing-detail responses omit owner phone, private address, and precise coordinates. A public map location may expose only a coarsened representation under an explicitly documented precision/coarsening policy; until that policy is defined, omit coordinates. Selecting that policy remains an open response-design decision in PROJECT_PLAN.md.

Offset pagination is the initial design because numbered pages and exact totals are useful at launch. It does not freeze results between requests: new listings and rent, inventory, or rating changes may move items across pages. Do not promise snapshot-stable browsing under concurrent writes. Evaluate cursor pagination when observed traffic or deep browsing requires it, retaining bounded queries throughout.

## Browser behavior

- Keep all filters, sort, page, and size in URL query parameters for shareable links and browser Back/Forward. Initial view is nationwide, page 0, size 20, NEWEST.
- Changing any filter, sort, or size resets page to 0. Clear filters also clears geo inputs and resets page/sort/size to their defaults.
- Provide separate controls for rental mode, bedrooms/BHK, and available rooms. Hide bedrooms for PG_HOSTEL and room/gender/sharing filters for ENTIRE_FLAT; clear incompatible parameters when switching category/mode. Label sharing in occupants per room and bedrooms as the containing flat's layout.
- Show PG prices as "INR <amount> / person / month", entire flats as "INR <amount> / property / month", and flat rooms as "INR <amount> / room / month" on cards, details, forms, and the recruiter demo. Display the basis next to rent filters/sort controls; changing price does not change inventory or imply a bed count.
- Provide accessible Previous/Next controls, numbered pages, matching count, and page-size selection. Display page numbers as one-based while translating to the zero-based API. Enable nearest sorting only with a valid geo query.
- Keep exact totals even when they exceed the offset cap; only offer pages whose offset is <= 10000. At the browsing limit, disable further navigation and prompt for narrower filters even if hasNext indicates more matches.
- Show loading, empty, error, and out-of-range page states. Offer return to the first page when changes leave the requested page beyond the result set. Cancel or ignore stale responses after a query change.
- Only unauthenticated production listing responses may enter shared caches. Their keys include the complete normalized query (all filters, sort, page, size) and deployment. All sandbox and authenticated responses use no-store, even when search semantics match; never cache them publicly. Invalidate affected public searches/details on listing/status/inventory/review changes.

## Examples

Nationwide, newest first, first 20 listings:

```text
/api/v1/listings/search?page=0&size=20&sort=NEWEST
```

Available entire two-bedroom flats in Bengaluru with Wi-Fi, rent up to INR 30000 per property, lowest rent first:

```text
/api/v1/listings/search?city=Bengaluru&category=FLAT&flatRentalMode=ENTIRE_FLAT&minBedrooms=2&maxBedrooms=2&maxRent=30000&hasWifi=true&sort=RENT_ASC&page=0&size=20
```

Individual rooms in flats nationwide, at least one vacancy and up to INR 10000 per room:

```text
/api/v1/listings/search?category=FLAT&flatRentalMode=ROOM&minTotalRooms=1&maxRent=10000&sort=RENT_ASC&page=0&size=20
```

## Acceptance criteria for implementation

- Verify nationwide, normalized city/locality, exact bedroom/room counts, inclusive ranges, amenity false versus omitted, gender vacancy, and combined filters against real PostGIS data.
- Verify PG per-person, entire-flat per-property, and flat-room per-room metadata/labels, category/mode comparisons, and quoted-value filters/sorts without accidental conversion. Verify incompatible mode filters, whole-flat NULL room totals, and NULLS LAST for ROOMS_DESC.
- Verify first/middle/last/empty/out-of-range pages, totals and flags, invalid page/size/offset, and request snapshot consistency.
- Verify each sort, ties across page boundaries on unchanged data, unrated owners last, and nearest ordering in metres; reject distance sort without geo inputs and all unknown sort values.
- Verify paused/private records and other-deployment data cannot affect results or counts. Multiple images must not duplicate a listing or inflate totals.
- Verify public search/detail payloads omit owner phone, private address, and precise coordinates; expose map coordinates only under the documented coarsening policy. Verify coarse coordinate-bound validation does not claim strict India geofencing.
- Verify PAUSED drafts/pending images stay out of search until explicit publication, pausing removes a listing from public results, and inventory/report writes cannot republish a draft. Verify sandbox/authenticated responses use no-store and public caches are invalidated after relevant writes.
- Verify invalid and incompatible inputs return 400, while valid contradictory filters return an empty result. Record query plans for representative nationwide and multi-filter searches.
- End-to-end checks cover URL sharing, Back/Forward, filter/sort/size page reset, empty results, stale requests, and mobile/keyboard search controls.
