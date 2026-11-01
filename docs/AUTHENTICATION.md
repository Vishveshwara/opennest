# OpenNest authentication decisions

Status: Phase 2 implements Google OpenID Connect, Redis sessions, CSRF/logout, private profiles, and derived trust badges. Google login is disabled by default until the selected deployment receives credentials and exact origins. Live Google registration, browser smoke testing, and hosted deployment remain unverified. WhatsApp and phone verification remain deferred and unavailable.

With Google disabled, the [restrictive security baseline](BACKEND_HTTP_ERRORS.md) denies normal requests and exposes no identity routes. Enabling Google replaces that baseline with identity-owned security. Neither mode provides Basic authentication, a generated password, a framework login page, or demo authentication. Configuration and verification commands are in the [Phase 2 handoff](IDENTITY_HANDOFF.md).

## Initial Google login

- Spring Boot is the authentication authority. Use Spring Security's Google OIDC client and Authorization Code flow with PKCE S256, state, and nonce bound to the initiating browser. Use maintained framework support; do not hand-write token validation or a parallel NextAuth login system.
- Request only openid/email/profile. Configure exact allowlisted redirect URIs and use HTTPS in production. Validate issuer, audience, signature, expiry, nonce, and authorization response state. Missing or mismatched checks fail closed.
- Identify the account by the verified issuer and subject (`iss`, `sub`), not email. Store the provider identity separately from the user. Google email and a typed phone number must never trigger automatic account merging. Linking requires an already authenticated, recently reauthenticated account and proof of the new identity.
- Google signup allows account creation without a phone number. A successful Google login establishes control of a Google account. Indian mobile number and property ownership remain unverified. Return verification state explicitly; never mark a phone verified from a Google profile or reserve a unique user phone from unverified input.
- Require separately verified +91 contact before an owner publishes a listing or exposes a contact number. Until an approved contact-verification method is implemented, publication will return 403 with a clear next step. Authenticated users may create PAUSED drafts and upload photos without a verified phone. Browsing/account creation do not depend on contact verification.
- Use backend-owned opaque sessions through Spring Session Redis. Rotate the session ID after login and privilege changes. Initial target: 30-minute inactivity timeout and 24-hour absolute lifetime; enforce both on the server. Logout invalidates the session and expires its cookie.
- Session cookies are HttpOnly, Secure in production, SameSite=Lax, and host-only, with distinct production/sandbox names. Route browser/API requests through the same site and scope CORS to configured origins. Never put session IDs, provider access/ID/refresh tokens, or client secrets in URLs or browser localStorage. OAuth state/nonce/PKCE challenge parameters may travel in framework authorization URLs; accept the short-lived code only on the configured callback. Never log callback query values or forward them to a frontend URL.
- Keep transient OAuth authorization state in the initiating server session; add CSRF protection to state-changing application requests and logout. Expose a no-store synchronizer-token endpoint for the frontend. An expired/missing session means reauthentication (401 for protected REST endpoints); unavailable Redis means 503, never a demo bypass.
- Production and recruiter backends will use separate redirect origins, Redis/session stores, cookie names/hosts, and identities. If Google login is enabled in the recruiter deployment, it must use its own OAuth registration; canned demo personas do not need live Google accounts. A demo session must never be exchangeable for a real session.
- Trusted sandbox seeding may create fabricated published listings without a real verified phone; this is a sandbox-only fixture exception, not an HTTP publication bypass. Disable sandbox contact actions and use no callable phone numbers. Keep quota/inventory/review fixtures testable without sending real messages.

This intentionally revises the supplied stateless-JWT browser-auth target. One backend-owned session system reduces duplicate login/account-linking logic. Revisit API bearer tokens only for a concrete client requirement.

## Implemented routes when Google is enabled

| Route | Responsibility |
| --- | --- |
| `GET /oauth2/authorization/google` | Spring Security login entry; redirect to Google with browser-bound authorization state. |
| `GET /login/oauth2/code/google` | Spring Security callback; validate/exchange the code and rotate the session. |
| `GET /api/v1/auth/csrf` | Return the initiating session's CSRF token; no-store; may create a pre-login session but does not authenticate the caller. |
| `POST /api/v1/auth/logout` | CSRF-protected session invalidation; expire cookie; return 204. |
| `GET /api/v1/users/me` | Authenticated account and verification state; no-store; 401 when unauthenticated. |

Successful login redirects to the configured frontend origin plus `/`. Failed login redirects to that origin plus `/?login=failed`. Caller-supplied return URLs are ignored. Neither redirect includes provider codes or tokens. Listing, draft, and image routes remain Phase 3 work and must reuse the session and CSRF policy.

An invalid callback or replay does not revoke an existing authenticated session. Logout remains a CSRF-protected POST. If account persistence fails after valid provider authentication, the new authentication state is cleared before returning the safe error.

`GET /api/v1/auth/csrf` returns `{token, headerName}`. Fetch a fresh token after login because authentication replaces the session and clears the pre-login token. Send the token through the returned header name for logout and future mutations. `GET /api/v1/users/me` returns `id`, `fullName`, `role`, `phoneNumber`, `phoneVerified`, `averageRating`, `reviewCount`, `brokerReportCount`, and `trustBadge`. Only the caller's account is selected. Both routes use `Cache-Control: no-store`.

