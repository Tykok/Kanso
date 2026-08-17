# Following the ownership rule through to its edges

The previous branch gave Kanso its first per-team authorization rule and stopped at
the ticket. Its final review found the edges it had not reached, and five decisions
followed. This is the addendum that records them, because four of the five change what
the product promises and one of them changes the rule itself.

Read alongside `2026-08-11-scoped-timeline-and-overlap-warnings-design.md`, which this
amends rather than replaces.

## What ships

- The open-team clause becomes an open-**chain** clause.
- `POST /api/projects/{id}/unarchive` requires a configurator, like every other
  disposition.
- `TicketService.create` consults the same rule as every other ticket mutation, and the
  composer stops offering teams that would refuse it.
- The project dialog says what leaving the team blank actually means.
- Kanso's read posture — everything is readable, only writes are scoped — is written
  down, and the one screen that contradicts it stops contradicting it.

## What does not

**Per-team read scoping.** Decided against, deliberately. Every `GET` in this instance
already answers for any team, and the cross-team timeline this project just built rests
on that. Scoping reads would make the context rows contradictory, would leave the
critical path traversing tickets the reader may not see, and would need an answer for
what a hidden bar looks like. It is a branch of its own, and probably a different
product.

**Individual ownership of a ticket.** Considered as a way to let a creator delete what
they just filed on someone else's board, and refused: nothing else in this model belongs
to a person. Everything belongs to a team, and inventing one exception is how a model
grows a second, quieter one.

**Relaxing the project/team invariant.** A project owned by a team still refuses another
team's tickets. Making it accept them would touch `ProjectService.update`'s coherence
rule and the ticket/project invariant both, and it deserves its own spec.

---

## 1. An open team becomes an open chain

The rule shipped last branch opened a team to everyone when **that team** had no members.
It now opens a team to everyone only when **that team and every one of its ancestors**
have no members.

`TicketAccess.mayEdit(actor, ticket)` is true when any of these holds:

1. The actor is `owner` or `admin`.
2. The actor is a member of the ticket's team **or of any of its ancestors**. Unchanged.
3. No team in the chain — the ticket's own team and every ancestor above it — has a
   single member.

### Why the old clause was wrong

It was justified as a migration guarantee: every instance running today has an empty
`team_members`, so without it the deploy locks every board behind a 403. That reasoning
is sound and still holds. What was wrong was calling it a *migration* state.

`TeamService.create` does not enrol its creator, and nothing else does either. So the
empty state is not a condition instances leave behind — it is the state every team is
born into and stays in until somebody remembers. The clause never decayed. It was an
unbounded door described in three documents as a one-time one.

### What the chain rule buys

A sub-team created under a populated parent is governed from the instant it exists. That
is the common case — teams are created inside an organisation that already has one — and
it closes the window without anyone having to remember anything.

A **root** team with no members is still open to everyone, and that is deliberate: it is
the migration case, and it is also the honest answer to "nobody has claimed this work
yet". You create the team, then you invite the people. Between those two acts the board
is open, and the members screen says so in as many words.

### The walk stops at nobody

Rule 2 already grants a member of any ancestor, not merely the nearest. That does not
change: a member of *Product* reaches *Product / Mobile / iOS* whether or not *Mobile*
has members of its own. The chain in rule 3 is only about deciding whether *anybody* has
claimed the work, and the answer is no only when the whole chain is silent.

### Cost, named

Rule 3 needs the membership of every team in the chain rather than of one team, and
`editableTeams` answers for up to `SCOPE_LIMIT` tickets at once. Done naively that is a
`members()` call per ancestor per team. So `TeamRepository` gains
`teamsWithMembers(ids): Set<UUID>` — one `SELECT DISTINCT team_id FROM team_members
WHERE team_id IN (…)` — and both entry points resolve their chains against a single
prefetch. The rule stays one function; only the lookup it consults changes shape.

---

## 2. Unarchiving a project is a disposition like the others

`ProjectService.unarchive(id)` takes no actor and checks nothing, so
`POST /api/projects/{id}/unarchive` answers any authenticated user, and `actions.ts`
offers the palette entry to anyone in a project scope.

The sharp edge is not the restoration, it is the asymmetry the last branch created: a
member can pull an archived project and every ticket in it back onto everyone's boards,
and then cannot put it back, because `archive` is now gated. A door that only opens.

