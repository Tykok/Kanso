# Creating teams, projects and tickets

Everything below already exists on the server. `teams.parent_team_id` is a
self-referencing foreign key with cycle detection, `TeamController` and
`ProjectController` expose full CRUD, and `TicketCreateRequest` accepts a status, a
priority, dates, a project and assignees. The web client calls almost none of it: it
can create a ticket with a title, and nothing else.

This spec closes that gap. It is the first of three — mouse parity across every
action comes next, web notifications after that.

## What ships

- Create, rename, reparent and archive teams and sub-teams.
- Delete a team or a project, after deciding explicitly what happens to what it holds.
- Create, edit and archive projects, with or without a team.
- Create tickets with their team, project, priority and assignee chosen up front.
- A sidebar that shows the team tree with its projects, and filters on click.
- One action registry that the keyboard handler, the command palette, the menus and
  the buttons all read from.

## What does not

Mouse paths for actions that already have a keyboard path — status changes, archive,
rename in place. Those are the second spec, which audits this registry for actions
with no mouse affordance and wires them. This spec ships mouse paths only for what it
introduces.

---

## Server

Four changes. No migration: the schema already says what we need it to — the work is
in deciding what the existing foreign keys are allowed to do on their own.

### Team writes are an admin action

`TeamService.create`, `update`, `delete`, `addMember` and `removeMember` require
`actor.instanceRole.canConfigureInstance`, following `AccountService.setInstanceRole`.
Projects and tickets stay open to every member — the shape of the organisation is an
admin decision, the daily work is not.

`/api/me` already carries `instanceRole`, so the client hides what it may not do. The
403 is the backstop, not the mechanism.

### Archiving a team walks the tree

**Invariant: an unarchived team never has an archived ancestor.**

Archiving a team archives its whole subtree; unarchiving one unarchives its ancestors.
A team left visible under a hidden parent is unreachable in the sidebar and confusing
everywhere else, and the asymmetric alternative — archive cascades, unarchive does not
— makes restoring a subtree a manual walk.

One transaction, one `sync_jobs` row per team touched, one realtime event per team.

The cascade stops at teams. A team's projects and tickets keep their own `archived`
flag: archiving a team is an organisational statement, not a claim that the work
inside it is finished. Its tickets stay in *All tickets* and reachable by identifier;
only the team and its sub-teams leave the sidebar.

Archive is the light action, reachable from a menu in one click. Delete is a separate,
heavier one — below.

### Deleting a team asks what happens to its contents

`tickets.team_id` is `ON DELETE CASCADE` (`V2__sync_engine.sql:80`) and
`teams.parent_team_id` is `ON DELETE SET NULL` (`V1__init.sql:9`). Left as they are,
deleting a team destroys every ticket under it in Postgres while Notion merely
archives the page — the mirror outlives the source of truth — and silently promotes
its sub-teams to the root. Neither is a decision anyone made.

So `DELETE /api/teams/{id}` takes a body saying, per category, what to do:

```jsonc
{
  "subTeams": "delete" | "reparent",   // reparent → the grandparent, or root
  "projects": "delete" | "reparent",   // reparent → the parent team, or no team
  "tickets":  "delete" | "move",
  "ticketsTargetTeamId": "…"           // required when tickets = "move"
}
```

Server-side the whole thing is one transaction, and the counts are recomputed inside
it, so contents created while the modal was open are handled by the chosen plan
instead of falling through to whatever the foreign keys do.

The request also carries the counts the modal displayed. If the recount disagrees,
the server changes nothing and returns `409` with the new figures; the modal reopens
with them and the name has to be retyped. Recounting keeps the operation coherent,
but it cannot keep a person's consent honest — someone who agreed to destroy 47
tickets did not agree to destroy 50. Everywhere else in Kanso an optimistic write
that turns out wrong simply snaps back; here it does not come back at all, which is
what buys the extra round trip.

