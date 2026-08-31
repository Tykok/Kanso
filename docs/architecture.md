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

**The import is the one exception, and it is one because a person is present.** The
Notion import asks which team a base becomes work in, so the thing the poller cannot
supply is supplied by the answer; the number then comes from that team's counter like
any other ticket's. Two rules keep the exception from leaking back into the mirror:
an imported ticket gets its **own** page in `Kanso · Tickets`, and the source page is
never adopted and never written to. Adopting it would put "Kanso wins" in charge of a
workspace somebody had just handed over, which is how an import erases the thing it
imported. A page the import still cannot take — no title, or already archived — is
reported with its id and its reason rather than filed as another "Untitled".
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

Rows 1 to 11 are all the mirror: what Kanso writes and reads back in its own four
databases, where it chose every column name. The import reads the other direction — a
workspace somebody else built, once — and it is lossy in its own ways, none of which have
anything to do with the mirror's.

| # | Problem | What Kanso does |
|---|---|---|
| 12 | **A column's name means nothing across workspaces.** A base built by somebody who never heard of Kanso calls its status `État` and its options `En cours`. The strict name match that serves the mirror imported such a workspace as four hundred tickets in `Todo`. | Names are a pre-fill and never a rule: `ImportSchema` suggests, the third step of screen 24 decides, and `MappedPageReader` reads a page *through* that answer. The title is the one exception and is found by **type** — `title` is the only property Notion requires of every database, and matching on `"Name"` is exactly what named a French workspace's pages "Untitled". |
| 13 | An imported select option has no reason to be a word Kanso knows: `Terminé`, `Bloqué`, `P0`. | Every option of a mapped column is on screen with the Kanso value it will take, and the ones nothing lands on are **named** rather than counted — a number tells the reader something was guessed, a list tells them what. An unmapped option takes the field's default, never a seventh status nothing else understands. |
| 14 | A rollup or a formula has no column here and no meaning outside Notion. | Preserved as its *displayed* value in an "Imported from Notion" section of the description, with every other property nothing claimed. Deliberately lossy: a readable line there is worth more than a faithful copy of Notion's internals in a column that would then have to be kept in step with it. |
| 15 | A relation only carries meaning if both ends come over. A `Projet` column pointing at a base being ignored — or imported as the wrong kind — has nothing to resolve to. | The second step says so beside the count of what it costs, and offers to import the other base as the kind that relation needs. It is never a blocker: the row lands in the fallback its base was given, and the relation is counted as dropped in the outcome. |
| 16 | A `two_property` relation is declared on both sides and the two can disagree — a ticket naming project B while project A claims to hold it. | The child's own answer wins, because the child is the row being written, and the disagreement is counted in the outcome rather than settled silently. A parent's inverse column (`Tâches` on a projects base) is a source of last resort, read only where the child said nothing. |
| 17 | Notion answers no page total for a data source, so a count is a walk at roughly 2.5 requests a second. | The walk is bounded by `kanso.notion.import.max-pages-per-database`, and a base longer than that reports the count it reached with a `+` — on the first step, the second, and the confirm button. A bare number in front of a confirm button would be a wrong one. The import then brings over the prefix it read and nothing past the bound; `follow-ups.md` holds that against it. |
| 18 | A `people` column can only become an assignee if a Kanso account already exists for that person. | The fourth step matches each Notion person met on a mapped column to an account and *writes* `users.notion_person_id`, so the answer holds for every later import and lets the mirror fill the `people` property afterwards. Anyone left unmatched leaves their rows unassigned rather than guessed at, and creating accounts stays the invitation flow's job. |

### Connecting Notion

An instance connects through a **public** integration and Notion's own consent screen,
which is also where the person chooses which pages Kanso may see — so the sharing that
used to be done by hand from a page's `•••` menu happens there, and the token arrives over
the wire instead of being transcribed. What no provider will do is issue a client to a host
it has never heard of, so creating the integration once and pasting its client id and
secret is the step that survives; it is the same step Google needs, for the same reason.

Three things about the flow are load-bearing:

