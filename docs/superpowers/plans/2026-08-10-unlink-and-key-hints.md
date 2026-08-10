# Erasing an arrow from the keyboard, and a key a menu can print — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `timeline.unlink` (`D`, predecessor picked from the command palette) and `Action.hint`, a display-only key label, so `app.palette` can print `⌘K` wherever the registry is read.

**Architecture:** Both changes are web-only and land in `apps/web/src/lib/actions.ts`, the action registry every keyboard, menu and palette surface already reads. `timeline.unlink` learns the dependency graph through a new `ActionContext.dependencies`, filled from the timeline query the chart already fetches, and resolves predecessor names against `ctx.tickets` — which is also what excludes out-of-scope edges. `Action.hint` is a second, display-only field; `hintOf(action, isMac)` is the one function all three hint consumers converge on.

**Tech Stack:** Next.js 16 app router (`apps/web`), React 19, TanStack Query v5, Zustand, Vitest 4 (`environment: "node"`), Playwright (`e2e/`).

**Spec:** `docs/superpowers/specs/2026-08-10-unlink-and-key-hints-design.md`

## Global Constraints

- No API change. Nothing under `apps/api/` is touched by this plan.
- `apps/web/AGENTS.md` applies: this Next.js version's docs live in `node_modules/next/dist/docs/`. No task here needs a Next API beyond `useState`/`useCallback`/`useMemo`, so nothing should be invented from memory either.
- `shortcut` dispatches and is only displayed when there is no `hint`. `hint` is displayed and is never dispatched. `resolveShortcut` must never read `hint`.
- A predecessor is listed if and only if it resolves in `ctx.tickets`. No branch on `outOfScope`.
- Vitest runs in `environment: "node"`: no DOM, no React rendering. Every unit test here is against pure functions and the registry.
- e2e assertions go through roles (`getByRole`), never private CSS classes. This is a debt `docs/follow-ups.md` records against the older specs and `e2e/12-timeline.spec.ts` deliberately does not repeat.
- Commands run from the repo root unless stated: `npm --prefix apps/web test`, `npm --prefix apps/web run typecheck`, `npm --prefix apps/web run lint`.
- Commit messages follow the log's shape: lowercase conventional prefix, subject as a sentence about behaviour (`feat(web): the palette can erase an arrow, not only draw one`).

## File Structure

| File | Change | Responsibility |
| --- | --- | --- |
| `apps/web/src/lib/actions.ts` | Modify | `ActionContext.dependencies` + `startUnlink`; `predecessorsOf`; the `timeline.unlink` entry; `Action.hint`; `hintOf`; `shortcutRows(isMac)` |
| `apps/web/src/lib/actions.test.ts` | Modify | Unit coverage for all of the above; `context()` gains the two new fields |
| `apps/web/src/lib/platform.ts` | Create | `isMac()` — the only place that reads `navigator` |
| `apps/web/src/lib/use-action-ctx.ts` | Modify | Feeds `dependencies` from `useTimeline`, passes `startUnlink` through |
| `apps/web/src/app/page.tsx` | Modify | `picker` state (link \| unlink), the unlink command list, `hintOf` for the palette |
| `apps/web/src/components/menu-items.ts` | Modify | Reads `hintOf` instead of slicing `shortcut` |
| `apps/web/src/components/overlays.tsx` | Modify | `HelpOverlay` calls `shortcutRows(isMac())` and drops the hardcoded `⌘K` row |
| `e2e/12-timeline.spec.ts` | Modify | Scenario 12 ends by erasing the arrow with `D` |
| `docs/follow-ups.md` | Modify | Two entries closed, one stale entry removed, two new ones |

