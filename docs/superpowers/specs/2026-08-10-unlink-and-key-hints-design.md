# Erasing an arrow without a mouse, and a key a menu can print

Two holes the timeline branch left, both written down in `follow-ups.md`, both about the
registry being the single place that knows what the keyboard does.

An arrow can be drawn from the keyboard — `d` opens the palette on the candidates — and
erased only with a pointer: click its line, or tab onto it, then `Backspace`. Nothing in
the registry removes a dependency, so nothing lists the gesture, and the palette cannot
offer it.

Separately, no menu prints `⌘K` against *Command palette*. `Action.shortcut` is a list of
`KeyboardEvent.key` values `resolveShortcut` dispatches on, and `app.palette` carries
none on purpose: ⌘K is intercepted ahead of the registry, and registering `k` there would
collide with `ticket.moveUp`, which owns it. So the hint is drawn in `HelpOverlay` as a
hardcoded pair of
`<kbd>`s, and the two menus that read the registry print nothing at all.

## What ships

- `timeline.unlink`, on `D`, symmetrical with `d`: the palette lists the selected
  ticket's predecessors and `Enter` erases one.
- `Action.hint`, a display string that is not also a dispatch key, so `app.palette` can
  be printed by everything that reads the registry.
- `hintOf`, one function the three hint consumers converge on, which renders `Mod` as the
  key the reader actually has.

## What does not

**A key that walks the arrows.** `j`/`k` move the cursor over rows; there is no
equivalent stepping from arrow to arrow. `D` reaches a dependency through the ticket it
constrains, which is how `d` already reaches one, and that is enough to make erasing
reachable without a mouse. An arrow cursor is a second selection model — the store holds
`selectedId` for rows and the chart holds its own selected dependency — and it is not
needed to close the hole.

**Erasing an out-of-scope edge from the keyboard.** See the naming rule below: an edge
whose other end is absent from the response cannot be named, and the palette lists names.
Its stub stays clickable.

**A generated status bar.** It hardcodes `j` `k` `1`–`6` `c` as well; it is an editorial
strip, not a list. Making it read the registry means generating all of it, which is a
different change.

**Platform-aware dispatch.** Only the *label* becomes platform-aware. ⌘K and Ctrl+K are
both already answered, in `page.tsx`, by one `metaKey || ctrlKey` test that predates this.

---

## `timeline.unlink`

### The gesture

`D` — `event.key` for Shift+d, its own registry entry, which is the convention `H` and
`L` already follow: nothing in the registry carries modifier state. `D` is free in the
`timeline` bucket, and `indexActions` throws at module load if that is ever wrong.

It always opens the palette, including when there is exactly one predecessor. The
alternative — erase immediately when unambiguous, list when not — makes one key do two
things depending on the shape of the graph, and makes the fast path the destructive one.

### Where the knowledge of the graph comes from

`ActionContext` gains `dependencies: TimelineDependency[]`, filled in `use-action-ctx.ts`
by `useTimeline(view === "timeline")`. Same query key as the chart, so the chart's own
fetch is reused and no second request exists; disabled in the list view, so the list pays
nothing for an action it cannot run.

**A consequence worth stating:** this is the first action in the registry whose
availability depends on a fetch. While the timeline query is in flight `dependencies` is
empty, `when` is false, `D` is inert and the action is absent from the palette. That is
honest — with no edges loaded, nothing knows whether there is anything to erase — but it
is new, and it is why `when` counts edges rather than assuming.

### The naming rule, which is also the scope rule

A predecessor is listed if and only if it resolves in `ctx.tickets`.

That one rule gives the intended behaviour without a branch for it. A predecessor sitting
in the unscheduled tray *is* in the tickets query — it has no dates, not no row — so it
is nameable and listed. An `outOfScope` predecessor is, by definition, absent from the
timeline response, and being out of the current scope it is absent from the tickets query
too, so it resolves to nothing and drops out. Resolution is the filter.

The rejected alternative was listing unresolvable edges with the chart's own wording,
`a ticket outside this view`. Two such edges are then two identical rows with different
consequences, which is a worse offer than no offer. Fetching the missing tickets by id to
name them properly is a request per edge on palette open, and a loading state in an
overlay that has none.

### One function, two readers

```ts
// actions.ts
export function predecessorsOf(ctx: ActionContext, successorId: string): Ticket[]
```

`when` counts its length; `page.tsx` builds the palette from the same call. This is the
discipline `when`/`run` already share through `onSelected`: an inert key cannot open an
empty list, and a listed row cannot fail to resolve.

