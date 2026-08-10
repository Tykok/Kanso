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
  `KANSO_WEB_ORIGIN`. Without it, CORS silently makes every page render as if the
  visitor were a member — which cost one full debugging run to diagnose.
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

**The timeline computes violated edges independently of the cascade.**
`TimelineService.violatedEdges` re-derives them from stored dates; `Cascade` returns its
own `violated` set and nothing persists it. The two agree today because both encode the
same rule — an edge is violated exactly when a `done` successor starts before its
predecessor ends — but they are two implementations of one fact. If violations ever
become a stored column, the timeline should read it rather than recompute.

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
edge, and a loading state in an overlay that has none.

**The link handle has no accessible name.** It is an `aria-hidden` span, like the two
resize grips beside it: pressing it does nothing, only dragging it does, and `d` already
draws an arrow from the keyboard. The consequence is that an end-to-end test cannot reach
it by role — the plan's sketch for scenario 12 expected a `button` named "depends on …" —
and has to press the bar's right edge by coordinate instead.
