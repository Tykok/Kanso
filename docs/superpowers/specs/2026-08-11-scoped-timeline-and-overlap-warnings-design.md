# Whose bar is this, and is the plan still true

The timeline can be dragged by anyone, anywhere, and it stays silent about the one
thing a dependency exists to say. Both are the same omission seen twice: the chart
draws a plan without saying who owns it or whether it holds.

This spec closes three gaps.

**Nobody owns a bar.** `TicketService` verifies nothing. Any authenticated person can
`PATCH` any ticket, link any two, and unlink any arrow. `team_members` has existed
since the first migration and is read by no service. Teams are a label on a row, not a
boundary.

**A broken dependency is invisible.** `TimelineService.violatedEdges` reports an edge
only when its successor is `done`, in deliberate agreement with `Cascade`. But
`Cascade.apply` skips any node none of whose predecessors are in its `dirty` set — so
dragging B leftwards under A never examines the edge A→B at all. The overlap persists,
nothing moves it, and nothing says so. This is the ordinary case, and it is silent.

**A bar does not say what state its work is in.** Colour on a bar means criticality
today. Status — the thing a person scanning a board is actually looking for — appears
nowhere on the chart.

## What ships

- `TicketAccess`: one authorization rule, consulted by every ticket mutation, backed
  by `team_members` and by team ancestry.
- A member-management surface in `TeamDialog`, owner/admin only, on the endpoints the
  API already exposes and the web client never called.
- The global timeline becomes read-only — mouse *and* keyboard.
- A team's or a project's timeline shows tickets that are not its own, drawn as
  read-only context: everything in the projects it shares, plus everything in its
  dependency closure.
- `TimelineEdge.overlap`: a successor that starts before its predecessor ends and is
  not done. An amber arrow and a ⚠ on the successor's row.
- A status pill on every ticket bar, in the colours the list view already uses.

## What does not

**Any change to `Cascade`.** Its `dirty` rule stays exactly as written. Repairing a
violation the request did not cause would move tickets nobody touched, in an edit
nobody made. The engine keeps declining; what changes is that the chart stops keeping
the result to itself.

**Dependency state in the list view.** `api.tickets` carries nothing about
dependencies, and adding it means another query on the busiest screen in the app. The
warning is a timeline fact in this version.

**A "repair this" action.** Warning and repairing are two features. The second raises
a question this one does not have to answer — who may push B when A and B belong to
two different teams — and it is additive afterwards.

**Per-project permissions.** The unit of ownership is the team. A project is already
constrained to hold tickets whose team may differ from its own, so a project-level
rule would be a second, contradictable answer to the same question.

**Refusing an overlap.** Deliberate overlap is a thing people plan. The requirement is
that the chart notices, not that it forbids.

---

## The decisions this rests on

### The owner of a ticket is its team, and membership is inherited downwards

`TicketAccess.mayEdit(actor, ticket)` is true when any of these holds:

1. The actor is `owner` or `admin` — the rule `TeamService.requireConfigurator`
   already encodes for team configuration, reused rather than re-spelled.
2. The ticket's team has no members at all.
3. The actor is a member of the ticket's team, **or of one of its ancestors**.

Rule 2 is the migration guarantee. Every instance running today has an empty
`team_members`; without it, deploying this spec locks every board in existence behind
a 403 and the only way out is a SQL prompt. An empty team is an unclaimed team.

Rule 3 runs down the tree, never up. A member of *Product* may move a ticket in
*Product / Mobile*; a member of *Mobile* may not move one in *Product*. This is the
same direction as `teams.descendantIds`, which already defines what a team's scope
contains. The other direction would be an escalation: joining the smallest team in the
instance would grant the largest.

A refusal is a 403 naming the team, never the UUID — `Ticket KAN-12 belongs to Mobile,
which you are not a member of`. `follow-ups.md` records the dialog that shows raw
UUIDs as a defect; this does not repeat it.

### Linking asks for the successor, not the predecessor

`ScheduleService.link(predecessorId, successorId)` requires the right to edit the
**successor**. Drawing an arrow from someone else's ticket to mine declares that *I
wait*, which commits nobody but me. The reverse — imposing a constraint on a ticket
that is not mine — is exactly the case the check is for. `unlink` takes the same rule:
erasing a constraint on my own ticket is my decision.

### The cascade does not ask who is driving

Moving my ticket pushes tickets in other teams downstream. It already does, and it
keeps doing it. Gating the cascade on membership would make a cross-team dependency
unenforceable — an arrow that only holds when both ends share a team is not a
dependency, it is a note. Permission governs the gesture; the graph governs the
consequences of the gesture.

