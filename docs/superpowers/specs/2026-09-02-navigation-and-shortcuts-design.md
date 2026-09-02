# One column, one selection, one keyboard

Three complaints, all about the same thing:

> Quand je clique sur "Trash" elle disparaît. Quand j'entre dans certaines Views je ne
> peux pas en sortir. […] je peux avoir le focus sur All Tickets MAIS AUSSI sur les
> autres onglets, c'est trop bizarre.

The sidebar is not one component with a bug in it. It is one component rendered by
**three different shells**, none of which can see the other two, plus four routes that
render no shell at all. And it draws its selection from **two independent sources** that
never consult each other. Both facts are documented in the code, by the code, as
deliberate: the shells were frozen for a six-branch fan-out, and each branch was told
not to touch the others' chrome.

The fan-out has landed. This is the integration pass those comments kept pointing at.

While the column is open, four more things get built in the same pass, because each of
them is a change to that same shell: a hover-reveal mode, a notification bell, a personal
view, and one keyboard instead of six.

## What is actually broken

**Three shells.** `app/page.tsx` builds the chrome inline for the list, the board and the
chart. `components/organise/shell.tsx` (`OrganiseShell`) rebuilds it for `/cycles`,
`/triage`, `/views`, `/workload`. `components/views/shell.tsx` (`ViewsShell`) rebuilds it
again for `/t/[key]` and `/p/[id]`. Each repeats the 248px grid, the
`preferences.sidebarVisible` test, the mobile drawer, the auth gates, and — in two of the
three — the overlay and dialog mounting. `OrganiseShell` says so in its own docstring:

> Promoting it to an `app/(app)/layout.tsx` would mean `page.tsx` adopting it, which is
> the edit the fan-out forbids — worth doing in the integration pass, not from a branch
> that cannot see the other five.

**Four routes with no shell at all.** `/trash`, `/settings`, `/docs` and `/docs/[id]`
draw their own page-width layout with a `Back` link. That is the whole of "quand je clique
sur Trash elle disparaît": nothing disappeared, the destination never had a sidebar. The
`Back` link is also why some pages feel like a trap — it is a `<Link href="/">`, so it
does not go back, it goes home, and on `/docs/[id]` there is not even that.

**Two selection axes.** The sidebar's "Views" group computes `current` from `pathname`.
Its "All tickets" row and every team and project row compute `current` from
`useUi().scope`, a zustand value a page load wipes. Neither knows about the other, so:

- `/docs` lights up **Documents** (route) *and* **All tickets** (scope is still `all`).
- Clicking a project from `/cycles/current` sets a scope nothing on screen is drawing,
  and the reader stays on the cycle wondering what their click did.
- Reloading `/cycles/current` silently resolves a *different* team, which is why
  `useOrganiseTeam` grew a `?team=` escape hatch — a workaround for exactly this split.

**Six keyboards.** `app/page.tsx`, `app/inbox/page.tsx`, `saved-view.tsx`,
`triage-view.tsx`, `views/shell.tsx` and `timeline/arrows.tsx` each attach their own
`window.addEventListener("keydown")`. "Next row" is written out by hand three times.
`⇧e`, `⌘k`, `Escape`, `F` and the four triage rulings are registry actions in none of
them, so `?` cannot list them and nothing can ever remap them.

## What already stands, and is reused whole

The interesting half is written. This spec adds less than it looks like.

- **The action registry.** `lib/actions/` is eight files composing one `ACTIONS` list,
  with `permits` as the single gate (seat, then `when`), `resolveShortcut(key, mode)`,
  `hintOf` for display, and `shortcutRows` generating the help overlay. `indexActions`
  throws at module load on a duplicate id or two actions claiming one key. None of that
  is replaced; bindings move out of it, everything else stays.
- **`tickets.completed_at` exists** in the schema, and has since `V1`. It is not on the
  wire, which is the only reason "what did I finish" looks like it needs a migration.
- **`/api/me/velocity?teamId=`** already answers "my pace, measured over closed cycles",
  with `VelocityService`'s three refusals intact (per working day, closed cycles only,
  computed on read).
