<p align="center">
  <img src="frontend/src/app/icon.png" width="112" height="112" alt="OpenNest app icon: a bird nesting on the roof of a house">
</p>

# OpenNest

A 3 BHK with one room free is great news if you’re moving solo. Less so if your whole group chat is moving in. OpenNest helps you find a whole flat, a room in a shared flat, or a PG/hostel directly from owners across India, with zero brokerage.

**App development is complete. Deployment is in progress.** The planned public home is `https://opennest.co.in`. In the meantime, you can [try OpenNest locally with Docker Desktop](docs/LOCAL_DOCKER_TESTING.md).

## Looking for a place?

- **Start anywhere in India.** Browse without signing in. Leave the city blank to search nationwide, or narrow it down by locality, budget, BHK, rooms available and amenities. Share the link with your flatmates and they’ll see the same search filters.
- **Know what the rent covers.** Every monthly price says whether it’s for the whole flat, one room or one person in a PG. Listings also show how many people the owner allows and what’s currently available.
- **Get a feel for the neighbourhood.** Browse the photos and open the approximate map when you want a look around. The exact address stays private.
- **Talk to the owner.** Sign in by sharing your own +91 contact privately with the Telegram bot and entering the OTP in the browser where you started. You can reveal owner contact details for up to ten distinct listings per day.
- **Read what other renters experienced.** Once you’ve revealed the contact for a listing, you can review the owner or report a request for brokerage. Public warnings identify those reports as allegations.

## Have a place to rent?

Start with a private draft. Set the monthly rent, occupancy limit and availability, then add photos of the required spaces. OpenNest checks the photos and requires a verified contact number before you publish. When a flat fills up or a room opens, update the vacancy on the same listing. You can also pause it while you take a break from enquiries.

## Take it for a spin

The local recruiter sandbox has 17 sample listings across five Indian cities. Open `/recruiter-sandbox` on the local sandbox site, try a renter persona, then switch to an owner and update a vacancy. The people and properties are fictional, so you can explore both sides of OpenNest. [Run the local demo](docs/LOCAL_DOCKER_TESTING.md#5-test-authenticated-behavior)

## Under the roof

| Part | Built with | Deployment target |
| --- | --- | --- |
| Website | Next.js, React and TypeScript | Vercel |
| API | Java 25 and Spring Boot | Render |
| Listing and account data | PostgreSQL with PostGIS | Supabase |

The backend owns the Redis-backed login sessions. Listing photos use private Cloudflare R2 storage. For setup, testing and contributions, see the [deployment guide](docs/DEPLOYMENT_SETUP.md), [Postman walkthrough](docs/POSTMAN_API_GUIDE.md) and [contributing guide](CONTRIBUTING.md).

## License

OpenNest is open source under the [GNU Affero General Public License, version 3 only](LICENSE) (`AGPL-3.0-only`).
