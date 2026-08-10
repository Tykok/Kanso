# An editable timeline, and the dependencies that move it

Kanso can say what a ticket is and who it belongs to. It cannot say when it happens,
or what it is waiting on. Dates exist as columns and are filled by nobody, because
nothing on screen ever asks for them.

This spec adds the screen that asks: a Gantt view where every ticket is a bar you can
drag, every project is a bar derived from the tickets inside it, and an arrow between
two tickets is a promise the schedule keeps — move the first, and the second gets out
of the way.

## What ships

- Dates become instants with an optional time, stored in UTC, displayed in each
  person's own timezone.
- A `ticket_dependencies` table: finish-to-start, no lag, acyclic.
- A scheduling engine that pushes successors forward when — and only when — a
  dependency is actually violated.
- A critical path computed from the dependency graph, drawn in red, and a distinct
  state for a chain that overruns a deadline someone posed.
- A timeline view at team scope and at project scope, with an unscheduled tray.
- Keyboard parity for every gesture the mouse gains.

## What does not

**A working-day calendar.** A ticket that ends Friday at 17:00 is followed, in this
version, by one that starts Friday at 17:00. The Gantt will draw work at 3am and on
Saturdays. Skipping nights, weekends and holidays needs a calendar per instance, an
arithmetic of duration that is no longer the subtraction of two instants, and a
holiday table that will be requested within the week. It layers cleanly on top of what
ships here, and it is separable, so it waits.

**Dependencies between projects.** A project's schedule is the schedule of its
tickets; a link between two projects would be a second graph capable of contradicting
the first. Two tickets in two different projects may depend on each other, and that is
how a cross-project constraint is expressed.

**Lag.** `FS + 2 days` and its negative form are real needs, and neither has been
asked for. `ALTER TABLE ticket_dependencies ADD COLUMN lag_days INT NOT NULL DEFAULT 0`
is non-destructive on the day one of them is.

**SS, FF and SF links.** Finish-to-start covers the requirement as stated. The other
three cost four arrow anchors in the renderer and four ways to pick the wrong link in
the picker; SF is used essentially nowhere.

**A conflict screen.** The cascade never asks permission and never proposes. It
applies, or it declines to move something and says why on the arrow.

---

## The decisions this rests on

### A dependency is a constraint, not an annotation

An arrow does work. Lengthen A, and if A now ends after B starts, B slides forward by
exactly the overlap, keeping its duration, and so on down the chain.

### Slack is real

If A ends on the 10th and B starts on the 15th, moving A to the 12th moves nothing.
B keeps five days of slack and keeps its dates. The descent stops at B, because a
ticket that did not move cannot have pushed anything behind it.

This is not an optimisation, it is the meaning of the feature. A critical path is the
set of tickets with **zero** slack; if every ticket were dragged forward on every
adjustment, no ticket would ever have slack and the red would mean nothing.

### The cascade never pulls backwards

Deleting a dependency, or shortening a predecessor, frees slack. It does not drag
anything into the past. A tool that yanks work earlier because an arrow was erased is
doing something nobody asked for and nobody can undo.

### A done ticket never moves

Shifting the dates of finished work rewrites history. When a predecessor is pushed
past the start of a `done` successor, the ticket stays where it is and **the arrow is
marked violated** — drawn in red, computed at read time. Staying silent here would be
the worst available outcome: a plan claiming to hold when it does not.

### Project dates are optional, and a posed one is a deadline

`projects.start_at` / `end_at` stay, nullable. When empty, a bound is derived. When
posed, the end bound is a deadline the critical path is measured against, and a chain
that overruns it produces negative slack — the most useful thing a Gantt ever tells
you, and something none of the alternatives could express.

An explicit **start** constrains nothing. It bounds the drawn bar and no more. Making
it a "no earlier than" would put projects back into the scheduling graph we just took
them out of.

### Resolution of a project's bounds, per bound independently

1. The project's explicit date, if posed.
2. Otherwise the earliest start / latest end among its tickets.
3. Otherwise the earliest / latest `completed_at` among its done tickets.
4. Otherwise no bar.

Rule 3 is retrospective by construction: a bar that appears only once work is finished
describes the past rather than a plan. It is the honest answer for a project whose
tickets nobody dated, and it is better than a blank row.