- **`/api/tickets` already takes `assigneeIds`**, and `/api/tickets/filters` already
  answers the facet catalogue an autocomplete needs.
- **The `activity` table** records every status change with its actor and timestamp,
  under a closed `ActivityKind` guarded by a CHECK.
- **`ViewFilters`** is a settled twelve-facet type, with `chipsOf` rendering it,
  `facets.ts` offering it and `filterParams` putting it on the wire. The filter *language*
  below is a new spelling of that same type, not a new filter model.
- **Charts are `<span>`s over pure geometry.** `burndown.ts` and `timeline-geometry.ts`
  compute percentages in tested modules and let the markup be dumb. Every chart here does
  the same. No chart library enters the bundle.
- **`radix-ui` and `lucide-react`** are already dependencies. The popover and the
  `PanelLeft` icon cost nothing new.

## What this is not

- **A redesign of the screens.** Every screen keeps its content. What changes is the
  frame around it, which row is lit, and which key reaches it.
- **A new permission model.** Everything added reads or writes only the caller's own
  data. `/api/me/stats` answers about you, like `/api/me/velocity` does.
- **A second filter model.** The token language compiles to `ViewFilters` and nothing
  else. What cannot be expressed as `ViewFilters` is not in the language.
- **A rewrite of the action registry.** Ids, labels, groups, `when`, `writes`, `permits`
  and the palette are untouched.
- **Notion-mirror work.** Nothing here changes what is pushed or polled.

---

# 1. One shell

A Next route group, `app/(app)/`, with one `layout.tsx` rendering `<AppShell>`. Route
groups do not appear in URLs, so every path stays exactly what it is today.

Inside the group: `/`, `/me`, `/inbox`, `/trash`, `/settings`, `/docs`, `/docs/[id]`,
`/cycles/[number]`, `/triage`, `/views`, `/views/[id]`, `/workload`, `/p/[id]`,
`/t/[key]`.

Outside it, deliberately shell-less: `/login`, `/setup` (there is nothing to navigate to
yet), `/about`, `/roadmap`, `/roadmap/[key]` (a shop window for people with no session),
`/design-system` (a contact sheet, not a destination).

`OrganiseShell` and `ViewsShell` are deleted. Their two remaining jobs — `useOrganiseTeam`
and the `aside` rail — survive as, respectively, a hook that stays where it is and a slot
on the shell.

Six files, none of them large:

| file | job |
|---|---|
| `components/shell/app-shell.tsx` | the grid, the two auth gates, the error strip |
| `components/shell/sidebar-frame.tsx` | pinned / hover / hidden, and the `PanelLeft` button |
| `components/shell/topbar.tsx` | mobile `☰`, breadcrumb, the `×`, the bell, the slot |
| `components/shell/topbar-slot.tsx` | how a page puts its own controls in that bar |
| `components/shell/overlays.tsx` | palette, help, settings, composer, detail, six dialogs |
| `components/shell/use-shell-keys.ts` | the app's one `keydown` |

**The slot.** A layout cannot take props from the page it wraps. `AppShell` renders an
empty `<div>` in the top bar and publishes its node through a context; `<TopbarSlot>`
portals its children into that node. A page that wants the List/Board/Timeline segmented
control, the filter input or a `New` menu renders `<TopbarSlot>` and it lands in the
shell's bar. About thirty lines, and it is what stops the app from growing a second bar
under the first.

**Why not keep one shell per page.** Because what broke is exactly what three copies
cannot keep in step. The sidebar is already one component; what was duplicated was the
*frame*, and every fix to a frame had to be made three times or be wrong twice.

**The `×`.** `Escape` leaves a destination. For the reader who does not use the keyboard,
`topbar.tsx` draws a `×` at the right end of the bar on every route that is *somewhere you
went* — anything but `/` — and it runs the same `app.back` action the key does. One
implementation, one behaviour, two ways in. It replaces the four hand-rolled `Back` links,
which pointed home rather than back.

