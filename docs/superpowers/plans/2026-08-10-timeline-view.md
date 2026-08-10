# Timeline view implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the scheduling engine on screen — a Gantt where every ticket is a bar you
can drag, every project is a bar derived from the tickets inside it, and every
dependency is an arrow you can draw and erase.

**Architecture:** Part 1 shipped the engine and `GET /api/timeline`, which already
returns bounds, slack, criticality and edges in one read. This part adds no scheduling
logic: the browser draws what that endpoint says and asks the API to change it. The
only pure module is `lib/timeline-geometry.ts` — dates to pixels, zoom arithmetic, and
the rule that a floating day is never converted — because Vitest runs in `environment:
"node"` with no DOM, so that is the only web code the suite can reach.

**Tech Stack:** Next.js 16.3 (App Router), React, TanStack Query, Zustand, Vitest
(node environment, `src/**/*.test.ts` only — `.tsx` is not collected), Playwright.

## Global Constraints

- **`apps/web/AGENTS.md` applies:** this is Next.js 16.3 and its APIs may differ from
  what you remember. Read `apps/web/node_modules/next/dist/docs/` before writing
  anything framework-shaped. The AGENTS.md block is written by `next dev`; committing
  it with your work is correct, removing it is not.
- **A floating date is never converted.** `dayValue` slices the ISO string;
  `new Date(instant.at)` anywhere near a `hasTime: false` value is the bug this whole
  feature exists to avoid.
- **Global CSS is imported from a route file**, following `settings.css` and
  `setup.css`: the timeline's stylesheet lives at `apps/web/src/app/timeline.css` and is
  imported by `apps/web/src/app/page.tsx`. Components do not import CSS.
- **Vitest collects `src/**/*.test.ts` only.** A test named `.test.tsx` runs nowhere.
  Anything that must be tested goes in a `.ts` module with no JSX.
- Run web checks with `pnpm --dir apps/web typecheck`, `pnpm --dir apps/web lint` and
  `pnpm --dir apps/web test`.
- Run the e2e suite from the repo root with `pnpm test:e2e` against the docker compose
  stack.
- The API is done and must not be modified by this plan. If a task appears to need an
  API change, stop and report it rather than editing `apps/api`.

## A decision this plan makes, and the spec did not

**The cascade is not replayed in the browser.** The spec asked for the local cascade to
run during a drag so successors move under the cursor, and claimed the rule would live
"written once" in `timeline-geometry.ts`. That is wrong: it would be written twice, once
in Kotlin and once in TypeScript, with no shared fixture keeping them in step. Two
scheduling engines that disagree produce exactly the failure a Gantt cannot afford —
the bar that snaps somewhere else after the server answers.

So: during a drag only the dragged bar follows the cursor. On release the `PATCH` goes
out, the row settles optimistically, and the refetch triggered by `onSettled` moves the
successors. This is also what most Gantt tools do — successors move on drop, not
during. If the successors-follow-the-cursor feel is wanted later, the honest way to get
it is for `PATCH /api/tickets/{id}` to return the moved rows the way the dependency
endpoints already do, not for the browser to learn to schedule.

## File Structure

**Created**

| Path | Responsibility |
|---|---|
| `apps/web/src/lib/timeline-geometry.ts` | **pure**: zoom, date ⇄ pixel, day labels, viewport maths |
| `apps/web/src/lib/timeline-geometry.test.ts` | its tests |
| `apps/web/src/app/timeline.css` | every timeline style |
| `apps/web/src/components/timeline/view.tsx` | scope, zoom, viewport, drag state |
| `apps/web/src/components/timeline/grid.tsx` | the time axis and the row backgrounds |
| `apps/web/src/components/timeline/row.tsx` | a project row or a ticket row |
| `apps/web/src/components/timeline/bar.tsx` | one bar: move, resize, link handle |
| `apps/web/src/components/timeline/arrows.tsx` | the SVG dependency layer |
| `apps/web/src/components/timeline/tray.tsx` | the unscheduled tray |
| `e2e/12-timeline.spec.ts` | the end-to-end scenario |

**Modified**

| Path | Change |
|---|---|
| `apps/web/src/lib/api.ts` | timeline types and calls |
| `apps/web/src/lib/queries.ts` | `useTimeline`, link/unlink mutations, `PatchInput`, `applyEvent` |
| `apps/web/src/store/ui.ts` | `view`, `zoom`, `linking` |
| `apps/web/src/lib/actions.ts` | `mode` on `Action`, the timeline keys |
| `apps/web/src/lib/actions.test.ts` | mode-aware resolution |
| `apps/web/src/lib/use-action-ctx.ts` | the timeline context fields |
| `apps/web/src/app/page.tsx` | the list ⇄ timeline switch |
| `apps/web/src/components/overlays.tsx` | help overlay grouped by mode |

---

### Task 1: The geometry, pure

**Files:**
- Create: `apps/web/src/lib/timeline-geometry.ts`
- Test: `apps/web/src/lib/timeline-geometry.test.ts`

**Interfaces:**
- Consumes: `KansoInstant` from `@/lib/api`.
- Produces: `Zoom`, `ZOOMS`, `PX_PER_DAY`, `dayKey`, `addDays`, `xOf`, `widthOf`,
  `instantAtX`, `snapDays`, `boundLabel`, `axisTicks`.

This is the only web module the test suite can reach, so everything that can be
expressed without a DOM belongs here rather than in a component.

- [ ] **Step 1: Write the failing tests**

