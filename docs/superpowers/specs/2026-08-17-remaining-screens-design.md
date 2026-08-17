# The fourteen screens that were never built

`2026-08-13-design-system-rollout-design.md` made the product look like the drawings. It
did so for the surfaces Kanso already had. The bundle holds twenty-eight screens plus a
public landing page, and the running app answers for ten of them.

This spec covers the rest. It is not one branch. It is a shared foundation written first,
then six independent slices that can be built at the same time without reading each
other's diffs — because the seams they meet at are decided here, before any of them
starts.

## Where the product actually stands

Drawn and running: 01 list, 02 ticket panel, 09 plan, 10 appearance settings, 11 setup,
12 login, 13 composer and row menu, 16 dark, 17 densities, 18 mobile under 720px,
25 shortcut sheet.

Drawn and half-running, which is worse than absent because it reads as done:

- **06 global search.** ⌘K opens a command palette that lists commands. The drawing is one
  field searching tickets, documents *and* commands at once, with a preview pane on the
  right and a tab strip. Same object, three quarters missing.
- **08 first session.** The wizard exists. The three-gesture empty state and the
  sidebar-footer checklist do not.
- **15 empty, offline, conflict.** There is one error strip. There is no offline queue, no
  conflict chooser, and no empty state that distinguishes "the filter found nothing" from
  "there is nothing".
- **24 Notion import.** Settings has a connections section. The three-step import that maps
  each Notion database onto a project or a document folder does not exist.

Absent: 03 ticket as a page, 04 board, 05 project page, 07 document, 14 inbox, 19 cycle,
20 triage, 21 saved view with bulk edit, 22 documents tree, 23 workload, 26 trash,
27 public roadmap, 28 contributor first step, and the landing page.

## What the drawings assume and the schema does not have

Reading the fourteen for their data rather than their pixels turns up four gaps that no
single screen owns:

**There are no comments.** Screens 16 and 18 draw a ticket's activity thread with authored
entries; 05 draws a project feed; 14 routes mentions into an inbox; 28 asks a contributor to
"say hello in the ticket's discussion". Nothing in the schema stores a sentence somebody
wrote.

**There are no labels.** Screen 21 filters a saved view on `label = sync`; 28 badges a
ticket `good first step`, `design system`, `nobody on it`. `tickets` has no label column and
there is no join table.

**There is no activity log.** "14:20 Tykok moved KAN-142 to in progress" (05), "M. Rey moved
the ticket to review" (14). `EventPublisher` fires on `afterCommit` into `pg_notify`, which
is transient by construction: a receiver that was not listening never learns. Persisting
what happened is a table, not a widening of `KansoEvent`.

**`notion_docs` is an index row, not a document.** Four columns: id, page id, title, URL.
Screen 07 draws blocks with drag handles, `/` insertion, a table of contents, ticket links
carrying live status pills, and "edited by Tykok 3 min ago". Screen 22 draws a folder tree,
three templates, and a recently-changed list. That is a subsystem — content, blocks,
folders, templates, backlinks — sitting behind one word in the deck.

The first three cross three slices each. They are built once, first, by hand, in the
foundation — not discovered independently by three agents who would each invent an
incompatible shape for them.

## Slicing

| | Screens | New migrations | Depends on |
| --- | --- | --- | --- |
| **0 — Foundation** | none | `V8` comments, labels, activity | — |
| **A — Views of existing work** | 03, 04, 05, 06 | none | 0 |
| **B — Documents** | 07, 22 | `V9` | 0 |
| **C — Organising** | 19, 20, 21, 23 | `V10` | 0 |
| **D — Inbox and states** | 14, 15, 24 | `V13` | 0 |
| **E — Trash** | 26 | `V11` | 0 |
| **F — Public surfaces** | 27, 28, landing | `V12` | 0 |

Migration numbers are assigned here and are not negotiable inside a slice: five agents
each reaching for `V8__` is the one collision that cannot be merged, only redone. The
numbers are not in dependency order — `V11` (trash) lands before `V13` (notifications)
because trash integrates earlier, and Flyway only cares that a number is unused.

Screen 08's missing half goes to D, whose subject is already "what the app says when there
is nothing to show". The board (04) is a third button on the existing `List / Timeline`
segmented control rather than a route: it draws the same scoped tickets query, and giving it
a URL of its own would mean two ways to be looking at one team's work.

