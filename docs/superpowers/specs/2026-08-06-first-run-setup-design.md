# Kanso — first-run setup, local accounts, preferences

Design agreed 2026-08-06.

## Problem

A fresh Kanso instance drops you straight into dev auth with the Notion mirror off
and no way to configure either without editing `.env` and restarting. Someone
self-hosting this should be able to link Notion, enable Google sign-in, and pick a
theme from the app itself — or skip all of it and start working.

## Two scopes, one wizard

The instance has an **owner**: the first account created. The wizard shows different
steps depending on who is looking.

| Step | Owner, first launch | Anyone else |
|---|---|---|
| 0 · Create account | email + password → becomes owner | — (arrives via invitation) |
| 1 · Notion | skippable | — |
| 2 · Google sign-in | skippable | — |
| 3 · Preferences | yes | only this |

Every step is skippable and reopenable from settings. Skipping sets
`setup_completed_at` so the banner stops appearing; it decides nothing permanently.

**Accepted consequence:** `docker compose up` no longer logs you in automatically.
First launch asks you to create an account. `KANSO_AUTH_MODE=dev` remains as an
explicit escape hatch for tests and CI.

**Precedence:** an environment variable always wins over the database. If
`NOTION_TOKEN` is set in `.env`, the wizard shows it as configured-by-environment
and read-only. Otherwise operator config and wizard config would silently disagree.

## Storage

```
instance_settings   single row: setup_completed_at, notion_parent_page_id,
                    google_client_id, notion_token_enc, google_client_secret_enc
user_preferences    per user: theme, accent, density, sidebar_visible,
                    show_sync_badges, show_status_bar, default_team_id, onboarded_at
users             + password_hash, instance_role (owner|admin|member)
invitations         token_hash, email, instance_role, expires_at, accepted_at
login_attempts      email, ip, at, succeeded  — rate limiting that survives restart
```

**Encryption: AES-GCM 256.** The key lives *outside* the database, or it protects
nothing: `KANSO_SECRET_KEY` if provided, otherwise generated on first boot into a
file on the data volume, with a warning to back it up. A lost key means the secrets
must be re-entered — the honest trade for self-hosting.

**Secrets never travel back to the browser.** The API answers
`{"notion": {"configured": true, "parentPageId": "…"}}`, never the token.

## Hot reload

Two beans become mutable so the wizard doesn't end in a restart:

- `ReloadableNotionClient` — a `NotionClient` delegating to the HTTP or the no-op
  implementation depending on current settings. The worker and poller are unaware.
- `DynamicClientRegistrationRepository` — replaces today's conditional bean. Always
  present, possibly empty, reloaded when the owner saves Google credentials.

## Local accounts

- **BCrypt**, minimum 12 characters, no other imposed rule.
- `POST /api/auth/login` → 204 and the same session cookie OIDC produces, so there
  is one authenticated path downstream, WebSocket handshake included.
- **Rate limit**: 5 attempts per email and per IP over 15 minutes, recorded in
  Postgres so it survives a restart and holds across instances.
- **Invitations**: a link to copy, no SMTP. Random token stored **hashed**,
  single-use, 7 days.
- The owner can only be claimed once — a partial unique index, so two simultaneous
  requests cannot both win.
- No password reset in v1: it would force SMTP into the compose file. The owner
  regenerates an invitation link instead.

## Preferences

`theme` (system/light/dark) · `accent` (6 presets) · `density` (comfortable/compact,
drives `--row-height`) · sidebar, sync badges, status bar.

Everything already runs through CSS variables, so this is mostly wiring. Preferences
are mirrored into `localStorage` as well: without it, dark mode flashes white while
`/api/me` is in flight.

## Out of scope

SAML, 2FA, fine-grained roles, custom logo, a free-form palette editor.

## Verification

- Owner claim is single-winner under concurrent requests.
- A wrong password six times in a row is refused on the sixth.
- An invitation is accepted once and refused the second time, and after expiry.
- Secrets round-trip through encryption and never appear in any API response.
- Saving Google credentials makes `/api/auth/mode` list the provider without a
  restart; saving a Notion token flips the client from no-op to HTTP.
- Theme and density survive a reload with no flash of the wrong theme.