**Breadcrumb.** Derived once, in `lib/nav.ts`, from the route and the scope: `Trash`,
`Documents`, `Core / Cycle 24`, `Core / Workload`. Not "Tickets / Documents" — `/docs` is
not a child of the ticket list, and a crumb claiming a parent the reader cannot climb to
is worse than no crumb.

# 2. One selection axis

One function, `lib/nav.ts`:

```ts
export type NavSelection =
  | { kind: "view"; id: string }      // a route: my-view, cycle, triage, …
  | { kind: "scope"; scope: Scope };  // the list, showing all / a team / a project

export function currentSelection(pathname: string, scope: Scope): NavSelection
```

and one rule, which is the whole fix:

- **`pathname !== "/"`** — the selection is the view whose route matches. No team, no
  project, no "All tickets", whatever the scope happens to hold.
- **`pathname === "/"`** — the selection is the scope: all tickets, or one team, or one
  project.

Two rows can no longer be lit, because there is now exactly one answer to "what is
selected" and every row asks the same function for it. `lib/nav.test.ts` asserts that over
a matrix of every route × every scope shape: exactly one match, always.

**Clicking a team or a project** calls `setScope` **and** `router.push("/…")`. The
sidebar's one job is to take you somewhere; a row that changes state without moving is the
click the maintainer called "pas fou".

**Scope stays in the store.** Sixty-five files read `useUi().scope`; converting them all to
read the URL is a different task with a different risk profile, and it is not what is
broken. Instead `AppShell` mirrors the scope into the query string on `/`
(`/?team=<id>`, `/?project=<id>`) and hydrates the store from it on mount — two effects in
one file. That buys back the two things the missing URL cost us: a reload keeps the scope,
and a pasted link says what it shows, which is the same property `?team=` was bolted onto
`useOrganiseTeam` to get.

**View links carry the team forward.** `navHref(item, scope)` puts `?team=<id>` on the
cycle, triage, workload and docs links while a team is selected, so going from a team's
list to that team's cycle keeps the subject. `useOrganiseTeam` already reads it, and its
four-step fallback is unchanged.

**`NAV_ITEMS`** loses `inbox` (it becomes the bell, §4) and gains `me` at the top (§5).
`live` stays: it is how a route that is still a placeholder stays out of the column.

# 3. Three sidebar modes

`preferences.sidebarVisible: boolean` becomes `preferences.sidebarMode`, one of:

- **`pinned`** — the 248px grid column, exactly today's behaviour, and the default.
- **`hover`** — the column collapses to nothing; a 12px hot zone runs down the left edge;
  entering it slides the sidebar in *over* the content as a fixed overlay with a shadow,
  and leaving it slides it out. It also opens on `focus-within`, so Tab reaches it, and
  closes on `Escape`.
- **`hidden`** — no column and no hot zone. The only way in is the `PanelLeft` button in
  the top bar, which opens that same temporary overlay. There is deliberately still a way
  in: a mode with no way back is the bug this spec exists to remove.

**The icon.** `PanelLeft` from lucide, in the sidebar header beside the seal, collapses
pinned → hover. The same icon in the top bar, shown whenever the column is not pinned,
pins it back. It toggles those two and no more. `hidden` is reachable from Appearance and
not from a click, because a control whose third state you discover by pressing it twice is
the kind of thing this pass exists to remove.

**Why a preference and not local state.** The other nine preferences are server-side and
travel with the session in `/api/me`. A tenth kept in `localStorage` would be the one
setting that does not follow you, and the first paint would have to guess.

Appearance's `Toggle` becomes a three-way `Segmented`. `lib/theme.ts`'s bootstrap script
parses the new value so the first paint does not flash a column it is about to collapse.
`setup/preferences-step.tsx` and `setup/preview.tsx` follow.

# 4. The bell

Inbox leaves the sidebar and becomes a bell in the top bar, on every route.