This is the one place where a person can move work they do not own. It is bounded: a
ticket is only ever pushed *later*, only ever by a constraint someone accepted when
the arrow was drawn, and only ever by the amount of the overlap.

### `editable` is computed on the server

`TimelineTicket` carries `editable: Boolean`, answered by the same `TicketAccess` the
mutations call. The client does not derive it.

Deriving it in the browser would need the whole membership graph and the ancestor
walk, and would put a second implementation of the rule next to the first.
`follow-ups.md` already records what that costs, for `violatedEdges` against
`Cascade.violated`: *"they are two implementations of one fact"*. One is enough.

The UI still draws the affordance — a bar you may not move has no handles and no link
grip — so the 403 is a backstop, never the way a person finds out.

### Read-only means the keyboard too

In scope `all`, the chart is not editable. The eight chart actions that write —
`timeline.shiftEarlier`, `shiftLater`, `shrinkEnd`, `growEnd`, `schedule`,
`unschedule`, `link`, `unlink` (`h`, `l`, `H`, `L`, `p`, `u`, `d`, `D`) — test the
scope in their `when` predicate and stay inert, as does the tray's drop.

`timeline.zoomOut`, `zoomIn` and `today` (`[`, `]`, `t`) stay live. They move the
viewport, not the plan, and a read-only chart you cannot navigate would be a worse
answer than no chart.

A view that refuses the mouse and accepts the keyboard is not read-only; it is a trap
with a discoverability problem. And the topbar says why — *Read-only — open a team or
a project to plan* — because a feature that is indistinguishable from a bug is a bug.

### A context row is not selectable

Tickets from other teams are drawn, greyed, with the owning team's key in the name
cell. They cannot be selected.

`page.tsx` builds its cursor list from the *tickets* query, which is scoped, and a
context ticket is not in it. Selecting one would set `selectedId` and the
cursor-keeping effect would bounce straight back to `visible[0]` — the bug closed last
week under *"Clicking a bar can lose the selection it just made"*. Widening `visible`
to the timeline response is the other fix and it is the wrong one here: the cursor is
what keys act on, and no key acts on a foreign ticket.

The cost is stated rather than hidden: there is no detail panel for a ticket you do
not own. Its name, its dates and its status are in the bar's tooltip and accessible
name, which is what a context row is for.

### `violated` and `overlap` are two facts

`TimelineEdge.violated` keeps its current meaning: *the cascade cannot repair this* —
which happens exactly when the successor is `done`. `TimelineEdge.overlap` is new: the
successor starts before the predecessor ends and is **not** done.

Merging them into one red state would destroy the only distinction that tells a reader
what to do. "It is finished, too late" and "move B and it is fixed" are different
sentences.

This makes `TimelineService` report strictly more than `Cascade` does, and that
divergence is intentional and must survive review. The two answer different questions:
`Cascade.violated` is *what this request could not repair*; `TimelineService` is *what
is broken now*, including breakage that predates every request. `follow-ups.md`
currently notes the two as agreeing implementations of one rule — that note is
superseded and gets rewritten as part of this work, or the next reviewer will
"fix" the divergence.

### Status goes on a pill, criticality stays on the bar

Colour on the bar already carries `data-state` — normal, critical, late — with red
plus a hatch so the distinction survives greyscale and colour blindness. Status takes a
separate mark rather than a share of the fill.

---

## Data model

No migration. `team_members` already exists with the columns the rule needs, and
`TeamRepository.teamIdsFor(userId)` already returns the memberships of a user.

---

## The authorization rule — `TicketAccess`

A new service in `dev.kanso.service`, not a method on `TicketService`: it is called by
`ScheduleService` too, and a mutual dependency between the two would be the wrong way
to share three lines.

```kotlin
@Service
class TicketAccess(
    private val teams: TeamRepository,
) {
    /** Throws [AccessDeniedException] when [actor] may not edit [ticket]. */
    @Transactional(readOnly = true)
    fun require(actor: User, ticket: Ticket)

    @Transactional(readOnly = true)
    fun mayEdit(actor: User, ticket: Ticket): Boolean

    /** Batched: one query for a whole timeline response rather than one per row. */
    @Transactional(readOnly = true)
    fun editableTeams(actor: User, teamIds: Set<UUID>): Set<UUID>
}
```

`editableTeams` exists because `TimelineService` answers `editable` for up to
`SCOPE_LIMIT` tickets in one response. Asking per ticket would be two thousand
ancestor walks; asking per distinct team is at most a few dozen, and the ancestry walk
is the `WITH RECURSIVE` the repository already owns.

