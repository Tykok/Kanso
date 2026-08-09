# Creating teams, projects and tickets

Everything below already exists on the server. `teams.parent_team_id` is a
self-referencing foreign key with cycle detection, `TeamController` and
`ProjectController` expose full CRUD, and `TicketCreateRequest` accepts a status, a
priority, dates, a project and assignees. The web client calls almost none of it: it
can create a ticket with a title, and nothing else.

This spec closes that gap. It is the first of three — mouse parity across every
action comes next, web notifications after that.

## What ships

- Create, rename and reparent teams and sub-teams.
- Archive or delete a team or a project, after deciding explicitly what happens to
  what it holds.
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

This means a team's `⋯` menu is not itself an admin-only surface: it is a surface
whose items are filtered per action. Creating a project inside a team is a project
action, not a team action, so it stays in that menu for every member even though the
other four items — sub-team, rename, archive, delete — require `canConfigure`.

### Archiving and deleting take the same plan

Removing a team from view raises the same question whichever way it is removed: what
happens to what it holds? So both actions take one shape — a **disposition plan** —
and differ only in what they do with it and in what they ask of the person first.

`tickets.team_id` is `ON DELETE CASCADE` (`V2__sync_engine.sql:80`) and
`teams.parent_team_id` is `ON DELETE SET NULL` (`V1__init.sql:9`). Left to themselves,
deleting a team destroys every ticket under it in Postgres while Notion merely
archives the page — the mirror outlives the source of truth — and silently promotes
its sub-teams to the root. Neither is a decision anyone made. The plan is what takes
those decisions back from the foreign keys.

```jsonc
{
  "subTeams": "take" | "keep",   // keep → reparented to the grandparent, or root
  "projects": "take" | "keep",   // keep → to the parent team, or no team
  "tickets":  "take" | "keep",
  "ticketsTargetTeamId": "…",    // required when tickets = "keep"
  "counts": { "subTeams": 2, "projects": 3, "tickets": 47 }
}
```

`take` means *goes with the team* — archived alongside it, or deleted with it. `keep`
means *stays active*, which always implies re-homing, since what is kept cannot hang
under something that is gone. Every category defaults to `keep`.

`PUT /api/teams/{id}` carries the plan when `archived` turns true;
`DELETE /api/teams/{id}` carries it always. One transaction either way, one `sync_jobs`
row and one realtime event per entity touched.

**Invariant: an unarchived team never has an archived ancestor.** `keep` upholds it by
reparenting the sub-team out; `take` upholds it by archiving the subtree. Unarchiving
a team unarchives its ancestors, so a subtree archived together can be restored from
any point in it rather than walked by hand.

#### The two severities

Both open the same modal with the same categories. They part on what confirms them:

| | Archive | Delete |
|---|---|---|
| Reversible | yes | no |
| Confirmation | one button | retype the team name |
| Counts changed under the modal | proceeds | `409`, nothing happens |

The `409` exists because recounting keeps the *operation* coherent but cannot keep a
person's *consent* honest: someone who agreed to destroy 47 tickets did not agree to
destroy 50. Everywhere else in Kanso an optimistic write that turns out wrong simply
snaps back; here it does not come back at all, which is what buys the extra round
trip. Archiving snaps back, so it does not pay it.

Counts are recomputed inside the transaction in both cases, so contents created while
the modal was open are handled by the chosen plan rather than falling through to
whatever the foreign keys do.

#### Tickets are the constrained case

`tickets.team_id` is `NOT NULL`, so a ticket has no team-less state to fall back to
the way a project does. Two situations:

- Tickets of **sub-teams that are kept** go with their sub-team. Nothing happens to
  them at all — no renumbering, identifiers unchanged. This is the common case.
- Tickets of **the team itself, kept active**, need a destination team, chosen in the
  modal. Moving them renumbers them: `UNIQUE (team_id, number)` (`V2:103`) and
  `teams.ticket_counter` mean `KAN-42` becomes `GRW-17`. That identifier is what people
  paste into Slack and commits and what is written into Notion, so the modal says so
  in as many words, with the count — for archiving as much as for deleting, because
  the renumbering is permanent either way.

Renumbering takes the whole block at once —
`UPDATE teams SET ticket_counter = ticket_counter + n … RETURNING` — and hands out
`(new − n + 1 … new)`. Allocating one at a time would hold the same row lock for the
same span while adding a round trip per ticket inside it, so every `c` pressed in the
destination team would wait longer for no benefit.

Sub-teams are reparented to the grandparent (root when there is none) explicitly,
rather than relying on `ON DELETE SET NULL` — the difference shows exactly when a
grandparent exists, which is when the cascade's answer is wrong. Projects go to the
parent team, or to the team-less section when there is none.

Both actions are admin-only, like every other team write.

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
a `⋯` menu: *New project in this team*, open to every member, and — admin only —
*New sub-team*, *Rename*, *Archive*, *Delete*. A member's menu on that row is that
one item alone; an admin's is all five. Hovering a project row reveals *Edit*,
*Archive* and *Delete*. `+` on the Projets header creates a project with no team.

*Delete* is last in the menu, separated, and never the default. Both it and *Archive*
open the disposition modal below, at their own severity.

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

**Disposition** — one component, one choice per category, driven by a `severity` prop.
Archiving and deleting ask the same questions; only the last two rows differ.