### A date without a time is floating

Stored as an instant, but never converted. "Ends on the 12th" reads as the 12th for
everyone; converting it would show the 11th to a colleague five hours behind, on data
they did not touch. A date **with** a time is converted to the reader's timezone,
because that one refers to a moment rather than to a day. `has_time` decides, exactly
as Notion's `include_time` does.

---

## Data model — `V6__timeline.sql`

```sql
ALTER TABLE tickets
  ADD COLUMN start_at       TIMESTAMPTZ,
  ADD COLUMN start_has_time BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN due_at         TIMESTAMPTZ,
  ADD COLUMN due_has_time   BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN completed_at   TIMESTAMPTZ;
-- backfill start_date / due_date at midnight UTC, has_time stays false,
-- then drop both DATE columns.
```

`projects` receives the same treatment for `start_at` / `end_at`.

`completed_at` is written by `TicketService` on the transition into `done` and cleared
on the way out — not by a trigger, so the rule stays next to the status logic that
owns it. It exists because `updated_at` moves on every edit: without it, renaming a
ticket would change the start date of its project.

```sql
CREATE TABLE ticket_dependencies (
  predecessor_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  successor_id   UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (predecessor_id, successor_id),
  CHECK (predecessor_id <> successor_id)
);
CREATE INDEX ON ticket_dependencies (successor_id);
```

The `CHECK` covers self-reference; longer cycles are refused at insert by a
`WITH RECURSIVE` walk — the same mechanism V1 uses for team parenting, and the fifth
raw SQL statement alongside the four `architecture.md` already justifies.

`preferences.timezone TEXT NOT NULL DEFAULT 'UTC'`, seeded from the browser on first
load. Detecting it per render instead would make every bar jump when Kanso is opened
from another country.

### Three things this schema deliberately permits

**Dependencies cross teams.** Nothing forbids it, and forbidding it would break the
case that motivates the feature. Worth writing down because the neighbouring rule is
the opposite: `TicketProjectCoherenceTest` requires a ticket and its project to share a
team. Two different links, two different rules, on purpose.

**A dependency may target an unscheduled ticket.** The arrow exists, the cascade has
nothing to move, and the constraint takes effect the day the ticket is dated.

**A ticket with only one of its two dates is a milestone**, of zero duration, sitting
on the date it has. Without that reading the cascade has no duration to preserve when
it shifts one, and a ticket carrying a due date but no start — the shape a deadline
naturally takes — would have no defined behaviour at all.

---

## The engine — `ScheduleService`

Three writes trigger it: a change to a ticket's dates, the creation of a dependency,
the deletion of one. It runs **inside the business transaction**, like `sync_jobs`
inserts: a half-applied cascade surviving a crash would be worse than no cascade.

Topological descent from the changed ticket over its successors. For each edge A → B:

| Condition | Behaviour |
|---|---|
| `start(B) ≥ end(A)` | B does not move. Stop descending this branch. |
| `start(B) < end(A)` | B slides by `end(A) − start(B)`, duration preserved. Descend. |
| B is `done` | B never moves, the edge is marked violated, stop descending. |
| B has no dates | Nothing to move, stop descending. |

Termination follows from acyclicity, which is enforced at insert. No defensive
iteration cap: if a cycle exists, the `WITH RECURSIVE` guard has a bug, and hiding it
behind a counter makes it undebuggable.

### Critical path

A backward pass over the **weakly connected component** of the dependency graph — not
over the project, and not over what happens to be on screen.

```
chainEnd         = latest end in the component
bound(X)         = min over successors B of ( lateFinish(B) − duration(B) ),
                   or chainEnd when X has no successor
lateFinish(X)    = min( bound(X), X's own project end when one is posted )
slack(X)         = lateFinish(X) − end(X)
```

**A deadline binds the ticket whose project posted it, and nobody else.** Taking the
tightest deadline in the component and applying it to every node was tried first, and
it made a ticket finishing ten days inside its own project's end read as late because
something upstream in a *different* project was tight. A predecessor's deadline cannot
constrain a successor's finish.

**A deadline only ever tightens.** A generous one does not buy the chain slack it does
not have — otherwise a distant project end would leave nothing critical anywhere, and
the red would vanish exactly when the plan is comfortable rather than when it is safe.