No new exception type and no new handler: `ApiExceptionHandler` already maps Spring's
`AccessDeniedException` to a 403 carrying its own message. `Errors.kt` is the house
pattern for service failures, but adding a fourth class there to reach a status the
handler already serves would be a second spelling of one answer.

### Call sites

| Call | Requires the right to edit |
|---|---|
| `TicketService.patch` | the ticket — **and the destination team, when the patch moves it** |
| `TicketService.delete` | the ticket |
| `ScheduleService.link` | the **successor** |
| `ScheduleService.unlink` | the **successor** |
| `ScheduleService.cascadeFrom` | nothing — see above |
| `NotionPoller` inbound writes | nothing — no actor |

**The two-sided check on `patch` is not optional.** `TicketPatch` carries `teamId`, so
a single-sided check lets anyone move a foreign ticket into a team of their own and
then edit it freely — the whole rule, defeated in two requests. Both ends are checked,
and the refusal names whichever side failed.

There is no `TicketService.archive`: archiving is `patch(archived = true)`, so it is
covered by the row above and needs no separate entry.

`TicketService.create` is deliberately absent. Creating a ticket in a team you are not
in is a separate policy question with a different answer for most instances, and
nothing in this spec depends on it.

The Notion poller has no acting user. Inbound scalar writes stay unchecked, which is
consistent with the mirror already being Kanso-authoritative for anything that matters
— and with the fact that reaching the mirror at all requires instance-level access.

---

## Members in `TeamDialog`

`api.ts` gains three calls against endpoints that already exist:
`GET|POST|DELETE /api/teams/{id}/members`. `TeamService.addMember` and `removeMember`
already call `requireConfigurator`, so the server side is done.

The section renders in `TeamDialog` when editing an existing team and the viewer is
owner or admin: the current members with their `MemberRole`, a picker over
`GET /api/people`, and a remove control per row. It is in the team's own dialog rather
than in settings because "who is in this team" is a property of the team, and a
settings page would be a second place to look for it.

On a new team the section is absent — there is no id to post against yet.

**Load-bearing:** without this surface the authorization rule is unusable. Rule 2 —
an empty team is open — is what keeps an instance working in the window before anyone
has been added, and it is what makes shipping the two in one branch safe rather than
merely convenient.

---

## The timeline response

### Scope resolution

`TimelineService.load(teamId, projectId)` resolves two sets.

**Own** — what the scope is about. Team scope: tickets whose `teamId` is in
`teams.descendantIds(teamId)`. Project scope: tickets in that project.

**Context** — read-only rows that give the own set its meaning:

- every ticket in a project that the own set has at least one ticket in, whatever team
  owns it;
- every ticket in the dependency closure of the own set.

The closure is already computed. `load` calls `dependencies.componentIds(scopeIds)`
and `tickets.findAllById(componentIds)` for the critical path, then discards
everything outside the scope before answering. Returning those rows marked as context
costs no new query and removes most of the `outOfScope` stubs — the arrows that today
point at something the view cannot name, and that `follow-ups.md` records as
unreachable from the keyboard for exactly that reason.

The project sweep is a new query: tickets whose `projectId` is in the own set's
distinct projects. It is the widening the reader asked for — *who else is working in
this project* — and it is the part of this design most able to surprise, because a
shared project pulls in a whole other team's work.

### Response shape

`TimelineTicket` gains:

| Field | Meaning |
|---|---|
| `teamKey` | whose ticket this is, printed in the name cell of a context row |
| `context: Boolean` | drawn for reading; not part of the scope, not selectable |
| `editable: Boolean` | `TicketAccess`'s answer; the client obeys, never re-derives |

`TimelineEdge` gains `overlap: Boolean` beside `violated`.

`TimelineView` gains `truncated: Boolean`.

### The cap becomes visible

`SCOPE_LIMIT = 2000` was safe while a scope was one team's subtree. Widening it to
shared projects can reach it. The cap stays — a Gantt is drawn, not paged — but the
response now says when it bit, and the chart prints a banner.

A Gantt missing bars without saying so is a plan that lies. That is strictly worse than
a message, and `follow-ups.md` already carries the same complaint about the reconciler
capping at 500 and only saying so in a log.

---

## The view

### Read-only, at three levels

**Scope `all`.** No drag, no resize, no link handle, no tray drop, and the six chart
keys inert. Topbar strip: *Read-only — open a team or a project to plan.*

**A context row.** No `drag`, no `link`, not selectable, `data-context` for the muted
fill, the owning team's key before the identifier in the name cell.