```
┌─ Archiver « Core » ──────────────────────────┐   ┌─ Supprimer « Core » ─────────────────────────┐
│ Cette équipe contient :                      │   │ Cette équipe contient :                      │
│                                              │   │                                              │
│  2 sous-équipes    (•) Garder actives        │   │  2 sous-équipes    (•) Garder actives        │
│                    ( ) Archiver avec         │   │                    ( ) Supprimer avec        │
│                                              │   │                                              │
│  3 projets         (•) Garder actifs         │   │  3 projets         (•) Garder actifs         │
│                    ( ) Archiver avec         │   │                    ( ) Supprimer avec        │
│                                              │   │                                              │
│  47 tickets        (•) Déplacer vers [Growth▾]   │  47 tickets        (•) Déplacer vers [Growth▾]
│                    ( ) Archiver avec         │   │                    ( ) Supprimer avec        │
│                                              │   │                                              │
│  ⚠ Les 47 tickets seront renumérotés :       │   │  ⚠ Les 47 tickets seront renumérotés :       │
│    KAN-1…KAN-47 deviennent GRW-…             │   │    KAN-1…KAN-47 deviennent GRW-…             │
│    Les liens existants cesseront de résoudre.│   │    Les liens existants cesseront de résoudre.│
│                                              │   │                                              │
│  Réversible depuis « Afficher les archivées »│   │  Tapez « Core » pour confirmer : [________]  │
│                     [Annuler]  [Archiver]    │   │                     [Annuler]  [Supprimer]   │
└──────────────────────────────────────────────┘   └──────────────────────────────────────────────┘
```

Every category defaults to keeping, in both severities. The renumbering warning shows
in both, because moving a ticket renames it for good whether the team it left was
archived or deleted.

Only the destructive side asks for the name to be retyped, and only it fails on a
count that drifted. Making archiving pay the same price would teach people to type
names without reading them, which is precisely what would make the delete modal stop
working.

An entity with nothing under it gets a plain confirmation: there is nothing to decide.

A **project** uses the same component with one row — its tickets, kept (they only lose
their `project_id`) or taken along.

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
- The plan is exercised against both verbs, since both take it:
  - `subTeams: "take"` archives (resp. deletes) the whole subtree.
  - `subTeams: "keep"` moves them to the grandparent, not to the root, when a
    grandparent exists.
  - `projects: "keep"` sends them to the parent team, and to no team when the team
    was a root.
  - `tickets: "keep"` renumbers from the destination team's counter, leaves no gap and
    no collision, and a concurrent creation in the destination team gets a distinct
    number.
  - Tickets of a kept sub-team keep their identifier untouched.
  - `tickets: "keep"` without `ticketsTargetTeamId` is a 400, and the team is
    untouched afterwards.
- Unarchiving a nested team unarchives its ancestors.
- After a sequence of archive/unarchive operations under any plan, no unarchived team
  has an archived ancestor.
- One sync job per entity touched, whichever plan ran.
- A ticket created after the counts were read makes the **delete** return 409 and
  change nothing; replaying with the new counts succeeds. The same drift lets the
  **archive** through — the severity difference is a tested behaviour, not a UI
  detail.
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
3. A member sees no Teams `+`, but does see a team row's `⋯`; its menu holds only
   *New project in this team*. An admin sees the `+` and a `⋯` whose menu holds all
   five items: *New project in this team*, *New sub-team*, *Rename*, *Archive*,
   *Delete*. The Playwright test asserts both item lists exactly, not just presence
   of the trigger, so a management action leaking back into a member's menu fails
   the suite.
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
  components/dialogs/disposition-dialog.tsx + per-category plan, severity prop
apps/api/src/main/kotlin/dev/kanso/
  service/TeamService.kt                ~  role guard, recursive archive, delete plan
  service/TicketService.kt              ~  move between teams, project coherence
  repo/TeamRepository.kt                ~  subtree archive, ancestor walk, counts
  api/Dtos.kt                           ~  delete-plan request
e2e/                                    +  Playwright
```

---

## Next, and deferred

**Its own spec, straight after this one — entity locks.** While someone holds the
disposition modal open on a team, nobody else should be editing or removing that same
team. A database row lock cannot express this: it dies with its transaction, and no
transaction stays open across a person's think-time. It needs an application lock —
holder, expiry, heartbeat, release on close, an answer for the tab that was closed
mid-modal, and a realtime broadcast so other clients grey the entity out rather than
discovering the conflict on submit.

The lock covers the targeted entity only, never its contents: changing a ticket's
status while its project is being deleted stays allowed. That is also why the count
`409` above is not made redundant by locking — the contents keep moving by design.

It earns a spec of its own because it is the same mechanism the Notion page lock below
needs. Written once, it serves both. Until it exists, the `409` is the only guard, and
a second person can fill in the modal before failing at the last step.

Deferred, kept here so they are not rediscovered as surprises:

- **N8N webhooks** on domain events (ticket created, team created, deadline reached),
  so anyone can wire their own integration.
- **Notion pages linked into Kanso**: pull pages, render them against tickets, teams
  and projects, edit them in a limited editor, sync back. While a page is being edited
  in Kanso, either lock it in Notion or warn that concurrent edits will be overwritten.
- **Google account linking**, then GitHub — repositories and issues alongside Kanso
  tickets.