- `owner=user` in the authorize URL is what makes Notion offer the page picker. Without it
  the consent screen asks for nothing and grants nothing useful.
- The client secret authenticates the token exchange over HTTP Basic. It never reaches a
  browser, which is where a URL parameter would put it — history, proxy logs, `Referer`.
- The callback is Notion navigating the browser to Kanso's own origin, so that request has
  to carry the session cookie. `SameSite` is pinned to `lax` in `application.yml` rather
  than left to the browser default, because `strict` would break connecting silently, on a
  setting nobody would think to connect to a Notion button. Behind a reverse proxy the
  forwarded host has to reach the app, or the redirect URI Kanso builds will not match the
  one registered.

Pasting an integration token still works and is the documented fallback: an instance that
already has a working internal integration should not have to redo it, and one whose
browser cannot reach a consent screen has no other way in.

The parent page — the page Kanso creates its four databases under — is chosen from a list
rather than named by id. One caveat is worth knowing: a search filtered to pages returns
database *rows*, and every page the mirror writes is one, so the list excludes any page
whose parent is a database or a data source. That is a rule about shape, not a list of
Kanso's own ids, so it cannot fall out of date.

### The table of origins, and why it is not `notion_page_id`

`teams.notion_page_id`, `projects.notion_page_id` and `tickets.notion_page_id` hold **the
mirror's page** — the row Kanso created inside `Kanso · Tickets` — and every outbound push
overwrites them. An import has to remember a different fact: which page in somebody else's
workspace a Kanso row was made from.

The two facts look alike enough to share a column, and that is exactly why they must not.
Put an imported page's id in `notion_page_id` and the next push aims *Kanso wins* at the
workspace somebody has just handed over: Kanso's state is written onto their own pages, and
the import erases the thing it imported. Screen 24 promises nothing in Notion changes, and
that promise dies the moment one column means both things.

So the second fact gets a table, `V15__notion_import_origin.sql`:

```sql
CREATE TABLE notion_import_origin (
  notion_page_id TEXT PRIMARY KEY,
  entity_type    TEXT NOT NULL CHECK (entity_type IN ('team', 'project', 'ticket', 'doc')),
  entity_id      UUID NOT NULL,
  data_source_id TEXT NOT NULL,
  imported_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (entity_type, entity_id)
);

CREATE INDEX notion_import_origin_source_idx ON notion_import_origin (data_source_id);
```

`notion_page_id` is the primary key because one Notion page becomes at most one Kanso row:
the constraint *is* the "import once" rule, enforced by Postgres rather than by remembering
to check, which is what makes the import safe to press twice. `entity_type` is the same
closed vocabulary the wire uses, held closed here by a `CHECK`.

Three things read this table, and only three:

1. **Resolving a relation** onto a row imported in an **earlier** session, so a link whose
   other end came over last month is silent rather than a question.
2. **Recognising a page**, so a second import leaves it alone.
3. **Finding a tickets base's container project** — the one `TicketImport` names after the
   base for tickets whose own relation answered nothing. That row is keyed by the base's
   *data source* id where every other row is keyed by a page id, which is the one place this
   table is written twice: a container somebody has since deleted has to be replaced by the
   new one, or the run after would find the dead id and make a third.

`data_source_id` is recorded on every row and, today, **read by nothing**. It is what a later
run would need to report "this base was already brought over, 396 of its 400 pages are here",
and `notion_import_origin_source_idx` is the index that query would use; no screen asks for
it yet, and `follow-ups.md` says so rather than leaving the column looking load-bearing.

None of the three is an inbound path. Re-reading Notion into an existing row is what "Notion
is read-only in practice" refuses, and would overwrite whatever has been done in Kanso since.

There is no foreign key, deliberately: the reference is polymorphic, and the alternative is
four nullable columns and a `CHECK` that exactly one is set. The cost is that deleting an
entity leaves a row pointing at nothing, so the seed the writers resolve against is filtered
to live rows first — `ImportOriginRepository.live`, one existence query per kind — and a
stale row then behaves exactly like a relation into an ignored base: it resolves to nothing
and falls back. Cleaning those rows up is a `follow-ups.md` line, not a trigger.

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