```ts
import { describe, expect, test } from "vitest";
import {
  addDays,
  axisTicks,
  boundLabel,
  dayKey,
  instantAtX,
  PX_PER_DAY,
  snapDays,
  widthOf,
  xOf,
} from "./timeline-geometry";

const floating = (day: string) => ({ at: `${day}T00:00:00Z`, hasTime: false });
const timed = (iso: string) => ({ at: iso, hasTime: true });

describe("a day is never converted", () => {
  test("a floating bound reads the same in Tokyo and in Los Angeles", () => {
    const bound = floating("2026-08-12");
    expect(boundLabel(bound, "Asia/Tokyo")).toBe(boundLabel(bound, "America/Los_Angeles"));
    expect(boundLabel(bound, "America/Los_Angeles")).toContain("12");
  });

  test("a timed bound is converted, because it names a moment", () => {
    const bound = timed("2026-08-12T23:00:00Z");
    expect(boundLabel(bound, "Asia/Tokyo")).not.toBe(boundLabel(bound, "America/Los_Angeles"));
  });

  test("a floating bound lands on its own day column whatever the reader's zone", () => {
    // The trap: a Date-based implementation puts 2026-08-12T00:00Z one column left
    // for anyone west of UTC.
    expect(dayKey(floating("2026-08-12"))).toBe("2026-08-12");
  });
});

describe("placing a bar", () => {
  test("x is the number of days from the origin, times the zoom", () => {
    expect(xOf(floating("2026-08-04"), "2026-08-01", "day")).toBe(3 * PX_PER_DAY.day);
    expect(xOf(floating("2026-08-04"), "2026-08-01", "month")).toBe(3 * PX_PER_DAY.month);
  });

  test("a bar is at least one zoom unit wide, so a milestone is still visible", () => {
    const day = floating("2026-08-04");
    expect(widthOf(day, day, "day")).toBe(PX_PER_DAY.day);
  });

  test("width spans the whole last day rather than stopping at its start", () => {
    // A ticket from the 4th to the 6th occupies three columns, not two.
    expect(widthOf(floating("2026-08-04"), floating("2026-08-06"), "day")).toBe(
      3 * PX_PER_DAY.day,
    );
  });

  test("x and instantAtX are inverses on a column boundary", () => {
    const x = xOf(floating("2026-08-09"), "2026-08-01", "week");
    expect(instantAtX(x, "2026-08-01", "week")).toEqual(floating("2026-08-09"));
  });

  test("a dropped bar is a floating day, never a moment", () => {
    expect(instantAtX(17, "2026-08-01", "day").hasTime).toBe(false);
  });
});

describe("dragging", () => {
  test("a pixel delta snaps to whole days", () => {
    expect(snapDays(PX_PER_DAY.day * 2 + 3, "day")).toBe(2);
    expect(snapDays(-PX_PER_DAY.day * 2 - 3, "day")).toBe(-2);
  });

  test("a sub-unit drag snaps to nothing rather than to a fraction", () => {
    expect(snapDays(2, "month")).toBe(0);
  });

  test("addDays crosses a month boundary without a timezone in sight", () => {
    expect(addDays(floating("2026-08-30"), 3)).toEqual(floating("2026-09-02"));
  });
});

describe("the axis", () => {
  test("day zoom labels every day", () => {
    const ticks = axisTicks("2026-08-01", 3, "day");
    expect(ticks.map((tick) => tick.day)).toEqual(["2026-08-01", "2026-08-02", "2026-08-03"]);
  });

  test("month zoom labels first-of-month only", () => {
    const ticks = axisTicks("2026-08-30", 5, "month");
    expect(ticks.map((tick) => tick.day)).toEqual(["2026-09-01"]);
  });
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `pnpm --dir apps/web test`
Expected: FAIL — `Failed to resolve import "./timeline-geometry"`.

- [ ] **Step 3: Write the module**

```ts
import type { KansoInstant } from "./api";

/**
 * Geometry only. No React, no DOM — Vitest runs in `environment: "node"`, so this is
 * the one place on the web side where a rule can be covered by a test.
 *
 * Every date here is handled as a `YYYY-MM-DD` string, never as a `Date`. A bar's
 * column is a civil day, and `new Date("2026-08-12T00:00:00Z")` in Los Angeles is the
 * 11th — which would put the bar one column left for anyone west of UTC, on data they
 * did not touch.
 */

export type Zoom = "day" | "week" | "month";
export const ZOOMS: readonly Zoom[] = ["day", "week", "month"] as const;

/** Pixels per calendar day. The zoom names describe the label density, not the unit. */
export const PX_PER_DAY: Record<Zoom, number> = { day: 28, week: 10, month: 3 };

/** The civil day a bound sits on, taken by slicing rather than by converting. */
export const dayKey = (instant: KansoInstant): string => instant.at.slice(0, 10);

const MS_PER_DAY = 86_400_000;

/** Whole days between two `YYYY-MM-DD` strings. Both are read as UTC, so no zone applies. */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / MS_PER_DAY);
}

/** A floating instant [days] later. Stays floating: shifting a day cannot invent a time. */
export function addDays(instant: KansoInstant, days: number): KansoInstant {
  const shifted = new Date(Date.parse(`${dayKey(instant)}T00:00:00Z`) + days * MS_PER_DAY);
  return { at: `${shifted.toISOString().slice(0, 10)}T00:00:00Z`, hasTime: false };
}

export function xOf(instant: KansoInstant, origin: string, zoom: Zoom): number {
  return daysBetween(origin, dayKey(instant)) * PX_PER_DAY[zoom];
}

