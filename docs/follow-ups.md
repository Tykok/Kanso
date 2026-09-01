# Follow-ups

Known gaps, carried out of the branch that built team/project/ticket CRUD
(commits `a972efa`..`69701ac`). Each was found by a review, judged not to block the
merge, and left deliberately. None is a mystery: the reasoning is here so the next
person does not rediscover it.

## Worth a decision

**`ProjectService.update` refuses instead of repairing.** Moving a project to another
team now returns 409 when tickets elsewhere point at it, rather than silently clearing
their `project_id`. It makes a previously-always-succeeding, member-facing call able to
fail. That was the deliberate reading of "a ticket's project belongs to its team is an
invariant", but the refusal message shows raw UUIDs in the dialog footer, and an
*archived* ticket blocks the move with no way to reach it from the screen.

**Sub-teams are creatable from the UI for the first time, and the sidebar caps
indentation at two levels.** A four-level nest renders levels 2, 3 and 4 at the same
indent, so the rows become visually indistinguishable. The cap predates this work; the
reachability does not.

## Test shape, not test count

**`TeamService.kt`'s null-`projectId` event payload is untestable as the suite stands.**
`EventPublisher` fires on `afterCommit`; every service test is `@Transactional` and
rolls back, so no event in the suite ever reaches `pg_notify`. Covering it needs a
different test shape, not another assertion.

**One Playwright scenario failed once and never again** — five consecutive green runs
afterwards, including a virgin database and a cold JVM. The assertion text was lost to
`test-results/` cleanup before it could be read, so "cold-start timeout" is a
hypothesis, not a finding. If it recurs, capture the output before rerunning.

**`useContents`'s `gcTime: 0` is not covered.** Vitest runs in `environment: "node"`
with no DOM, and the behaviour is a cache lifetime rather than a rendered result.

## Small and mechanical

- `project-dialog.tsx`'s `route()` docstring still claims the only team-mentioning
  server message is a 400. The 409 added later makes that stale.
- `team-dialog.tsx` sends `Parent team X does not exist` (400) and `A team cannot be
  its own parent` (409) to the dialog footer rather than under the Parent field.
- `e2e/README.md` does not mention that a non-default `WEB_PORT` also needs
  `KANSO_WEB_ORIGIN` — closed. Without it, CORS silently made every page render as if the
  visitor were a member, which cost one full debugging run to diagnose here and stayed
  merely annoying for two branches. The scoped-timeline branch makes it dangerous instead:
  scenario 13 is a *permissions* test, and a permissions test that goes green because
  every visitor is rendered as a member is not slow to fail, it cannot fail. The README
  now names the variable next to `WEB_PORT`.
- `TeamService.archive` does not check the team is already archived, so re-archiving
  re-runs the whole dispersal.
- `TeamService.requireTicketDestination` does not check the destination team is
  unarchived. The UI filters archived teams out, so this is reachable only through the
  API — live tickets moved into an invisible team.
- The e2e suite keys on private CSS classes (`.row`, `.row-id`, `.status`, `.nav-item`)
  rather than roles.

## Not a defect, but load-bearing to know

**~~There is no CI.~~ Closed: `.github/workflows/ci.yml`.** The Gradle suite, and the web
unit tests, typecheck and lint, now run on every push. What tipped it was not this entry
but the OAuth branch: an authorisation server, an unauthenticated row-creating
`/connect/register` and a webhook-shaped surface are code whose regressions are silent,
and "when someone remembers" was affordable for CRUD and is not affordable for that.
Playwright is still waiting to be remembered, deliberately — `e2e/README.md`'s environment
variables decide whether scenario 13 is a permissions test or a test that cannot fail, and
a first CI is the worst place to get that wrong. The workflow's closing comment carries
that argument and the one against `next build`, which is a real gap left open only because
it was not run clean while the file was written.

**`app.palette` is a dead action.** ⌘K is intercepted ahead of the registry, and no menu
references it, so its only surface is the palette itself.

---

# Carried out of the mouse-parity branch

Every action reachable with a mouse (commits `da2e6bc`..`HEAD`). Same rule as above:
each was found by the whole-branch review, judged not to block the merge, and the
reasoning is written down so it is not rediscovered.

## Worth a decision

**`ProjectDialog` has no contextual title.** `TeamDialog` renders `New team under X`
when `creationSeed` pre-fills a parent, and scenario 9 asserts that title as the place
the context reappears now that the dedicated "New sub-team" action is gone. The project
dialog gets the same kind of pre-fill — the team select is seeded from the scope — and
still says only `New project`. Nothing on screen says *which* team it will land in
except the select itself, which a person who did not open it has not read. Two dialogs
answering the same question two ways is the part worth deciding, not the wording.

**A ticket row now costs three tab stops instead of one.** The status pill, the
priority mark and the `⋯` are each a `<button>` in the tab order, so tabbing through a
list of twenty tickets is sixty stops. Every one of them is reachable by keyboard
without tabbing — `1`-`6`, `e`, `x` and the palette all act on the selected row — so
the tab stops buy a keyboard user nothing they did not already have, while making Tab
a worse way to leave the list. Roving `tabindex` across a row, or taking the pills out
of the tab order entirely, are both defensible; picking one is a decision about who Tab
is for.

## Test shape, not test count

**`ticket.delete` from the command palette is unconfirmed.** The action's `when` is
`hasSelection`, so `availableActions` offers it in the palette, and `page.tsx` runs it
against the same context — it should work. Nothing exercises it: scenario 11 deletes
through a row's `⋯`, and the palette's own coverage stops at the actions the keyboard
already had. "Should work" is the phrasing that preceded three of this branch's
regressions.

**No menu shows `⌘K` against *Command palette* — closed.** Menu hints are mapped from
`Action.shortcut`, which is a list of `KeyboardEvent.key` values `resolveShortcut`
dispatches on; `app.palette` deliberately carries none, because ⌘K is intercepted ahead
of the registry and registering `k` there would collide with `ticket.moveUp`. It now
carries `hint: "Mod+K"` instead — a display string that is never dispatched — and
`hintOf` expands `Mod` to the modifier the reader's own keyboard has. The three surfaces
that each spelled the rule out themselves (`menu-items.ts`, the palette in `page.tsx`,
`shortcutRows`) read that one function, and the overlay's hardcoded `⌘K / Ctrl+K` pair is
gone with it.

## Small and mechanical

- **The brand block is contingent on the registry.** `Menu` renders `null` for an empty
  item list, which is what makes a member see no `⋯` on a team row. The same rule
  applies to the brand: if `app.settings`, `app.help`, `app.palette` and `app.logout`
  ever all fail their `when`, the identity header and the version footer disappear with
  them — the two pieces of the popover that are not menu items at all, and the ones the
  spec says answer a question nothing else answers. All four are `when: () => true`
  today, so it is unreachable rather than broken.

---

# Carried out of the timeline branch

## Not a defect, but load-bearing to know

**An undated ticket in the middle of a chain blanks the critical path around it.**
`CriticalPath` drops any edge with an unscheduled end, so `A → B(no dates) → C` leaves
A and C as singleton components and neither gets a slack figure. Splicing `A → C` past
the hole was considered and refused: `Cascade` stops its descent at that same undated
node, so the splice would have the critical path assert a constraint the engine does
not enforce. Two components disagreeing about what the arrows mean is worse than the
surprise. Revisit only if the cascade ever learns to traverse an undated ticket.

**The migration cast is the kind that ships silently.** `V6__timeline_dates.sql` uses
`start_date::timestamp AT TIME ZONE 'UTC'`, not `::timestamptz`, because the bare cast
reads the date in the session's timezone — a server in Paris would have stored every
existing day as 22:00 the day before. No test covers it: Testcontainers starts empty,
so there is no legacy row to convert. Any future backfill of a date column has the same
trap.