```ts
{
  id: "timeline.unlink",
  label: "Remove a dependency",
  shortcut: "D",
  mode: "timeline",
  group: "ticket",
  when: (ctx) =>
    onTimeline(ctx) && ctx.selected !== undefined &&
    predecessorsOf(ctx, ctx.selected.id).length > 0,
  run: onSelected((ctx, ticket) => ctx.startUnlink(ticket.id)),
}
```

`ActionContext.startUnlink(successorId: string)` sits beside `startLink`, and for the same
reason: the picker is the palette, the palette is rendered by the page, so the page owns
the pending state.

### The page's pending state

`linkFor?: string` becomes:

```ts
const [picker, setPicker] = useState<{ kind: "link" | "unlink"; ticketId: string }>();
```

One state, two lists, one overlay. `commands` branches on `kind`; `closeOverlay` clears it
already, which is what keeps a ⌘K after an abandoned `D` from still asking about a
dependency.

The unlink rows read `Stop waiting for KAN-12: Migrate schema` — the imperative of the
`d` rows' `Wait for KAN-12: …`, so the two lists are legible as inverses.

A failure reports through `reportError` into the `topbar-error` strip, where the cycle 409
from `d` already lands: `useUnlinkDependency` exists and is optimistic about nothing, so
there is no snapping-back row to serve as the signal.

---

## `Action.hint`

### The field

```ts
/**
 * What a menu prints for this action, when the key it answers is not a key
 * `resolveShortcut` can dispatch on. `hint` is never dispatched and `shortcut` is only
 * displayed when there is no `hint`.
 */
hint?: string;
```

`app.palette` carries `hint: "Mod+K"` and, still, no `shortcut`. `Mod` is canonical rather
than resolved in the registry: the registry is a module, the reader's platform is a
runtime fact, and mixing them would make the registry's contents depend on where it was
imported.

### Rendering

```ts
// actions.ts — pure, so Vitest reaches it
export function hintOf(action: Action, isMac: boolean): string | undefined

// lib/platform.ts — the one place that reads navigator
export function isMac(): boolean
```

`hintOf` expands `Mod+` to `⌘` on a Mac and `Ctrl+` elsewhere, and otherwise falls back to
today's behaviour: the first key of `shortcut`, through `KEY_LABELS`.

Three call sites converge on it, replacing three spellings of the same idea:
`menu-items.ts:29`, `page.tsx:282` (the palette's own `hint`), and `shortcutRows()`, which
must now emit a row for an action that has a `hint` and no `shortcut`.

The hardcoded `⌘K / Ctrl+K` pair leaves `HelpOverlay`. `Esc` stays: it is not an action,
it is the way out of whatever is on top.

### Why reading `navigator` at render is safe here

`page.tsx` returns `Loading…` while `me`, `authMode` or `setup` are in flight. On the
server all three always are, so the server's HTML contains no menu, no overlay and no
status bar — none of the surfaces that print a hint exists at hydration, so none can
mismatch. This is the same trade `view.tsx` already makes when it resolves the reader's
timezone with `Intl.DateTimeFormat().resolvedOptions().timeZone` in a `useMemo`.

---

## Tests

**Vitest** (`actions.test.ts`, `environment: "node"`, which everything here is reachable
from):

- `predecessorsOf` resolves a predecessor drawn as a bar and one sitting in the tray,
  drops an `outOfScope` id that resolves to nothing, and ignores edges belonging to
  another successor.
- `timeline.unlink`'s `when`: false with no selection, false when the selected ticket has
  no predecessor, true with one, false in the list view.
- `hintOf`: `"Mod+K"` → `⌘K` and `Ctrl+K`; an action with only a `shortcut` is unchanged;
  `ArrowDown` still prints `↓`.
- `shortcutRows()` contains a *Command palette* row under `Anywhere`, which is what says
  the hardcoded pair is no longer needed.

**Playwright** (`e2e/12-timeline.spec.ts`, which already draws an arrow): select the
successor, press `D`, assert the palette offers `Stop waiting for …` by role, `Enter`, and
assert the arrow is gone. By role rather than by private class — keying on `.row`,
`.status` and friends is a complaint `follow-ups.md` already holds against that suite.

## `follow-ups.md`, at the end of the branch

Two entries close, in the file's own `— closed` form, keeping the reasoning: *No menu
shows `⌘K` against Command palette*, and *Erasing an arrow starts with a click or a Tab*.

One entry is stale and goes: *Clearing a due date only takes effect on refetch* was fixed
when `PatchInput` grew `unset` and `usePatchTicket` began deleting those fields from the
optimistic copy.

Two arrive: `D`'s availability depends on the timeline fetch, and an `outOfScope` arrow
remains erasable only with a pointer.

Out of scope and still open: `ticket.delete` from the palette is unexercised. The `D`
scenario passes next to it without covering it.
