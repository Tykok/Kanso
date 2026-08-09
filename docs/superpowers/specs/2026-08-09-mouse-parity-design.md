# Every action reachable with a mouse

Kanso is keyboard-first and stays that way. But keyboard-first has been read, so far,
as keyboard-only: a person who reaches for the mouse cannot change a ticket's status
from the list, cannot rename one at all, cannot create a team or a project without
finding the right row in the sidebar first, and cannot discover who they are signed in
as.

This is the second of three specs. The first gave teams, projects and tickets their
create, edit, archive and delete paths, and built the action registry every surface
reads from. This one finishes the sentence: **every action in that registry has a mouse
path, and the ones people reach for most are one click from where they are already
looking.**

## What ships

- A `New` menu in the top bar: ticket, project, team — filtered by what you may do.
- The sidebar's brand block becomes the account and instance menu.
- Ticket rows become actionable: the status pill and the priority mark open menus, and
  a `⋯` appears on hover for rename, archive and delete.
- One rule for what a new thing inherits from where you created it.
- A version that comes from the build rather than from a constant nobody updates.

## What does not

The logo image itself. The file provided is black ink; under the dark theme it
disappears. The brand block becomes the menu trigger with the text it already carries,
and swapping in the image is a one-line change once a light variant exists. Shipping a
logo that vanishes for half the users is worse than shipping none.

---

## The registry gains two actions, and no surface invents its own

`ticket.delete` exists only inside the detail panel today; `app.logout` exists nowhere.
Both become registry actions, so the menus below list them the same way they list
everything else, and `when` keeps answering both "may I show this" and "may I run this".

Nothing in this spec adds a code path that decides on its own whether to show
something. Every new surface filters the registry.

## The `New` menu

`page.tsx`'s top bar has a `New c` button that opens the composer. It becomes a `Menu`
trigger — the component task 12 built and task 17 exercised — listing **Ticket** (`c`),
**Project** and **Team**.

The items are registry actions, so a member sees no *Team* without a second rule
written anywhere. The sidebar's own `+` buttons stay: one is contextual, one is global,
and having both is the point rather than a duplication to resolve.

## The brand block becomes a menu

`sidebar.tsx` renders `Kanso 簡素` inside an inert `<div>`. It becomes a `<button>`
opening a menu:

```
┌──────────────────────┐
│ Élie Treport         │
│ elie@…  · owner      │
├──────────────────────┤
│ Settings           , │
│ Keyboard shortcuts ? │
│ Command palette   ⌘K │
├──────────────────────┤
│ Sign out             │
├──────────────────────┤
│ v0.3.1 · a1078bf     │
└──────────────────────┘
```

The identity header answers a question nothing in the interface answers today outside
dev mode. *Sign out* is separated because it is the one entry that ends the session.
The version line is not a menu item — it is not focusable and does not respond to a
click.

The Notion sync summary stays in the sidebar foot where it is. It is passive and always
visible; moving it into a menu would make it less useful, not more.

## Ticket rows become actionable

`tickets.tsx` exposes exactly two gestures: click to select, double-click to open. Three
additions:

| Surface | Opens | Why there |
|---|---|---|
| `StatusPill` | the six statuses | it is what you are already looking at when you decide to change it |
| `PriorityMark` | the five priorities | same |
| `⋯` on hover | rename, archive/unarchive, delete | less frequent, and destructive in one case |

Status and priority are the two most frequent actions in the application, and putting
them behind a shared `⋯` would cost three gestures where two will do. Everything else
lives in the menu.

Each row's menus receive a context re-scoped to that row — `{...ctx, selected: ticket}`
— the same shape the sidebar already uses for its own rows, whose correctness the task
13 review verified against the "menu on team B acts on team A" failure. Reusing the
shape rather than inventing a second one is what keeps that verification meaningful.

## What a new thing inherits from where it was created

One pure function, tested directly, in the shape of `composer-seed.ts` — which the
previous branch extracted for exactly this reason.

| Scope | Ticket | Project | Team |
|---|---|---|---|
| All tickets | team must be chosen | no team | root |
| Team A | team A | team A | sub-team of A |
| Project X, owned by team A | project X, team A | team A | sub-team of A |
| Project X, no team | **blocked**, team must be chosen | no team | root |

Two properties hold across the whole table, and they are what separate this from the
defect the previous branch removed:

- **Every inferred value is pre-filled and editable**, never imposed. `teams.data[0]`
  was wrong because it was invisible; these are on screen before anything is submitted.
- **A ticket always belongs to a team, a project does not.** From a team-less project
  there is nothing to infer, so creation blocks and the team selector takes focus. It
  is the only cell in the table that refuses.

The team column reads one level up from wherever you are: from a project, it resolves
the project's team and offers it as the parent, the same way the composer already
resolves a project's team for a ticket.

## The version comes from the build

`apps/web/package.json` says `0.1.0` and has never moved. A version that only changes
when someone remembers is worse than none, because people quote it in bug reports.

- **Web**: the short commit is injected as a build argument. `docker-compose.yml`
  already passes `NEXT_PUBLIC_API_URL` that way — this is the same mechanism, not a new
  one.
- **API**: `springBoot { buildInfo() }` generates the metadata, and the version travels
  on `/api/me`, which the client already fetches on every load. Opening `/actuator/info`
  would mean changing `SecurityConfig`, which today permits `/actuator/health` alone.

The menu prints one line when the two agree and two when they diverge. A web/API skew is
precisely what you want to see written down when reading a bug report.

## Errors

Unchanged from the first spec, and the reason to restate it is that this one adds
surfaces outside dialogs. Actions fired from a menu report through the top bar's error
line, which already exists and already clears itself when the scope changes. Nothing
here adds a second error mechanism.

## Tests

**Vitest** — the seeding function against all twelve cells of the table above; the two
new registry actions; and the `New` menu's filtering by role.

**Playwright** — creating each of the three from each scope and asserting the resulting
link **on the server**, not merely on screen; the brand menu (identity, settings, sign
out); changing a status and a priority through the pills; renaming, archiving and
deleting through a row's `⋯`.

One rule carried over, and it is not a formality. Three features on the previous branch
survived their own removal before anyone noticed — the coherence rule, the mirror
assertions, and the archive half of the disposition fix. **Every test here must fail
when the thing it covers is removed, and the plan states the mutation to apply for each
one.** Verifying that is part of the work, not a virtue to be trusted.

---

## Deferred

- The logo image, pending a variant that survives the dark theme.
- Everything in `docs/follow-ups.md`, carried out of the previous branch.
- N8N webhooks, the Notion page mirror, Google and GitHub linking — unchanged from the
  first spec's list.