Task 1 is pure logic and testable alone. Task 2 wires it to the page (typecheck + lint are its gate; behaviour is Task 3's). Task 3 proves it in a browser against the real API. Task 4 is independent of 1–3 and could be done first; it is placed after so the branch's headline feature lands first. Task 5 is the paperwork the previous branches established.

---

### Task 1: `predecessorsOf`, and the registry entry that uses it

**Files:**
- Modify: `apps/web/src/lib/actions.ts`
- Test: `apps/web/src/lib/actions.test.ts`

**Interfaces:**
- Consumes: `ActionContext`, `Action`, `onSelected`, `onTimeline`, `hasSelection`, `actionById`, `availableActions` (all already in `actions.ts`); `TimelineDependency` and `Ticket` from `./api`.
- Produces:
  - `ActionContext.dependencies: TimelineDependency[]`
  - `ActionContext.startUnlink: (successorId: string) => void`
  - `predecessorsOf(ctx: ActionContext, successorId: string): Ticket[]`
  - the action id `"timeline.unlink"`, shortcut `"D"`, `mode: "timeline"`

- [ ] **Step 1: Write the failing tests**

In `apps/web/src/lib/actions.test.ts`, add `predecessorsOf` to the import list from `./actions`, and `TimelineDependency` to the type import from `./api`:

```ts
import {
  ACTIONS,
  actionById,
  availableActions,
  indexActions,
  predecessorsOf,
  resolveShortcut,
  shortcutRows,
  type Action,
  type ActionContext,
} from "./actions";
import type { Project, Team, Ticket, TimelineDependency } from "./api";
```

Add two fixtures after the `scheduled` fixture (around line 58):

```ts
/** A second bar, so a predecessor can be one the chart draws rather than a tray chip. */
const earlier: Ticket = {
  ...ticket,
  id: "ticket-3",
  identifier: "KAN-3",
  title: "Migrate the schema",
  start: { at: "2026-08-01T00:00:00Z", hasTime: false },
  due: { at: "2026-08-02T00:00:00Z", hasTime: false },
};

const dependency = (predecessorId: string, successorId: string): TimelineDependency => ({
  predecessorId,
  successorId,
  violated: false,
  outOfScope: false,
});
```

Add the two new fields to `context()`'s returned object, beside `startLink`:

```ts
    dependencies: [],
    startLink: vi.fn(),
    startUnlink: vi.fn(),
```

Add `"timeline.unlink"` to `REQUIRED_IDS`, after `"timeline.link"`.

Add this describe block after the existing `describe("the timeline actions", …)` block:

```ts
describe("predecessorsOf", () => {
  it("names a predecessor drawn as a bar and one waiting in the tray", () => {
    // `ticket` has no dates, so it is a tray chip; `earlier` is a bar. Both are rows of
    // the tickets query, which is the only thing the rule asks.
    const ctx = timeline({
      tickets: [ticket, scheduled, earlier],
      dependencies: [dependency(earlier.id, scheduled.id), dependency(ticket.id, scheduled.id)],
    });
    expect(predecessorsOf(ctx, scheduled.id).map((row) => row.id)).toEqual([earlier.id, ticket.id]);
  });

  it("drops an edge whose other end is outside this scope", () => {
    // The server's own word for "absent from this response". Being out of scope, it is
    // absent from the tickets query too, so there is no name to print.
    const ctx = timeline({
      dependencies: [
        { predecessorId: "ticket-elsewhere", successorId: scheduled.id, violated: false, outOfScope: true },
      ],
    });
    expect(predecessorsOf(ctx, scheduled.id)).toEqual([]);
  });

  it("ignores the edges of another successor", () => {
    const ctx = timeline({ dependencies: [dependency(scheduled.id, ticket.id)] });
    expect(predecessorsOf(ctx, scheduled.id)).toEqual([]);
  });
});

describe("timeline.unlink", () => {
  it("is offered only when the selected ticket waits on something nameable", () => {
    expect(ids(timeline())).not.toContain("timeline.unlink");

    const waiting = timeline({ dependencies: [dependency(ticket.id, scheduled.id)] });
    expect(ids(waiting)).toContain("timeline.unlink");

    const unnameable = timeline({
      dependencies: [
        { predecessorId: "ticket-elsewhere", successorId: scheduled.id, violated: false, outOfScope: true },
      ],
    });
    expect(ids(unnameable)).not.toContain("timeline.unlink");
  });

  it("is withheld from the list, where there is no arrow to erase", () => {
    const inList = context({
      selected: scheduled,
      tickets: [ticket, scheduled],
      dependencies: [dependency(ticket.id, scheduled.id)],
    });
    expect(ids(inList)).not.toContain("timeline.unlink");
  });

  it("is withheld while the timeline query has not answered", () => {
    // The first action in the registry whose availability depends on a fetch: with no
    // edges loaded, nothing knows whether there is anything to erase.
    expect(ids(timeline({ dependencies: [] }))).not.toContain("timeline.unlink");
  });

  it("asks the palette which arrow to erase, rather than erasing one on its own", () => {
    const ctx = timeline({ dependencies: [dependency(ticket.id, scheduled.id)] });
    actionById("timeline.unlink").run(ctx);
    expect(ctx.startUnlink).toHaveBeenCalledWith(scheduled.id);
    expect(ctx.patchTicket).not.toHaveBeenCalled();
  });

  it("answers Shift+D on the chart and nothing in the list", () => {
    // `event.key` for Shift+d is "D" — the same convention `H` and `L` follow, so the
    // registry still carries no modifier state.
    expect(resolveShortcut("D", "timeline")?.id).toBe("timeline.unlink");
    expect(resolveShortcut("D", "list")).toBeUndefined();
    expect(resolveShortcut("d", "timeline")?.id).toBe("timeline.link");
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm --prefix apps/web test`
Expected: FAIL. `predecessorsOf` is not exported (`No "predecessorsOf" export is defined`), and once that is fixed the `timeline.unlink` block fails on `Unknown action "timeline.unlink"`.

- [ ] **Step 3: Add the two context fields**

In `apps/web/src/lib/actions.ts`, extend the type import at line 2:

```ts
import type {
  Project,
  Team,
  Ticket,
  TicketPriority,
  TicketStatus,
  TimelineDependency,
} from "./api";
```

Add to `ActionContext`, immediately after `zoom: Zoom;`:

```ts
  /**
   * The arrows the chart is drawing, and an empty list anywhere it is not.
   *
   * Filled from the timeline query — the same cache entry the chart reads, so this costs
   * no second request — and empty while that query is in flight. `timeline.unlink` is
   * therefore the first action whose availability depends on a fetch: with no edges
   * loaded, nothing knows whether there is anything to erase, and saying so is more
   * honest than offering a picker that would open empty.
   */
  dependencies: TimelineDependency[];
```

Add beside `startLink`, keeping its docstring's shape:

```ts
  /**
   * Asks which predecessor of [successorId] to erase. Same picker as [startLink], for the
   * same reason: the palette is the app's only list, and one gesture is not worth a
   * second way of being in a state.
   */
  startUnlink: (successorId: string) => void;
```

- [ ] **Step 4: Write `predecessorsOf`**

In `apps/web/src/lib/actions.ts`, after the `isScheduled` helper (around line 134):

```ts
/**
 * The predecessors of [successorId] this screen can name.
 *
 * An edge is listed exactly when its other end resolves in `ctx.tickets`, and that one
 * rule is also the scope rule. A predecessor sitting in the unscheduled tray is a row of
 * the tickets query — it has no dates, not no row — so it is named and offered. One the
 * timeline response marked `outOfScope` is outside the current scope, so it is absent
 * from that query too, resolves to nothing, and drops out. The palette lists names, and
 * two edges nothing can name would be two identical rows with different consequences.
 *
 * Read by `when` and by the picker in `page.tsx`, which is what keeps an inert key from
 * opening an empty list and a listed row from failing to resolve.
 */
export function predecessorsOf(ctx: ActionContext, successorId: string): Ticket[] {
  return ctx.dependencies
    .filter((edge) => edge.successorId === successorId)
    .flatMap((edge) => ctx.tickets.find((row) => row.id === edge.predecessorId) ?? []);
}
```

- [ ] **Step 5: Add the registry entry**

In `apps/web/src/lib/actions.ts`, directly after the `timeline.link` entry (around line 551):

```ts
  {
    id: "timeline.unlink",
    label: "Remove a dependency",
    // `D`, the `event.key` of Shift+d, so `d` and its inverse are one keystroke apart.
    shortcut: "D",
    mode: "timeline",
    group: "ticket",
    // It always opens the picker, even with a single predecessor: one key doing two
    // things depending on the shape of the graph would make the fast path the
    // destructive one.
    when: (ctx) =>
      onTimeline(ctx) &&
      ctx.selected !== undefined &&
      predecessorsOf(ctx, ctx.selected.id).length > 0,
    run: onSelected((ctx, ticket) => ctx.startUnlink(ticket.id)),
  },
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `npm --prefix apps/web test`
Expected: PASS, all files. Note the other consumers of `ActionContext` do not compile yet — that is Task 2 — so **do not** run `typecheck` here; `use-action-ctx.ts` will report the two missing fields.

- [ ] **Step 7: Commit**

```bash
git add apps/web/src/lib/actions.ts apps/web/src/lib/actions.test.ts
git commit -m "feat(web): the registry can remove a dependency, and knows which ones it can name"
```

---

### Task 2: Wire `D` to the palette

**Files:**
- Modify: `apps/web/src/lib/use-action-ctx.ts`
- Modify: `apps/web/src/app/page.tsx`

**Interfaces:**
- Consumes: `predecessorsOf`, `ActionContext.dependencies`, `ActionContext.startUnlink` (Task 1); `useTimeline`, `useUnlinkDependency` (both already exported from `@/lib/queries`); `actionErrorMessage` from `@/lib/errors`.
- Produces: the browser behaviour Task 3 asserts — `D` on the chart opens the palette listing `Stop waiting for KAN-12: Migrate the schema`, and `Enter` erases that dependency.

- [ ] **Step 1: Feed `dependencies` into the context**

In `apps/web/src/lib/use-action-ctx.ts`, add `useTimeline` to the import from `./queries`:

```ts
import {
  useDeleteTicket,
  useMe,
  usePatchTicket,
  useProjects,
  useTeams,
  useTimeline,
  useUnarchive,
} from "./queries";
```

Add `startUnlink` to the `local` parameter, under `startLink`:

```ts
  /** Opens the predecessor picker for a ticket. Page-local: the palette is its list. */
  startLink: (successorId: string) => void;
  /** Opens the same picker to erase one instead of to draw one. */
  startUnlink: (successorId: string) => void;
```

After the `useProjects()` line, add:

```ts
  // The chart's own query, asked for again rather than passed down: same key, so this is
  // the same cache entry the chart reads and no second request exists. `enabled` is the
  // view, so the list pays nothing for edges no action there can use.
  const timeline = useTimeline(view === "timeline");
```

`view` is already destructured from `useUi()` on the line above, so nothing else moves.

Extend the destructuring of `local`:

```ts
  const { tickets, selected, move, startRename, startLink, startUnlink, reportError } = local;
```

Add to the returned object, beside `startLink`:

```ts
      dependencies: timeline.data?.dependencies ?? [],
      startLink,
      startUnlink,
```

And to the dependency array, beside `startLink`:

```ts
      timeline.data,
      startLink,
      startUnlink,
```

- [ ] **Step 2: Turn the page's `linkFor` into a two-kind picker**

In `apps/web/src/app/page.tsx`, replace the `linkFor` state (lines 69–74) with:

```ts
  /**
   * The ticket whose arrows the palette is asking about, and which question it is
   * asking. Page-local rather than in the store: it lives exactly as long as the overlay
   * it re-labels, and the palette is rendered here.
   */
  const [picker, setPicker] = useState<{ kind: "link" | "unlink"; ticketId: string }>();
```

Add `predecessorsOf` to the `@/lib/actions` import, and `useUnlinkDependency` to the `@/lib/queries` import:

```ts
import { availableActions, predecessorsOf, resolveShortcut } from "@/lib/actions";
```

```ts
  const link = useLinkDependency();
  const unlink = useUnlinkDependency();
```

- [ ] **Step 3: Replace the two callbacks**

Replace `startLink` (lines 169–175) and `closeOverlay` (lines 179–182) with:

```ts
  /**
   * `d` and `D` on the chart. The predecessor is picked from the palette the app already
   * has rather than from a link mode of its own: nothing else in this interface is modal,
   * and one keyboard gesture is not worth teaching a second way to be in a state.
   */
  const startLink = useCallback(
    (successorId: string) => {
      setPicker({ kind: "link", ticketId: successorId });
      open("palette");
    },
    [open],
  );

  const startUnlink = useCallback(
    (successorId: string) => {
      setPicker({ kind: "unlink", ticketId: successorId });
      open("palette");
    },
    [open],
  );

  // The palette is one overlay with three lists, so leaving it has to put the ordinary
  // one back — otherwise ⌘K afterwards would still be asking about a dependency.
  const closeOverlay = useCallback(() => {
    setPicker(undefined);
    close();
  }, [close]);
```

Pass the new callback to the context hook (around line 196):

```ts
  const ctx = useActionContext({
    tickets: visible,
    selected,
    move,
    startRename,
    startLink,
    startUnlink,
    reportError,
  });
```

- [ ] **Step 4: Add the unlink list to `commands`**

In the `commands` memo, replace `if (linkFor) {` with `if (picker?.kind === "link") {`, replace both uses of `linkFor` inside it with `picker.ticketId`, and add the unlink branch immediately after that block:

```ts
    // The inverse list, in the same overlay. "Stop waiting for" against "Wait for", so
    // the two are legible as opposites rather than as two unrelated pickers.
    if (picker?.kind === "unlink") {
      const successorId = picker.ticketId;
      return predecessorsOf(ctx, successorId).map((predecessor) => ({
        id: `timeline.unlink.${predecessor.id}`,
        label: `Stop waiting for ${predecessor.identifier}: ${predecessor.title}`,
        run: () => {
          unlink.mutate(
            { successorId, predecessorId: predecessor.id },
            {
              // Nothing here is optimistic — freeing slack pulls nothing earlier — so a
              // refusal has no row snapping back to serve as its signal, and goes to the
              // strip every other failed action reports into.
              onError: (error) => reportError(actionErrorMessage(error)),
              onSuccess: () => reportError(null),
            },
          );
          closeOverlay();
        },
      }));
    }
```

Update the memo's dependency array: replace `linkFor` with `picker` and add `unlink`.

```ts
  }, [ctx, teams.data, setScope, close, picker, visible, link, unlink, reportError, closeOverlay]);
```

- [ ] **Step 5: Verify the whole client compiles and the suite is still green**

Run: `npm --prefix apps/web run typecheck && npm --prefix apps/web test && npm --prefix apps/web run lint`
Expected: all three clean. `typecheck` is the real gate for this task — it is what proves every `ActionContext` construction site now supplies `dependencies` and `startUnlink`.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/use-action-ctx.ts apps/web/src/app/page.tsx
git commit -m "feat(web): D asks the palette which arrow to erase"
```

---

### Task 3: Prove it in a browser

**Files:**
- Modify: `e2e/12-timeline.spec.ts`
- Read for context: `e2e/README.md` (how to bring the stack up, and the `WEB_PORT`/`KANSO_WEB_ORIGIN` trap)

**Interfaces:**
- Consumes: the behaviour from Task 2; the existing scenario-12 fixtures `groundwork`, `follows`, `api`, `page`, `second`.
- Produces: nothing other tasks read.

The suite needs the stack running. `e2e/README.md` is authoritative; a non-default `WEB_PORT` also needs `KANSO_WEB_ORIGIN`, or CORS silently makes every page render as if the visitor were a member.

- [ ] **Step 1: Write the failing assertions**

In `e2e/12-timeline.spec.ts`, append this block at the end of the scenario, immediately **before** `await api.dispose();`. It has to come last: erasing the arrow undoes the constraint every assertion above it reads.

```ts
  // --- erase the arrow, without a mouse ---------------------------------------
  //
  // `d` draws one and, until now, only a pointer could erase one: click the line or tab
  // onto it, then Backspace. `D` is the inverse of `d` through the same palette, which is
  // what makes the gesture reachable from the keyboard at all.
  const arrow = page.getByRole("button", {
    name: `${groundwork.identifier} → ${follows.identifier}`,
  });

  // Clicking the bar puts the cursor on its ticket, which is what `D` acts on.
  await second.click();
  await page.keyboard.press("Shift+D");

  // Named after the predecessor, so a graph with several offers a choice between names
  // rather than between identical rows.
  const option = page.getByRole("button", {
    name: `Stop waiting for ${groundwork.identifier}: ${groundwork.title}`,
  });
  await expect(option).toBeVisible();

  const [erased] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/tickets/${follows.id}/dependencies/${groundwork.id}`) &&
        response.request().method() === "DELETE",
    ),
    page.keyboard.press("Enter"),
  ]);
  expect(erased.ok(), "the dependency was not erased").toBeTruthy();

  await expect(arrow).toHaveCount(0);

  /*
   * Nothing moves back. Freeing slack does not pull work earlier — the successor keeps the
   * dates the cascade gave it — and with no edge left neither end has slack to report, so
   * the red goes away while the bars stay where they are.
   */
  await expect(second).toHaveAttribute("data-state", "normal");
  await expect(first).toHaveAttribute("data-state", "normal");
  await expect
    .poll(async () => (await second.boundingBox())!.x)
    .toBe(before!.x + PX_PER_DAY_AT_DAY_ZOOM);