**Tickets are the constrained case.** `tickets.team_id` is `NOT NULL`, so a ticket has
no team-less state to fall back to the way a project does. Two situations:

- Tickets of **sub-teams that are kept** move with their sub-team. Nothing happens to
  them at all — no renumbering, identifiers unchanged. This is the common case.
- Tickets of **the deleted team itself** need a destination team, chosen in the modal.
  Moving them renumbers them: `UNIQUE (team_id, number)` (`V2:103`) and
  `teams.ticket_counter` mean `KAN-42` becomes `GRW-17`. That identifier is what
  people paste into Slack and commits and what is written into Notion, so the modal
  says so in as many words, with the count.

Renumbering draws from the destination team's `ticket_counter` with the same
`UPDATE … RETURNING` used at creation, so a concurrent creation in the destination
team cannot collide with the move.

Sub-teams are reparented to the grandparent (root when there is none) before the row
goes, rather than relying on `ON DELETE SET NULL` — the difference matters when the
grandparent exists, which is exactly when the cascade's answer is wrong. Projects go
to the parent team, or to the team-less section when there is none.

Deleting anything is admin-only, like every other team write.

### A ticket's project must belong to its team

`TicketPatchRequest` accepts a `teamId`, so a ticket created in Core against a Core
project can be moved to Growth and keep pointing at a project no view of its team
shows. The composer's project list cannot prevent this — it only bounds creation.

`TicketService.patch` therefore clears `project_id` when the new team is not the
project's team and the project is not team-less. Not a database constraint: making it
one would also forbid the team-less projects this spec deliberately allows.

### Nothing else

`parentTeamId`, `projectId` on tickets, `assigneeIds`, `includeArchived`,
`GET /api/tickets?projectId=` — all present, none called.

---

## Action registry

`page.tsx` is 403 lines and defines every action twice: once in a `switch` over
`event.key`, once in the `commands` array for the palette. This spec adds around ten
actions. Duplicating them again is how a menu ends up missing something the keyboard
has.

```ts
type Action = {
  id: string;                    // "team.archive"
  label: string;
  shortcut?: string;             // resolved against event.key
  group: "ticket" | "team" | "project" | "view" | "app";
  when: (ctx: ActionContext) => boolean;
  run: (ctx: ActionContext) => void;
};
```

`ActionContext` carries the current scope, the selected ticket, the loaded teams and
projects, the mutations, and `canConfigure`.

Four consumers, one list:

| Consumer | Reads |
|---|---|
| keyboard handler | `shortcut` → action, then `when` |
| command palette | every action where `when` is true |
| context menus and buttons | actions by `id` |
| help overlay | every action with a `shortcut` |

The help overlay stops being a hand-written table that drifts from the handler.

`when` returning false means the action is absent from menus and inert from the
keyboard — the same predicate answers "may I show this" and "may I run this".

---

## Sidebar

```
┌─ Kanso ────────┐
│ Views           │
│  All tickets    │
│ Teams        +  │
│  ▾ Core    KAN  │
│    • Refonte    │   project of Core
│    ▾ Mobile MOB │   sub-team
│       • iOS     │
│  ▸ Growth  GRW  │
│ Projets      +  │
│  • Migration DB │   no team
│  • Audit 2026   │
│ ─────────────── │
│  Show archived  │
└─────────────────┘
```

Projects belonging to a team are nested under it. Projects with no team live in a
root-level `Projets` section. Every project appears exactly once.

`+` on the Teams header creates a root team (admin only). Hovering a team row reveals
a `⋯` menu: *New project*, *New sub-team*, *Rename*, *Archive*, *Delete*. Hovering a
project row reveals *Edit*, *Archive* and *Delete*. `+` on the Projets header creates
a project with no team.

*Delete* is last in the menu, separated, and never the default. It opens the modal
below; *Archive* does not.

Depth stays capped at two indents, as today (`sidebar.tsx:20`).