### The activity log, and why it is not the event stream

`activity` records what happened: an actor, a kind from a closed vocabulary, and a
`jsonb` payload carrying the before and after of one scalar. It is written by the
services that already publish events — `TicketService` on create, patch, assignment
and archive; `CommentService` and `LabelService` on their own writes — **inside the
business transaction**, at the same point as the change itself.

That is the whole design decision, and it is the opposite of the one above.
`EventPublisher` fires after commit precisely so that nobody sees a change before it
is durable, which means a receiver that was not listening never learns it happened.
A log built on `pg_notify` would therefore be lossy in exactly the case it exists
to explain: the outage, the restart, the tab that was closed. So the log is a table
written in the transaction, and the event stream stays what it is — a hint to
refetch, carrying no history.

The two are read by different things and answer different questions. A view asks the
event stream *has anything changed*; the project page and the ticket page ask the log
*what has been done here*, newest first. Nothing derives one from the other.

Two consequences worth stating. Because the log is transactional, `now()` cannot be
its clock: Postgres resolves `now()` to the transaction timestamp, so every row a
transaction wrote would tie and the order would be undefined exactly where a feed
needs it. `created_at` is written from Kotlin instead. And because the suite is
`@Transactional` and rolls back, no test in it ever reaches `pg_notify` — so the log
is asserted through `ActivityService`, never through an event.

A row can name a private ticket, which is why no public projection reads it.

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

**Team membership now decides who may move a ticket, not only who belongs where.**
`TicketAccess.claimedBy` is a three-part rule, tried in order: the actor is `owner` or
`admin`, which bypasses the whole rule, per the roles above; the actor is a member of
the ticket's team **or of any of its ancestors**; failing both, an **open chain** — the
ticket's own team and every ancestor above it, with not one member between them.