`slack = 0` → critical. `slack < 0` → the chain overruns a posted deadline. A component
of one ticket is never critical: a ticket with no arrows painted red would be saying
something about dependencies it does not have.

The component is the right unit because a chain can span three projects. Anchoring per
project would cut such a chain into three unrelated critical paths; anchoring on what
is visible would repaint the screen when the filter changes, on identical data.

**This is why the read endpoint expands its query.** It starts from the tickets in
scope, walks out to the closure of their components (`WITH RECURSIVE`, the sixth raw
statement), computes over that, and returns only the scope.

### Write amplification

A cascade over N tickets issues N `UPDATE`s and N `sync_jobs`, the latter coalesced by
the existing partial unique index — adjusting the same bar three times does not queue
three pushes.

Realtime emits **one** event, not N. `pg_notify` caps payloads at 8000 bytes and 200
UUIDs alone come to 7200. The event carries the teams and projects touched; receivers
refetch. That is the doctrine already in place, applied to a case that would otherwise
break it.

---

## API

One read endpoint, because derived bounds, slack and criticality are computed together
over the same component closure. Splitting them would mean computing that closure three
times.

```
GET /timeline?scope=all|team:{id}|project:{id}
```

```jsonc
{
  "projects": [{
    "id": "…", "name": "…",
    "start": { "at": "2026-08-03T00:00:00Z", "hasTime": false, "derived": true },
    "end":   { "at": "2026-09-15T00:00:00Z", "hasTime": false, "derived": false }
  }],
  "tickets": [{
    "id": "…", "identifier": "KAN-42", "projectId": "…",
    "start": { "at": "…", "hasTime": true },
    "due":   { "at": "…", "hasTime": true },
    "floatMinutes": 0,
    "critical": true,
    "late": false
  }],
  "dependencies": [
    { "predecessorId": "…", "successorId": "…", "violated": false, "outOfScope": false }
  ],
  "unscheduled": [{ "id": "…", "identifier": "KAN-51", "title": "…" }]
}
```

`derived` is per bound, so the view can tell a posed date from a deduced one — without
it, nothing on screen says what may be edited. `floatMinutes` is null for an
unscheduled ticket or an isolated one. `outOfScope` marks a link whose other end is
absent from the response; it is what the project view draws as an annotated stub.

```
PATCH  /tickets/{id}                          # extended dates, triggers the cascade
POST   /tickets/{id}/dependencies             # { predecessorId }
DELETE /tickets/{id}/dependencies/{predId}
```

All three return the tickets the cascade actually moved, so the client can settle
without waiting for the realtime refetch. A cycle is a `409` **naming the offending
chain**: a bare "cycle detected" on a forty-ticket graph is unusable.

There is no preview endpoint. There is no conflict screen to preview into.

---

## The view

| File | Responsibility |
|---|---|
| `components/timeline/view.tsx` | scope, zoom, viewport, drag state |
| `components/timeline/grid.tsx` | time axis and row backgrounds |
| `components/timeline/row.tsx` | a project row (derived bar, not editable) or a ticket row |
| `components/timeline/bar.tsx` | one bar: move, resize, link handle |
| `components/timeline/arrows.tsx` | the SVG dependency layer, `outOfScope` stubs included |
| `components/timeline/tray.tsx` | the unscheduled tray |
| `lib/timeline-geometry.ts` | **pure**: date ⇄ pixel, zoom levels, floating vs converted rendering |

The last one is deliberately React-free. `follow-ups.md` records that Vitest runs in
`environment: "node"` with no DOM, so whatever must be tested on the web side has to
live outside a component. The two rules that will actually break are in there: timezone
conversion, and zoom arithmetic.

A list ⇄ timeline toggle in the top bar; `view` joins the `ui` store. Scope keeps
coming from the sidebar, and the project view is the same component with
`scope = project`.

**Mouse.** Bar body moves. Edges resize. A handle on a bar's right edge dragged onto
another bar creates the dependency. The tray drags onto the grid to schedule.

**Two interaction traps, both handled in the first version:**

*Realtime must not pull the bar out from under the cursor.* A refetch landing mid-drag
would reposition what is being dragged. Updates received during a drag are held and
applied on release.