/**
 * A bar covers its last day rather than stopping at its start, and never shrinks below
 * one column — a milestone carries one bound and would otherwise be zero pixels wide.
 */
export function widthOf(start: KansoInstant, end: KansoInstant, zoom: Zoom): number {
  const days = daysBetween(dayKey(start), dayKey(end)) + 1;
  return Math.max(days, 1) * PX_PER_DAY[zoom];
}

export function instantAtX(x: number, origin: string, zoom: Zoom): KansoInstant {
  return addDays({ at: `${origin}T00:00:00Z`, hasTime: false }, Math.round(x / PX_PER_DAY[zoom]));
}

/** A pixel delta as whole days. Truncates, so a drag shorter than one column moves nothing. */
export function snapDays(dx: number, zoom: Zoom): number {
  return Math.trunc(dx / PX_PER_DAY[zoom]);
}

/**
 * How a bound reads to one person. A floating bound is formatted from its own string
 * with no zone applied; a timed one is converted, because it names a moment.
 */
export function boundLabel(instant: KansoInstant, timezone: string): string {
  if (!instant.hasTime) {
    const [, month, day] = dayKey(instant).split("-");
    return `${day}/${month}`;
  }
  return new Intl.DateTimeFormat(undefined, {
    timeZone: timezone,
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(instant.at));
}

export type AxisTick = { day: string; x: number; label: string };

/** Where the axis draws a label, and what it says. */
export function axisTicks(origin: string, dayCount: number, zoom: Zoom): AxisTick[] {
  const ticks: AxisTick[] = [];
  for (let offset = 0; offset < dayCount; offset += 1) {
    const day = dayKey(addDays({ at: `${origin}T00:00:00Z`, hasTime: false }, offset));
    const [year, month, dayOfMonth] = day.split("-");
    const isFirst = dayOfMonth === "01";
    const isMonday = new Date(`${day}T00:00:00Z`).getUTCDay() === 1;

    if (zoom === "day" || (zoom === "week" && isMonday) || (zoom === "month" && isFirst)) {
      ticks.push({
        day,
        x: offset * PX_PER_DAY[zoom],
        label: zoom === "month" ? `${month}/${year.slice(2)}` : `${dayOfMonth}/${month}`,
      });
    }
  }
  return ticks;
}
```

- [ ] **Step 4: Run the tests**

Run: `pnpm --dir apps/web test`
Expected: PASS — all thirteen cases.

- [ ] **Step 5: Typecheck and commit**

```bash
pnpm --dir apps/web typecheck && pnpm --dir apps/web lint
git add apps/web/src/lib/timeline-geometry.ts apps/web/src/lib/timeline-geometry.test.ts
git commit -m "feat(web): timeline geometry, where a day never becomes a moment"
```

---

### Task 2: The client and its hooks

**Files:**
- Modify: `apps/web/src/lib/api.ts`
- Modify: `apps/web/src/lib/queries.ts`

**Interfaces:**
- Produces: `TimelineView`, `TimelineProject`, `TimelineTicket`, `TimelineDependency`,
  `TimelineUnscheduled`, `api.timeline`, `api.linkDependency`, `api.unlinkDependency`;
  `useTimeline()`, `useLinkDependency()`, `useUnlinkDependency()`;
  `keys.timeline(scope)`.

- [ ] **Step 1: Add the types and calls**

In `apps/web/src/lib/api.ts`, after the `Project` types:

```ts
/** A project bound, plus whether anyone posted it — a derived one is not editable. */
export type TimelineBound = KansoInstant & { derived: boolean };

export type TimelineProject = {
  id: string;
  name: string;
  start?: TimelineBound;
  end?: TimelineBound;
};

export type TimelineTicket = {
  id: string;
  identifier: string;
  title: string;
  projectId?: string;
  status: TicketStatus;
  start?: KansoInstant;
  due?: KansoInstant;
  /** Null for a ticket with no dependencies: it has no slack to report. */
  slackMinutes?: number;
  critical: boolean;
  late: boolean;
};

export type TimelineDependency = {
  predecessorId: string;
  successorId: string;
  violated: boolean;
  /** The other end is outside this response, so the arrow is drawn as a stub. */
  outOfScope: boolean;
};

export type TimelineUnscheduled = { id: string; identifier: string; title: string };

export type TimelineView = {
  projects: TimelineProject[];
  tickets: TimelineTicket[];
  dependencies: TimelineDependency[];
  unscheduled: TimelineUnscheduled[];
};
```

In the `api` object, alongside `tickets`:

```ts
  timeline: (scope: Scope): Promise<TimelineView> => {
    const params = new URLSearchParams();
    if (scope.kind === "team") params.set("teamId", scope.id);
    if (scope.kind === "project") params.set("projectId", scope.id);
    return request(`/api/timeline?${params}`);
  },

  /** `{id}` is the successor; the body names what it now waits on. */
  linkDependency: (successorId: string, predecessorId: string): Promise<{ movedTicketIds: string[] }> =>
    request(`/api/tickets/${successorId}/dependencies`, {
      method: "POST",
      body: JSON.stringify({ predecessorId }),
    }),

  unlinkDependency: (successorId: string, predecessorId: string): Promise<void> =>
    request(`/api/tickets/${successorId}/dependencies/${predecessorId}`, { method: "DELETE" }),
```

Match the existing `request` helper's signature exactly — read it before writing these.

- [ ] **Step 2: Add the query key and hook**

In `apps/web/src/lib/queries.ts`, extend `keys`:

```ts
  timeline: (scope: Scope) =>
    ["timeline", scope.kind, scope.kind === "all" ? "" : scope.id] as const,
```

and add:

```ts
/**
 * One query for the whole screen. Bounds, slack, criticality and arrows are computed
 * together over the same dependency closure on the server, so asking for them
 * separately would mean walking that closure more than once.
 */
export const useTimeline = (enabled: boolean) => {
  const scope = useUi((state) => state.scope);
  return useQuery({
    queryKey: keys.timeline(scope),
    queryFn: () => api.timeline(scope),
    enabled,
  });
};
```

`enabled` so the list view does not pay for a query nothing renders.

- [ ] **Step 3: Invalidate the timeline on a ticket event**

In `applyEvent`, the tickets branch becomes:

```ts
  if (entity === "tickets") {
    queryClient.invalidateQueries({ queryKey: ["tickets"] });
    // A cascade moves tickets other than the edited one, and the event names only
    // the entity — so the whole view is refetched rather than patched.
    queryClient.invalidateQueries({ queryKey: ["timeline"] });
  }
```

Add the same `["timeline"]` invalidation to the `projects` branch: a project's derived
bounds change when its tickets do, and its explicit ones when it is edited.

- [ ] **Step 4: Teach `PatchInput` about dates, and make `unset` optimistic**

`PatchInput` never listed the date field, so a cleared date reappears until the server
answers — `follow-ups.md` records this. Dragging makes it visible.

```ts
type PatchInput = {
  id: string;
  status?: TicketStatus;
  priority?: TicketPriority;
  title?: string;
  description?: string;
  archived?: boolean;
  start?: KansoInstant | null;
  due?: KansoInstant | null;
  unset?: string[];
};
```

In `usePatchTicket`'s `onMutate`, replace the naive spread with one that honours `unset`
and invalidates the timeline too:

```ts
      queryClient.setQueryData<Ticket[]>(key, (current) =>
        (current ?? []).map((ticket) => {
          if (ticket.id !== id) return ticket;
          const patched: Ticket = { ...ticket, ...body };
          // JSON cannot tell an absent key from an explicit null, so the server takes
          // a list of fields to clear. The optimistic copy has to clear them too, or
          // the value the person just removed sits there until the refetch lands.
          for (const field of body.unset ?? []) {
            delete (patched as Record<string, unknown>)[field];
          }
          patched.mirror = {
            ...ticket.mirror,
            state: ticket.mirror.state === "disabled" ? "disabled" : "pending",
          };
          return patched;
        }),
      );
```

`unset` must not itself be spread onto the ticket — strip it from `body` before the
spread, or the row carries a stray field.

Add `queryClient.invalidateQueries({ queryKey: ["timeline"] })` to `onSettled`.

- [ ] **Step 5: Add the two mutations**

```ts
/**
 * Drawing an arrow. The response carries the tickets the new constraint moved, but the
 * timeline is refetched rather than patched from it: the same edit also changes slack
 * and criticality for tickets that did not move at all.
 */
export function useLinkDependency() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ successorId, predecessorId }: { successorId: string; predecessorId: string }) =>
      api.linkDependency(successorId, predecessorId),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["timeline"] });
      queryClient.invalidateQueries({ queryKey: ["tickets"] });
    },
  });
}