`Show archived` at the foot toggles `includeArchived` on both queries. Archived rows
render dimmed with an *Unarchive* action.

### Selection

`store/ui.ts` replaces `teamId?: string` with:

```ts
scope: { kind: "all" } | { kind: "team"; id: string } | { kind: "project"; id: string }
```

That scope is the tickets query key. A team scope keeps `includeDescendants=true`, so
a parent shows the work of its sub-teams. A project scope sends `projectId`.

### One projects query

`api.projects()` is called once without a `teamId` and grouped client-side. The
sidebar needs every project to draw the tree; keeping a query per team would mean N
requests to render it.

---

## Ticket composer

```
┌────────────────────────────────┐
│ Corriger le login OAuth        │
├────────────────────────────────┤
│ [Core ▾][Aucun ▾][— ▾][moi ▾] │
│ ↵ créer   esc annuler          │
└────────────────────────────────┘
```

One title field, Enter creates — the speed that made it worth building. Below it,
selectors for team, project, priority and assignee, prefilled from the current scope,
clickable and reachable with Tab.

This fixes a real defect. Today `page.tsx:126` falls back to `teams.data[0]` when the
view is "All tickets", so a ticket silently lands in whichever team sorted first. The
context bar makes the target visible; when no team can be resolved, the team selector
opens and creation waits rather than guessing.

**A ticket always belongs to a team; a project does not.** From a team-less project,
`c` opens the composer with the team selector unresolved and required. It is the only
path where the context bar blocks.

The project selector lists the chosen team's projects **plus** every team-less
project. No SQL constraint ties `tickets.project_id` to `tickets.team_id` and this
spec does not add one — it only bounds what the UI offers.

---

## Dialogs

Both reuse the existing `Backdrop` from `overlays.tsx`.

**Team** — name, key (optional; `TeamService.resolveKey` derives it from the name
otherwise), parent team. Reparenting is the same dialog.

**Project** — name, status, lead, start and end dates, team. Clearing the team is how
a project becomes transverse; the `PUT` simply omits `teamId`.

Server errors land on the field that caused them: `409 Team key 'KAN' is already
taken` under the key, `409 Moving team … would create a cycle` under the parent.

**Delete** — one modal, one choice per category, nothing preselected as destructive:

```
┌─ Supprimer « Core » ─────────────────────────┐
│ Cette équipe contient :                      │
│                                              │
│  2 sous-équipes    (•) Rattacher à la racine │
│                    ( ) Supprimer             │
│                                              │
│  3 projets         (•) Rattacher à la racine │
│                    ( ) Supprimer             │
│                                              │
│  47 tickets        (•) Déplacer vers [Growth ▾]
│                    ( ) Supprimer             │
│                                              │
│  ⚠ Les 47 tickets seront renumérotés :       │
│    KAN-1…KAN-47 deviennent GRW-…             │
│    Les liens existants cesseront de résoudre.│
│                                              │
│  Tapez « Core » pour confirmer : [________]  │
│                     [Annuler]  [Supprimer]   │
└──────────────────────────────────────────────┘
```

Every option defaults to keeping. The name has to be retyped — the modal deletes
records that took months to accumulate, and it is the only place in Kanso that does.
The counts come from the server, and it recounts before acting.

An empty team gets a plain confirmation: there is nothing to decide.

Deleting a **project** is the same modal with one row — its tickets, kept (they only
lose their `project_id`) or deleted.

---

## Errors

No toast system — there is none today and it would be one more mechanism to maintain.

- Inside a dialog: a message under the offending field.
- Outside a dialog (archiving from a menu): a dismissible line in the topbar.
- Optimistic mutations (status, priority): unchanged — the row visibly snaps back,
  which is the honest signal that the change did not land.

---

## Tests

### Kotlin — JUnit + Testcontainers, as today

