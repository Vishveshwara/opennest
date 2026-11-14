# OpenNest Telegram verification contract

Telegram Bot API is the sole authentication method as of 10 November 2026. The maintainer approved its self-contact evidence on 9 November and its use for login on 10 November. [Authentication decisions](AUTHENTICATION.md) define account/session behavior; [setup and Postman flow](TELEGRAM_HANDOFF.md) provides runnable steps.

## Contact evidence

An arbitrary chat OTP cannot verify a typed number. The native [request_contact button](https://core.telegram.org/bots/api#keyboardbutton) must supply the expected +91 number, with contact.user_id equal to both from.id and private chat.id. [Contact.user_id is optional](https://core.telegram.org/bots/api#contact); missing or mismatched values fail closed. Reject bot senders, group/forwarded messages, stale timestamps, and contacts outside the active challenge.

A random 256-bit start token contains no phone, account ID, OTP, or browser credential; Redis stores its digest. The first valid private sender binds atomically. Six-digit codes derive from random per-challenge salt and keyed HMAC, with only a verification HMAC retained. Only an original-session, CSRF-protected code submission can create an authenticated session. A bot update, delivery receipt, or status poll cannot.

This establishes Telegram-account access associated with the shared contact. It does not establish fresh SIM/SMS possession, property ownership, or protection against active OTP phishing. No identity is automatically linked by email, display name, username, or typed contact.

## Limits and private state

Challenges last five minutes from creation, allow five incorrect code attempts, and have one active challenge per initiating session. Restarts cancel the previous challenge with a 60-second cooldown. A CODE_SENT challenge permits three fresh-code resends, 30 seconds apart. Each rotates the salt/HMAC and invalidates the old code without extending expiry or resetting bad attempts. CODE_READY delivery retries reuse the prepared generation, even when the fresh-resend allowance is exhausted. Original-session/CSRF protections remain; private /start resumes the bound chat or gives browser guidance. Starts are limited to five per rolling hour per session and expected contact; status polling is limited to once per two seconds.

Realm-prefixed Redis scripts atomically enforce binding, state, attempts/rates, update leases/deduplication, and polling offsets. No new account or verified contact is persisted before OTP completion and a supplied signup name. Correct-OTP NAME_REQUIRED remains anonymous, with the original-session proof retained only until the same challenge expires. PostgreSQL preserves identity/contact/receipt consistency and idempotent completion; expiry/rollback behavior is described in AUTHENTICATION.md.

Current contact provenance stays in one private V4 receipt per verified account. V5 login receipts are private and expire with the challenge, then are deleted hourly. No chat history, Telegram username, vCard, or plaintext OTP is retained. Future contact replacement/account erasure must remove dependent receipts with private account data.

## Transport and isolation

Production uses HTTPS webhooks and an independent secret_token as documented by [setWebhook](https://core.telegram.org/bots/api#setwebhook). Exactly one matching X-Telegram-Bot-Api-Secret-Token header is required before parsing the body. Telegram does not supply a Meta-style body HMAC. Only message updates are selected. JSON/body bounds, deduplicated update IDs, and sender/freshness checks apply.

Explicit development polling uses a separate bot/update stream, one leased Redis consumer, persisted acknowledged offsets, and getMe/getWebhookInfo validation. It refuses an existing webhook, never deletes it automatically, and cannot run in production. Recruiter authentication/contact actions stay disabled.

The fixed-host HTTP client disables redirects and bounds connection, total response time, and body bytes. OpenNest permits one send per second per private chat and 25 per second in aggregate, honoring 429/retry_after. Ambiguous delivery failures retry the same prepared code without approving a user. Delivery acknowledgement is fenced by the salt generation. Reopening an active deep link or plain /start on the bound chat resumes the flow; an unbound/cancelled link gives browser restart guidance. A verified OTP awaiting a name directs the user back to that browser.

## Cost and secrets

Telegram's [Bot Platform](https://core.telegram.org/bots) currently has no per-message charge within the [documented messaging limits](https://core.telegram.org/bots/faq#how-do-i-avoid-hitting-limits). This is not a lifetime-free hosting or provider guarantee. A bot cannot initiate a conversation or send to an arbitrary phone number; the owner must start it. [Telegram Gateway](https://core.telegram.org/gateway) is a separate paid API and is unselected. No paid broadcasts, console OTP, WhatsApp, SMS, or automatic-login fallback exists.

Bot API URLs embed the token. Logs, failures, traces, Git, and shared exports must exclude provider URLs, secrets, start links, contacts, Telegram IDs, codes, and session data. Environment configuration owns bot identity and secrets. The local setup helper reads bot identity/webhook status without messages or webhook changes. It never exposes a tunnel.

Full routes, configuration, account migration limits, verification evidence, and remaining live/hosted checks are recorded in TELEGRAM_HANDOFF.md.