**A ticket in scope that is not yours.** Same treatment as a context row minus the
team-key prefix, because the scope already says which team is being looked at. Reached
whenever a shared project brings in another team's work.

All three fall out of props `TimelineBar` already understands. A bar with no `drag`
answers the pointer with nothing — that is what a project bar does today — so
read-only costs no new code path in the component, only the decision of when to pass
the prop.

### The overlap warning

The arrow takes `data-overlap` and an amber arrowhead, alongside the existing
`tl-arrowhead-violated` marker and the red one. Two markers become three.

The successor's name cell takes a ⚠. The sentence goes in `title` and in the bar's
accessible name: *starts before KAN-12 ends*, or *2 dependencies not respected* beyond
one. A glyph and not only a colour — the same discipline `data-state`'s hatch already
follows.

The badge is derived in the browser by grouping the response's edges by successor. No
new field, and no duplicated rule: it is a projection of data already in hand.

### The status pill

A `span.tl-status` **sibling** of the bar, centred on its left edge, half in and half
out, `z-index` above the bars. Sibling because `.tl-bar` has `overflow: hidden` and a
child would be clipped — the same reason `.tl-link` is a sibling on the right. Straddling
the edge rather than sitting beside it because a pill placed fully outside would eat
the gutter and collide with the neighbouring bar at month zoom.

The fill is `var(--status-backlog)` … `var(--status-canceled)`, the tokens `pills.tsx`
already uses. One definition, two views.

**The known cost.** At month zoom a column is three pixels, so a one-day ticket's bar
is entirely covered by its own pill and its criticality colour disappears. The red
outline of `data-state="late"` bleeds past the pill and saves the worst case; the
"critical but on time" case does not survive. Accepted, and recorded in
`follow-ups.md` rather than discovered later.

**A test consequence.** The pill is `aria-hidden` and the status joins the bar's
accessible name — `KAN-12: Title — In progress`. That name is the only handle the
Playwright suite has on a bar. **Every existing scenario that queries a bar by name
breaks and must be updated in the same commit.**

---

## The Notion mirror

Nothing to push. Membership already syncs through `TeamService.addMember`, which
enqueues a team upsert. Overlap and status are derived and displayed, not stored.

---

## Tests

**Kotlin, `TicketAccess`.** Owner and admin pass. A team with no members is open. A
member of an ancestor passes. A member of a descendant is refused on the ancestor's
ticket. A stranger is refused.

**Kotlin, services.** 403 from `patch`, `delete`, `link` and `unlink`, each naming the
team. A `patch` that moves a ticket the actor owns into a team they do not is refused
— the escape hatch, closed and covered. `link` succeeds when the actor owns the
successor and not the predecessor, and is refused in the mirror case. The cascade
still pushes tickets in teams the actor is not in.

**Kotlin, `TimelineService`.** An overlap on a non-done successor sets `overlap` and
not `violated`. A done successor keeps `violated` and does not set `overlap`. A
context ticket from a shared project appears with `editable = false`. A ticket in the
dependency closure appears and its edge is no longer `outOfScope`. `truncated` is set
at the cap.

**Vitest.** The `when` predicates in `actions.ts` return false for the eight writing
chart actions in scope `all` and true in team scope, and stay true for the three
viewport ones in both. This is where "read-only from the keyboard" is proved; nothing
else can prove it.

**Playwright.** One two-user scenario, which `KANSO_AUTH_MODE=dev` makes reachable
from a single browser via the `X-Kanso-User` header. A member of team A opens team A,
sees a bar owned by team B with no handles, drags one of their own bars into overlap,
and reads the ⚠ and the amber arrow.

Note from `e2e/README.md`, and it matters more here than anywhere: a non-default
`WEB_PORT` also needs `KANSO_WEB_ORIGIN`. Without it CORS makes every visitor render
as a member — which on a permissions scenario means green for the wrong reason.

---

## Shipping order

Two stages, one branch. The first has no dependency on the second and is mergeable
alone.

**Stage 1 — what the bar says.** The status pill, the `overlap` field, the amber
arrow, the ⚠ badge, the updated Playwright names. Pure read and render; no permission
model involved.

**Stage 2 — who may move it.** `TicketAccess` and its call sites, the members section,
`editable`/`context`/`teamKey`/`truncated`, the widened scope, read-only at scope
`all`.

They share one document because they share a visual vocabulary. *This bar is greyed
because it is not yours* and *this bar is in alert* must be distinguishable at a
glance, and deciding them in two specs is the surest way to end up with two greys.
