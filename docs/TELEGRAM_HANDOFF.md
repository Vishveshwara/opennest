<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:f30797d7ad434f726a828a020ee91687c1b97311e1ea5a8dc3e2191e17786cf1 -->

<!-- coderabbit-ai-generated-section:v1:ecfcef91dc56fa90b4f9f2a8e4d72096c55198c6a0f112792567c60deccea56f -->

# OpenNest Telegram login setup

Telegram Bot API is OpenNest's only authentication provider by maintainer decision on 10 November 2026. The existing bot token is stored only in the Git-ignored local .env. The setup helper successfully checked getMe/getWebhookInfo, discovered the bot username, generated private webhook/HMAC keys, enabled explicit local polling, and removed old provider settings. It sent no message and changed no webhook.

## Start locally and test with Postman

Run from the repository root with Java 25 and Linux Docker:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-telegram-local.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Build
```

The first command reuses the private token or requests hidden input if absent. It preserves unrelated configuration and refuses registered webhooks/concurrent .env edits. No tunnel or production hosting is needed for explicit local polling. The second starts stores, builds the backend, and runs it on localhost:8080.

Import [the Telegram Postman collection](postman/OpenNest-Telegram.postman_collection.json), then:

1. Send Get CSRF token. Keep Postman's localhost cookie jar enabled; no browser sign-in or copied session cookie is required.
2. Set phoneNumber to your own +91 contact in a private local variable and start a challenge. Open the returned botUrl in Telegram, press Start, and share your own contact through the native button.
   If Share my contact is hidden, open Telegram's reply keyboard near the message input. You can use the same botUrl and account in the mobile app while keeping Postman on your computer. Complete within five minutes of challenge creation; an expired contact update cannot produce an OTP. Create a new challenge after the cooldown and use the new link if needed.

3. Put the latest six-digit code in the private local otp variable and submit completion from the same cookie session. Existing linked accounts return AUTHENTICATED. A new user with no fullName receives NAME_REQUIRED and stays anonymous; set the private fullName variable and repeat the same request/code within the original five-minute lifetime. AUTHENTICATED rotates the session cookie.
4. Fetch CSRF again. The pre-login token is invalid after authentication. Read `GET /api/v1/users/me` to see your own profile and phoneVerified=true.
5. Use authenticated listing APIs with the fresh token. Import [the listing collection](postman/OpenNest-Listings.postman_collection.json) and follow [the full Postman guide](POSTMAN_API_GUIDE.md), postponing logout until done. Publication still requires required validated images and the transactional listing quota.
6. Send `POST /api/v1/auth/logout` with the fresh CSRF token to invalidate the session. A later profile request returns 401.

Do not share Postman exports containing real contacts, codes, cookies, or secrets. The repository collection leaves all private inputs empty. Challenge status requests must be at least two seconds apart. A challenge lasts five minutes, permits five bad-code attempts, and restarts require 60 seconds. Fresh-code resends allow three generations, 30 seconds apart, without extending expiry or resetting attempts. CODE_READY delivery retries reuse the same generation immediately. The UI displays the resend/restart countdowns and remaining allowance. At most five starts per rolling hour are allowed per initiating session and expected contact. Authenticated accounts must log out before starting another sign-in.

## Configuration and routes

| Setting | Behavior |
| --- | --- |
| OPENNEST_TELEGRAM_AUTH_ENABLED | Default false; set true to enable. Explicit sole-provider opt-in, PRIMARY only |
| OPENNEST_TELEGRAM_POLLING_ENABLED | Default false; set true to enable. Explicit development polling; false selects webhooks |
| TELEGRAM_BOT_USERNAME | Bot username discovered by setup |
| TELEGRAM_BOT_TOKEN | Private BotFather token |
| TELEGRAM_WEBHOOK_SECRET | Independent 32 to 256 safe-character secret |
| TELEGRAM_OTP_HMAC_KEY | Independent secret, at least 32 UTF-8 bytes |

Production forbids polling and sandbox secrets. SANDBOX_ bot settings are reserved for separation guards, not a recruiter login method. Removed Google enablement and OTP_PROVIDER/CONSOLE_DEV are rejected. Origins remain exact, separately configured backend/frontend values.

| Route | Behavior |
| --- | --- |
| `GET /api/v1/auth/csrf` | Creates/uses anonymous session, returns token/headerName |
| `POST /api/v1/auth/telegram/challenges` | Anonymous session + CSRF, body {phoneNumber}, returns challengeId/botUrl/expiresAt/restartAt/status |
| `GET /api/v1/auth/telegram/challenges/{id}` | Initiating-session-only no-store status with expiresAt/resendAt/resendsRemaining |
| `POST /api/v1/auth/telegram/challenges/{id}/resend` | Same session + CSRF, body {}; rotate an eligible fresh code or retry pending delivery; return status/deadlines/remaining allowance |
| `POST /api/v1/auth/telegram/challenges/{id}/complete` | Same session + CSRF, body {code, fullName?}; correct new-user OTP without a name returns NAME_REQUIRED while anonymous; repeat with name to create/sign in. Existing linked accounts authenticate directly. AUTHENTICATED rotates session |
| `GET /api/v1/users/me` | Authenticated caller's private profile |
| `POST /api/v1/auth/logout` | CSRF-protected logout, 204 |
| `POST /api/v1/webhooks/telegram` | Webhook transport only, exact provider secret header, no browser CSRF |

Account JSON rejects unknown/duplicate fields and trailing values, capped at 8 KiB. Webhooks are capped at 64 KiB. No response exposes codes or Telegram IDs. Fixed safe errors distinguish invalid input, missing authentication, access denial, conflicts, explicit rate limits, dependency outages, and unexpected failures.

## Data and security

[AUTHENTICATION.md](AUTHENTICATION.md) describes the sole-provider decision and existing-account recovery. [TELEGRAM_CONTACT_VERIFICATION.md](TELEGRAM_CONTACT_VERIFICATION.md) defines contact/update/transport checks.

No account or phone is reserved before successful OTP completion. Identity is the validated Telegram sender, never a typed phone/email. V1-V4 remain unchanged; V5 adds four-column, short-lived login receipts. Eight application tables now contain 87 columns; the six ORM mappings still cover 76 columns. Current contact provenance remains one private V4 receipt per verified account. No chat history, vCard, username, or plaintext code is retained.

Existing verified Telegram associations recover their accounts only when unambiguous. Accounts with only old provider identities are preserved and need explicit recovery; no accounts merge by matching contact. Old provider sessions are invalidated through the versioned Telegram session namespace. Challenge and identity advisory locks plus user locking make signup and durable completion atomic. Receipt retries survive Redis cleanup failure within the original challenge lifetime; expired receipts are deleted hourly.

The approved evidence is access to the Telegram account associated with its own shared +91 number. It does not prove fresh SIM possession or property ownership. Bot callbacks alone never log a browser in. Actual response bodies and headers share bounded client deadlines/byte limits. Sending stays within private-chat/aggregate budgets with provider retry_after backoff and no paid sends.

## Verification status

On 10 November 2026, the clean Java 25/Linux amd64 Docker gate passed all 553 tests: 427 default, 53 schema/persistence, 25 datastore isolation, 17 listing and 31 Telegram authentication integration cases. There were no failures, errors or skips. Six Python report-gate tests, 20 isolated launcher regressions, wrapper/context checks, packaged boundaries, OAuth/OIDC artifact absence and whitespace validation passed. The three added all-mode cases exercise every listing HTTP route through Telegram-created cookies, with actual image validation and controlled storage. Real-store tests include session rotation/CSRF/logout/expiry, concurrent signup, returning identity, legacy recovery/refusal, and database/Redis outage rejection. Setup syntax and fake-provider safety checks pass. The live token's bot identity and empty webhook state were checked successfully on 10 November without messages. The maintainer subsequently completed real local self-contact/OTP authentication and confirmed the private profile with phoneVerified=true on 10 November. That manual evidence does not include live listing writes, repeated login/failure/logout or R2. Hosted HTTPS webhooks, CI, deployment and production readiness remain unverified. [The expanded API verification record](API_VERIFICATION.md) records current cross-API evidence.


<!-- /coderabbit-ai-generated:v1 -->

## Signup names and missed codes

Start with the Telegram-linked +91 phone, share your own contact, and enter the latest code in the browser where the challenge began. Existing verified Telegram accounts sign in automatically. New users then see Full name and Create account. The name is saved only when that same code/challenge completes successfully. Existing names are preserved. Name normalization uses NFKC and whitespace collapse, with a 120-code-point maximum; control characters are rejected. users.full_name already exists, so no migration is added.

For a missed code, keep the current challenge and use Resend code after its visible 30-second cooldown. Only the newest code is valid. After three fresh resends, use Start again, subject to its separate 60-second cooldown and existing five-starts-per-hour limits. A failed prepared delivery remains retryable as Retry code delivery, even at zero fresh resends remaining. This reuses its code and original expiry. Plain /start can resume a bound chat; it cannot identify a browser flow that was never linked. Cancelled/expired links give guidance to use the current browser challenge link.

The browser keeps the code/name only in component memory while finishing signup. Refreshing loses that UI state; no code/name/challenge is saved to localStorage or sessionStorage. Create a fresh challenge after an expired or lost browser flow and use its new link. Real Telegram signup, returning login and resends remain maintainer smoke checks; automated verification uses controlled bot providers and fabricated contacts.

## Signup and retry follow-up verification, 14 November 2026

The clean Java 25 schema/isolation gate passed 650 tests with no failures, errors or skips. New controlled-provider tests cover anonymous NAME_REQUIRED with no account/contact writes, name normalization and invalid-name retry, existing-user sign-in without name changes, resend cooldown/cap, old-code invalidation, unchanged expiry/attempts, concurrent resend generation, original-session/CSRF rejection, plain /start/reopened-link recovery, stale preparation rejection and delivery-failure retry. Three HTTP bot cases cover fixed browser guidance and keyboard removal. The separately selected real-store browser wrapper passed all four scenarios, including the new-user name form, bringing the final Java report set to 651.

Frontend types, lint, production build and 64 tests passed. Seven signup/resend UI cases cover name completion, returning sign-in, resend/restart timers, failed delivery, allowance exhaustion, and retrying a prepared delivery at zero fresh allowance. Localhost and LAN browser checks passed with cookies, CSRF, no-store, invalid-input handling and logout. The local backend was restarted with the new artifact; the development frontend picked up the new flow. No real OTP/contact was submitted or bot message initiated by verification. Live signup/returning/resend smoke tests, physical-device behavior and remote CI remain maintainer checks.
