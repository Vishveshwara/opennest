# Test OpenNest APIs in Postman

Use the Postman desktop app with the local backend running. No bearer token or copied browser cookie is required; the backend uses the cookie created during Telegram login. Use localhost consistently, since changing to 127.0.0.1 changes cookie scope.

## Start and sign in

1. Start Docker Desktop's Linux engine and use a Java 25 JDK.
2. From the repository root, run the existing private setup if needed, then start OpenNest:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-telegram-local.ps1
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Build
   ```

3. Import [OpenNest-Telegram.postman_collection.json](postman/OpenNest-Telegram.postman_collection.json).
4. Keep Postman's cookie jar enabled. Use No environment, or avoid environment variables with the same names as collection variables. In collection Variables, leave baseUrl=http://localhost:8080 and set only your own phoneNumber locally, +91 followed by ten digits. Keep codes, contacts and cookies private.
5. Send requests 1 and 2 to fetch anonymous CSRF and create a challenge. Scripts save csrfToken/challengeId automatically.
6. Open the returned botUrl, press Start and use Share my contact. This is a reply keyboard near the typing area, not a button attached below the message. If hidden, use Telegram's keyboard icon. You may open the same botUrl/account in Telegram mobile while retaining the original Postman session on your computer.
7. Set the local otp variable and send request 4. Expect status AUTHENTICATED. Request 3 is optional status polling and never authenticates; wait at least two seconds between polls.
8. Send requests 5 and 6 to refresh CSRF after rotation and read the private profile. Expect phoneVerified=true. Postpone requests 7 and 8, which log you out, until after testing listings.

Challenges expire five minutes after creation, including time spent finding the contact button. If contact was shared after expiry, create a new challenge and use its new botUrl. Wait at least 60 seconds before restarting, with at most five starts per rolling hour per session/contact. Never type your phone as a chat message instead of sharing the native self-contact.

## Test listing APIs in the same cookie session

Import [OpenNest-Listings.postman_collection.json](postman/OpenNest-Listings.postman_collection.json). Its requests share the localhost cookie jar with the login collection but have their own variables. Send them individually; the media steps require your file input.

1. Send listing request 1 to fetch CSRF into this collection, then request 2 to confirm the authenticated profile.
2. Inspect the fabricated PG example in request 3, then send it once. It creates a retained PAUSED draft and saves listingId/version. Any Indian city is supported; the example city is not a default or allowlist. Deletion is not implemented, so reuse the saved ID instead of creating repeated drafts.
3. Send requests 4 and 5 for GET /api/v1/listings/mine and GET /api/v1/listings/{id}. Your draft is visible to you; its owner response includes version and private address/coordinates.
4. Send request 6 for nationwide search. PAUSED drafts are excluded even when their owner searches. Search/detail responses remain no-store, and anonymous public data omits contact/private address/precise coordinates.
5. For media, configure R2 explicitly as described in [the listing handoff](LISTINGS_HANDOFF.md). With storage disabled, requests 7 and 9 return 503 by design. Do not enable it with placeholder credentials or use a backend upload proxy.
6. Choose a small valid JPEG/PNG/WebP. Set local imageByteSize to its exact byte count, imageContentType to its actual MIME type, and segment=ROOM. For a local file, obtain its size without printing its contents:

   ```powershell
   (Get-Item -LiteralPath 'C:\path\to\your\image.png').Length
   ```

7. Send request 7 to reserve the upload. It saves imageId/uploadUrl/signed headers. In request 8 choose Body > binary and select that exact file. Send the PUT directly to the returned storage URL; its pre-request script applies the signed headers. This request has no OpenNest CSRF header.
8. Send request 9 to finalize/validate. Expect dimensions and final metadata. For this PG example, repeat requests 7-9 with segment=BATHROOM before publishing. FLAT requires ROOM/HALL/BATHROOM/KITCHEN. Each image must have a separate reservation and completion.
9. Send request 10 with the saved current version to publish. A prepared PG becomes ACTIVE when rooms are available. A missing required photo/contact or a quota conflict returns 409 or the documented contact-required 403. Do not bypass these checks.
10. Request 11 sets the PG room counters to zero. A published PG becomes FULL_OCCUPIED, with a new saved version. To find it through search, explicitly use status=FULL_OCCUPIED. ENTIRE_FLAT uses wholeFlatAvailable/maxOccupants rather than this room payload.
11. Request 12 pauses the listing, saving its latest version. Anonymous detail becomes 404 and public search excludes it; you can still read it through mine/detail. The retained draft is not deleted.
12. When finished, return to the login collection, refresh CSRF with request 5 and send logout request 7. Request 8 should return 401. Public listing browsing remains available after logout.

Inventory bodies and all mode-specific fields are defined in [LISTING_MODEL.md](LISTING_MODEL.md). Filters, sorting and pagination are defined in [LISTING_SEARCH.md](LISTING_SEARCH.md). Prices are INR per person/month for PG, per room/month for FLAT ROOM and per property/month for ENTIRE_FLAT. Occupancy capacity does not multiply the price.

## Understand failures

| Result | Meaning and next step |
| --- | --- |
| Connection refused | Keep the backend terminal open and check the configured port |
| 400 | Inspect exact JSON types, required/mode-specific fields, duplicates and query parameters |
| 401 | Authenticate again with the same cookie host; sessions may have expired or been logged out |
| 403 on POST/PATCH | Fetch a fresh CSRF token in the collection making the request; retain cookies |
| 404 for another owner's PAUSED detail | Expected private-read protection |
| 403 for a different owner's write with valid CSRF | Expected owner authorization denial |
| 409 | Check challenge expiry/code/lockout, current listing version, required images or quotas |
| 429 | Respect challenge/status/image limits instead of repeatedly restarting |
| 503 on media | Storage may be disabled/unconfigured; this is separate from successful session authentication |

Phone reveals, reviews/reports and recruiter persona APIs are implemented in Phase 4; reset and telemetry are operator CLI-only. Frontend and deployment remain future work. There is no active Google/OAuth route. [API_VERIFICATION.md](API_VERIFICATION.md) records automated coverage, OAuth removal and the distinction between confirmed local behavior and hosted readiness.

## Test Phase 4 interactions and sandbox

Import [OpenNest-Interactions.postman_collection.json](postman/OpenNest-Interactions.postman_collection.json). Authenticate PRIMARY using the Telegram collection, set listingId to another owner's published listing, and refresh CSRF in this collection. Reveal before reviewing/reporting; repeat reveal/report requests to check idempotency. Reviews and caller state contain no contact data.

For the separate sandbox backend, enable persona switching, fetch its own CSRF, select seeker-new, then refresh CSRF after rotation. Read nationwide listings and set sandboxListingId to a fabricated listing before reviewing it. Sandbox reveal stays forbidden. Follow [the Phase 4 handoff](PHASE4_HANDOFF.md) for isolated startup and operator CLI reset/telemetry. Keep runtime contacts/tokens out of exported collections.