**The cascade is not separately observable.** `TicketService.patch` publishes one event
for the edit, and that event announces the cascade only by construction: `EventPublisher`
fires `afterCommit`, so it lands after the moved rows are written, and every receiver
answers it by invalidating `["tickets"]` wholesale. Nothing names *which* tickets moved.
That is enough for the current client and deliberately so — the plan's second event was
a byte-identical duplicate and was not shipped. A client that ever wants to animate the
moved bars rather than refetch them needs `KansoEvent` to carry ids, which is a change
to `Events.kt`, not to the scheduler.

**The timeline computes violated edges independently of the cascade — closed, and no
longer true by design, not by drift.** `TimelineService.brokenEdges` walks every edge in
the current dependency closure on every load and asks the stored dates one question: does
the successor start before the predecessor ends. `Cascade.violated` answers for one descent
from one moved ticket, and reports only the edges *that walk* touched and could not repair
because the successor was done. These were never going to stay one rule: the cascade's
answer is scoped to a request — "what moving this ticket just failed to fix" — while the
timeline's is scoped to nothing but the data on the way in — "what is broken as of this
read," which includes an edge no cascade has touched all session, one broken by a date
edited through Notion, or one that was already broken before this branch existed. A
timeline that deferred to the cascade's set would go blank the moment nobody had recently
dragged a bar. If violations are ever persisted as a column, it is the timeline's
whole-closure answer that belongs there — the cascade's is narrower by construction and
would under-report from the day it was written.

**Which column a *timed* bound occupies is unspecified.** `xOf` places a bar by
`dayValue`, which slices the UTC day, so a due at `2026-08-12T23:00Z` sits in the 12th's
column while its own tooltip reads `13/08 08:00` to a Tokyo reader. Harmless today —
every bound the timeline draws is floating, because nothing in the interface can yet
create a timed one — and unremarked, which is why it is written down here. It surfaces
the first time a ticket carries a time.

**`.main` never let `.list` scroll, and nobody noticed for two branches.** A grid item's
automatic minimum size is its content height, so `.main` grew past `.shell` and the
document scrolled; `.list { overflow-y: auto }` had never engaged. `min-width: 0` was
already there — only its vertical twin was missing. Fixed on `.main` in `globals.css`
rather than scoped to the timeline. **This changes how the ticket list scrolls and has
not been seen in a browser**: the compose stack has not been started on this branch.
Check it when the e2e suite first runs.

**`DetailPanel.onPatch` is still untyped.** Task 8 narrowed `ActionContext.patchTicket`
to `PatchInput`, closing that hole on the keyboard path. The detail panel takes the same
kind of bag as `Record<string, unknown>` on a different path, and closing it needs
`projectId` added to `PatchInput` — a wider change than exporting the type.

**Two spellings of "today" — closed.** `actions.ts` and `view.tsx` now both call
`today()` in `timeline-geometry.ts`, which is also what the chart's today rule stands on.
`laterBy` moved there with it: both are day arithmetic, and the geometry module is the
only web module the suite can reach, so the "a day is never converted" rule that each of
them turns on is now covered by a test rather than by a comment.

