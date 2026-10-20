# OpenNest authentication decisions

Status: Phase 0 design only. No login, provider integration, session service, or webhook is implemented. The maintainer requested Google and WhatsApp as options and asked to start with the safer option. Initial implementation uses Google OpenID Connect through Spring Security; WhatsApp is deferred and disabled by default.

## Initial Google login

- Spring Boot is the authentication authority. Use Spring Security's Google OIDC client and Authorization Code flow with PKCE S256, state, and nonce bound to the initiating browser. Use maintained framework support; do not hand-write token validation or a parallel NextAuth login system.
- Request only openid/email/profile. Configure exact allowlisted redirect URIs and use HTTPS in production. Validate issuer, audience, signature, expiry, nonce, and authorization response state. Missing or mismatched checks fail closed.
- Identify the account by the verified issuer and subject (`iss`, `sub`), not email. Store the provider identity separately from the user. Google email and a typed phone number must never trigger automatic account merging. Linking requires an already authenticated, recently reauthenticated account and proof of the new identity.
- Google signup may create a user without a phone number. It proves control of a Google account, not ownership of an Indian mobile number or property. Return verification state explicitly; never mark a phone verified from a Google profile.
- Require separately verified +91 contact before an owner publishes a listing or exposes a contact number. Until an approved contact-verification method is implemented, publication stays blocked with a clear next-step response. Browsing/account creation must not pretend this dependency is solved.
- Use backend-owned opaque sessions through Spring Session Redis. Rotate the session ID after login and privilege changes. Initial target: 30-minute inactivity timeout and 24-hour absolute lifetime; enforce both on the server. Logout invalidates the session and expires its cookie.
- Session cookies are HttpOnly, Secure in production, SameSite=Lax, host-only, with distinct production/sandbox names. Route browser/API requests through the same site and scope CORS to configured origins. No credentials in URL query strings or browser localStorage.
- Keep transient OAuth authorization state in the initiating server session; add CSRF protection to state-changing application requests and logout. Expose a no-store synchronizer-token endpoint for the frontend. Session loss or Redis failure requires reauthentication or an explicit unavailable response, never a demo bypass.
- Production and recruiter backends use separate OAuth registrations, redirect origins, Redis/session stores, cookie names/hosts, and identities. Demo personas must not be exchangeable for a real session.

This intentionally revises the supplied stateless-JWT browser-auth target. One backend-owned session system reduces duplicate login/account-linking logic. Revisit API bearer tokens only for a concrete client requirement.

## Planned routes

| Route | Responsibility |
| --- | --- |
| `GET /oauth2/authorization/google` | Spring Security login entry; redirect to Google with browser-bound authorization state. |
| `GET /login/oauth2/code/google` | Spring Security callback; validate/exchange the code and rotate the session. |
| `GET /api/v1/auth/csrf` | Return the initiating session's CSRF token; no-store; does not authenticate the caller. |
| `POST /api/v1/auth/logout` | CSRF-protected session invalidation; expire cookie; return 204. |
| `GET /api/v1/users/me` | Authenticated account and verification state; no-store; 401 when unauthenticated. |

Keep successful and failed callback redirects on a configured OpenNest origin. Never let a return URL become an open redirect or carry provider/access/session tokens.

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
- Publication blocked without a verified owner contact; authenticated browsing remains available. Contact verification has its own tests when a method is selected.
- Every deferred WhatsApp challenge/webhook/status path remains unavailable until explicitly implemented and enabled after review. Later tests must cover spoofed events, duplicates, mismatched phones/recipients, expiration, racing completions, session relay, and bounded polling.