export function useUnlinkDependency() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ successorId, predecessorId }: { successorId: string; predecessorId: string }) =>
      api.unlinkDependency(successorId, predecessorId),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["timeline"] });
      queryClient.invalidateQueries({ queryKey: ["tickets"] });
    },
  });
}
```

- [ ] **Step 6: Verify and commit**

```bash
pnpm --dir apps/web typecheck && pnpm --dir apps/web lint && pnpm --dir apps/web test
git add apps/web/src/lib/api.ts apps/web/src/lib/queries.ts
git commit -m "feat(web): the timeline read, its two writes, and a clear that is optimistic"
```

---

### Task 3: The view, read-only

**Files:**
- Modify: `apps/web/src/store/ui.ts`
- Create: `apps/web/src/app/timeline.css`
- Create: `apps/web/src/components/timeline/view.tsx`, `grid.tsx`, `row.tsx`, `bar.tsx`
- Modify: `apps/web/src/app/page.tsx`

**Interfaces:**
- Consumes: `useTimeline` (Task 2), the geometry module (Task 1).
- Produces: `useUi().view` (`"list" | "timeline"`), `useUi().zoom`, `<TimelineView />`.

Nothing is draggable yet. This task ends with a Gantt you can look at.

- [ ] **Step 1: Extend the store**

In `apps/web/src/store/ui.ts`:

```ts
export type View = "list" | "timeline";
```

Add to `UiState`: `view: View`, `zoom: Zoom`, `setView: (view: View) => void`,
`setZoom: (zoom: Zoom) => void`; initial `view: "list"`, `zoom: "day"`. Import `Zoom`
as a type from `@/lib/timeline-geometry`.

Do not reset `selectedId` on a view change: the same ticket is selected in both, and
losing the cursor when switching would make the toggle feel like a navigation.

- [ ] **Step 2: Compute the viewport and render the rows**

`view.tsx` owns: the origin day, how many days the grid covers, and the grouping of
tickets under their projects.

```tsx
"use client";

import { useMemo } from "react";
import { TimelineGrid } from "./grid";
import { TimelineRow } from "./row";
import { addDays, dayKey, PX_PER_DAY } from "@/lib/timeline-geometry";
import { useTimeline } from "@/lib/queries";
import { useUi } from "@/store/ui";