## Slice 0 — the foundation

Sequential, mine, committed before any agent starts. Two halves.

### The seams

Six files are edited by every slice as the code stands. They are restructured once, here,
so that afterwards each agent writes only files nobody else opens.

**`lib/api.ts` → `lib/api/`.** Today: 567 lines of types plus one `export const api = {…}`
object. Becomes `lib/api/core.ts` (verbatim move) and `lib/api/index.ts` re-exporting it.
Each slice adds `lib/api/<slice>.ts` exporting its own object — `docsApi`, `organiseApi`,
`inboxApi`, `trashApi`, `publicApi` — and one re-export line in the barrel, written by me
in slice 0 against files that do not exist yet. Agents fill; nobody merges.

**`lib/queries.ts` → `lib/queries/`.** Same shape, same reason.

**`lib/actions.ts` → `lib/actions/`.** 777 lines, and `ACTIONS: readonly Action[]` is a
single array literal — six agents appending to it is six conflicts in one expression. It
becomes `lib/actions/core.ts` (the current array, renamed `coreActions`), one empty
`lib/actions/<slice>.ts` per slice, and `lib/actions/index.ts` composing them in a fixed
order. `resolveShortcut`, `availableActions`, `hintOf` and `predecessorsOf` move to the
barrel unchanged. This file was already past the length at which it stops being re-read;
the split is owed regardless of the fan-out.

**`app/page.tsx`.** Frozen for the fan-out, which means slice 0 has to pre-cut the one hole
the board needs: `View` becomes `"list" | "board" | "timeline"`, the segmented control gains
its third button, and the render switch calls `<BoardView>` from
`components/board/view.tsx` — a stub committed here, filled by A. Otherwise 04 either edits
the page every other slice is forbidden to touch, or it becomes a route, which is the answer
this spec already refused.

**`store/ui.ts`.** The `Overlay` and `Dialog` unions gain every value the fourteen screens
need — `"bulk"`, `"triage"`, `"conflict"`, `"blockInsert"`, `{ kind: "saveView" }`,
`{ kind: "importMap" }`, `{ kind: "restore"; … }` — in one closed edit here. An agent that
needs local state beyond those creates `store/<slice>.ts`; it does not widen this file.

**`components/sidebar.tsx`.** The nav rows move to `components/nav-items.ts`, which carries
all fourteen entries from day one, each behind a boolean an agent flips when its route stops
being a stub. The sidebar renders whatever the list holds. Nobody edits the sidebar again.
Flipping that boolean is the one edit to a shared file every slice is allowed: one line each,
in a list of fourteen, which is the cheapest conflict git can be handed and the only one the
integration pass expects to see.

**Route stubs.** Every route below exists after slice 0, rendering a one-line placeholder,
so no agent creates a directory a second agent also creates: `/t/[key]`, `/p/[id]`,
`/docs`, `/docs/[id]`, `/cycles/[number]`, `/triage`, `/views/[id]`, `/workload`, `/inbox`,
`/trash`, `/roadmap`, `/roadmap/[key]`, `/about`.

Frozen for the whole fan-out: `app/globals.css`, `styles/tokens.css`, `app/layout.tsx`,
`app/providers.tsx`. Existing tokens only, Tailwind utilities for everything else, and
`lib/tokens.test.ts` keeps the rule honest. A slice that believes it needs a new token says
so instead of adding one.

E2E spec numbers are reserved: 17 for A, 18 for B, 19 for C, 20 for D, 21 for E, 22 for F.

### The shared backend

`V8__comments_labels_activity.sql`, with the services beside it.

**Comments.** `comments(id, ticket_id, doc_id, author_id, body, created_at, updated_at)`,
exactly one of `ticket_id`/`doc_id` non-null, enforced by a check — a comment belongs to one
thing, and a nullable pair with no constraint is how a third case gets written by accident.
`comment_mentions(comment_id, user_id)`, filled by the service from `@` handles resolved at
write time rather than re-parsed on read, so renaming a user does not silently drop a
mention that was already delivered. Every write goes through `TicketAccess`, which is the
one rule that already holds for every mutation and does not get an exception here.

**Labels.** `labels(id, team_id, name, colour)` — team-scoped, because two teams calling
different things `sync` is normal and a global namespace would make them fight — plus
`ticket_labels(ticket_id, label_id)`. `colour` is a token name from the closed accent
vocabulary, not a hex string; the database is where an unknown value gets refused, as
`user_preferences` already argues.