- A member gets 403 on every team write; an admin succeeds.
- Archiving a team archives its whole subtree.
- Unarchiving a nested team unarchives its ancestors.
- After a sequence of archive/unarchive operations, no unarchived team has an
  archived ancestor.
- Archiving a team enqueues one sync job per team touched.
- Deleting a team with `subTeams: "reparent"` moves them to the grandparent, not to
  the root, when a grandparent exists.
- Deleting a team with `projects: "reparent"` sends them to the parent team, and to
  no team when the deleted team was a root.
- Deleting a team with `tickets: "move"` renumbers from the destination team's
  counter, leaves no gap and no collision, and a concurrent creation in the
  destination team gets a distinct number.
- Tickets of a kept sub-team keep their identifier untouched.
- `tickets: "move"` without `ticketsTargetTeamId` is a 400, and the team still exists
  afterwards.
- A ticket created after the counts were read makes the delete return 409 and change
  nothing; replaying with the new counts succeeds.
- Patching a ticket's team clears a `project_id` pointing at another team's project,
  and leaves a team-less project alone.

### Vitest — new in `apps/web`

The registry only. Pure logic, no rendering:

- A key resolves to exactly one action.
- `when` predicates: a member sees no team actions; a ticket action is absent with no
  selection; a project action is absent outside a project scope.
- The help overlay is generated from the actions carrying a `shortcut`.

### Playwright — new, at the repo root

Against the real `docker compose` stack in `KANSO_AUTH_MODE=dev`, where identity comes
from the `X-Kanso-User` header. No OAuth to simulate, and two users are playable in
one test, which the permission scenarios need — and the realtime and notification
specs will need after that.

1. Create a team → a sub-team → a project inside it → a team-less project → a ticket,
   and find each one in the sidebar.
2. Selecting a parent team shows its sub-teams' tickets; selecting a project filters
   to it.
3. A member sees neither the Teams `+` nor a team `⋯` menu; an admin sees both.
4. Delete a team keeping everything: the sub-teams, projects and tickets are all still
   reachable afterwards, at their new place, and the renumbered identifiers are the
   ones the modal announced.
5. **Keyboard non-regression:** `j/k`, `1..6`, `c`, `e`, `x`, `/`, `⌘K`, `,`, `?` do
   exactly what they do today.

Scenario 5 is the point. The registry rewrites the keyboard path; this test is what
says whether behaviour moved with it.

---

## Files

```
apps/web/src/
  lib/actions.ts                        +  registry, types, key resolution
  lib/use-action-ctx.ts                 +  assembles data, mutations, permissions
  lib/api.ts                            ~  projectId filter, project/team writes
  lib/queries.ts                        ~  scope-keyed tickets, projects once
  store/ui.ts                           ~  scope replaces teamId; dialog state
  app/page.tsx                          ~  composition only
  components/sidebar.tsx                ~  tree, Projets section, row menus
  components/composer.tsx               +  moved out of overlays.tsx, context bar
  components/dialogs/team-dialog.tsx    +
  components/dialogs/project-dialog.tsx +
  components/dialogs/delete-dialog.tsx  +  per-category choices, typed confirmation
apps/api/src/main/kotlin/dev/kanso/
  service/TeamService.kt                ~  role guard, recursive archive, delete plan
  service/TicketService.kt              ~  move between teams, project coherence
  repo/TeamRepository.kt                ~  subtree archive, ancestor walk, counts
  api/Dtos.kt                           ~  delete-plan request
e2e/                                    +  Playwright
```

---

## Deferred

Kept here so they are not rediscovered as surprises:

- **N8N webhooks** on domain events (ticket created, team created, deadline reached),
  so anyone can wire their own integration.
- **Notion pages linked into Kanso**: pull pages, render them against tickets, teams
  and projects, edit them in a limited editor, sync back. While a page is being edited
  in Kanso, either lock it in Notion or warn that concurrent edits will be overwritten.
- **Google account linking**, then GitHub — repositories and issues alongside Kanso
  tickets.