`components/inbox/bell.tsx` — the `Bell` icon with an unread pip fed by `useUnreadCount`,
which already reads the `all` tab's cache entry and so costs no second request. A Radix
`Popover`, about 380px, listing the most recent notifications with `InboxRow`
**verbatim**: same row, same actions, same optimistic mark-read. Its header carries
`Mark all read` (the `Shift+e` action, §6). Its footer carries `⤢`, which navigates to
`/inbox` — the existing full screen with its four tabs, unchanged.

The popover is a peek and the page is the place to work. Nothing is implemented twice,
because the row and the queries are shared.

# 5. My view — `/me`

A personal home, first row of the sidebar's Views group, with tabs like the data sources
of a Notion database. Tabs live in `?tab=`, never in the path, so §2's single-selection
rule needs no special case and every tab is a link somebody can paste.

A four-number strip sits above the tabs on every tab — **open / overdue / blocked /
finished this week** — and each number is the tab that explains it. A zero overdue prints
in faint ink, never in `--urgent`: `inbox/tabs.tsx` already argues that a red zero is an
alarm about nothing.

| tab | shows | source |
|---|---|---|
| **Assigned** | my open tickets across every team, grouped by status | `/api/tickets` + `assigneeIds=[me]` |
| **Due** | mine with a date: overdue, this week, later | same query, `lib/row-metrics.ts` |
| **Blocked** | mine whose predecessor is unfinished | `ticket_dependencies` |
| **Done** | what I finished: bars per week over twelve weeks, the total, and the unsized share | `tickets.completed_at`, aggregated server-side |
| **Velocity** | points per working day per closed cycle, per team, declared vs measured | `/api/me/velocity`, `lib/velocity.ts` |

Two numbers ride along where they are read rather than getting a tab of their own:

- **The cycle commitment**, at the head of *Done*: my committed points against my finished
  points in the active cycle. It is the only figure that can be read *during* a cycle —
  velocity, by construction, can only be read after one.
- **Estimate hygiene**, at the head of *Velocity*: how many of my open tickets carry no
  estimate. That is precisely what makes a measured velocity understate, so it belongs
  beside it and nowhere else.

**Server.** One endpoint, `GET /api/me/stats`, from `MyStatsController` +
`MyStatsService`: the four strip counts, twelve weekly buckets of completed work, the
active cycle's commitment, the unestimated count, and the recently-finished tickets (id,
identifier, title, `completedAt`, estimate). No migration — every column exists.

`completedAt` is **not** added to the shared ticket DTO. One screen needs it, one response
carries it. Widening the row every list in the app already fetches, to serve one chart, is
how a DTO becomes a junk drawer.

Weeks are ISO weeks computed server-side, because a bucket boundary decided in the browser
and a count decided in Postgres will disagree twice a year.

**Front.** `components/me/`: `me-view.tsx` (the tabs and the strip), one file per tab, and
`done-bars.ts` for the bar geometry — pure, unit-tested, in the tradition `burndown.ts`
set. A bar that comes back wrong is invisible in a screenshot and obvious in a number.

# 6. One keyboard

## 6.1 Chords, in one field

`Action.shortcut?: string` and `Action.hint?: string` collapse into
`Action.defaultKeys?: readonly string[]`, whose members are chords:

```
"n"  "Enter"  "?"  "Shift+e"  "Shift+ArrowDown"  "Mod+f"  "Mod+k"
```

`Mod` renders as `⌘` or `Ctrl+` per platform at display time, as `hintOf` already does.
Shift is written as a prefix rather than smuggled into a letter's case — `"Shift+e"`, not
`"E"` — so a chord can be *parsed*, and a capture UI can *produce* one.

This retires three problems at once:

- `⌘K` stops being intercepted ahead of the registry in two files and becomes a binding
  like any other.
- `⇧↑↓` becomes expressible, which is the reason `saved-view.tsx` had to keep its own
  handler ("the registry has no modifier state to read").
- There is no longer a printed field and a dispatched field that can disagree about what
  a key does.

## 6.2 One dispatcher

`use-shell-keys.ts` is the app's only `keydown` listener. The six existing handlers are
deleted and their keys become registry actions:

| new action | key | was |
|---|---|---|
| `app.back` | `Escape` | hand-rolled in three files, `Back` links in four |
| `inbox.markAllRead` | `Shift+e` | hardcoded in `app/inbox/page.tsx` |
| `triage.accept` / `.reject` / `.duplicate` / `.defer` | as today | hardcoded in `triage-view.tsx` |
| `organise.select` | `x` | a `hint` that dispatched nothing; now real |
| `organise.selectRange` | `Shift+ArrowUp` / `Shift+ArrowDown` | hardcoded in `saved-view.tsx` |
| `view.cycleDrawing` | `Mod+v` | no key at all |

One exception keeps its own listener: `timeline/arrows.tsx`. It listens during a mouse
drag in order to cancel it, which is not a shortcut — it is a gesture's escape hatch,
alive only while the pointer is down.

Order inside the dispatcher, unchanged in spirit from what `page.tsx` does today: a dialog
or overlay swallows everything but its own `Escape`; a text field swallows every bare key;
then the registry resolves, `permits` gates, and the action runs.

## 6.3 Bindings as data

New `lib/shortcuts.ts`, pure and tested:

- `DEFAULT_BINDINGS` — derived from the registry, so the defaults have one source.
- `mergeBindings(overrides)` → `{ keys, index, rejected }`. A user binding that collides
  with another action in the same mode is **refused, with its reason**, not thrown:
  `indexActions` may crash the app at module load over a developer's typo, but user data
  that bricks the interface is unacceptable. `rejected` is what the settings page shows,
  naming the action that holds the key.
- `resolveShortcut(chord, mode, index)`.
- `formatChord(chord, isMac)` — the one place a key becomes text, for the help overlay,
  the menus and the settings table.

Unknown action ids in the stored overrides are ignored on read: an action deleted in a
later version must not make a stored preference unreadable.

## 6.4 The defaults, simplified

One intention, one key, in every mode.

| key | does | before |
|---|---|---|
| `n` / `↓` | next — row, card **and** bar | `j` `↓`, written out three times |
| `p` / `↑` | previous | `k` `↑` |
| `Mod+f` | filter… | `F` |
| `Mod+g` | group by… | `g` |
| `Mod+o` | order (sort) by… | `f` |
| `Mod+v` | next drawing: list → board → timeline | nothing |
| `Mod+k` | command palette | intercepted outside the registry |
| `Shift+p` | choose a priority, through the palette | nothing — palette-only |
| `Shift+e` | mark everything read (inbox) | hardcoded |
| `Escape` | close what is open, then leave | hardcoded ×3 |

`j` and `k` are **dropped**, on the maintainer's ruling. The arrows stay as the second
spelling of those two actions, so nothing becomes unreachable.

`timeline.schedule` **loses its key** (`p`) and keeps its palette entry and its row menu —
also on the maintainer's ruling. It is the one action in the registry that gets quieter
here.

Unchanged: `c`, `Enter`, `e`, `x`, `s`, `1`–`6`, `/`, `,`, `?`, `u`, `d` / `Shift+d`,
`[` `]` `t`, `h` `l` `Shift+h` `Shift+l` on the chart, and `h` `l` `←` `→` on the board.

Two chords deliberately shadow the browser, and both are written down here so they are not
discovered later:

- **`Mod+f`** takes over find-in-page. Notion, Linear and Slack all do this; it is the
  expected behaviour for an app whose own search is better than the browser's. Bare keys
  are inert inside text fields, so find-in-page stays reachable from any input.
- **`Mod+v`** shadows paste over the list, where paste did nothing. Inside every input,
  textarea and document, paste is untouched by the same guard. This is the only chord in
  the set that overlays a system reflex; it is one line in the defaults table and one
  click in Settings if it proves wrong in use.

## 6.5 The settings page

A seventh section in `/settings`: **Shortcuts**.
`components/settings/shortcuts-section.tsx`, plus `shortcut-capture.tsx` for the one
interactive part.

