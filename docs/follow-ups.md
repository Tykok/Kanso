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

**No menu shows `⌘K` against *Command palette*.** Menu hints are mapped from
`Action.shortcut`, which is a list of `KeyboardEvent.key` values `resolveShortcut`
dispatches on; `app.palette` deliberately carries none, because ⌘K is intercepted ahead
of the registry (see the dead-action note above) and registering `k` there would
collide with `ticket.prev`. The spec's mock draws the hint. Showing it needs a display
string that is not also a dispatch key — a second field, or a shortcut type that
separates the two.

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

**Clearing a due date only takes effect on refetch.** `PatchInput` in
`apps/web/src/lib/queries.ts` never listed the date field, and ticket patches reach it
through `ActionContext.patchTicket`'s `Record<string, unknown>`, so the field is
unchecked end to end. The optimistic `setQueryData` spread applies `due` but not
`unset: ["due"]`, so the cleared date reappears until the server answers. This predates
the timeline work — it behaved identically when the field was `dueDate` — and it will
be more visible once bars can be dragged.

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
