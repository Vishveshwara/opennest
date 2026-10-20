# OpenNest listing search plan

Status: proposed contract for the first listing-search implementation in Phase 3 and the frontend in Phase 5. No search endpoint exists yet. Pagination, sorting, and filters will be implemented together, with identical behavior in the public portal and recruiter sandbox on their respective backends.

## Request and filters

`GET /api/v1/listings/search` returns nationwide results when `city` is omitted. Supplied filters combine with AND and run in the database before pagination. Do not fetch all listings for browser-side filtering or sorting.

| Parameter | Planned behavior |
| --- | --- |
| `city`, `locality` | Optional exact matches after shared Unicode normalization, trimming, whitespace collapsing, and case normalization on writes and queries. No city default or fixed city allowlist. Locality may be used without city and then matches nationwide. |
| `category` | Optional `FLAT` or `PG_HOSTEL`. |
| `status` | `ACTIVE` by default; explicit `FULL_OCCUPIED` also supported. `PAUSED` is never searchable publicly. Fully occupied listings remain accessible by public detail URL. |
| `minRent`, `maxRent` | Inclusive integer monthly quoted rent bounds in INR; PG_HOSTEL is per person, FLAT is per whole property. Each >= 0 and minimum <= maximum. No implicit conversion between bases. |
| `minBedrooms`, `maxBedrooms` | Inclusive integer FLAT bedroom/BHK bounds, each >= 0; 0 means studio. Excludes PG_HOSTEL; explicit PG_HOSTEL combined with bedroom filters is invalid. Set both to the same value for an exact bedroom count. |
| `minTotalRooms`, `maxTotalRooms` | Inclusive nonnegative integer bounds on currently available rooms: male + female + shared. Set both to the same value for an exact count. These are rooms, not beds or BHK. |
| `genderRoomAvailable` | Optional `MALE`, `FEMALE`, or `SHARED`; requires the corresponding available-room counter > 0. |
| `maxSharingPerRoom` | Integer 1..10; matches listings whose maximum occupants per room is at most this value. |
| `hasWifi`, `hasGym`, `hasSwimmingPool` | Omitted = unrestricted, true = amenity required, false = amenity absent. |
| `lat`, `lng`, `radiusKm` | All three required together. Radius > 0 and <= 100 km; validate coordinates against the project's India bounds. Use geography ST_DWithin in metres. |

Blank text, duplicate scalar parameters, unknown parameters, invalid types/enums, incomplete geo queries, and reversed ranges return 400 Problem Details. Unsupported values must not silently fall back to defaults. Valid filters that happen to match no listings return an empty result, not an error.

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
| `ROOMS_DESC` | total_rooms_available DESC |
| `RATING_DESC` | owner's average_rating DESC NULLS LAST; unrated owners remain unrated |
| `DISTANCE_ASC` | distance to supplied lat/lng ASC; requires the complete geo query |

Every item includes `currency=INR`, `rentPeriod=MONTH`, and category-derived `rentBasis` (`PER_PERSON` for PG_HOSTEL, `PER_PROPERTY` for FLAT). RENT_ASC/RENT_DESC sort the quoted monthly_rent. Mixed-category results retain these different bases; label the control "Quoted monthly rent" and offer the category filter for comparisons on one basis. Never present a per-person quote as the price for a whole room or property. Security deposits use the same basis and an explicit label.

Items and count queries must use identical filters and visibility rules. Obtain the page and totals from a consistent database snapshot for that request. Paginate distinct property IDs before hydrating image collections; joins must not duplicate cards or inflate counts. Use projections/batched relations to avoid a query per card. Inspect query plans for nationwide and city-filtered cases before tuning indexes.

Offset pagination is the initial design because numbered pages and exact totals are useful at launch. It does not freeze results between requests: new listings and rent, inventory, or rating changes may move items across pages. Do not promise snapshot-stable browsing under concurrent writes. Evaluate cursor pagination when observed traffic or deep browsing requires it, retaining bounded queries throughout.

## Browser behavior

- Keep all filters, sort, page, and size in URL query parameters for shareable links and browser Back/Forward. Initial view is nationwide, page 0, size 20, NEWEST.
- Changing any filter, sort, or size resets page to 0. Clear filters also clears geo inputs and resets page/sort/size to their defaults.
- Provide separate controls for bedrooms/BHK and available rooms. Hide bedroom controls for PG_HOSTEL and clear incompatible bedroom parameters when switching categories. Label sharing in occupants per room.
- Show PG prices as "INR <amount> / person / month" and flat prices as "INR <amount> / property / month" on cards, details, creation forms, and the recruiter demo. Display the pricing basis next to rent filters and sort controls; changing price does not change room inventory or imply a bed count.
- Provide accessible Previous/Next controls, numbered pages, matching count, and page-size selection. Display page numbers as one-based while translating to the zero-based API. Enable nearest sorting only with a valid geo query.
- Keep exact totals even when they exceed the offset cap; only offer pages whose offset is <= 10000. At the browsing limit, disable further navigation and prompt for narrower filters even if hasNext indicates more matches.
- Show loading, empty, error, and out-of-range page states. Offer return to the first page when changes leave the requested page beyond the result set. Cancel or ignore stale responses after a query change.
- Cache keys include the complete normalized query (all filters, sort, page, size) and deployment. Invalidate affected public search pages on listing/inventory/review changes; never share personalized or sandbox data through public caches.

## Examples

Nationwide, newest first, first 20 listings:

```text
/api/v1/listings/search?page=0&size=20&sort=NEWEST
```

Two-bedroom flats in Bengaluru with Wi-Fi, at least one available room, rent up to INR 30000, lowest rent first:

```text
/api/v1/listings/search?city=Bengaluru&category=FLAT&minBedrooms=2&maxBedrooms=2&minTotalRooms=1&maxRent=30000&hasWifi=true&sort=RENT_ASC&page=0&size=20
```

## Acceptance criteria for implementation

- Verify nationwide, normalized city/locality, exact bedroom/room counts, inclusive ranges, amenity false versus omitted, gender vacancy, and combined filters against real PostGIS data.
- Verify PG per-person and flat per-property price metadata/labels, category-scoped comparisons, and quoted-value rent filters/sorts without accidental multiplication by occupants or rooms.
- Verify first/middle/last/empty/out-of-range pages, totals and flags, invalid page/size/offset, and request snapshot consistency.
- Verify each sort, ties across page boundaries on unchanged data, unrated owners last, and nearest ordering in metres; reject distance sort without geo inputs and all unknown sort values.
- Verify paused/private records and other-deployment data cannot affect results or counts. Multiple images must not duplicate a listing or inflate totals.
- Verify invalid and incompatible inputs return 400, while valid contradictory filters return an empty result. Record query plans for representative nationwide and multi-filter searches.
- End-to-end checks cover URL sharing, Back/Forward, filter/sort/size page reset, empty results, stale requests, and mobile/keyboard search controls.
