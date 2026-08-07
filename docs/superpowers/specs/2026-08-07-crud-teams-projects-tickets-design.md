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

Three changes. No migration: the schema already says what we need it to.

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

`DELETE /api/teams/{id}` stays in the API and no menu points at it. `tickets.team_id`
is `ON DELETE CASCADE` (`V2__sync_engine.sql:80`): deleting a team destroys its
tickets in Postgres while Notion merely archives the page, so the mirror outlives the
source of truth. Archive is what the UI offers.

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
a `⋯` menu: *New project*, *New sub-team*, *Rename*, *Archive*. Hovering a project
row reveals *Edit* and *Archive*. `+` on the Projets header creates a project with no
team.

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
4. **Keyboard non-regression:** `j/k`, `1..6`, `c`, `e`, `x`, `/`, `⌘K`, `,`, `?` do
   exactly what they do today.

Scenario 4 is the point. The registry rewrites the keyboard path; this test is what
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
apps/api/src/main/kotlin/dev/kanso/
  service/TeamService.kt                ~  role guard, recursive archive
  repo/TeamRepository.kt                ~  subtree archive, ancestor walk
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