The login transaction creates a user and provider identity together. A uniqueness conflict on issuer/subject rolls back the losing transaction and reads the winning account. Email is metadata and never merges users. No account-linking or contact-edit endpoint exists. After login, Redis stores an OpenNest principal containing the user UUID, deployment realm, and authentication time. Provider tokens and authorized clients are discarded. Session expiry requires a new login.

Trust precedence was approved for Phase 2: reports >= 1 with average < 3 gives `CRITICAL_BROKER_AND_LOW_RATING`; otherwise reports >= 1 gives `BROKERAGE_WARNED`; otherwise reviews >= 2 with average < 3 gives `LOW_RATINGS_BEWARE`; otherwise reviews >= 3 with average >= 4.5 gives `TOP_RATED`; otherwise `STANDARD`. No reviews means `STANDARD` and a NULL average. The calculator does not change listings. Brokerage warnings must describe allegations.

The planned public website is `https://opennest.co.in`. The proposed frontend/API domain arrangement, preview restrictions, and host-only cookie implications for server rendering are documented in the [deployment plan](DEPLOYMENT_STACK.md#network-and-browser-session-topology). API and sandbox origins, domain control, DNS/TLS, routing, and Google OAuth registrations remain unverified or undecided; the website choice alone does not configure authentication.

## Review of user-sent WhatsApp verification

The proposed wa.me link/QR and incoming webhook can associate an incoming sender with a challenge, but that alone is not a reviewed browser-login protocol. A forwarded link can trick a phone owner into approving a challenge opened in somebody else's browser. A signed webhook confirms the sender/provider event; it does not prove the sender intended to authorize that browser. This is the reason to begin with standard Google OIDC.

Before any WhatsApp option can be enabled:

- Validate real account/number availability, allowed use, production onboarding, test-number restrictions, provider terms, and current rates against Meta's current documentation/account console. A developer test number is not evidence of eligibility for public production authentication.
- Verify webhook subscription challenges using a separate verification token. Validate POST events with X-Hub-Signature-256 using the Meta app secret and exact raw request bytes before trusting any sender/message fields; compare signatures in constant time. A subscription token does not authenticate POST payloads.
- Accept only message events for the configured business account/recipient; distinguish delivery/status notifications. Deduplicate provider message IDs, handle retries/out-of-order events, and check expiry server-side. Never trust an unsigned client-submitted `from` field.
- Use a cryptographically random challenge with at least 128 bits of entropy, TTL at most five minutes, stored hashed and bound to the expected normalized +91 number, initiating server session, and action (login versus linking). A six-digit code cannot be the sole public login bearer token. Enforce attempt/request/poll limits.
- If polling is retained, use the initiating browser session, no-store responses, a minimum two-second interval, and stop on expiry/completion. A webhook may update challenge state but cannot set a browser session or expose credentials through a public status URL. Completion must be CSRF-protected and atomically single-use with replay/concurrency tests.
- Explicitly solve the forwarded-link/session-relay threat before allowing authentication or contact linking. Sender equality and high entropy alone do not solve it. Do not enable the proposed automatic-login-on-webhook behavior merely because a matching message arrived.
- Evaluate provider-delivered WhatsApp authentication templates as the alternative phone-verification path, including template/account requirements and spend limits. Choose and document the reviewed flow before adding live challenge/webhook endpoints.
- No outbound reply is required by the proposed inbound-only design, but do not turn that into a zero-cost guarantee. Infrastructure, number/provider costs, eligibility, policy changes, and any later outbound messages need separate evaluation. No hardcoded bot number, live message sending, or production credentials in fixtures.

## Provider fact-check and sources

Checked 20 October 2026. Meta's public pricing overview describes charging for delivered business messages and links current rate cards. It does not establish a lifetime-free service or zero-onboarding guarantee for this custom login use case. Some detailed Meta pages required login or returned rate-limit errors during review; exact production test-number eligibility and account requirements remain unverified. Do not encode the claimed INR 0.115 reply price as a constant or publish "free forever" copy.

- [Google OpenID Connect and server-flow validation](https://developers.google.com/identity/openid-connect/openid-connect)
- [Spring Security Google login configuration](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)
- [Meta official webhook signature example](https://github.com/fbsamples/whatsapp-api-examples/blob/main/signature-validation-with-webhooks-payloads/app.py)
- [WhatsApp official pricing overview and linked rate cards](https://business.whatsapp.com/products/platform-pricing)
- [Meta WhatsApp production setup documentation](https://developers.facebook.com/documentation/business-messaging/whatsapp/get-started)

## Acceptance criteria for implementation

- Google success, denial, expired/reused code, invalid state/nonce/issuer/audience/signature, unsafe redirect, and PKCE mismatch.
- Session rotation, idle/absolute expiry, logout, CSRF rejection, unavailable Redis, and production rejection of demo sessions.
- Separate users for unrelated subjects even if emails collide; account linking requires proof of both identities. Never fabricate a verified phone for Google users.
- Publication blocked without a verified owner contact; authenticated drafts/uploads and public browsing remain available. Sandbox fixture seeding cannot activate a production bypass. Contact verification has its own tests when a method is selected.
- Every deferred WhatsApp challenge/webhook/status path remains unavailable until explicitly implemented and enabled after review. Later tests must cover spoofed events, duplicates, mismatched phones/recipients, expiration, racing completions, session relay, and bounded polling.