A table grouped by `ActionGroup` — ticket, team, project, view, app — with a column for the
mode (list / board / timeline / everywhere), and a search box over labels and keys. Each
row: the label, its current chords, a button that enters capture ("press a combination",
`Escape` cancels), and a reset to default. One `Reset everything` above the table.

Capture rules:

- A chord already held in the same mode is refused, naming its holder. Never a silent
  steal — the registry throws over exactly this ambiguity today, and the UI must be as
  strict without being fatal.
- `Escape` and `Tab` are not assignable. They are the two keys by which a reader escapes a
  capture that went wrong.
- A bare printable key is allowed, because bare keys are already inert in text fields.

The `?` overlay reads the **effective** bindings, so it can never describe a keyboard the
reader does not have.

## 6.6 The view controls, on screen

The three chords above are also three buttons — **Filter**, **Group**, **Order** — at the
left of the top bar's slot, for the reader who would rather click. A tenth preference,
`showViewControls`, defaults on and hides them for the reader who would not. The bell, the
breadcrumb and the `×` are not optional; these are.

# 7. The filter language

Filtering becomes a query somebody types, GitHub- and Slack-style, with completion:

```
status:todo,in_progress assignee:@me -status:done estimate:none
```

It compiles to `ViewFilters` and to nothing else. The twelve facets map like this:

| token | `ViewFilters` |
|---|---|
| `status:todo,in_progress` | `status` |
| `-status:done` | `statusNot` |
| `priority:urgent,high` | `priority` |
| `project:onboarding` | `project` (name → id) |
| `assignee:@me`, `assignee:tykok` | `assignee` |
| `assignee:none` | `unassigned` |
| `cycle:24`, `cycle:current` | `cycle` |
| `label:bug` | `label` |
| `open:>7d` | `openedForDays` |
| `estimate:none` | `unestimated` |
| `estimate:3..8` | `estimateMin` / `estimateMax` |

`lib/filter-query.ts`, three pure functions and a test file:

- `parse(text, catalog)` → `{ filters, errors }`. Errors are per token and carry their
  span, so the input can underline the bad word rather than refuse the whole line. An
  unparseable tail never discards the filters that did parse.
- `format(filters, names)` → text. `format(parse(t)) === t` for canonical input is a
  property test, because the input and the chips both have to be able to be the source of
  truth without drifting.
- `suggest(text, caret, catalog)` → keys before the colon, values after it, values scoped
  to their key. `↑↓` moves, `↵` and `Tab` accept. The catalogue is `/api/tickets/filters`,
  which already answers exactly this question and is already cached.

**One truth.** The text is what the reader edits; `ViewFilters` is what the query sends;
the chip strip stays as a *rendering* of `ViewFilters`, and removing a chip rewrites the
text. One direction each, three pure functions, no round trip that can disagree with
itself. `filter-composer.tsx` (240 lines of dialog) is replaced by the input and its
completion list; `chips.ts`, `facets.ts` and `filterParams` are untouched.

# 8. Data

One migration, additive to one table.

**`V27__sidebar_mode_and_shortcuts.sql`**

```sql
alter table user_preferences
  add column sidebar_mode text not null default 'pinned',
  add column shortcuts jsonb not null default '{}';

update user_preferences set sidebar_mode = 'hidden' where sidebar_visible = false;

alter table user_preferences
  add constraint user_preferences_sidebar_mode_chk
    check (sidebar_mode in ('pinned', 'hover', 'hidden'));

alter table user_preferences drop column sidebar_visible;
```

The CHECK is the two-sided guard `user_preferences` already uses for its other enums: a
Kotlin enum so a typo in a service is a compile error, a constraint so a row written by
anything else is refused.

`shortcuts` holds **overrides only** — never a full copy of the defaults. A stored copy
would freeze today's key set into every existing account, and a default improved later
would reach nobody. `{}` is the honest representation of "I never changed anything".

**Validation is split, and the split is deliberate.** The API validates *shape*: an object
of string keys to arrays of short strings, with a cap on entries and on chord length. It
cannot validate *meaning*, because the action registry is a front-end module and the
server has no way to know whether `ticket.rename` exists. The client validates meaning,
and `mergeBindings` ignores what it does not recognise. Writing this down here is cheaper
than rediscovering it the first time an id is renamed.