*Optimism has to cover the cascade, not just the dragged bar.* Moving A locally and
waiting for the response before B, C and D follow produces a visible jump. The cascade
is applied locally during the drag, by the same rule the server uses — which is why
that rule lives in `timeline-geometry.ts`, written once and tested.

**Visual states.** Normal; critical (red fill); late (red fill **and** a hatched
border); `done` (dimmed, never draggable); violated edge (red arrow). Late is not
distinguished from critical by colour alone — the distinction has to survive a
colour-blind reader and a greyscale screenshot.

### Keyboard

`BY_KEY` throws at module load when two actions claim one key, so a key cannot mean two
things on two screens. `Action` therefore gains `mode?: "list" | "timeline"`, `BY_KEY`
is keyed by `${mode ?? "any"}:${key}`, and `resolveShortcut(key, mode)` tries the
current mode before the shared map. Duplicate detection survives, per mode.

`page.tsx` blocks `meta`/`ctrl`/`alt` but not `shift`, and `event.key` for `Shift+h` is
`"H"` — a distinct entry in the map. So the shift/resize pair needs no modifier
plumbing at all.

| Key | Effect |
|---|---|
| `j` `k` | change row (unchanged) |
| `h` `l` | shift the selected bar by one zoom unit |
| `H` `L` | move its end — that is, change its duration |
| `p` | schedule: out of the tray, one unit at the viewport start |
| `u` | unschedule: clear the dates, back to the tray |
| `[` `]` | zoom out / in (day, week, month) |
| `t` | recentre on today |
| `d` | add a dependency |

None of these is claimed today.

`d` opens a picker listing candidate predecessors — the existing `CommandPalette`
component, which everyone already knows how to drive. It does **not** enter a link
mode: the app has no modal navigation anywhere, and introducing one so an arrow can be
drawn by keyboard costs a whole mental model. Removing a link uses the symmetric
picker.

`shortcutRows()` currently produces a flat list and must group by mode. Otherwise the
help overlay offers `h` `l` `H` `L` to someone in the list view, where they do nothing.

---

## The Notion mirror

Dependencies mirror as a self-referencing relation on the Tickets database
(`Blocked by` / `Blocks`), added to `NotionSchema` and written by `NotionMapper`.
Nothing changes inbound: relations are already Kanso-authoritative, and note #11 of
`architecture.md` — Notion's self-referencing relation accepts a cycle — applies to
tickets unchanged. These arrows are never read back.

**Two problems this feature creates in the mirror. Neither is optional.**

**Dates are scalar, so the poller applies them inbound.** It writes to the repository
directly today. With a cascade in place, a date edited in Notion would bypass the
entire engine and break the plan silently. `NotionPoller` has to go through
`ScheduleService` like any other write. The anti-echo guards (note #5) prevent the loop
that would otherwise follow.

**Derived project bounds are not pushed.** Send a computed bound to Notion and the
poller reads it back as a posed one: the derivation quietly becomes a pinned date
nobody chose. So only explicit bounds are pushed, and a project with derived dates
appears in Notion without dates. The mirror's timeline loses; this is the same trade
note #7 already made, preferring an honest round-trip to a prettier view.

`completed_at` is not mirrored. No Notion property corresponds to it, and inventing one
would create a column the poller has to remember to ignore.

---

## Tests

**API.** `ScheduleService`, in service tests: slack absorbs the move (B does not
budge); slack exceeded (B slides, duration preserved); a `done` successor never moves
and its edge is marked violated; a cycle is refused with the chain named; zero slack →
critical; negative slack → late; the anchor takes the tightest deadline across a
component spanning two projects.

Known gap, carried not closed: `follow-ups.md` records that every service test is
`@Transactional` and rolls back, so no event in the suite ever reaches `pg_notify`. The
single cascade event inherits that hole. Closing it needs a different test shape, not
another assertion.

**Web.** `timeline-geometry.ts` under Vitest: a floating date renders identically in
two timezones; a timed date converts; zoom arithmetic; and the local cascade produces
the same result as the server rule.

**E2E.** One Playwright scenario: two dated tickets, link them, lengthen the first,
assert the second slid and the chain is marked critical. Written against roles rather
than private CSS classes — `follow-ups.md` already holds that against the existing
suite.