The open-chain clause is not a migration guarantee, though it does prevent the migration
disaster: deploying against an empty `team_members` would otherwise 403 every existing
board, with no cure short of a SQL prompt. But that is a consequence of the clause, not
what it is *for*. `TeamService.create` never enrols its creator, and nothing else does
either, so an empty team is not a state instances leave behind — it is the state every
team is **born** into, and stays in until somebody remembers to invite people. The
clause walks the whole chain because the question it answers is "has anyone, anywhere
above this ticket, claimed the work" — one populated ancestor answers that for every
descendant beneath it at once. A sub-team created under a populated organisation is
governed from the instant it exists: that is the common case, and the chain closes it
without anyone having to remember a step. A **root** team stays open until it,
specifically, gets a member — which is both the migration case (every pre-existing
instance's `team_members` is empty on the day this ships) and the honest answer to
"nobody has claimed this work yet": you create the team, then you invite people, and the
board is open in between.

A team enters the open state the moment `TeamService.create` returns it, and leaves the
moment anyone — the team itself or any ancestor above it — gets a first member. There is
no third door, and nothing to remember: the chain rule is what makes a sub-team's door
close on its own.

Ancestry runs **downward only**: a member of a parent team may move work in any of its
sub-teams, never the reverse. The other direction would turn joining the smallest team
in an instance into a route to editing the largest — the direction is a security
property, not an implementation detail.

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

### Team-owned projects

A ticket's project must belong to the ticket's own team — checked identically in `create`
and `patch` — with one exception: a project that itself belongs to no team is
**transverse and belongs everywhere**. That is the only shape in which a project ever
holds tickets from more than one team; a project that has picked a team can never again
appear on a second one's board. The rule lives in `TicketService`, not a database
constraint, because a constraint would also forbid the team-less projects the sidebar
already shows in their own section — the transverse case is deliberate, not a gap the
schema should close.

The timeline's scope widening — drawing in everyone else with work in a shared project —
depends on this entirely: a team-owned project never has a second team's tickets to pull
in, so the widening is live only for a transverse project and inert everywhere else.

### Reads are open, writes are scoped

No `GET` in Kanso is scoped by team. `/api/tickets`, `/api/projects`, `/api/timeline`
and every team's own member roster answer any authenticated user about any team, and the
cross-team timeline this project just built depends on exactly that: a context row draws
a ticket the reader may not be allowed to move, and the critical path walks edges without
asking who owns either end.

Per-team read scoping was considered and refused, not merely never proposed. It would
make the timeline's own context rows contradictory — a row exists to show who else is
doing what, and hiding some of "who else" removes the reason the feature was built. It
would put the critical path in the position of traversing tickets the reader cannot see,
and it would need an answer for what a hidden bar looks like on a chart that otherwise
never omits what exists without saying so. None of those is a small fix, and all three
follow from scoping a single endpoint, not from scoping all of them.

So the posture is deliberate and one-directional: **everything is readable, only writes
are scoped.** `TicketAccess` gates `create`, `patch`, `delete`, and every team move —
every write — and gates nothing that only reads. The one screen that used to disagree,
`MembersSection` hiding the roster from a plain member while the endpoint behind it
answered them anyway, has been brought into line rather than kept as the exception: a
plain member now sees the roster too, read-only, with the add/remove controls gated as
before.

This is what should stop the next person from "fixing" one endpoint in isolation:
scoping a single `GET` would not make Kanso more private — every other endpoint would
still answer the same question — it would only make it inconsistent, at a cost the
timeline has already paid to avoid.

### Scheduling

A dependency is one finish-to-start arrow: the successor cannot begin before its
predecessor ends. Moving a date settles the **weakly connected component** around the
change, not the direct successors alone — a diamond has a ticket whose two
predecessors are both in the blast radius, and settling it against one of them would
leave the other violated.

Three rules decide what moves, and each is a product decision rather than an
optimisation:

1. **Slack is respected.** A successor that still starts after its predecessor ends
   does not move, and the descent stops there. Without this every micro-adjustment
   would creep the whole graph forward and no ticket would ever have slack, which
   would make the critical path meaningless.
2. **Nothing is ever pulled backwards.** Freeing slack does not drag work into the
   past — nobody expects it and nobody could undo it.
3. **A done ticket never moves.** Its edge is reported violated instead: a plan that
   claims to hold when it does not is the worst outcome available.

A moved ticket keeps its duration and only the bounds it already had, so a milestone
— one bound on purpose, typically a due date with no start — does not sprout the
other and become a dated span nobody asked for.

The critical path is computed **per weakly connected component**, never per project
and never over the visible scope: a chain can span three projects, and anchoring on
what happens to be on screen would repaint identical data whenever the filter
changes.

A date edited in Notion goes through the same engine. The inbound poller writes the
scalar and then cascades, so a date typed into the mirror cannot break the plan in
silence.

### Persistence

Exposed for CRUD, with Flyway owning the schema — no DDL generation, so the
migrations are the single definition of the database.

Seven statements are raw SQL through Spring's `JdbcClient`, because the Exposed DSL
cannot express them and each is load-bearing:

1. `WITH RECURSIVE` for the team subtree.
2. `UPDATE … FROM (… FOR UPDATE SKIP LOCKED)` to claim jobs.
3. `ON CONFLICT … WHERE status = 'pending'` to coalesce onto a partial index.
4. `SELECT pg_notify(…)`.
5. `WITH RECURSIVE … UNION` for a dependency component. The walk ignores the
   direction of the arrows, so it revisits every node from both ends — `UNION ALL`
   would not terminate.
6. `WITH RECURSIVE` accumulating a `uuid[]` for the path a refused dependency would
   close. "Cycle detected" on its own is not something anyone can act on.
7. `CAST(:payload AS jsonb)` on the activity insert. The driver sends a Kotlin string
   as `varchar`, which Postgres refuses for a `jsonb` column; `sync_jobs` casts the
   same way. Reads come back through Exposed.

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
- No attachments or sub-tickets. Comments, mentions and labels exist as of `V8`,
  saved views and cycles as of `V10`.
- A full reconcile queues at most 500 tickets per call and says so in the log.