It takes the same guard as `archive` and `delete`, matching `TeamService.unarchive`,
which has required a configurator all along.

---

## 3. Creating a ticket asks the same question as changing one

`TicketService.create` takes no actor. Since `delete` became gated, that leaves the
model's one write-once corner: anybody may file a ticket on any team's board, and then
nobody but that team or an admin can remove it.

`create` now consults `TicketAccess` on the **destination team**, exactly as `patch`
already does for its `teamId` side. One rule, every mutation, finally true.

### The composer has to know

Gating `create` without changing the composer would offer a team select whose options
end in a 403. The client cannot compute the answer — that was the whole point of putting
`editable` on the server — so `TeamResponse` gains `editable: Boolean`, from the same
`TicketAccess.editableTeams`, and the composer offers only the teams that will accept
the ticket.

This is the same shape as `TimelineTicket.editable` and for the same reason: the client
obeys an answer it is given, and never derives one.

---

## 4. A project with no team is a shared project, and the dialog should say so

The cross-team context rows the timeline now draws come from two places: the dependency
chain, which works everywhere, and a shared project — which, because a team-owned
project refuses another team's tickets, means a project with **no** team.

~~Nothing in the interface says that. The project dialog offers an optional team field and
leaves the reader to infer what leaving it blank does. The feature is reachable only by
someone who already knows the rule, which is the same as not shipping it.~~

**Struck during the follow-through pass — this claim is false.**
`apps/web/src/components/dialogs/project-dialog.tsx:132` has carried
`hint="No team makes the project transverse: it shows in the root Projects section and
any team's tickets may point at it."` since commit `6c14a90`, 7 August — five days before
this spec was written. The interface already says it. The error was describing the
dialog without opening it, the exact defect class this addendum exists to correct in the
code; it turns out the spec itself was not exempt.

~~One sentence under the team field, in the register the dialogs already use. No rule
changes.~~

**What this section's real content is:** nothing ships here. The existing hint is
adequate, and a second sentence beside it would not close a gap, it would duplicate one.
The question this section meant to ask survives the correction, though: not whether the
dialog *states* the rule — it does — but whether a person discovers a hint under a field
they had no reason to open. That is a product question about discoverability, not a
copywriting one, and no sentence placed in a dialog answers it. It stays open, correctly
this time.

---

## 5. Reads are open; writes are scoped. Say it, and stop contradicting it

No `GET` in Kanso is scoped by team. `/api/tickets`, `/api/projects`, `/api/timeline`
and `/api/teams/{id}/members` all answer any authenticated user about any team, and this
project's own cross-team timeline depends on that.

So the posture is: **everything is readable, only writes are scoped.** That is now
written into the architecture as a decision rather than left as an accident.

One screen contradicts it. `MembersSection` is hidden entirely from a plain member,
while the endpoint behind it answers them perfectly well. Hiding a roster that the API
hands over on request buys no privacy and costs a member the ability to answer "who is
in this team, and therefore who can move this bar" — a question the timeline now makes
them ask.

A plain member sees the roster, read-only: no add control, no remove control. Owner and
admin keep the editing surface they have.

---

## Tests

**Kotlin.** The chain rule, at each shape that distinguishes it from the old one: an
empty child under a populated parent is closed to strangers and open to the parent's
members; an empty child under an empty parent under a populated grandparent resolves to
the grandparent; a fully empty chain is open to everyone; a populated team is unaffected.
`teamsWithMembers` returns exactly the teams holding at least one row. `create` refuses a
stranger and names the team. `unarchive` refuses a member and admits an admin.
`TeamResponse.editable` is true and false in one response for a non-admin actor.

**Vitest.** `project.unarchive` is absent from the palette without `canConfigure`.

**Playwright.** Extend scenario 3, which already runs two identities: a member sees the
roster and no controls on it; an admin sees the controls.

---

## What the next reader should know

Rule 3's chain walk is the second version of this clause. The first was written as a
migration guarantee and was in fact a permanent, unbounded opening, described correctly
nowhere. If a third version is ever needed, the question to ask first is not "what should
the rule be" but "how does a team get into this state, and how does it leave" — which is
the question nobody asked the first time.
