# Kanso — architecture and the limits of the Notion mirror

## The one decision everything follows from

**Postgres is the operational source of truth. Notion is an asynchronous mirror.**

Notion cannot sit between two people editing the same board: a call takes roughly
300–800 ms, the integration is capped near 3 requests per second, and there is no
push worth relying on. Anything routed through it inherits those numbers.

So Kanso writes to Postgres, answers the user, and pushes to Notion afterwards.
The app stays fully usable when Notion is unreachable, and reconciles later.

```
  browser ──REST──▶ Spring API ──▶ Postgres   (source of truth)
     ▲                  │              │
     └──STOMP/WS────────┘              │ pg_notify
                                       ▼
                              every API instance
                                       │
                                       ▼
                            sync_jobs (outbox) ──▶ Notion   (mirror)
                                       ▲                │
                                       └─── poller ◀────┘
```

---

## Consequences worth stating out loud

### Notion is read-only in practice

The conflict rule is *Kanso wins*: if the Postgres row changed after the last
successful push, an edit made in Notion is discarded and a corrective push is
queued. Notion converges back on its own.

This means **someone editing in Notion will see their change reverted**. That is
the design, not a bug — but it has to be communicated to the people reading the
mirror, or they lose trust in the tool. Put a banner on the parent page saying the
databases are maintained by Kanso.

If Notion-side editing ever needs to be first-class, the inbound path needs
per-field merge and a real conflict UI. That is a v2 conversation, not a setting.

### Inbound sync covers scalar fields only

The poller applies title, status, priority, dates and the archived flag. It does
**not** apply relations or people:

- Notion replaces a relation array wholesale, so accepting one back would turn a
  concurrent edit into silent data loss.
- `people` cannot represent a Kanso user with no Notion account, so reading it back
  would quietly drop assignees.

Relations and assignments stay Kanso-authoritative.

### Pages created in Notion are not adopted

A ticket needs a team and a per-team number, neither of which a page created by
hand in Notion can supply. The poller logs such pages and moves on rather than
inventing rows. Create tickets in Kanso.

---

## Where the Notion mapping is lossy

| # | Problem | What Kanso does |
|---|---|---|
| 1 | A `people` property only accepts Notion workspace members, so a Kanso user without a Notion account cannot appear in it. | Write `people` when `users.notion_person_id` is known, and always write a parallel `Assignees (Kanso)` rich-text with everyone's name. The mirror stays readable; the text column is ignored on the way back. |
| 2 | A relation needs its target page to already exist. | Jobs carry a dependency priority (team 10 → project 20 → ticket 30 → doc 40) and a push whose target has no page yet is deferred a few seconds instead of failing. |
| 3 | Writing a relation replaces the whole array; there is no merge. | Every push writes the entity's complete state from Postgres. Consistent with "Kanso wins", and the reason partial pushes are never attempted. |
| 4 | **A relation can only point at pages inside the data source it targets.** Kanso's docs are arbitrary pages elsewhere in the workspace, so projects cannot relate to them directly. | The mirrored `Docs` database holds one index row per referenced page, carrying its URL, and relations point at those. `notion_docs` therefore stores two ids: `notion_page_id` (the real page) and `mirror_page_id` (the index row). |
| 5 | Our own writes move `last_edited_time` and come back as changes. | Two guards, both needed: ignore pages whose `last_edited_by` is our bot, **and** ignore anything not strictly newer than the `notion_last_edited_time` recorded by the last push. A human can edit in the same second we do, which is why one guard is not enough. |
| 6 | Writing an unknown `select` option silently creates it, so the status vocabulary drifts. | The vocabulary is closed in Kotlin *and* by a `CHECK` constraint. An unknown value coming back is logged and ignored, never adopted. |
| 7 | Notion has one date property with `start`/`end`; a range read back with only a start is ambiguous (start date or deadline?). | **Deliberate deviation from the original spec:** tickets and projects carry two separate date properties (`Start`, `Due` / `End`) rather than one range. Less pretty in Notion timelines, but it round-trips without guessing. |
| 8 | Notion archives, it does not delete. | A Kanso delete archives the page; a page moved to Notion's trash sets `archived = true` in Kanso. There is no mirrored hard delete. |
| 9 | The 2025-09-03 API nests data sources under databases, and the published schema reference still documents `database_id` for relations. | Database and data source ids are both discovered at bootstrap and persisted in `notion_databases` — never guessed. For relation configs the newer `data_source_id` shape is tried first and falls back to `database_id` on a validation error, logging which one the workspace accepted. |
| 10 | `Kanso ID` is not unique on Notion's side; a duplicated page produces two rows claiming the same entity. | Reconciliation is by `notion_page_id` first; `Kanso ID` is only a recovery key. |
| 11 | Notion's self-referencing team relation accepts a cycle. | Acyclicity is enforced in Postgres with `WITH RECURSIVE`, on the way in and on the way back. Team parenting is never accepted from Notion. |

