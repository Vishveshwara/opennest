# OpenNest authentication decisions

On 10 November 2026 the maintainer selected Telegram Bot API as OpenNest's only authentication method, replacing the earlier Google OIDC design. Google routes, OAuth client dependencies, credential configuration, and login handlers are removed. Spring Security and backend-owned Redis sessions remain. [Telegram setup and Postman guide](TELEGRAM_HANDOFF.md) describes the current flow.

## Login and account identity

1. Fetch `GET /api/v1/auth/csrf` to establish an anonymous browser session and obtain its synchronizer token.
2. Submit the expected +91 contact to `POST /api/v1/auth/telegram/challenges` with that cookie and CSRF token. A five-minute challenge binds the initiating session, realm, contact, random start-token digest, and bounded attempts/rates. No account or user phone is reserved yet.
3. Open the returned botUrl and press Start. Only a fresh, unforwarded private message from a non-bot user can bind the first sender. Share the sender's own contact through the native button. Require chat.id=from.id=contact.user_id and the expected +91 number. Missing user_id fails closed.
4. The bot sends a six-digit OTP to that private chat. Enter it in the initiating browser through `POST /api/v1/auth/telegram/challenges/{id}/complete` with CSRF protection. Neither a bot event nor status polling signs anyone in.
5. Atomic completion resolves the stable Telegram sender identity, persists verified contact and receipts, rotates the session ID, clears the old CSRF token, and explicitly saves a minimal realm-bound principal. Fetch a fresh CSRF token before subsequent mutations.

The maintainer approved this contact evidence on 9 November 2026 and its use as sole authentication on 10 November. It proves access to the Telegram account associated with the shared number, not fresh SIM possession or property ownership. A six-digit code alone is never a public login bearer: the initiating private browser session and completed self-contact challenge are required. The flow does not claim resistance to active OTP phishing.

Account identity is issuer https://telegram.org plus the validated Telegram sender ID. Typed phones, display names, usernames, or email never select or merge users. New account names default to OpenNest user. Returning login must match the existing verified contact; contact replacement and identity linking are outside this flow. A phone already held by another account conflicts without disclosing that account.

## Preserving existing data

V1 through V4 and all existing accounts/listings remain intact. A single existing verified contact receipt for a Telegram sender can establish that user's Telegram identity during successful OTP completion. Ambiguous legacy receipts conflict; an account with only an old provider identity requires a separately reviewed recovery process. There is no email/phone auto-linking or anonymous migration endpoint.

The Redis namespace changes to opennest:<realm>:telegram-auth:v1:sessions, invalidating old provider sessions. Reverting application code must use its own session namespace and retain V5; rollback must not drop private receipts or restore obsolete authentication as an unreviewed production option.

V5 adds short-lived telegram_login_receipts for safe completion retries while the original challenge remains valid. PostgreSQL advisory locks serialize each challenge and Telegram identity, followed by the user-row lock. User, identity, verified contact, current contact receipt, and login receipt commit together. A database rollback keeps the Redis claim retryable. Committed receipts permit same-session retries after Redis challenge loss. The controller carries private retry binding across session rotation. Expired receipts are ignored immediately and deleted hourly.

## Sessions, authorization, and isolation

Sessions are opaque, HttpOnly, host-only, SameSite=Lax, Secure in production or HTTPS development. Idle expiry is 30 minutes; authenticated absolute expiry is 24 hours. Logout is CSRF-protected `POST /api/v1/auth/logout` and invalidates the session. `GET /api/v1/users/me` returns only the caller's authorized profile. Private and authentication responses are no-store.

Telegram authentication requires explicit OPENNEST_TELEGRAM_AUTH_ENABLED=true and is unavailable in the recruiter sandbox. When disabled, anonymous listing browsing remains available with exact-origin CORS while owner/account routes stay denied. Sandbox personas remain a separate future phase. Untrusted headers never select stores or grant access.

Publication still requires verified +91 contact, required validated images, ownership, and the transactional five-listing quota. Telegram signup verifies contact before creating an authenticated user, but the publication gate remains necessary for legacy and fabricated test accounts. Browsing needs no login. Owners without Telegram cannot authenticate through this selected method.

Production requires secret-authenticated HTTPS webhooks. Explicit development polling uses one leased consumer and persisted offsets; it refuses an existing webhook and is forbidden in production. Console OTP, password, Basic, automatic webhook login, WhatsApp, SMS, and paid Gateway fallbacks are unavailable. Provider/dependency outages fail closed with fixed safe errors.

## Sources and verification

- [Telegram Bot API contact request and Contact fields](https://core.telegram.org/bots/api#keyboardbutton)
- [Telegram bot deep links](https://core.telegram.org/bots/features#deep-linking)
- [Spring Security session authentication and explicit context persistence](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Current setup, privacy limits, and verification evidence](TELEGRAM_HANDOFF.md)

Automated checks cover original-session/CSRF enforcement, rotation, old-token rejection, logout, absolute expiry, realm isolation, stable returning identity, concurrent signup/completion, legacy recovery/refusal, phone conflicts and rollback, provider retry, duplicate/untrusted contacts, bounded HTTP calls, and removed-provider route denial. Live bot identity was checked during setup. On 10 November 2026 the maintainer completed a real local self-contact/OTP login in Postman and read the authorized profile with phoneVerified=true. Hosted readiness, live repeated-login/failure/logout and R2 remain separate checks. [API_VERIFICATION.md](API_VERIFICATION.md) records cross-API coverage and OAuth removal.