**Arrows pass behind the bars, not over them.** Task 4 painted the dependency layer last
so an arrow would be drawn over the bars it joins. Once a bar could be dragged, a routed
arrow's vertical leg — a 2px column crossing every row between its two ends — answered
the pointer instead of the bar underneath it, and the press did nothing at all. The layer
moved under the bars (`z-index: 0` against the bar's `1`), which costs an arrow a few
pixels of visibility where a bar lies on it and buys one rule holding for both: what you
can see, you can hit. If a dense chart ever makes a chain hard to follow, the fix that
keeps both is two SVG layers — a transparent hit layer under the bars and a
`pointer-events: none` paint layer over them — not putting the paint layer back on top.

**A project bar cannot be dragged, and one of its bounds could be.** `TimelineBound`
carries `derived`, per edge, so a project with a posted start and a deduced end has one
edge somebody chose. Neither is editable from the chart: the only write is
`PUT /api/projects/{id}`, which replaces the row wholesale, and `TimelineProject` carries
a name and two bounds — a PUT built from it would silently clear the project's status,
lead and team. Resizing an explicit project bound needs either a PATCH on projects or the
timeline response carrying enough to rebuild the whole body.

**Clicking a bar can lose the selection it just made — closed.** `page.tsx` kept
`selectedId` inside the *tickets* query filtered by the search box while the chart drew
the *timeline* query, so with a filter typed, clicking an excluded bar selected it and the
cursor-keeping effect bounced straight back to `visible[0]` — and `h`/`l`/`H`/`L` then
acted on *that* row rather than on the bar under the pointer. `visible` is now the rows
the view on screen actually draws: the filtered list in the list view, every unarchived
ticket in scope on the chart. Filtering the chart to match the list was the other way to
make one list feed both and is a different feature — a Gantt with half its bars hidden
draws arrows to tickets that are not there.

**The cursor's order is the list's, not the chart's.** `j` and `k` walk the tickets query
in its own order, while the chart groups tickets under their projects, so on a board with
projects the two orders differ and `j` can jump a screenful. It was already so before the
timeline's cursor list widened; it is more visible now that the chart's cursor covers
every row rather than the filtered few.

**Erasing an arrow starts with a click or a Tab — closed.** `timeline.unlink` on `D` is
the inverse of `d` through the same palette: it lists the selected ticket's predecessors
by name and `Enter` erases one. The arrows stay focusable — one tab stop per dependency —
so the pointer path is unchanged; what was missing was a key that reaches a dependency
through the ticket it constrains, the way `d` already reaches one. There is still no key
that walks arrow to arrow, and that is a second selection model rather than a gap.

**`timeline.unlink` is the first action gated on a fetch.** `ActionContext.dependencies`
comes from the timeline query, so while that query is in flight `D` is inert and the
action is absent from the palette — `when` counts edges rather than assuming there is
something to erase. Every other `when` in the registry answers from the store or from a
list the page already holds. If a second action ever needs the graph, the question worth
asking first is whether `ActionContext` should carry a loading state rather than an empty
list that reads as "no arrows".

**An out-of-scope arrow is still pointer-only.** `predecessorsOf` lists an edge exactly
when its other end resolves in `ctx.tickets`, which is what silently excludes the ones the
timeline response marks `outOfScope`: outside the current scope, they are absent from that
query too, so there is no name to print. Two unnameable rows in the picker would be two
identical rows with different consequences. Their stubs remain clickable, and naming them
properly means fetching each missing ticket by id when the palette opens — a request per
edge, and a loading state in an overlay that has none. The same drop-out can happen fully
*inside* scope: `api.tickets` caps at 200 while the timeline's own scope cap is 2000, so
past 200 tickets a same-scope edge can still be undrawable-as-a-name and `D` inert on it.

**The link handle has no accessible name.** It is an `aria-hidden` span, like the two
resize grips beside it: pressing it does nothing, only dragging it does, and `d` already
draws an arrow from the keyboard. The consequence is that an end-to-end test cannot reach
it by role — the plan's sketch for scenario 12 expected a `button` named "depends on …" —
and has to press the bar's right edge by coordinate instead.

---

# Carried out of the scoped-timeline-and-overlap-warnings branch

Membership became a permission and the timeline widened to draw who else is in the room
(commits `4364466`..`HEAD`). Same rule as above: each was found by the whole-branch
review, judged not to block the merge, and the reasoning is written down so it is not
rediscovered.

## Worth a decision

**The shared-project widening is real for a transverse project only.** `TicketService.create`
refuses a ticket whose project belongs to a different team; the codebase's own words, now
also in `architecture.md`, are "a team-less project is transverse and belongs everywhere."
So a project holds several teams' tickets exactly when it has no team of its own — which
means the timeline's widening to "everyone with work in a shared project" does what its name
says only for a project nobody has assigned to a team, and is inert for every project that
has one. The choice was partly justified by `ProjectService.update`'s 409 as proof that
projects are already multi-team; that is only true of team-less ones. The dependency-closure
path still widens the scope for every project regardless. Worth deciding whether the
shared-project widening should be retired to the closure-only case it already subsumes for
team-owned projects, since today it reads as a general rule and is not one.

**A team roster is readable by anyone and shown to almost none of them.**
`GET /api/teams/{id}/members` answers for any authenticated user; the dialog that displays
that same list is mounted only behind `canConfigure`. Unreachable through today's UI — the
only path to the dialog is already gated on the same permission — so nothing leaks yet, but
read and hide disagree about who a roster belongs to, and the first new surface that lists
members without routing through that dialog makes the disagreement live.

**SCOPE_LIMIT no longer bounds what the timeline can return, and the widening spends its
own headroom.** `TimelineService.load` builds `graphTickets` from the dependency closure
with no cap, so a response is now `own(<=2000) + shared(<=2000) + |closure|` with the last
term unbounded — `SCOPE_LIMIT`'s own KDoc still promises a bound against a pathological
instance, and that promise is now false, not merely untested. Two distinct costs follow
from it, not one: `findByProjectIds` has no id exclusion, so every own ticket that has a
project is refetched into `shared`, spending cap headroom meant for other teams' rows; and
separately, once truncation drops an own ticket past the cap anyway, that same ticket can
come back through `shared` or the closure, drawn `context = true, editable = true` —
labelled as someone else's work that the reader may nonetheless move.

**Row-level violated and overlap arrows share one glyph and one colour.** The arrow itself
tells red from amber; the ⚠ beside a row does not, for either state. Shipped as the brief
specified — worth a UX decision later, not a defect here.

## Not a defect, but load-bearing to know

**The status pill covers its own bar at month zoom, and the loss is symmetric.** A column
is three pixels there, so an 8px pill straddling a one-day ticket's left edge covers the
bar outright, not partially. `critical` is a flat fill and `late` is a hatch over the same
colour specifically so the two survive greyscale and colour blindness — a distinction the
pill erases for both alike. Neither state keeps a residual signal; "critical but on time"
and "late" become the same unreadable pill, not one worse than the other. Accepted when
the pill was chosen over sharing the bar's own fill, on the grounds that colour on the bar
already means criticality.

**A context row cannot be opened.** There is no detail panel for a ticket you do not own:
the cursor list is the scoped tickets query, and putting a foreign ticket into it would
reintroduce the selection bounce closed on the timeline branch. Its name, dates and status
are in the tooltip and the accessible name, and that is the whole of what it gets.

**Creating a ticket is ungated by design, and that is a real door, not an oversight —
closed.** Every mutation on an *existing* ticket ran through `TicketAccess`; `create` did
not, so a member of any team could drop a ticket onto any other team's board. The
follow-through pass gated it: `create` now calls `access.requireTeam(actor, teamId)`
before anything else, exactly as `patch` already did for its `teamId` side, and the
composer stops offering a team that would refuse the ticket (`TeamResponse.editable`).
One rule, every mutation, finally true — see "Carried out of the ownership follow-through
pass" below.

## Test shape, not test count

**Nothing unit-tests the accessible name a bar builds.** It is the only handle the
Playwright suite has on a bar, and its construction at `bar.tsx:188` carries no coverage of
its own: `vitest` runs in `environment: "node"`, so exercising it means extracting a pure
`accessibleBarName(name, status)` first. Cheap, and not done.

**A project-scoped view can now return rows for other projects, and nothing asserts it
either way.** `projectRows`' union of dependency-closure projects onto a project-scoped view
is unconditional, so `projectId != null` no longer guarantees exactly one project row the
way it always used to. Consistent with the new KDoc's stated intent, but untested in either
direction — a regression back to "exactly one" would pass silently.

**`12-timeline.spec.ts` and scenario 13 divide the same feature's coverage.** Scenario 13
asserts only non-movable bars; the counter-proof that a movable bar still renders its
handles lives in `12-timeline.spec.ts`. Fine as a division of labour, and worth knowing
before assuming either file alone proves the feature.

## Small and mechanical

- `unlink`'s error body changed. A missing successor used to surface as
  `No dependency X -> Y`, because the delete failed first; it now surfaces as
  `No ticket Y`, because the authorization check needs the successor loaded before the
  delete runs. Same 404, different sentence, and safe to change: nothing parses either
  body — no e2e assertion, no client code — so the guarantee worth recording is that
  there is no consumer to break, not that one was checked and spared.
- A tray chip builds its accessible name independently of a scheduled bar's — in
  `tray.tsx` rather than `bar.tsx` — so a ticket's chip and its bar now have differently
  shaped names before and after scheduling, with nothing keeping the two constructions in
  step.
- `message()` in `members-section.tsx` duplicates the identical helper in
  `people-section.tsx` byte for byte. Two occurrences; worth a shared helper at three.
- A failed roster or people fetch renders identically to "this team has no members" —
  `members.data ?? []`. Inherited from `people-section.tsx`, which does the same, so a
  convention gap rather than a regression.
- `canPlan` exists twice — `actions.ts` over an `ActionContext`, `view.tsx` over a
  `Scope` — in the one task of this branch that otherwise goes out of its way to avoid a
  second implementation of a rule.
- `TimelineService.load` is now ~110 lines with six named collections before the return.
  The decomposition is obvious — a private `resolveDrawn(...)` returning a small holder —
  and the next widening will not fit without it.

---

# Carried out of slice 0, the foundation for the remaining screens

## Not a defect, but load-bearing to know

**A long-lived compose volume makes the whole e2e suite fail at the seed, and the message
names the wrong thing.** `seedInstance` claims the instance only when `needsOwner` is true.
On a volume where somebody has already been through the setup wizard by hand, that is false,
so `owner@kanso.test` is provisioned as a plain member and every scenario dies on
`Could not create the team …` — sixteen of seventeen, all pointing at `POST /api/teams`
rather than at the identity that issued it. The suite's own `ADMIN` constant is what makes
this confusing: the account is *named* owner and is not one.

`users_single_owner` is a unique index, so there is no fixing this by promoting the test
account beside the existing owner — one of the two has to be demoted, or the volume has to
go (`docker compose down -v`). Promoting `owner@kanso.test` to `admin` gets sixteen
scenarios green and leaves scenario 10 red on its display name, which the suite sets only
when it claims a virgin instance.

The honest fix is for `seedInstance` to assert that `ADMIN` actually holds the role its name
claims, and to say so, rather than letting the first write fail three call frames later.
Not done here: it is the e2e suite's shape, and slice 0 had no business changing it while
six branches were being cut from the same tree.

**`components/route-stub.tsx` is temporary by construction.** Fourteen routes render it so
that no two parallel branches create the same directory. Each slice deletes its own use of
it; when the last one has, the file goes too. If it is still here once the six have landed,
something did not get built.

**`nav-items.ts`' `live` flag is the one shared edit six branches are allowed.** One line
each, in a list of fourteen. If two branches ever need to change the same row, the
convention has failed and the row belongs somewhere else.

## Worth a decision

**`comments.doc_id` exists and is refused.** The column is in `V8` because the foundation is
shared and slice B was not going to get its own comments table. `CommentService` throws a
400 on a `docId` all the same: the spec asks for comments on documents *and* for
`TicketAccess` on every write, and `notion_docs` has no team, so both cannot hold. The
service chose the access rule over the column. Whoever gives a document an owner — slice B's
`doc_pages` carries a team — is the one who can lift the refusal, and until then the schema
promises something the API declines.

**Comments and labels publish no `KansoEvent`.** Every other mutation announces itself on
`pg_notify` and every client answers by invalidating a query key. These two do not, so a
comment thread open in two tabs does not converge until something else forces a refetch.
Deliberate: naming a wire event is a contract, and the branch that wrote the service was not
the branch that would consume it. `queries/social.ts` keys on `comments` / `labels` /
`activity` as first segments precisely so `applyEvent` can invalidate them the day the
contract exists.

**A mention resolves against the email local part.** `users` has no handle column, so
`@lea` matches `lea@anything`, case-insensitively. An unresolved handle stays plain text, as
specified. An *ambiguous* one — the same local part at two domains — resolves to nobody,
which is a judgement call made in the service: notifying the wrong person is worse than
notifying none. A real handle column would retire the whole question.

## Small and mechanical

- `activity.payload` is typed `Record<string, unknown>` on the client rather than a union
  discriminated on `kind`. Eleven payload shapes each carrying one sentence would be eleven
  types to hold what a renderer reads with a `switch` on `kind` anyway. Revisit if a second
  consumer starts reaching into payloads it did not write.
- `ActivityKind` is spelled out in three places: the `activity_kind_chk` CHECK, the Kotlin
  enum, and `ACTIVITY_KINDS` in `lib/api/social.ts`. The Kotlin enum and the CHECK guard each
  other the way `user_preferences` already does; the client list is a third copy nothing can
  reconcile from a test, because Vitest has no database.

---

# Carried out of the six parallel slices

Fourteen screens and five migrations, built on six branches at once and integrated in one
sequential pass. Same rule as every section above: each of these was found, judged not to
block, and written down so it is not rediscovered.

## Worth a decision

**The trash covers all four kinds now, and the folder's cascade was the decision in it.**
`DocTrashSource`, `ViewTrashSource` and `FolderTrashSource` landed as three beans with no
branch in `TrashService` and no column in any of the three schemas, which is what `V11` bet
on. Two things are worth knowing rather than rediscovering.

Deleting a folder **cascades to its sub-folders and not to its pages** — the line `V9` drew
in the schema, where `doc_folders.parent_id` is CASCADE and `doc_pages.folder_id` is SET
NULL. So one entry is the countdown for a whole branch: nothing is destroyed without a row
in `trash_entries` (the invariant the table rests on), one gesture stays one row to restore,
and `holds` says `FOLDERS(cascades = true)` so the pane names the branch before anybody
confirms. The pages are never written to at all, which is what makes a restore exact — they
read at the root while the folder counts down, and `DocService.folders` prunes the branch in
Kotlin rather than by subquery because what has to disappear is a *branch*, which a `NOT IN`
cannot ask.

`archiveInstead` is refused by default on the `TrashSource` interface, and `archivable` is
sent to the client as `canArchive` so the pane draws two buttons rather than a third that
refuses everything. Only `tickets` has an `archived` column; giving the other three one to
fill a tab would invent a fact no screen draws.

**~~Screen 21's label chip is refused, by name.~~ Done.** `SERVED_FILTERS` serves `label`,
`ViewTicketRepository` has the clause, and the test that asserted the refusal asserts the
filter. The key holds label *ids*, not names: a view reaches into descendant teams and two
of them may both own the name `sync`.

**~~Screen 28 badges no labels.~~ Done.** `firstSteps()` narrows to a label named
`good first step`, matched across the instance because no id can be hard-coded. Where no
team has defined one it falls back to every unclaimed ticket and says so — the response
carries `firstStepLabel`, and the eyebrow reads `Unclaimed · N available` rather than
crediting a judgement nobody made. A team that defined it and marked nothing gets none.

**Nothing else attaches a label yet.** `ticket-labels.tsx` is the one control — the detail
panel, the ticket page, and the strip's sixth button through `BulkEdit.labelId`. The board
rows, the triage list and the search results draw no labels at all, and neither does a
document. The label's `colour` is stored and never drawn: no screen in the bundle colours a
label, so there is no token for the six accents and inventing one was not this branch's
call.

**Notifications have call sites now, and `project_slipped` still has none.** Assignment,
status moves, mentions, replies and the mirror's conflicts are all recorded, each in the
transaction of the change it is about; the failures tab needs none, being derived from
`outbound_jobs`, because the queue is already where "the mirror refused this" is true and a
stored copy keeps saying so after a retry succeeds. `PROJECT_SLIPPED` is the one kind of the
seven with no writer, and not for want of looking: a project's end is *derived* —
`TimelineService.projectRows` computes it from its tickets' bounds on every read, and
nothing stores the previous answer to compare against. Nor is the scheduler one place.
`ScheduleService.cascadeFrom` returns early for a ticket with no dependencies, so a lone due
date moves a project's end with the cascade never running, and `TicketService.create`,
`patch`, `delete` and `NotionPoller.applyTicket` each move it by their own route. Recording
the slip means storing the derived end — one column, and a migration — or leaving the kind
unwritten, which is what it is.

**Three things a conflict row does not carry.** They are written for tickets only: the
chooser's `Keep Notion` is `ticketPatchFor`, which patches a ticket and nothing else, and a
team could not be recorded whatever the chooser did, `entity_type` being closed to ticket,
project and doc. They carry no `theirActor`, because the page gives Notion's own user id and
`NotionClient` exposes no way to resolve one to a name — the chooser draws a bare "Notion"
rather than a uuid, which it was already written to do. And only `title` and `description`
are diffed: a status or a priority coming back is a value from a closed vocabulary that the
corrective push settles on its own, and it is not one the chooser could apply either.

**`ticket_files` is a table the spec did not authorise.** Slice F added it because screen
28's "where to look" list had nowhere to live and one screen draws it. Flagged rather than
hidden; overrule it if the list belongs in a doc page instead.

**`README.md` still says MIT.** The landing page, screen 28 and `github.md` all say
AGPL-3.0, and the pages now say AGPL-3.0 to visitors. One of the two is wrong and it is not
a thing to change on somebody else's behalf.

## Not a defect, but load-bearing to know

**The sidebar's Cycle, Triage and Workload links do not carry `?team=`.** `useOrganiseTeam`
now reads that parameter first, which is what makes those routes linkable and reload-stable
— but `nav-items.ts` is a static list, so clicking through the app still relies on the
scope. It works, because clicking sets the scope in the same session; a link copied out of
the address bar after clicking will not carry the team.

**Comments and labels publish no `KansoEvent`, and neither does anything in `docs`.**
`realtime.ts` subscribes to a hardcoded `["tickets","projects","teams"]` and `applyEvent`
branches on the same three, so a `docs` event would be unreceivable and publishing one would
be dead code. Every new query key starts with its own first segment (`docs`, `comments`,
`labels`, `activity`, `trash`) precisely so one `applyEvent` branch and one topic will be
the whole of that work.

**`ActionContext` carries no router and no query client**, so no route-owning slice could
register a working navigation action. `lib/actions/docs.ts` and `lib/actions/trash.ts` are
deliberately empty and say why; `⇧e` in the inbox and `x`/`⇧↑↓` in a saved view are
dispatched by their own pages. Three slices hit this independently, which makes it the next
thing to decide about the registry rather than three separate annoyances.

**A notification is not access-checked.** `NotificationService.record` writes to whoever it
is handed, so mentioning somebody outside a ticket's team puts that ticket's name and an
excerpt of the comment in their inbox. Right for a mention — a member typed the address on
purpose, and the excerpt is the sentence they wrote — and worth knowing before a kind with a
recipient set nobody chose by hand is added.

**`store/ui.ts`'s `restore` dialog is unused.** The trash restores in place, and a
confirmation for an undo is not worth a dialog. Slice 0 reserved it; nothing opens it.

**Three of the drawings contradict themselves, and each was decided in code with a test
whose message says so.** Screen 19 reads "58 % · 14 of 24" over a breakdown summing to 24
where done is 9 — percent and count are one fact here. Screen 05 shows 62% against a legend
of 4 done of 18 — `donePercent` is done over not-canceled. Screen 27's delivered cards carry
version numbers (`v0.7`) that no model in the product can supply, so they print the real
completion date instead.

**`pnpm install` in a fresh worktree silently skips `@rolldown/binding-darwin-arm64`** under
Node 20.14 (rolldown wants `^20.19 || >=22.12`), and vitest then dies on a missing wasm
binding. `pnpm install --force` fixes it and leaves the lockfile alone. Four of the six
agents hit this. It gets *remembered* as a broken lockfile, because the install exits 0 and
the failure surfaces one command later — it is not. All fourteen platform bindings are in
there, and an install pinned to the runner's `linux`/`x64`/`glibc` resolves
`@rolldown/binding-linux-x64-gnu` out of it cleanly; on Node 24 the darwin binding lands
and `pnpm test`, `typecheck` and `lint` are all green. pnpm skipping an optional dependency
whose `engines` do not match is the whole bug. `ci.yml` pins Node 22 for this and no other
reason, which is worth knowing before someone lowers it.

## Small and mechanical

- `components/route-stub.tsx` had no callers left once the six landed, which is the check
  it was written to be. Deleted.
- ~~`import-dialog.tsx` is 318 lines.~~ Split per step: a 137-line shell plus one file per
  step and a small module for the labels two of them share.
- ~~Screen 24's first step needs a workspace-search method `NotionClient` does not have.~~
  Done: `searchDatabases`, implemented in every client, with `NoopNotionClient` answering
  "no token, and here is why" rather than throwing. Discovery excludes Kanso's own four
  mirrored databases by both ids — without that the import offers `Kanso · Tickets` back
  and duplicates every ticket in the instance. Notion answers no total for a data source,
  so the page count is a bounded walk and the screen prints `2000+` rather than a number it
  did not finish computing.
- Screen 07's "Lié à" rail lists tickets only. The drawing also shows a project above them,
  derivable from the linked tickets' `projectId` — left out rather than guessed.
- Drag-and-drop reordering of doc blocks is not wired: the handle is drawn, and the two
  arrow buttons beside it do the work. `PUT /blocks/{id}/position` takes an index, so the
  gesture is a layer over an endpoint that already exists.
- `V11` still has no FK on `entity_id` — it cannot, pointing at four tables — but the two
  disposition paths now clean up after themselves through `TrashRepository.forget(kind, ids)`
  and `TrashDisposal`. The read still skips an entry with nothing behind it, because nothing
  in the schema can promise the state never arises.
- A trashed ticket still appears in the dependency closure the timeline draws, and
  `schedule.link` will still accept an edge onto one.
- `DocService.page()` calls `tickets.get(id)` once per linked ticket because
  `TicketService.decorate` is private. A page's rail carries a handful; a `getAll(ids)` is
  the right fix.
- Slice B put its Exposed objects in `dev.kanso.docs.DocTables` rather than appending to
  `db/Tables.kt`, and invites the integrator to move them. Slices C, D, E and F appended.
  Two conventions now.

---

# Carried out of the pass that made the fourteen functional

Four branches, in parallel again, closing what the fan-out had left drawn but unreachable.

## Worth a decision

**`PROJECT_SLIPPED` still has no writer, and it needs a column.** A project's end is
*derived*: `TimelineService.projectRows` recomputes it from its tickets' bounds on every
read, and nothing stores the previous answer to diff against. The scheduler is not a funnel
either — `ScheduleService.cascadeFrom` returns early on a component of fewer than two nodes,
so a lone ticket's due date moves a project's end without the cascade running at all, and
four other paths move it by their own routes. Recording "the project slipped three days"
means persisting the derived end. The drawing shows it; the schema cannot say it.

**The conflict chooser can name the field but not the person who edited it.** `NotionPage`
carries `lastEditedById`, a Notion user id, and `NotionClient` exposes no user lookup. The
chooser falls back to a bare "Notion", which is honest; a uuid where a person goes would not
be. A `users.retrieve` call and a small cache would close it.

**`conflictExists` is not scoped by recipient.** The guard that stops the mirror recording
the same conflict every thirty seconds keys on entity, field and the discarded value — so if
the assignee set changes between two polls, somebody who became an assignee after the first
recording is never told about a conflict that is still live. Scoping the guard per user
records N times instead of once; leaving it means a new reader can miss one. Neither is
obviously right, which is why it is here.

**`NotificationService.record` performs no access check.** Mentioning somebody outside a
ticket's team puts that ticket's name and a comment excerpt in their inbox. That is right for
a mention — you were addressed — and worth knowing before a kind with an unchosen recipient
set is added.

## Not a defect, but load-bearing to know

**A folder's delete cascades to sub-folders and not to pages, and that is `V9`'s own line.**
`doc_folders.parent_id` is `ON DELETE CASCADE`, `doc_pages.folder_id` is `ON DELETE SET
NULL` — structure travels with the branch, writing does not. One trash entry is the countdown
for the whole branch, the pane counts it before anyone confirms, and the pages read at the
root while the branch is doomed so that restoring puts them back exactly rather than
approximately.

**"Archive instead" only means something for tickets.** Nothing else carries `archived`, and
adding the column elsewhere would assert a fact no screen draws. `canArchive` travels from
the bean to the response to the pane, so the exit is drawn only where it works — rather than
`kind === "ticket"` in the pane, which is the sentence that outlives the behaviour.

**A trashed saved view still holds its name against the unique index.** `findByTeamAndName`
stays unfiltered on purpose, and `create` now says the name is in the trash instead of naming
a view nobody can see.

**"Pages created in Notion are not adopted" has an exception now** — the import adopts them,
because a person supplies the team the poller cannot. An imported ticket gets its *own*
mirror page in `Kanso · Tickets`; the source page is never adopted or written to, since
"Kanso wins" would otherwise overwrite the workspace that was just imported. Recorded in
`architecture.md`.

**Five of the import's six discovery tests were written implementation-first.** The branch
mutated `NotionDiscovery` to check and reported that they did not fail. Preview and import
were genuinely red first. Worth knowing which half of that slice the tests actually pin.

## Small and mechanical

- A label's `colour` is stored, closed by a `CHECK`, and drawn nowhere: no screen in the
  bundle colours a label and there is no token for the six accents in that role.
- The label control on the panel and the ticket page has no test of its own — the web suite
  is pure-logic Vitest with no component tests, and no scenario opens the detail panel. The
  bulk path is covered end to end and at the service level.
- `V11__trash.sql`'s comments still say only `ticket` has a table behind it. Flyway validates
  checksums, so an applied migration cannot be edited; `Trash.kt` carries the correction.
- `e2e/support.ts` has no document seeders, so `21-trash.spec.ts` inlines its own `seedJson`.
- `Menu` writes `aria-label` on its trigger, which *replaces* a visible child's text. With
  `asChild` that is WCAG 2.5.3 waiting to happen: `pills.tsx` prefixes (`Status: In
  progress`), the bulk strip's Label button now uses the visible word itself, and any future
  `asChild` trigger with text has to do one or the other.

---

# Carried out of the pass that made connecting simpler

## Worth a decision

**Google still needs one trip to the Cloud Console, and always will.** No flow issues an
OAuth client by consent, so the admin's one-time client creation cannot be replaced by a
button — which is why that pass made the trip *verifiable* (a probe that tells a wrong secret
from a wrong code) and *shorter* (the downloaded JSON fills both fields) rather than
pretending to delete it. If a hosted Kanso ever exists it can register one client and skip
this; a self-hosted instance cannot, and should not be told otherwise.

**One-click Notion — no integration to create at all — needs a redirect broker somebody
operates.** Notion requires the redirect URI to be registered on the integration, so an
instance at an arbitrary hostname cannot borrow a client the project registered unless the
callback passes through a host the project runs. That is a service to operate and a third
party in the path, against "hébergeable par n'importe qui, aucune télémétrie". Declined for
that reason, not for effort.

## Not a defect, but load-bearing to know

**`/api/setup`'s admin guard has no test, on any endpoint.** Nothing in the repository
exercises it, so the two endpoints added here are consistent with their neighbours rather
than newly exposed — but "consistent with an untested guard" is what it is. Covering it means
new `@SpringBootTest` scaffolding for the wizard, which no scenario has needed yet.

**The Notion callback needs forwarded headers behind a reverse proxy.** The redirect URI is
built from the incoming request so that what Notion was told and what Notion is answered
cannot drift — which is the right trade, and it moves the failure to a proxy that does not
forward its host. Recorded in `architecture.md`.

**`conflictExists`-style de-duplication has no equivalent here, and does not need one.** A
second consent simply replaces the token; re-connecting is idempotent by construction.

## Small and mechanical

- `asText(defaultValue)` is deprecated in Jackson 3 and used at 49 call sites across the
  API, including the ones added here. Consistent, and a real migration when somebody wants
  it — not a thing to do half of.
- The consent flow was written implementation-first and its tests verified by mutation
  (dropping `owner=user`, dropping the workspace name) rather than by having been red first.
  The distinction is recorded because it is the discipline this repository otherwise keeps.
- An untitled Notion page lists as `Untitled page · <8 id chars>`: a page nobody can pick is
  worse than one with an ugly name.

---

# Carried out of the branch that made an existing workspace importable

Screen 24 could already read *one* shape of Notion database — a base becomes a project, its
columns matched by exact English name. This branch made it read the workspace people
actually arrive with: separate bases for teams, projects and tasks, wired by relations, with
columns called `État` and options called `En cours`, people matched to Kanso accounts, and a
table of origins that makes a second import skip rather than duplicate. Same rule as every
block above: each item below was found by a review or by the browser pass, judged not to
block the merge, and left deliberately. The reasoning is here so the next person does not
have to derive it again.

## Worth a decision

**An origin row outlives the entity it names, and nothing cleans it up.**
`notion_import_origin` carries no foreign key — the reference is polymorphic onto four
tables — so deleting a team, a project or a ticket leaves a row whose `entity_id` resolves
to nothing. That much is harmless by construction: `ImportOriginRepository.live` filters the
seed the writers *resolve* against to rows whose entity still exists, so a stale row behaves
exactly like a relation into an ignored base — it resolves to nothing and falls back.

The "already imported" set is deliberately **not** filtered the same way, and
`NotionImportService.read` says why: a page whose Kanso row somebody has since deleted is
not a page to import again, because resurrecting a team a reader chose to remove is the
louder mistake. That reading is right for a deletion somebody meant. It is wrong for a
deletion somebody regrets, and there is nothing anywhere that can tell the two apart, which
is what makes this a decision rather than a cleanup: a sweep that dropped stale rows would
turn every accidental delete into a re-import, and doing nothing means a base can never be
brought over again. What is missing first is a way to *see* the stale rows — the number of
them, per base, on the preview — before anybody decides what to do with them. The spec named
the cleanup as out of the branch, and `architecture.md` records why the table has no foreign
key in the first place.

**A base longer than `max-pages-per-database` imports its first two thousand pages, and the
only thing that says so is a `+`.** Notion answers no page total for a data source, so the
count and the read are the same bounded walk, and the import brings over exactly the prefix
it walked. The screens are honest about the *count* — `pageCount` prints `2000+ pages` on
step 1, step 2 and the confirm button — but nothing anywhere says "and the rest will not come
over", and the outcome afterwards reports two thousand rows written with no mention of what
was left behind. Somebody importing a five-thousand-row base cannot tell that from a
complete import except by arithmetic. Raising the bound is a setting; saying it out loud is a
sentence somebody has to write, and the preview is the place for it rather than the count.

**Cycling a base's target on step 2 costs one Notion call per target tried.** The relation
suggestion needs the base's schema, so step 2 asks for it as soon as the base is kept. The
query is keyed by `(sourceId, target)` with `staleTime: Infinity`, so keeping a base once
costs exactly one call and walking back into step 2 or forward into step 3 costs none — the
plan's own worry, "one call per base per screen visit", the cache already answered. What is
left is the cycling: a reader who presses the button through teams, projects, tickets and
documents to read the labels spends four calls on a base they may then ignore, and the
client's ceiling is ~2.5 requests a second across the whole application. Asking only for the
target a reader settles on, or debouncing the button, would close it.

**The parent-side link rule is per base, not per page.** A `single_property` relation can
live on the parent alone — a `Tâches` column on the projects base and nothing on the tasks
base — so `openFallbacks` treats a link as resolvable when *either* end names the other,
which is exactly what `ImportLinks.resolveOneToOne` reads. But a schema cannot say how many
pages that column actually names. A parent column naming 10 of 300 children therefore hides
the fallback selects for the whole base, and the other 290 rows land in the auto-named
project with nothing having asked where they should go. Answering per page needs the
preview's counts on a screen that currently holds only the schema.

**A `documents` base is always asked where its unlinked rows land, and a `tickets` base is
asked twice.** `openFallbacks` answers per target. Documents has no relation to try, so it
always returns `teamId` — which is the question step 2 already asked and the reader already
answered. Tickets returns `projectId` *and* `teamId` together, so choosing a project does
not retire the team question, even though a project determines its team. Neither is wrong;
both put a select in front of somebody who has already decided.

**A second import of a `documents` base still creates a second folder.** A tickets base's
container project is recorded in `notion_import_origin` under the base's own data source id,
so a later run finds it instead of making another; a folder cannot be, because `entity_type`
is `CHECK`-constrained to `team | project | ticket | doc` and a folder is none of them. The
two container kinds therefore behave differently and only one is safe to press twice. It
waits on a migration that gives a folder a kind of its own — which is also where the
`documents` target's own spec will have to start, since the spec left importing documentation
properly out on purpose.

**`HttpNotionClient.listUsers` walks its cursor with no upper bound.** Every other walk in
that client is capped — `NotionDiscovery.search` at `max-databases`, the page walk at
`max-pages-per-database` — because a Notion answer that repeats a cursor or never clears
`has_more` would loop. `listUsers` is the exception, on the argument that a workspace's
membership is small. That argument is about the data, not about the answer.

## Not a defect, but load-bearing to know

**An option's meaning has two authorities, and the screen is what keeps them agreeing.**
`MappedPageReader` applies the mapping's own answer for an option and falls back to matching
the option's *label* against Kanso's vocabulary — which is what lets a hand-written request
map `Etat → status` and get `In Progress` right with no option table at all, and is tested as
such. The columns step therefore cannot print "this takes the field's default" for an option
whose label the reader would match: `ImportSchema` sends the label match for every candidate
column, the step seeds it whenever a column is picked, and whatever is left on "— default —"
is drawn with the value the *reader* would actually apply to it. The alternative — deleting
the label fallback so the mapping is the only authority — was weighed and declined: three
tests document the fallback as a decision, and it is the half of the contract an API client
depends on. What is load-bearing to know is that the two paths exist and that only the
per-option pre-fill keeps the screen honest about them.

**`preview.skipped` counts what the *reader* refused, and one page can still be refused by
the writer.** `TeamService.resolveKey` gives up after ninety-nine collisions, so a teams base
holding a hundredth name that shares its first three alphanumerics has one page `TeamImport`
drops at the insert — reported in the outcome's `skipped`, invisible to the preview, which
runs before any transaction and cannot know which keys will be free. The confirm button's
number is therefore a ceiling by one page per exhausted key prefix. Same family as the
sentence below about the project count: a preview is arithmetic over what was read, and a
key is only settled by writing one.

**Step 5 can promise one project more than the import writes.** `import-step-three.tsx`
counts a tickets base as one project, because `TicketImport` makes a container project for
tickets whose own relation answered nothing — and the preview cannot say whether every
ticket will resolve. When they all do, no container is created: the header reads "1 team, 2
projects" and the outcome underneath reports one project. Found by the browser pass, which
asserts the outcome and deliberately does not assert the header, because asserting a number
that can be wrong is how a wrong number gets kept.

**The suite's Notion workspace sits at the network boundary, not inside the application.**
`e2e/notion-workspace.ts` answers Notion's own HTTP API and the container is pointed at it
through `NOTION_BASE_URL`, so the client under test is the real `HttpNotionClient` — its
search-filter fallback, its cursors, its property parsing. The alternative considered was a
`NotionClient` bean chosen by a Spring profile, which would have put `FakeNotionWorkspace`
into the production source set to make a test possible, and was ruled out before the work
started. The cost is that scenario 23 needs a stack carrying two extra variables and skips
without them; `e2e/README.md` holds the command, and the skip's own message repeats it.

**Nothing in the import writes to Notion, and the suite now says so instead of assuming
it.** Imported rows get their own pages in `Kanso · Tickets` through the outbox like any
other row, and the source workspace is never written to. Scenario 23's workspace records
every write it is asked for and the scenario expects none — a live assertion of the promise
screen 24 makes in words.

**A batch matching two Notion ids to the same Kanso account leaves that account's
`notion_person_id` dependent on map insertion order.** Ambiguous input rather than a defect:
two Notion identities cannot both be the one id a Kanso row carries. Nothing in either
screen can produce it, since a row is one person; a hand-written `PUT /api/notion/people`
can.

**`droppedAssignees` counts assignments, not people.** One outsider on fifty pages reports
fifty. The number exists to say "some work came over unassigned", which fifty says as well
as one — but it reads like a headcount and is not one.

**A one-to-one relation naming a row that has since been deleted falls back correctly and is
not counted.** Only the dependency pass counts its own drops, so the outcome can report zero
dropped relations for an import where a link genuinely went nowhere.

**`notion_import_origin.data_source_id` is written on every row and read by nothing.** It is
the column that would let a later run report "this base was already brought over, 396 of its
400 pages are here", and `notion_import_origin_source_idx` is the index that query would
use — but no screen asks for it, and `ImportOriginRepository.countBySource` was removed as
dead code during the branch on the understanding that whichever task needed it would bring it
back with a test. None did. Nothing is broken; what is misleading is that the spec describes
the report as though it exists, so `architecture.md` now says plainly that the column is
recorded and unread.

## Test shape, not test count

**The browser pass walks the five screens once, along the path a reader takes.** What it
does not cover: a second import of the same plan (skipping is proven in process by
`NotionImportTest`), a base whose schema Notion refuses in the middle of the dialog, the
`documents` target, and the 403 from `listUsers` reaching step 4 as a sentence rather than
as an empty list. Each of those is another full walk through a five-step dialog against a
suite that runs one worker, which is the reason they are not there rather than an argument
that they should not be.

**Nothing pins that the person correspondence is written before the writer runs.**
`NotionPeople.link` runs first inside `perform`, so that a page whose assignee is dropped
still leaves the correspondence behind — and the ordering is hard to observe from a
`@Transactional` test class that rolls back.

**`rowCounts()` in the import tests covers neither `notion_import_origin` nor
`users.notion_person_id`.** So the "`peopleSeen` writes nothing" assertion is weaker than
its name; the real guarantee is structural — the function takes no `people` parameter and is
handed nothing that can write.

**No test drives a refused fallback project through the import.** The access check itself is
verified in `TicketService`; what is unproven is that a `Fallback.projectId` naming a project
the actor may not write to is refused before anything is written.

**`DocumentImport`'s lazy folder and `TeamImport.settleParents` skipping an already-imported
child are unpinned.** Both are second-run behaviours of paths whose first run is covered.

**The "no token configured" branch of `NotionPeople.view` has no test.** Its behaviour was
verified by reading `NoopNotionClient.enabled = false`.

## Small and mechanical

- Four files in `sync/importer` are past the ~180-line shape the rest of the package holds
  to: `NotionImportService.kt` (246), `MappedPageReader.kt` (234), `ImportLinks.kt` (201),
  `ImportModel.kt` (187). `import-step-columns.tsx` (358) and `import-dialog.tsx` (301) are
  the same story in the browser. Splitting any of them is its own piece of work.
- Two refusal sentences — "Notion is rate-limiting this integration…" and "Notion refused
  the request: …" — are written out verbatim in three places: `NotionImportService`,
  `NotionPeople` and `setup/NotionParentPages`. `SetupController` carries a fourth, worded
  differently.
- `ImportPlanner` reads `MappedPageReader.refusal` twice over the same page list, once to
  keep the adoptable pages and once to keep the refused ones, and `ImportWriter` reads it a
  third time to name the reason. Bounded and small; one partition would say it once.
- `ImportOriginRepository.live` pools every kind's surviving ids into one flat `Set<UUID>`,
  so a row survives if its id exists under *any* kind. Unreachable with v4 UUIDs, and a
  per-kind filter would be exact for no extra code.
- `V15__notion_import_origin.sql` still states the fall-through as unconditional, with no
  pointer to `live`. It cannot simply be corrected: editing an applied migration changes its
  Flyway checksum and every existing instance would then fail validation. The explanation
  lives in `architecture.md` instead.
- `ImportOriginRepository.recordContainer` keys a container project by the base's *data
  source* id in a column named `notion_page_id`. Its doc comment says so; a `require` would
  make it enforceable.
- `ImportLinks` keeps the first target of a child-side one-to-one relation holding several
  and discards the rest without counting them in `droppedRelations`.
- `read` and `write` in `NotionImportService` each query `byPageIds`, and each comment claims
  to be the only one; carrying the first result onto `PlannedBase` would collapse them.
- A `PUT /api/notion/people` costs two full `users` scans, one in `link` and one in the
  `view()` built for the response.
- `peopleSeen` filters its pages by reaching past `ColumnMapping.property(field)` to
  `columns.containsKey` — a loop skip written as though it were a correctness test.
- The fallback select's copy is split between `import-columns.ts` (`FALLBACK_LABELS`) and
  `import-step-columns.tsx` (`FALLBACK_NONE`), while every sibling label table lives in
  `import-targets.ts`.
- `lib/api/inbox.ts` imports `NotionImportSchema` from a component module — the reverse of
  every other wire type there — and `Fallback` is declared in one file and re-inlined in the
  other.
- `linkConflicts`' two plural branches in `import-step-three.tsx` duplicate the whole clause
  for one `s`.
- `openFallbacks` picks its `LINK` row by `base.schema.target` while judging both ends by
  `kept`. They cannot diverge in the real screen, but the function reads two ways about
  which is authoritative.
- `queries/inbox.ts`'s long doc comment now heads `importSchemaQuery` while the hook's own
  comment explains `useQueries`, so it reads slightly out of place.
- `notion-people-section.tsx` invalidates `keys.people` on a Notion-link save with no stated
  reason: comment it or drop it.
- Three tests in `NotionImportTest` repeat the same four-line `perform(admin, team.id,
  listOf(ImportPlanEntry(engineering.dataSourceId, ImportTarget.TICKETS, engineeringMapping)))`
  verbatim. They are the cases Task 8 moved back onto `perform` from the writer, and a
  one-line private helper would say it once without reintroducing the shortcut that made
  them worth moving.
- `docker-compose.yml`'s `api` service carries `extra_hosts: host.docker.internal:host-gateway`,
  and that line exists for the e2e suite alone: it is what lets a `NOTION_BASE_URL` naming the
  host reach the machine running Playwright on Linux, where Docker Desktop's own alias does
  not exist. On Docker Desktop it is redundant, and in production it is inert — nothing reads
  that hostname unless `NOTION_BASE_URL` names it. So it is one line in a shipped file that
  only tests need, which is a small untidiness rather than a risk. The clean fix is a
  `docker-compose.e2e.yml` override holding it and the two Notion variables together; it was
  not done that way because the override would then have to be threaded through every
  documented command — including the three in `e2e/README.md` that predate the import — and
  one line that is inert unless `NOTION_BASE_URL` names that host did not seem to earn a
  second compose file.

---

# Carried out of the branch that made Kanso an authorisation server

Kanso now issues its own OAuth 2.1 grants so an agent can reach `/api/mcp` as the member
who authorised it: dynamic client registration, PKCE, a consent screen the API serves
itself, opaque tokens bound to this resource by RFC 8707, and a revoke button. Same rule
as every block above: each item below was found by a review, judged not to block the
merge, and left deliberately. The reasoning is here so the next person does not have to
derive it again.

## Worth a decision

**Reuse of a spent refresh token does not revoke the grant.** This is the one MUST of the
spec's seven that is unmet, and it costs more than it did when it was written down.
`reuseRefreshTokens(false)` rotates: every refresh mints a new token and the old one stops
working. What the OAuth 2.1 security BCP (§4.3.1) actually asks of a public client's
refresh token is rotation **plus replay detection** — a spent token presented a second
time should invalidate the whole chain, because a replay is the signature of a stolen
token being used alongside the real client. `OAuth2RefreshTokenAuthenticationProvider`
throws a plain `invalid_grant` and contains no call to
`OAuth2Authorization.Builder.invalidate`; the code-replay path in
`OAuth2AuthorizationCodeAuthenticationProvider` *does* invalidate and cascade, so the
asymmetry is the library's and not a misreading. Confirmed on a running instance: a spent
token was refused, and the live one it had been rotated into kept working afterwards.

Two things make it matter now rather than later. `PublicClientRefresh` made refresh tokens
exist at all on this branch — before it, the library refused a public client one, so there
was nothing to replay. And a refresh token lives sixty days against an access token's one
hour, so it is the credential worth stealing. Implementing it means a custom
`AuthenticationProvider` for the refresh grant that recognises a token the authorisation no
longer holds and revokes the row, which is a provider replacing one of the library's — the
kind of thing to decide on purpose. The member-facing mitigation exists and is real:
Settings → Agents revokes, and revocation deletes the authorisation rows, so a stolen
refresh token dies with the next Revoke.

**A public client cannot call `/oauth2/revoke`.** The same defect
[PublicClientRefresh] fixes for the refresh grant applies to token revocation: none of the
library's converters authenticates a credential-less client there either, so a client that
wanted to hand its own token back is answered 401 with an empty body. Nothing in Kanso
needs it — revocation is a member action on the Settings screen, and it deletes more than
one token — and a well-behaved client shutting down cleanly is a nice thing to allow. The
converter is already written and narrow; widening it to the revocation endpoint is a
one-line matcher change plus its tests. Left out because the finding named the refresh
grant, and a security surface widened past its finding is a surface nobody reviewed.

**An anonymous browser at `/oauth2/authorize` gets a bare 401 with an empty body.** In
7.1.0 the authorization endpoint filter is installed *after* `AuthorizationFilter`, so
`anyRequest().authenticated()` on chain 1 refuses the request before the endpoint is
reached, and the configurer's own `HttpStatusEntryPoint(UNAUTHORIZED)` answers it.
Confirmed on a running instance: `HTTP/1.1 401`, `Content-Length: 0`, no
`WWW-Authenticate`. `ConsentController` already handles the anonymous case properly — it
mints a return address and sends the member to the login screen — but nothing ever reaches
it, because `/oauth/consent` is only redirected to *after* `/oauth2/authorize` has
succeeded. So the whole first-run path works only for a member who is already signed in in
that browser, which is the common case and is now what the README and the Agents screen
both say. The fix is small and is not a filter: a chain-1
`exceptionHandling().defaultAuthenticationEntryPointFor(...)` matched on an `Accept` of
`text/html`, sending a browser to the login screen and leaving every machine caller its
401. It is left to a decision because it changes what an unauthenticated request to an
OAuth endpoint gets back, which is not a thing to do as a side effect.

## Not a defect, but load-bearing to know

**The token endpoint creates a session when it refuses.** Chain 1 has no
`sessionManagement` configuration, so a request denied by `AuthorizationFilter` reaches
`ExceptionTranslationFilter`, which saves the request into an `HttpSessionRequestCache` and
sets a `JSESSIONID` — observed on a `POST /oauth2/token` that was refused. Harmless: the
session holds one cached request and no authentication, machine clients discard the cookie,
and the sessions expire. Worth knowing because it is a session per refused machine call,
and because `sessionManagement { it.sessionCreationPolicy(STATELESS) }` on that chain would
end it — untried here because chain 1 also serves `/oauth2/authorize`, which is a browser
flow that wants its session.

**`activity.via_client_id` is writable now and still unwritten.** `V19` moved the foreign
key onto `oauth2_registered_client(client_id)`, the public id that is the only one reaching
a service layer — see the migration for why the column moved rather than the principal.
Nothing under `src/main` writes it yet; `AgentRightsTest` carries a tripwire that fails on
the day something does, and says so in its own assertion message.

## Test shape, not test count

**One extra Spring context, on purpose.** `OidcChainWiringTest` is
`@SpringBootTest(properties = ["kanso.auth.mode=oidc"])`, which is a second context
configuration in a suite whose `SecurityBootstrapTest` warns against multiplying them. It
buys the only coverage there is of `/oauth2/authorize`, the chain ordering,
`AgentPrincipalFilter` as installed, `ResourceValidator` as wired and both
`IssuerAppending*` handlers — before it, deleting the two lines that satisfy RFC 9207 left
the whole suite green. Everything needing the oidc chain belongs in that file rather than in
a third context.

**The end-to-end flow is driven by hand, not by a test.** What the suite covers is every
piece of it; what proved the pieces fit was a `curl` walkthrough against a running instance
— register, authorise, consent, exchange, call, refresh — and that is where two findings
came from that no test could see: `McpBearerFilter` querying Exposed outside a transaction
(fixed, and now pinned by a `NOT_SUPPORTED` test), and a public client never being issued a
refresh token at all. A `@SpringBootTest(webEnvironment = RANDOM_PORT)` in oidc mode would
cover the sequence, at the cost of a third context; the walkthrough is cheaper and is
somebody remembering to run it, which is the trade being recorded.

## Already closed — do not reopen

Three items deferred by earlier reviews on this branch were closed by later commits on it,
and are named here because a reader working backwards through the ledger would otherwise
reopen them.

- **`GrantService`'s forward-looking prose.** Its rationale for reading the library's tables
  directly now names `GrantServiceTest` as what holds the formats it assumes.
- **`McpBearerFilter.shouldNotFilter`'s prefix breadth.** Closed by `da28c1a`: the guard
  matches the path Spring routes on, with a wildcard whose reach is argued in the KDoc and
  pinned by `whatever Spring routes to the endpoint, this filter has already seen`.
- **Login before client validation in `ConsentController`.** The client is looked up before
  the anonymous branch, and the ordering carries the attack it prevents in a comment.

## Favourites (KAN-10)

**Ordering is insertion order, and manual reordering is not there.** `favourites` carries a
`created_at` and no `position`. The useful version — drag a pin above another — needs a
column, a reorder endpoint, a drag affordance in a 248px column and a keyboard equivalent
for a keyboard-first product, and none of it falls out of what shipped. Oldest first rather
than newest first, so a fifth pin does not renumber the four above it.

**Tickets and doc folders cannot be pinned.** Argued at length in `V22__favourites.sql`'s
header. In short: every kind that *can* be pinned is a place somebody goes back to, with a
name they chose and a row that fits; a ticket is work that finishes and is recognised by
two facts (`KAN-142 · <title>`) where the column holds one, and a doc folder has no route
of its own to send anybody to. Both are a column and a `@Component` away if the argument
stops holding.

**`s` does not reach the two record routes; a star does.** The registry's key is dispatched
by `app/page.tsx` and `components/views/shell.tsx`. `/views/[id]` runs a hand-written key
handler that never falls through to `resolveShortcut`, `/docs/[id]` gives its keys to the
caret, and `OrganiseShell` mounts no command palette at all — so on those two screens the
gesture is `FavouriteStar` in the header rather than a keypress. Giving `OrganiseShell` the
palette, or letting the saved view's handler fall through to the registry, would close it
and is worth doing on its own rather than from here.

**`/docs/[id]` draws no Favourites section, because it draws no sidebar.** That route has
its own tree rail. Pre-existing, and it means a pinned document is visible from every
screen except the document's own.