```

- [ ] **Step 2: Run the scenario to verify it fails**

With the stack up:

Run: `npx playwright test e2e/12-timeline.spec.ts`
Expected: FAIL at `await expect(option).toBeVisible()` — before Task 2 there is no such command; after Task 2 this step is the one that proves the wiring, so if it passes on the first try, re-read Task 2's Step 4 and confirm the branch is actually reached (`picker.kind`, not `linkFor`).

- [ ] **Step 3: Make it pass**

No new production code should be needed: Tasks 1 and 2 are the implementation. If it fails, the likely causes in order are (a) the palette's `Enter` handler runs `matches[active]`, so the option must be the only match — assert its visibility first, as written, rather than typing a query; (b) the bar click landed on a resize grip or the link handle instead of the bar's middle — `second.click()` targets the centre, which is neither; (c) `dependencies` is empty because `useTimeline(view === "timeline")` was given the wrong flag.

- [ ] **Step 4: Run the whole e2e suite**

Run: `npx playwright test`
Expected: PASS. Nothing else in the suite draws a dependency, so scenario 12 is the only one whose state this touches.

- [ ] **Step 5: Commit**

```bash
git add e2e/12-timeline.spec.ts
git commit -m "test(e2e): the arrow that was drawn with a mouse is erased with a key"
```

---

### Task 4: `Action.hint`, and a key printed for the reader who has it

**Files:**
- Create: `apps/web/src/lib/platform.ts`
- Modify: `apps/web/src/lib/actions.ts`
- Modify: `apps/web/src/components/menu-items.ts`
- Modify: `apps/web/src/components/overlays.tsx`
- Modify: `apps/web/src/app/page.tsx:282`
- Test: `apps/web/src/lib/actions.test.ts`

**Interfaces:**
- Consumes: `Action`, `KEY_LABELS`, `shortcutRows` (all in `actions.ts`).
- Produces:
  - `Action.hint?: string`
  - `hintOf(action: Action, isMac: boolean): string | undefined`
  - `shortcutRows(isMac: boolean): { mode: View | undefined; keys: string; label: string }[]` — **signature change**, the existing call sites must pass the flag
  - `isMac(): boolean` from `@/lib/platform`

- [ ] **Step 1: Write the failing tests**

In `apps/web/src/lib/actions.test.ts`, add `hintOf` to the import from `./actions`. Add this describe block before `describe("shortcutRows", …)`:

```ts
describe("hintOf", () => {
  it("prints the palette's key as the one the reader actually has", () => {
    const palette = actionById("app.palette");
    expect(hintOf(palette, true)).toBe("⌘K");
    expect(hintOf(palette, false)).toBe("Ctrl+K");
  });

  it("keeps that hint out of the dispatch table", () => {
    // The whole point of a second field: ⌘K is intercepted ahead of the registry, and
    // `k` there would collide with `ticket.moveUp`.
    expect(actionById("app.palette").shortcut).toBeUndefined();
    expect(resolveShortcut("K", "list")).toBeUndefined();
    expect(resolveShortcut("k", "list")?.id).toBe("ticket.moveUp");
  });

  it("falls back to the first spelling of a shortcut, printed for a human", () => {
    expect(hintOf(actionById("ticket.moveDown"), true)).toBe("j");
    expect(hintOf(actionById("app.help"), true)).toBe("?");

    const arrowOnly: Action = {
      id: "test.arrow",
      label: "Arrow",
      shortcut: "ArrowDown",
      group: "view",
      when: () => true,
      run: () => {},
    };
    expect(hintOf(arrowOnly, true)).toBe("↓");
  });

  it("prints nothing for an action the keyboard cannot reach", () => {
    expect(hintOf(actionById("project.create"), true)).toBeUndefined();
  });
});
```

Then update `describe("shortcutRows", …)` — every call now takes the flag, and the first test's definition of "bound" widens to include a hint:

```ts
describe("shortcutRows", () => {
  it("is derived from the actions the keyboard can reach, not written by hand", () => {
    const bound = ACTIONS.filter(
      (action) => action.shortcut !== undefined || action.hint !== undefined,
    );
    const rows = shortcutRows(true);

    expect(rows).toHaveLength(bound.length);
    for (const action of bound) {
      expect(rows.some((row) => row.label === action.label)).toBe(true);
    }
  });

  it("carries the palette's row, which the overlay used to draw by hand", () => {
    expect(shortcutRows(true).find((row) => row.label === "Command palette")).toEqual({
      mode: undefined,
      keys: "⌘K",
      label: "Command palette",
    });
    expect(shortcutRows(false).find((row) => row.label === "Command palette")?.keys).toBe("Ctrl+K");
  });

  it("prints the arrow keys as arrows rather than as DOM key names", () => {
    const rows = shortcutRows(true);
    expect(rows.find((row) => row.label === "Move down")?.keys).toBe("j / ↓");
    expect(rows.find((row) => row.label === "Move up")?.keys).toBe("k / ↑");
  });

  it("carries no row for an action the keyboard cannot reach", () => {
    expect(shortcutRows(true).some((row) => row.label === "New project")).toBe(false);
  });

  it("names the mode of every row, so the help overlay can group them", () => {
    const rows = shortcutRows(true);
    // Undefined, not "list": the row belongs to both views and the overlay says so.
    expect(rows.find((row) => row.label === "Move down")?.mode).toBeUndefined();
    expect(rows.find((row) => row.label === "Move bar earlier")?.mode).toBe("timeline");
    expect(rows.find((row) => row.label === "Rename ticket")?.mode).toBe("list");
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm --prefix apps/web test`
Expected: FAIL — `No "hintOf" export is defined`, and `shortcutRows` still takes no argument.

- [ ] **Step 3: Add the field**

In `apps/web/src/lib/actions.ts`, add to `Action` after the `shortcut` docstring and field:

```ts
  /**
   * What a menu prints when the key this action answers is not a key `resolveShortcut`
   * can dispatch on. `hint` is never dispatched; `shortcut` is only printed when there is
   * no `hint`, so the two cannot disagree about which one does what.
   *
   * `Mod+` is canonical and expanded at display time: the registry is a module, and which
   * modifier the reader's keyboard carries is a runtime fact about the reader.
   */
  hint?: string;
```

Give `app.palette` its hint, leaving `shortcut` absent:

```ts
  {
    id: "app.palette",
    label: "Command palette",
    // No `shortcut`: ⌘K is intercepted in `page.tsx` ahead of the registry, and `k` here
    // would collide with `ticket.moveUp`. `hint` is display only, which is what lets the
    // menus print a key the registry does not dispatch.
    hint: "Mod+K",
    group: "app",
    when: () => true,
    run: (ctx) => ctx.open("palette"),
  },
```

- [ ] **Step 4: Write `hintOf` and widen `shortcutRows`**

In `apps/web/src/lib/actions.ts`, replace the block from `const KEY_LABELS` to the end of the file with:

```ts
const KEY_LABELS: Record<string, string> = {
  ArrowDown: "↓",
  ArrowUp: "↑",
};

/**
 * The key to print for [action], or nothing when the keyboard cannot reach it.
 *
 * One function for the three surfaces that used to spell this out themselves — the row
 * menus, the command palette and the help overlay — so a display rule cannot hold in one
 * and not the others. [isMac] is passed rather than read here: this module is imported by
 * the test suite under `environment: "node"`, where there is no `navigator` to ask.
 */
export function hintOf(action: Action, isMac: boolean): string | undefined {
  if (action.hint !== undefined) return action.hint.replace("Mod+", isMac ? "⌘" : "Ctrl+");
  // The first spelling only: `ticket.moveDown` owns both `j` and `ArrowDown`, and a menu
  // entry reading "j ArrowDown" teaches nothing.
  const first = action.shortcut?.split(" ")[0];
  return first === undefined ? undefined : (KEY_LABELS[first] ?? first);
}

/**
 * Rows for the help overlay, generated from the shortcuts.
 *
 * Each row carries its mode — undefined for the keys both views answer — because a
 * flat list would offer `h` `l` `H` `L` to someone in the list, where they do nothing
 * at all.
 *
 * An action carrying only a `hint` gets a row too: that is what replaced the hardcoded
 * `⌘K` pair the overlay used to draw beneath the generated list.
 */
export function shortcutRows(
  isMac: boolean,
): { mode: View | undefined; keys: string; label: string }[] {
  return ACTIONS.flatMap((action) => {
    const keys =
      action.hint !== undefined
        ? hintOf(action, isMac)
        : action.shortcut
            ?.split(" ")
            .map((key) => KEY_LABELS[key] ?? key)
            .join(" / ");
    return keys === undefined ? [] : [{ mode: action.mode, keys, label: action.label }];
  });
}
```

- [ ] **Step 5: Create `platform.ts`**

Create `apps/web/src/lib/platform.ts`:

```ts
/**
 * Which modifier this reader's keyboard carries.
 *
 * Read at render rather than resolved once at import, and safe to do so: `page.tsx`
 * returns `Loading…` while `me`, `authMode` or `setup` are in flight, and on the server
 * all three always are — so the server's HTML contains no menu, no overlay and no status
 * bar. None of the surfaces that print a key exists at hydration, so none can mismatch.
 * `view.tsx` already makes the same trade when it resolves the reader's timezone.
 *
 * `navigator.platform` is deprecated and is still the only thing every browser answers
 * the same way; the user-agent string is the fallback, and a wrong guess costs a printed
 * label, never a keystroke — `page.tsx` answers ⌘K and Ctrl+K with one test either way.
 */
export function isMac(): boolean {
  if (typeof navigator === "undefined") return false;
  return /mac/i.test(navigator.platform || navigator.userAgent);
}
```

- [ ] **Step 6: Point the three consumers at it**

In `apps/web/src/components/menu-items.ts`, replace the `hint` line and its comment:

```ts
import { actionById, hintOf, type ActionContext } from "@/lib/actions";
import { isMac } from "@/lib/platform";
```

```ts
      // One rule for every surface that prints a key, including the actions whose key is
      // not one the registry dispatches — see `hintOf`.
      hint: hintOf(action, isMac()),
```

In `apps/web/src/app/page.tsx`, line 282:

```ts
        hint: hintOf(action, isMac()),
```

with `hintOf` added to the `@/lib/actions` import and `isMac` imported from `@/lib/platform`.

In `apps/web/src/components/overlays.tsx`, `HelpOverlay`:

```ts
export function HelpOverlay({ onClose }: { onClose: () => void }) {
  const rows = shortcutRows(isMac());
```

and delete the hardcoded palette row, keeping `Esc`:

```tsx
                {/*
                  The one key the registry cannot own as a shortcut: Escape is not an
                  action but the way out of whatever is on top of the list. ⌘K used to be
                  drawn here beside it and now comes from `app.palette`'s `hint`.
                */}
                {section.mode === undefined && (
                  <div style={{ display: "contents" }}>
                    <kbd>Esc</kbd>
                    <span>Close</span>
                  </div>
                )}
```

Add `import { isMac } from "@/lib/platform";` to that file.

- [ ] **Step 7: Run everything**

Run: `npm --prefix apps/web test && npm --prefix apps/web run typecheck && npm --prefix apps/web run lint`
Expected: all clean. If `typecheck` reports another `shortcutRows()` call site, pass `isMac()` there too — the signature change is deliberate and there should be exactly one caller.

- [ ] **Step 8: Check the two surfaces in a browser**

With the stack up, open the app, then:
1. Press `?` — the *Anywhere* section lists `⌘K  Command palette` once, from the registry, with `Esc  Close` still beneath it.
2. Open a ticket row's `⋯` menu — items still print their keys (`e`, `x`), unchanged.

Expected: exactly one `⌘K` row in the overlay, not two.

- [ ] **Step 9: Commit**

```bash
git add apps/web/src/lib/actions.ts apps/web/src/lib/actions.test.ts apps/web/src/lib/platform.ts apps/web/src/components/menu-items.ts apps/web/src/components/overlays.tsx apps/web/src/app/page.tsx
git commit -m "feat(web): a key a menu can print, even when the registry cannot dispatch it"
```

---

### Task 5: The paperwork

**Files:**
- Modify: `docs/follow-ups.md`

**Interfaces:**
- Consumes: nothing. Produces: nothing. This is the convention the previous three branches established — an entry is closed in place, with its reasoning kept.

- [ ] **Step 1: Close the two entries this branch answered**

In `docs/follow-ups.md`, replace the *No menu shows `⌘K`* entry (in the mouse-parity branch's "Test shape, not test count" section) with:

```markdown
**No menu shows `⌘K` against *Command palette* — closed.** Menu hints are mapped from
`Action.shortcut`, which is a list of `KeyboardEvent.key` values `resolveShortcut`
dispatches on; `app.palette` deliberately carries none, because ⌘K is intercepted ahead
of the registry and registering `k` there would collide with `ticket.moveUp`. It now
carries `hint: "Mod+K"` instead — a display string that is never dispatched — and
`hintOf` expands `Mod` to the modifier the reader's own keyboard has. The three surfaces
that each spelled the rule out themselves (`menu-items.ts`, the palette in `page.tsx`,
`shortcutRows`) read that one function, and the overlay's hardcoded `⌘K / Ctrl+K` pair is
gone with it.
```

Replace the *Erasing an arrow starts with a click or a Tab* entry (last in the timeline branch's section) with:

```markdown
**Erasing an arrow starts with a click or a Tab — closed.** `timeline.unlink` on `D` is
the inverse of `d` through the same palette: it lists the selected ticket's predecessors
by name and `Enter` erases one. The arrows stay focusable — one tab stop per dependency —
so the pointer path is unchanged; what was missing was a key that reaches a dependency
through the ticket it constrains, the way `d` already reaches one. There is still no key
that walks arrow to arrow, and that is a second selection model rather than a gap.
```

- [ ] **Step 2: Delete the stale entry**

Remove the *Clearing a due date only takes effect on refetch* entry from the timeline branch's section entirely. It was fixed in the same branch that recorded it: `PatchInput` now lists both date fields and `unset`, and `usePatchTicket`'s `onMutate` deletes every unset field from the optimistic copy. An entry describing behaviour the code no longer has costs the next reader a full investigation.

- [ ] **Step 3: Add what this branch leaves behind**

Append to the timeline branch's "Not a defect, but load-bearing to know" section:

```markdown
**`timeline.unlink` is the first action gated on a fetch.** `ActionContext.dependencies`
comes from the timeline query, so while that query is in flight `D` is inert and the
action is absent from the palette — `when` counts edges rather than assuming there is
something to erase. Every other `when` in the registry answers from the store or from a
list the page already holds. If a second action ever needs the graph, the question worth
asking first is whether `ActionContext` should carry a loading state rather than an empty
list that reads as "no arrows".

**An out-of-scope arrow is still pointer-only.** `predecessorsOf` lists an edge exactly
when its other end resolves in `ctx.tickets`, which is what silently excludes the ones the
timeline response marks `outOfScope`: outside the current scope, they are absent from that
query too, so there is no name to print. Two unnameable rows in the picker would be two
identical rows with different consequences. Their stubs remain clickable, and naming them
properly means fetching each missing ticket by id when the palette opens — a request per
edge, and a loading state in an overlay that has none.
```

- [ ] **Step 4: Commit**

```bash
git add docs/follow-ups.md
git commit -m "docs: two follow-ups closed, one stale, two new"
```

---

## Verification before the branch is called done

- [ ] `npm --prefix apps/web test` — green
- [ ] `npm --prefix apps/web run typecheck` — clean
- [ ] `npm --prefix apps/web run lint` — clean
- [ ] `npx playwright test` — green, with the stack up per `e2e/README.md`
- [ ] `?` in the browser shows exactly one `⌘K` row, generated
- [ ] `D` on a chart with no arrows does nothing, and does not open an empty palette

## Left open on purpose

`ticket.delete` from the command palette is still unexercised — `follow-ups.md` records it,
and Task 3's scenario passes next to it without covering it. Adding it is roughly five
lines in `e2e/mouse.spec.ts` or a third block in scenario 12, and it was scoped out of
this branch rather than forgotten.
