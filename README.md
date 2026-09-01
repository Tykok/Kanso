# Kanso

Kanso runs a project end to end — teams, projects, tickets, cycles, a roadmap, a
timeline with dependencies, workload — and keeps the documents that explain it
alongside. Everyone who does not open it reads the same data in Notion.

**What it does**

- **A whole project, not a task list.** Cycles, a roadmap, a timeline that knows its
  dependencies and warns about overlap, workload per person.
- **The document sits next to the work.** Notion pages are referenced by projects *and*
  tickets, many-to-many on both sides — not filed in a second tool.
- **At the keyboard.** Keyboard-first: `j`/`k`, `1`…`6`, `⌘K`, and list, board and
  timeline are one scoped query drawn three ways.
- **Nobody else has to learn Kanso.** Connect Notion and Kanso feeds four databases
  that the people following along build their own views on; an existing workspace is
  imported once — with its own columns and its own words, so a base whose status is
  called `État` and whose options read `En cours` comes over as work already started
  rather than as a pile of `Todo`. One door to move in, one source of truth to live in.

**Postgres is the operational source of truth. Notion is an asynchronous mirror.**
People who move work forward every day live in Kanso; everyone else keeps reading
the same data in Notion. Notion is never in the collaboration loop — it is too slow
(~300–800 ms per call, ~3 req/s, no reliable push) to sit between two users typing.

Kanso stays fully usable when Notion is down, and reconciles afterwards.

---

## Quick start

```bash
cp .env.example .env      # fill in OIDC credentials, or set KANSO_AUTH_MODE=dev
docker compose up
```

- Web: http://localhost:3000
- API: http://localhost:8080
- Health: http://localhost:8080/actuator/health

First launch opens a **setup wizard**. You create the owner account with an email
and a password, then — if you want to — link Notion, enable Google sign-in, and pick
a theme. Every step after the account is skippable and reopenable from settings
(`,`), so you can be filing tickets in under a minute and configure the rest later.

Whoever comes next arrives through an invitation link the owner generates, and only
sees the preferences step. Nobody but the owner and admins can reconfigure the
instance.

Notion is optional. With no token, every write still enqueues a sync job and a no-op
client completes it — the queue and the retry machinery are exercised, nothing calls
out to the network.

`KANSO_AUTH_MODE=dev` remains for tests and CI: identity comes from an
`X-Kanso-User` header and nothing is verified. It has to be asked for explicitly and
logs a loud warning. Never expose an instance running that way.

### Keyboard

| | |
|---|---|
| `j` `k` / `↑` `↓` | move |
| `Enter` | open |
| `c` | create |
| `e` | rename in place |
| `1`…`6` | status, backlog → canceled |
| `x` | archive |
| `/` | filter |
| `⌘K` / `Ctrl+K` | command palette |
| `,` | settings |
| `?` | shortcuts |

### Connecting Notion

From the wizard, or later from settings → setup. Notion will not issue an OAuth client
to a host it has never heard of, so one step happens in Notion: create a **public**
integration and register the redirect URI Kanso prints next to the field. Then paste
what that page gives you — its authorization URL, or the client id and the secret
together — into the client id field, and both are filled in from the one paste.

Everything after that is a button. "Connect Notion" opens Notion's own consent screen,
where you pick which pages Kanso may see, so nothing has to be shared by hand from a
`•••` menu. "Create the databases" then builds the four mirrored databases and queues
everything already in Postgres.

Set `NOTION_CLIENT_ID` and `NOTION_CLIENT_SECRET` in the environment and the paste is
gone for good: every instance you bring up afterwards — a fresh database, a test stack,
a second deployment — shows only the button. They pin the *door*, not the connection;
nothing is mirrored until somebody consents.

Pasting an internal integration token still works, folded away behind "Paste an
integration token instead" — for an instance that already has one, and for one whose
browser cannot reach a consent screen at all.

Anything set in the environment (`NOTION_TOKEN`, `GOOGLE_CLIENT_ID`, …) **wins over
the wizard** and is shown read-only — operator config and in-app config disagreeing
silently is worse than either one alone.

Secrets entered in the wizard are encrypted with AES-GCM before they touch the
database, using a key from `KANSO_SECRET_KEY` or generated into the `kanso-data`
volume. Back that up: without the key the stored secrets have to be entered again.

`GET /api/admin/sync` reports queue depth, failures and poll cursors.
`POST /api/admin/notion/reconcile` rewrites every page from Postgres.

### Connecting an agent

One command, run wherever the agent lives:

```bash
claude mcp add --transport http kanso http://localhost:8080/api/mcp
```

Nothing is pasted. The command only writes down an address; the first time the agent calls
it, Kanso answers `401` with a header naming its own authorisation server, and the agent
registers itself, opens your browser and asks. What you read there is a Kanso page on
Kanso's origin: which application is asking, **where it would send the code**, how old its
registration is, and the permissions in sentences rather than scope names. Approve it and
the agent holds a token; it acts as you, reaches exactly what you reach, and every change
it makes says your name in the activity feed. Settings → Agents lists what you have let
in and revokes it — revoking takes the tokens with the permission, so the next request is
refused rather than the next hour.

**Sign in to Kanso in that browser first.** The authorisation URL answers a browser with
no session `401` with an empty body, so an agent's link opens on a blank page rather than
on a login screen. It is the one rough edge left in this flow and it is written down
rather than smoothed over: signing in first makes it invisible.

**This release declares no tools.** The endpoint answers enough of MCP to prove the door
works — `initialize`, and `tools/list` with an empty list. That is the honest answer from
a server that has none yet, and a placeholder that claimed a tool would be a placeholder
somebody trusted. What goes behind the door comes next.

Dev mode has no door at all, and says so instead of failing quietly:

> Kanso is running with KANSO_AUTH_MODE=dev, where identity comes from an unverified
> header. Connecting an agent is disabled: an authorisation server behind that would issue
> durable tokens to anyone who can reach it. Switch to oidc to enable it.

### Local development (no Docker for the apps)

```bash
docker compose up -d db                      # Postgres only
cd apps/api && ./gradlew bootRun             # http://localhost:8080
cd apps/web && pnpm install && pnpm dev      # http://localhost:3000
```

---

## Architecture

```
  browser ──REST──▶ Spring API ──▶ Postgres  (source of truth)
     ▲                  │              │
     └──STOMP/WS────────┘              │ pg_notify
                                       ▼
                              every API instance
                                       │
                                       ▼
                            sync_jobs (outbox) ──▶ Notion  (mirror)
                                       ▲                │
                                       └── poller ◀─────┘
```

| Concern | Choice | Why |
|---|---|---|
| Persistence | `JdbcClient`, hand-written repositories | Recursive team hierarchy, three pure join tables, and writes that must enqueue a sync job in the *same* transaction. Hibernate's lazy loading and `ddl-auto` would fight Flyway for no gain. Flyway owns the schema. |
| Realtime | STOMP over WebSocket + Postgres `LISTEN/NOTIFY` | Every instance receives the notification and pushes to its own clients. One broadcast path, no double delivery, scales past one node without Redis. |
| Outbound sync | Outbox table + `@Scheduled` worker | The job row is written in the business transaction, so a crash cannot lose it. Dequeued with `FOR UPDATE SKIP LOCKED`, throttled by a token bucket, retried with exponential backoff. |
| Inbound sync | Incremental polling on `last_edited_time` | Notion has no dependable push. A persisted cursor per data source, plus echo suppression so our own writes don't come back as changes. |
| Conflicts | Kanso wins | If the Postgres row moved after the last successful push, the Notion edit is rejected and a corrective push is enqueued. Notion converges back on its own. |
| Auth | OIDC (Google / GitHub), session cookie | The same cookie authenticates the WebSocket handshake, so realtime auth is free. |

See [`docs/architecture.md`](docs/architecture.md) for the decisions in full, and for
the places where the Notion mapping is lossy.

---

## Data model

`teams` nest recursively through `parent_team_id`. A **team owns its tickets**;
a **project** groups tickets across the team and carries a lead, a status and dates.
`notion_docs` are Notion pages referenced by both projects and tickets (many-to-many
on both sides).

Every entity stores a `notion_page_id` in Postgres and carries a `Kanso ID` rich-text
property in Notion — two keys, so reconciliation survives losing either one.

Tickets get a short per-team identifier (`KAN-142`), allocated inside the insert
transaction from a counter on the team row.

---

## Repository layout

```
apps/api    Spring Boot 4 / Kotlin — REST, WebSocket, sync engine, migrations
apps/web    Next.js — keyboard-first UI, TanStack Query, Zustand, STOMP client
docs        architecture notes
```

## Status

v1 in progress. Not yet: per-field merge on conflict, Notion webhooks, attachments,
sub-tickets.

## License

AGPL-3.0 — the full text is in [`LICENSE`](LICENSE). The network clause is the reason:
Kanso is run as a service by whoever deploys it, so a hosted fork owes its changes to
the people using it.