**Activity.** `activity(id, entity_type, entity_id, actor_id, kind, payload jsonb,
created_at)`. Written by the services that already publish events, at the same point in the
same transaction as the change — not by a listener on `pg_notify`, which would make the log
lossy in exactly the case it exists to explain. `kind` is a closed vocabulary; `payload`
carries the before and after of a scalar change and nothing else. The public roadmap (F)
reads none of it: an activity row can name a private ticket.

**One preference column.** `user_preferences.open_ticket`, `'panel' | 'page'`, default
`panel`. Screen 02 says in so many words that the setting "lives in the preferences", and it
does not: `Preferences` carries theme, accent, density and three booleans, and nothing about
how `↵` opens a ticket. It belongs here rather than in A because A is otherwise free of
migrations, and a slice that owns no schema is a slice whose branch cannot collide.

Three read endpoints and three write endpoints, `GET`s open and writes scoped, the rule
`architecture.md` already states. Kotlin tests for each; the suite is `@Transactional` and
rolls back, so the `afterCommit` hole noted in `follow-ups.md` applies here too and the
activity row is asserted by the service test, not by an event assertion.

## The six slices

Each is a branch. Each reads its own screens in the bundle **in full** before writing
anything — the drawings carry dimensions and copy that no summary preserves. Each writes
tests first. None merges: integration is sequential and mine.

### A — Views of existing work

Screens 03, 04, 05, 06. No migration, no new endpoint: everything drawn is already served.

03 is the panel's content at page width on `/t/[key]`, resolved through
`GET /api/tickets/by-key/{teamKey}/{number}`, with `⇧↵` and `⤢` navigating to it and `esc`
going back. Which one `↵` gives is read from `preferences.openTicket`, the column slice 0
adds; A renders its control in the appearance section and honours it.

04 is the board: one column per status, colour in the column header and a 2px rule at the
top of each card, `h`/`l` across columns, `1`–`6` moving a card, drag and drop as well. It
is a `view` value, not a route, and it fills the `components/board/view.tsx` stub slice 0
leaves behind the third segmented button.

05 is `/p/[id]`: description, lead, period, status counts, first five tickets, linked
documents, activity feed. The feed reads slice 0's activity endpoint.

06 rebuilds the palette as the drawing has it — tab strip, tickets and documents and
commands in one result list, preview pane on the right, `3 of 12 results`. It is the one
slice that edits an existing component (`command-palette.tsx`) rather than adding files, and
it owns that file for the duration.

### B — Documents

Screens 07 and 22. `V9`, and the largest single piece of new backend in the spec.

`doc_folders` (a tree, parent-nullable, team-scoped), `doc_pages` (title, folder, author,
`notion_page_id` nullable so a Kanso-native page is legal), `doc_blocks` (ordered, typed,
one row per block, `jsonb` content), `doc_templates` (three seeded: cycle note, decision,
incident report), and the backlinks that make a ticket mentioned in a page the same ticket —
`ticket_docs` exists and gains a block-level counterpart.

The behaviour the README calls out as decided, and which is B's to honour: `c` inside a
document creates a ticket already attached to the page — the page receives a reference
block, the ticket keeps the link — and it is not the generic composer.

`notion_docs` stays exactly where it is. It indexes a page somebody wrote in Notion; a
`doc_page` is a page written here. Merging the two is a migration this spec does not
authorise, and the mirror's semantics do not survive it.

### C — Organising

Screens 19, 20, 21, 23. `V10`: `cycles(team_id, number, starts_on, ends_on, state)`,
`ticket_cycles`, `saved_views(team_id, name, shared, filters jsonb, group_by, sort_by)`.

19 reads a cycle's progress, remaining work as a hatched projection, and which tickets will
not fit at the current rate. 20 is the triage queue: one incoming ticket at a time, four
keys, each advancing to the next, nothing lost. 21 is a saved view with its filter chips,
plus bulk edit — `x` and `⇧↑↓` select, and a strip acts on the selection. 23 is workload:
open tickets per person cut by status, counted, never estimated in points.

The similarity figures in 20 ("looks like KAN-142, 68%") are trigram similarity on title
via `pg_trgm`, computed on read. Not a model, not a stored score.

### D — Inbox and states