/** A week of air either side, so the first bar is not flush against the axis. */
const PADDING_DAYS = 7;

export function TimelineView() {
  const zoom = useUi((state) => state.zoom);
  const timeline = useTimeline(true);

  const bounds = useMemo(() => {
    const days = (timeline.data?.tickets ?? []).flatMap((ticket) =>
      [ticket.start, ticket.due].filter(Boolean).map((bound) => dayKey(bound!)),
    );
    // An empty timeline still needs an axis, so fall back on a window around today.
    const today = new Date().toISOString().slice(0, 10);
    const first = days.length ? days.reduce((a, b) => (a < b ? a : b)) : today;
    const last = days.length ? days.reduce((a, b) => (a > b ? a : b)) : today;
    const origin = dayKey(addDays({ at: `${first}T00:00:00Z`, hasTime: false }, -PADDING_DAYS));
    const end = dayKey(addDays({ at: `${last}T00:00:00Z`, hasTime: false }, PADDING_DAYS));
    return { origin, dayCount: Math.max(daysBetween(origin, end), 1) };
  }, [timeline.data]);

  /**
   * Rows in reading order: each project once, its tickets under it, and the
   * project-less tickets last under no heading. A project with no scheduled tickets
   * still gets its row — its bar may come from an explicit bound.
   */
  const rows = useMemo(() => {
    const view = timeline.data;
    if (!view) return [];
    const byProject = new Map<string | undefined, typeof view.tickets>();
    for (const ticket of view.tickets) {
      const key = ticket.projectId;
      byProject.set(key, [...(byProject.get(key) ?? []), ticket]);
    }
    return [
      ...view.projects.flatMap((project) => [
        { kind: "project" as const, project },
        ...(byProject.get(project.id) ?? []).map((ticket) => ({ kind: "ticket" as const, ticket })),
      ]),
      ...(byProject.get(undefined) ?? []).map((ticket) => ({ kind: "ticket" as const, ticket })),
    ];
  }, [timeline.data]);

  if (timeline.error) return <div className="empty error">{(timeline.error as Error).message}</div>;

  return (
    <div className="tl">
      <div className="tl-names">
        {rows.map((row) => (
          <div className="tl-name" key={row.kind === "project" ? row.project.id : row.ticket.id}>
            {row.kind === "project" ? row.project.name : row.ticket.identifier}
          </div>
        ))}
      </div>
      <div className="tl-pane">
        <div style={{ width: bounds.dayCount * PX_PER_DAY[zoom], position: "relative" }}>
          <TimelineGrid origin={bounds.origin} dayCount={bounds.dayCount} zoom={zoom} />
          {rows.map((row) => (
            <TimelineRow
              key={row.kind === "project" ? row.project.id : row.ticket.id}
              row={row}
              origin={bounds.origin}
              zoom={zoom}
            />
          ))}
        </div>
      </div>
    </div>
  );
}
```

Import `daysBetween` alongside `addDays`, `dayKey` and `PX_PER_DAY`. Export the `Row`
union (`{ kind: "project"; project: TimelineProject } | { kind: "ticket"; ticket: TimelineTicket }`)
from this file — `row.tsx` and `arrows.tsx` both take it.

The names column and the pane scroll together vertically and the pane alone
horizontally; `.tl` is a two-column grid with `overflow: hidden` and `.tl-pane` carries
`overflow-x: auto`.

- [ ] **Step 3: The grid, the rows and the bars**

`grid.tsx` draws the axis from `axisTicks(origin, dayCount, zoom)` and a vertical rule
per tick. `row.tsx` renders one row's label and its bar. `bar.tsx` renders a positioned
`<div>` with `left: xOf(...)` and `width: widthOf(...)`, and `data-` attributes for its
state:

```tsx
<div
  className="tl-bar"
  data-state={ticket.late ? "late" : ticket.critical ? "critical" : "normal"}
  data-done={ticket.status === "done" ? "" : undefined}
  style={{ left, width }}
  title={`${ticket.identifier} · ${boundLabel(start, timezone)} → ${boundLabel(due, timezone)}`}
>
  <span className="tl-bar-label">{ticket.title}</span>