`showViewControls` (§6.6) rides in the same migration as a boolean column, since it is the
same table and the same round trip.

Touched on the API side: `Model.kt` (`Preferences`), `PreferencesService`,
`PreferencesRepository`, `PreferencesController`, `Tables.kt`, `Mappers.kt`,
`PreferencesTest.kt`. Plus `MyStatsController` / `MyStatsService` / `MyStatsTest` for §5,
which add no columns at all.

# 9. Testing

**Unit, front.**

- `lib/nav.test.ts` — the matrix: every route × every scope shape yields exactly one
  selection. Breadcrumbs for each route. `navHref` carrying the team.
- `lib/shortcuts.test.ts` — merge, conflict refusal with its reason, unknown ids ignored,
  chord parsing and formatting on both platforms, `Mod` expansion.
- `lib/filter-query.test.ts` — every facet's token, negation, the round-trip property,
  per-token errors with spans, completion at a caret in the middle of a line.
- `components/me/done-bars.test.ts` — bar geometry, empty weeks, the tallest-week scale.
- `lib/actions/index.test.ts`, extended — every action either carries `defaultKeys` or is
  classified as palette-only, and no two defaults collide within one mode.

**Unit, API.** `PreferencesTest` for the new columns, their defaults, the migration of
`sidebar_visible = false`, and the shape validation of `shortcuts`. `MyStatsTest` for the
weekly buckets (including the week nothing closed), the commitment figure, and the
assignee split of a shared ticket.

**End to end.** `e2e/23-navigation.spec.ts`, new: click every sidebar row in turn and
assert the column is still there and `[data-current=true]` matches exactly one row; the
`×` and `Escape` both leave; hover mode reveals and retracts; the bell opens and `⤢` lands
on `/inbox`; a remapped key works and the help overlay agrees with it.

Existing specs that name this navigation and will need reworking: `16-mobile-nav`,
`17-views`, `18-documents`, `19-organising`, `20-inbox`, `21-trash`, `keyboard`, `mouse`,
`14-menu-keyboard`. `shots.spec.ts` needs new baselines for the sidebar modes.

# 10. Slices

Ordered so each is worth shipping alone, and so the reported bugs die first.

1. **One shell, one selection.** The route group, `AppShell` and its six files, the three
   old shells deleted, `lib/nav.ts`, the `×`, the derived breadcrumb, scope mirrored into
   the URL. This is what fixes all three complaints.
2. **Sidebar modes.** `sidebarMode`, the migration, `PanelLeft`, the hover overlay,
   Appearance's segmented control, the setup step.
3. **The bell.** The popover, the pip, Inbox out of `NAV_ITEMS`.
4. **My view.** `/api/me/stats`, `/me` and its five tabs, the number strip,
   `done-bars.ts`.
5. **One keyboard.** Chords, `lib/shortcuts.ts`, the single dispatcher, the six handlers
   deleted, the simplified defaults, the help overlay reading effective bindings.
6. **Configurable keyboard, and the filter language.** The `shortcuts` column, the
   Shortcuts settings section and its capture, `showViewControls` and the three buttons,
   `lib/filter-query.ts` and the completion input.

Slice 6 carries two features because they are one surface seen twice: the top bar's three
controls, and the three chords that open them.

# 11. Stated risks

- **`Mod+v` shadows paste** over the list. Guarded by the typing check, so every field
  still pastes. It is the only chord here that overlays a system reflex; bindings are
  data, so changing it is one line and one click.
- **`Mod+f` shadows find-in-page**, as it does in Notion, Linear and Slack.
- **Nine e2e specs assert today's navigation.** Reworking them is not a rounding error on
  slice 1, and it is planned as part of that slice rather than after it.
- **Sixty-five files read `useUi().scope`.** This spec deliberately does not convert them
  to URL-derived state. If that conversion is ever wanted, §2's mirror is the seam where
  it would happen.
