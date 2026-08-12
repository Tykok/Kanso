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

**There is no CI.** Vitest and Playwright run only when someone remembers. The branch's
largest single investment is currently unenforced.

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

**Creating a ticket is ungated by design, and that is a real door, not an oversight.**
Every mutation on an *existing* ticket runs through `TicketAccess`; `create` does not, so a
member of any team may drop a ticket onto any other team's board. Not a takeover path — the
very next patch on that ticket, by anyone, is refused unless it satisfies the three-part
rule — but a board's ticket count is not protected by team membership, only its content is.

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