Screens 14, 15, 24, and 08's missing half. `V13`: `notifications(user_id, kind, entity_type,
entity_id, actor_id, read_at, created_at)`, written by the same services that write activity
— an activity row says what happened, a notification says who needs to know.

15 is three states: a filter that found nothing saying so (`it is the filter, not the
database: 14 tickets exist outside it`), an offline banner listing the queued writes, and a
conflict chooser. The offline queue is **written to disk**, IndexedDB, not held in memory:
it survives a reload, order is preserved per actor, and a rejected write stays in the queue.
The README states this as decided; it is D's to deliver, and it is the hardest thing in this
spec that has no server component.

24 is the three-step import, whose second step maps each Notion database onto a project, a
document folder, or nothing, and writes nothing before the preview is confirmed.

### E — Trash

Screen 26. `V11`. Soft deletion becomes real across four entity kinds — ticket, document,
saved view, folder — with a deleted-by, a deleted-at, a thirty-day countdown shown as
`28 j`, and three exits: restore into a named parent, archive instead, delete for good.

`archived` already exists on teams, projects and tickets and is a different fact: archived
is a decision, deleted is a countdown. E adds columns beside it; it does not repurpose it.
`TeamService.archive` re-running its whole dispersal on an already-archived team, noted in
`follow-ups.md`, is E's to fix, because it is the same code path.

The drawing's own detail is load-bearing: deleting a document that mentioned two tickets
deletes neither ticket — only the reference goes.

### F — Public surfaces

Screens 27, 28, and the landing page. `V12`: a `public` flag on tickets and
`votes(ticket_id, voter_key, created_at)`.

These are the only routes that answer without a session, so they are also the only place
where getting the read model wrong leaks a private ticket. The rule: a public route reads a
dedicated projection that selects `public = true` and nothing else — never a scoped query
with the actor omitted. No activity, no comments beyond those a future decision marks
public, no assignee emails.

`voter_key` is a hash of IP and day, not an account: the drawing shows open voting without
sign-in, and storing the raw address to prevent double voting would be a worse trade than
letting a determined visitor vote twice.

The landing page is `/about`, not `/`. `/` is the application.

## How six branches become one

A slice owns its own routes, its own `lib/api/<slice>.ts`, `lib/queries/<slice>.ts`,
`lib/actions/<slice>.ts`, `store/<slice>.ts`, its components under
`components/<slice>/`, its API package under `dev.kanso.<slice>`, its migration, and its
numbered Playwright spec. Three existing files are handed to exactly one slice each and to
nobody else: `components/command-palette.tsx` and
`components/settings/appearance-section.tsx` to A, `components/settings/connections-section.tsx`
to D. Anything not on this list and not created by the slice is read-only to it — including
`components/tickets.tsx`, `components/detail-panel.tsx` and `app/page.tsx`, which every
slice will be tempted by and none may edit. A slice that cannot finish without touching one
says so and stops; I make the edit in the integration pass, where it is one diff instead of
six.

Each agent works in its own git worktree on its own branch, touching only files its slice
owns, and stops when its slice is green: `pnpm typecheck`, `pnpm vitest run`, its own
Playwright spec, and — for the five with a migration — the Kotlin suite.

Integration is sequential, in the order A, B, C, E, D, F, with typecheck, vitest and the
Kotlin suite between each. A is first because it has no migration and its route
resolution is what the others link into. F is last because it is the only one whose
mistakes are visible to people without an account.

Testcontainers means five JVM suites at once on one laptop. If the machine chokes, the
backend runs serialise; the browser and unit suites do not.

## What is deliberately not here

**The eight mobile screens.** `Kanso - Mobile.dc.html` sits at the repository root, outside
the bundle, and carries screens 29 through 36 — my tickets, ticket read-first, status sheet,
inbox pile, create, search-instead-of-navigation, read-only document, dark and offline. They
are a native or PWA surface, not a narrower window: screen 18 already covers the responsive
web under 720px. Their own spec, later.

**Per-field merge on conflict.** Screen 15 offers "keep mine / keep Notion" per field. The
mirror's documented rule is that Kanso wins; the drawing's own board lists per-field merge as
a backlog ticket. D builds the chooser over the existing rule and does not change it.

**Comment editing and threading.** Slice 0 stores a body, an author and a timestamp. Replies
are drawn once, in 14, as `your comment received a reply`, and one drawing is not enough to
design a thread model around.