</div>
```

A project row's bar is the same element with `data-kind="project"` and no handles; when
either bound is `derived`, mark it `data-derived` so the style can say the bar was
deduced rather than posted.

A ticket with only one bound renders from that bound to itself — `widthOf` already
floors at one column, so a milestone is a square rather than a hairline.

- [ ] **Step 4: Write the stylesheet**

`apps/web/src/app/timeline.css`, using the existing custom properties (`--surface`,
`--border`, `--text-dim`, `--accent`, `--row-height`, `--radius`) rather than new
colours. The three states:

```css
.tl-bar[data-state="normal"] { background: var(--accent-soft); border-color: var(--accent); }
.tl-bar[data-state="critical"] { background: var(--urgent); color: #fff; }

/*
 * Late is critical plus a hatch. The two are both red on purpose — a chain that
 * overruns its deadline is a worse case of the same thing — so the difference cannot
 * be carried by colour alone, or it disappears for a colour-blind reader and in every
 * greyscale screenshot.
 */
.tl-bar[data-state="late"] {
  background: repeating-linear-gradient(
    45deg, var(--urgent), var(--urgent) 6px, color-mix(in srgb, var(--urgent) 70%, #000) 6px,
    color-mix(in srgb, var(--urgent) 70%, #000) 12px
  );
  color: #fff;
}

.tl-bar[data-done] { opacity: 0.55; }
.tl-bar[data-kind="project"] { background: var(--surface-hover); border-style: dashed; }
```

Import it from `apps/web/src/app/page.tsx`, next to where the page imports its other
modules — component files do not import CSS in this codebase.

- [ ] **Step 5: Wire the switch into the page**

In `apps/web/src/app/page.tsx`, add a two-button toggle to the topbar reading
`useUi().view`, and render `<TimelineView />` in place of `<TicketList />` when the view
is `timeline`. Keep the filter input and the `New` menu where they are.

- [ ] **Step 6: Verify and commit**

```bash
pnpm --dir apps/web typecheck && pnpm --dir apps/web lint && pnpm --dir apps/web test
git add apps/web
git commit -m "feat(web): a timeline you can look at, grouped by project"
```

---

### Task 4: The arrows

**Files:**
- Create: `apps/web/src/components/timeline/arrows.tsx`
- Modify: `apps/web/src/components/timeline/view.tsx`, `apps/web/src/app/timeline.css`

**Interfaces:**
- Consumes: the row order and geometry from Task 3.
- Produces: `<TimelineArrows rows={…} deps={…} origin={…} zoom={…} />`.

- [ ] **Step 1: Draw the layer**

One absolutely-positioned `<svg>` over the whole scrollable pane, `pointer-events:
none` on the layer and `auto` on each path so an arrow can be clicked without eating
drags on the bars underneath.

For each dependency where both ends are on screen, a path from the right edge of the
predecessor's bar to the left edge of the successor's, routed as three segments
(out, across, in) rather than a straight diagonal — a diagonal through a dense chart
cannot be followed by eye.

```tsx
const path = (from: { x: number; y: number }, to: { x: number; y: number }) => {
  const mid = from.x + Math.max((to.x - from.x) / 2, 12);
  return `M ${from.x} ${from.y} H ${mid} V ${to.y} H ${to.x}`;
};
```

- [ ] **Step 2: Draw the out-of-scope stubs**

A dependency with `outOfScope: true` has one end with no row. Draw a short stub from the
end that exists, pointing outwards, with a `<title>` naming the other ticket:

```tsx
<title>{`Depends on a ticket outside this view`}</title>
```

The identifier of the absent ticket is not in the response — `TimelineDependency`
carries ids only. Do not invent a lookup: say that the other end is elsewhere, and stop
there. Naming it would need the endpoint to carry the identifier, which is an API change
and out of this plan's scope. **Report it as a finding.**

- [ ] **Step 3: Style violated arrows**

`stroke: var(--urgent)` and a marker, for `violated: true`. A violated edge means a
`done` successor its predecessor now overruns — the cascade could not repair it, so the
screen has to say so.

- [ ] **Step 4: Verify and commit**

```bash
pnpm --dir apps/web typecheck && pnpm --dir apps/web lint
git add apps/web
git commit -m "feat(web): dependency arrows, including the ones that leave the screen"
```

---

### Task 5: The unscheduled tray

**Files:**
- Create: `apps/web/src/components/timeline/tray.tsx`
- Modify: `apps/web/src/components/timeline/view.tsx`, `apps/web/src/app/timeline.css`

- [ ] **Step 1: Render it**

A collapsible strip above the grid listing `timeline.data.unscheduled`, each as a chip
carrying its identifier and title, with a count in the header: `Unscheduled · 12`.

On the current database almost every ticket lands here, which is the point — it is the
measure of what is left to plan, and without it the first load of the timeline is an
empty screen that suggests the feature is broken.

Hide the strip entirely when the list is empty.

- [ ] **Step 2: Verify and commit**

```bash
pnpm --dir apps/web typecheck && pnpm --dir apps/web lint
git add apps/web
git commit -m "feat(web): the unscheduled tray, which is most of the board today"
```

---

### Task 6: Dragging a bar

**Files:**
- Modify: `apps/web/src/components/timeline/bar.tsx`, `view.tsx`, `apps/web/src/app/timeline.css`

**Interfaces:**
- Consumes: `snapDays`, `addDays` (Task 1); `usePatchTicket` (Task 2).

- [ ] **Step 1: Move**

Pointer events, not HTML5 drag-and-drop: `setPointerCapture` on pointerdown, track
`event.clientX - startX`, convert with `snapDays`, and offset the bar with a CSS
transform while the pointer is down. On pointerup, `patch.mutate({ id, start, due })`
with both bounds shifted by the same number of days.

A ticket with one bound sends only that bound. Sending both would give a milestone a
start it never had — the API deliberately keeps the shape it was given, and the browser
must not undo that.

- [ ] **Step 2: Resize**

Two handles, six pixels wide, at the bar's edges. The left handle moves `start`, the
right one moves `due`, and neither may cross the other: clamp so the bar never inverts,
because the API answers a due before a start with a 400 and a dialog nobody asked for.

Resizing is unavailable on a bar with one bound — there is nothing to resize; the
handles are not rendered.

- [ ] **Step 3: Hold realtime updates during a drag**

A refetch landing mid-drag repositions what is under the cursor.

In `view.tsx`, keep a `dragging` ref. While it is set, `useTimeline` must not apply new
data: pass `notifyOnChangeProps: []` while dragging, or hold the rendered snapshot in
state and refresh it on pointerup. Either is acceptable; say which you chose and why.

- [ ] **Step 4: Do not make the successors follow the cursor**

Only the dragged bar moves during the drag. The successors move when the refetch lands
after the patch settles. This is the plan's decision, stated at the top: replaying the
cascade in TypeScript would be a second scheduling engine, free to disagree with the
first.

- [ ] **Step 5: Verify and commit**

```bash
pnpm --dir apps/web typecheck && pnpm --dir apps/web lint && pnpm --dir apps/web test
git add apps/web
git commit -m "feat(web): bars move and resize, and a refetch no longer yanks them"
```

---

### Task 7: Drawing an arrow, and scheduling from the tray

**Files:**
- Modify: `apps/web/src/components/timeline/bar.tsx`, `tray.tsx`, `view.tsx`, `arrows.tsx`
- Modify: `apps/web/src/store/ui.ts`

**Interfaces:**
- Consumes: `useLinkDependency`, `useUnlinkDependency` (Task 2).
- Produces: `useUi().linking` — `{ fromId: string } | undefined`.

- [ ] **Step 1: The link handle**

A small circular handle on the right edge of a bar, visible on hover. Pointerdown on it
sets `linking = { fromId }`; while set, the arrows layer draws a rubber-band path from
that bar to the pointer. Pointerup over another bar calls
`link.mutate({ successorId: targetId, predecessorId: fromId })`. Pointerup anywhere else
clears `linking` and does nothing.

- [ ] **Step 2: Report the refusal where it happened**

A cycle is a 409 whose message names the chain. Show it on the timeline, not in a
dialog: reuse the `topbar-error` element `page.tsx` already renders for action failures,
so a refusal reads the same way here as everywhere else in the app.

- [ ] **Step 3: Erasing**

Click an arrow to select it; `Backspace` or a `×` on the selected arrow calls
`unlink.mutate(...)`. Nothing moves afterwards — the API deliberately does not pull work
backwards when slack is freed, and the screen should not pretend otherwise.

- [ ] **Step 4: Drag from the tray**

Dragging a chip out of the tray and dropping it on the grid patches the ticket with
`start` = the day under the pointer, `due` = the same day. A one-day milestone is the
honest default: any other length would be a guess presented as a plan.

- [ ] **Step 5: Verify and commit**

```bash
pnpm --dir apps/web typecheck && pnpm --dir apps/web lint
git add apps/web
git commit -m "feat(web): arrows can be drawn and erased, and the tray can be emptied"
```

---

### Task 8: The keyboard

**Files:**
- Modify: `apps/web/src/lib/actions.ts`, `actions.test.ts`, `use-action-ctx.ts`
- Modify: `apps/web/src/app/page.tsx`, `apps/web/src/components/overlays.tsx`

**Interfaces:**
- Produces: `Action.mode?: "list" | "timeline"`, `resolveShortcut(key, mode)`.

- [ ] **Step 1: Write the failing tests**

Append to `apps/web/src/lib/actions.test.ts`:

```ts
test("one key means different things in the two views", () => {
  expect(resolveShortcut("h", "timeline")?.id).toBe("timeline.shiftEarlier");
  expect(resolveShortcut("h", "list")).toBeUndefined();
});

test("a shared key still resolves in both", () => {
  expect(resolveShortcut("j", "list")?.id).toBe("ticket.moveDown");
  expect(resolveShortcut("j", "timeline")?.id).toBe("ticket.moveDown");
});

test("the shift and resize pair are distinct keys, not a modifier", () => {
  // `event.key` for Shift+h is "H", so the registry needs no modifier plumbing.
  expect(resolveShortcut("H", "timeline")?.id).toBe("timeline.shrinkEnd");
  expect(resolveShortcut("l", "timeline")?.id).toBe("timeline.shiftLater");
  expect(resolveShortcut("L", "timeline")?.id).toBe("timeline.growEnd");
});

test("a duplicate key inside one mode is still a build-time error", () => {
  // The guard that made the registry trustworthy must survive the split.
  expect(() => registerForTest([
    { id: "a", label: "A", shortcut: "z", mode: "timeline", group: "view", when: () => true, run: () => {} },
    { id: "b", label: "B", shortcut: "z", mode: "timeline", group: "view", when: () => true, run: () => {} },
  ])).toThrow(/claimed by both/);
});
```

`registerForTest` does not exist yet — extract the current module-level loop in
`actions.ts` into an exported function so the duplicate guard can be exercised without
mutating the real registry.

- [ ] **Step 2: Run and watch fail**

Run: `pnpm --dir apps/web test`
Expected: FAIL — `resolveShortcut` takes one argument.

- [ ] **Step 3: Add the mode**

`Action` gains `mode?: "list" | "timeline"` — absent means "both". The key map is keyed
`${mode ?? "any"}:${key}`, and `resolveShortcut(key, mode)` tries `${mode}:${key}` then
`any:${key}`. The duplicate check stays, per bucket.

- [ ] **Step 4: Add the eight actions**

All with `mode: "timeline"`, none of whose keys is claimed today:

| id | key | label |
|---|---|---|
| `timeline.shiftEarlier` | `h` | Move bar earlier |
| `timeline.shiftLater` | `l` | Move bar later |
| `timeline.shrinkEnd` | `H` | Pull the end in |
| `timeline.growEnd` | `L` | Push the end out |
| `timeline.schedule` | `p` | Schedule this ticket |
| `timeline.unschedule` | `u` | Send back to the tray |
| `timeline.zoomOut` / `timeline.zoomIn` | `[` / `]` | Zoom |
| `timeline.today` | `t` | Recentre on today |
| `timeline.link` | `d` | Add a dependency |

`d` opens the existing `CommandPalette` filtered to candidate predecessors — not a
modal link mode. The app has no modal navigation anywhere, and introducing one so an
arrow can be drawn by keyboard costs a whole mental model for one gesture.

`ActionContext` gains the fields these need: `view`, `zoom`, `setZoom`, `shiftSelected`,
`resizeSelected`, `scheduleSelected`, `unscheduleSelected`, `recentre`.

**Also narrow `ActionContext.patchTicket`.** It is typed
`(input: { id: string } & Record<string, unknown>) => void`, so every keyboard-driven
patch bypasses `PatchInput` entirely — a caller can pass a misspelled field and nothing
objects. Retype it as `(input: PatchInput) => void` and fix whatever that surfaces. This
is the other half of the `unset` follow-up Task 2 closed in the mutation but not in the
type.

- [ ] **Step 5: Pass the mode from the key handler**

In `page.tsx`, `resolveShortcut(event.key)` becomes `resolveShortcut(event.key, view)`.

- [ ] **Step 6: Group the help overlay**

`shortcutRows()` returns a flat list today, so the help overlay would offer `h` `l` `H`
`L` to someone in the list view where they do nothing. Return
`{ mode, keys, label }[]` and have `HelpOverlay` render a section per mode.

- [ ] **Step 7: Verify and commit**

```bash
pnpm --dir apps/web typecheck && pnpm --dir apps/web lint && pnpm --dir apps/web test
git add apps/web
git commit -m "feat(web): the timeline answers the keyboard, and the registry learned modes"
```

---

### Task 9: The end-to-end scenario

**Files:**
- Create: `e2e/12-timeline.spec.ts`
- Modify: `e2e/README.md`

Read `e2e/support.ts` and one existing spec first: this suite has conventions about
seeding and about waiting that a new file has to follow.

- [ ] **Step 1: Write the scenario**

```ts
import { expect, test } from "@playwright/test";
import { signIn, newTeam, newTicket } from "./support";

test("lengthening a ticket pushes the one that depends on it", async ({ page }) => {
  await signIn(page);
  const team = await newTeam(page);
  await newTicket(page, { team, title: "Groundwork", start: "2026-08-03", due: "2026-08-05" });
  await newTicket(page, { team, title: "Follows on", start: "2026-08-05", due: "2026-08-07" });

  await page.getByRole("button", { name: "Timeline" }).click();

  const first = page.getByRole("button", { name: /Groundwork/ });
  const second = page.getByRole("button", { name: /Follows on/ });
  const before = await second.boundingBox();

  // Draw the arrow: the handle on the predecessor, dropped on the successor.
  await first.hover();
  await page.getByRole("button", { name: /depends on Groundwork/i }).dragTo(second);

  // Then lengthen the predecessor by dragging its right edge two columns out.
  const box = await first.boundingBox();
  await page.mouse.move(box!.x + box!.width - 2, box!.y + box!.height / 2);
  await page.mouse.down();
  await page.mouse.move(box!.x + box!.width - 2 + 56, box!.y + box!.height / 2, { steps: 8 });
  await page.mouse.up();

  await expect
    .poll(async () => (await second.boundingBox())!.x)
    .toBeGreaterThan(before!.x);
  await expect(second).toHaveAttribute("data-state", "critical");
  await expect(first).toHaveAttribute("data-state", "critical");
});
```

`signIn`, `newTeam` and `newTicket` are illustrative names — read `e2e/support.ts` and
use whatever it actually exports, extending it if a ticket cannot yet be created with
dates. `56` is two day-columns at `PX_PER_DAY.day = 28`; import the constant rather than
repeating the number if the suite can reach it.

Drive it through the interface, not the API — the point of this scenario is that the
whole stack agrees, and seeding over HTTP would skip the half most likely to be wrong.

Query by role and accessible name. `follow-ups.md` already holds it against this suite
that it keys on private CSS classes — `.row`, `.status` — so a new file must not add
more. The bars need accessible names for this: give each bar
`role="button"` and `aria-label={`${identifier}: ${title}`}`, which Task 3 should have
provided; add it there if it is missing rather than reaching for a class here.

- [ ] **Step 2: Run it**

```bash
docker compose up -d --build
pnpm test:e2e -- 12-timeline
```

Expected: PASS. Report the run honestly — this branch's part 1 never started the compose
stack once, so this is the first end-to-end evidence that any of it works together.

- [ ] **Step 3: Commit**

```bash
git add e2e
git commit -m "test(e2e): a dependency moves the ticket behind it"
```

---

## Self-Review

**Spec coverage.** Everything in the spec's *The view* and *Keyboard* sections maps to
a task: the file split → Task 3; arrows including stubs → Task 4; the tray → Task 5;
the two interaction traps → Task 6; the link picker and `d` → Tasks 7 and 8; the
registry's `mode` and the help grouping → Task 8; the Playwright scenario → Task 9.

**One spec requirement is deliberately not implemented:** the local cascade during a
drag. The reasoning is at the top of this plan, and the consequence — successors move
on drop rather than under the cursor — is the visible difference.

**Two things this plan knows it does not answer.** An out-of-scope arrow cannot name the
ticket at its other end, because `TimelineDependency` carries ids only; naming it is an
API change. And the timeline is refetched wholesale on every ticket event, because
`KansoEvent` carries no ids — `follow-ups.md` already records that. Both are noted where
they bite rather than worked around.