---

## Mechanisms

### The outbox

`sync_jobs` rows are inserted **in the business transaction**, so a crash right
after the commit cannot lose a push.

- A partial unique index allows at most one `pending` job per entity. Since a push
  writes the whole row, five queued pushes are redundant — the insert coalesces.
- Claiming flips the row to `running`, which frees that slot: an edit made while a
  push is in flight still gets queued.
- Claims use `FOR UPDATE SKIP LOCKED`, so several workers or API instances take
  disjoint sets instead of blocking on the same head row.
- Retries back off exponentially **with jitter** — without it, a Notion outage makes
  every queued job retry in the same instant and trip the rate limit again.
- Waiting on a dependency or on the rate limiter is *deferred*, not *retried*: it
  does not spend an attempt, because nothing is wrong with the job.

### Realtime

The API does not push to its own broker directly. It calls `pg_notify` **after
commit**, and every instance is `LISTEN`ing:

- After commit, because emitting inside the transaction lets a client refetch and
  see stale data.
- Through Postgres, because that fans out to every instance without Redis or sticky
  sessions — and the instance that made the change receives it exactly like its
  peers, so there is only one broadcast path to reason about.

Events carry an id and enough scope to decide whether a view cares. Receivers
refetch. Shipping whole entities would mean two definitions of their shape, and
`pg_notify` caps payloads at 8000 bytes anyway.

The `LISTEN` connection is opened directly rather than borrowed from Hikari: a
pooled connection parked forever shrinks the pool and gets recycled out from under
you by `maxLifetime`. Losing it is still expected, so the loop reconnects.

### Auth

OAuth2 login (Google, GitHub) ending in a session cookie. The same cookie
authenticates the WebSocket handshake, so realtime needs no token plumbing.

CSRF tokens are **off**, deliberately and with a bounded justification: the session
cookie is `SameSite=Lax`, so a third-party page cannot make the browser attach it to
a POST/PATCH/DELETE, and every mutation is one of those. Reads are side-effect free.
**If Kanso ever needs a cross-site cookie (`SameSite=None`) or gains a
state-changing GET, CSRF protection has to come back on.**

`KANSO_AUTH_MODE=dev` trusts an `X-Kanso-User` header and verifies nothing. It has to
be asked for explicitly and logs a loud warning; first launch shows the setup wizard
instead. Never expose an instance running in that mode.

**Roles.** `users.instance_role` is `owner | admin | member` and answers "who may
configure this instance" — a different question from `team_members.role`, which is
about belonging to a team. The owner is whoever completed the setup and is not
assignable from the role list: handing ownership away from a dropdown is how an
instance ends up with nobody able to configure it.

**Changing a password ends the other sessions.** Spring Security's `SessionRegistry`
was tried first and is not enough: `expireNow()` only sets a flag that
`ConcurrentSessionFilter` turns into a rejection, and that filter is only in the
chain when concurrency control is configured — the session stayed marked expired and
kept answering 200. `UserSessions` holds the sessions themselves and invalidates
them. It sees only the sessions held by this instance: exact for the single-instance
deployment the compose file ships, best-effort behind replicas.

**Unlinking a provider is refused when it would leave no way in.** An account with
neither a password nor a provider cannot be signed into, and nothing inside the app
can undo that afterwards.

### Persistence

Exposed for CRUD, with Flyway owning the schema — no DDL generation, so the
migrations are the single definition of the database.

Four statements are raw SQL through Spring's `JdbcClient`, because the Exposed DSL
cannot express them and each is load-bearing:

1. `WITH RECURSIVE` for the team subtree.
2. `UPDATE … FROM (… FOR UPDATE SKIP LOCKED)` to claim jobs.
3. `ON CONFLICT … WHERE status = 'pending'` to coalesce onto a partial index.
4. `SELECT pg_notify(…)`.

They run on the connection Spring already holds, inside the same transaction as the
Exposed statements around them.

### Ticket numbering

`UPDATE teams SET ticket_counter = ticket_counter + 1 … RETURNING`, inside the
insert transaction. The row lock on the team serialises concurrent creates, so two
people pressing `c` at the same moment get 41 and 42 — no gaps, no collisions, and no
per-team sequence to keep in step.

---

## Known limits in v1

- Notion-authored pages are not adopted.
- Inbound sync is scalar-only (see above).
- Sessions are in memory: more than one API instance needs a shared session store
  (one property with `spring-session-jdbc`).
- No comments, attachments, saved views, or sub-tickets.
- A full reconcile queues at most 500 tickets per call and says so in the log.
