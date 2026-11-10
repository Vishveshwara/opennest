# OpenNest identity handoff

The former provider-specific instructions were superseded by the maintainer's Telegram-only decision on 10 November 2026.

Use [Telegram setup and Postman flow](TELEGRAM_HANDOFF.md), [current authentication decisions](AUTHENTICATION.md), and the [API index](ai-context/API_CONTRACTS.md). No provider redirect, OAuth credentials, or copied browser session is required.

The identity foundation's trust rules, private profile access, Redis sessions, CSRF/logout, safe errors, and datastore isolation remain. Earlier 298-test Phase 2 evidence describes the earlier revision; it does not establish the current sole-authentication result. All existing accounts/listings and migration history remain intact. Accounts lacking an unambiguous verified Telegram association require explicit recovery rather than email/phone auto-linking.


The maintainer confirmed real local Telegram OTP authentication and the authorized verified profile on 10 November 2026. [API_VERIFICATION.md](API_VERIFICATION.md) records cross-API/session authorization and OAuth removal. Hosted readiness remains separate.
