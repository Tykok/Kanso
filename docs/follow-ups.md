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
