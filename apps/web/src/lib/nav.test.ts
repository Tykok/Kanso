import { describe, expect, it } from "vitest";
import { isNavRow, NAV_ITEMS } from "@/components/nav-items";
import type { Scope } from "@/store/ui";
import {
  breadcrumbOf,
  currentSelection,
  isCurrentItem,
  isCurrentScope,
  navHref,
  routeOf,
} from "./nav";

/**
 * The matrix, which is the whole point of the slice.
 *
 * The reported bug is "je peux avoir le focus sur All Tickets MAIS AUSSI sur les autres
 * onglets" — two rows lit at once. That is not something a component test can catch
 * reliably, because it only shows up on a *pair* of routes and scopes nobody thought to
 * open together. So the claim is made over every combination instead: every route the
 * shell wraps, crossed with every shape a scope can take, and the count of lit rows is
 * asserted rather than eyeballed.
 *
 * `lit` below is deliberately the same expression the three row components use — nothing
 * is re-derived here. If a component ever computes its own answer again, this test goes
 * on passing and the bug comes back, which is why `sidebar.tsx`, `sidebar-tree.tsx` and
 * `favourites.tsx` all take their `current` from these two functions and hold no rule of
 * their own.
 */

/** The teams and projects the column is drawing, in the two shapes a row can take. */
const TEAM_ROWS: Scope[] = [
  { kind: "team", id: "team-core" },
  { kind: "team", id: "team-infra" },
];
const PROJECT_ROWS: Scope[] = [
  { kind: "project", id: "project-onboarding" },
  { kind: "project", id: "project-sync" },
];

/** Every scope row in the column: `All tickets`, then the tree. */
const SCOPE_ROWS: Scope[] = [{ kind: "all" }, ...TEAM_ROWS, ...PROJECT_ROWS];

/** Every scope the store can hold, including the two that name nothing on screen. */
const SCOPES: Scope[] = [
  ...SCOPE_ROWS,
  // A scope pointing at a team or a project the column is not drawing — archived, or
  // deleted in another tab. It must light nothing, and must not light "All tickets"
  // either: absent is not the same answer as "everything".
  { kind: "team", id: "team-gone" },
  { kind: "project", id: "project-gone" },
];

/**
 * Every route inside `app/(app)/`, spelled as a reader would reach it.
 *
 * `sidebar` says whether the column has a row for it — which is what makes the
 * difference between "exactly one row is lit" and "no row is lit", and the second is a
 * legitimate answer: a ticket page and the settings are somewhere you went, not a
 * destination the column offers.
 */
const ROUTES: { path: string; sidebar: boolean }[] = [
  { path: "/", sidebar: true },
  // `/me` gained a row with §5's screen and `/inbox` lost one to §4's bell — the two
  // halves of the same edit to `NAV_ITEMS`, and the reason this column is `sidebar` and
  // not `live`.
  { path: "/me", sidebar: true },
  { path: "/inbox", sidebar: false },
  { path: "/triage", sidebar: true },
  { path: "/cycles/current", sidebar: true },
  { path: "/cycles/24", sidebar: true },
  { path: "/views", sidebar: true },
  { path: "/views/view-1", sidebar: true },
  { path: "/workload", sidebar: true },
  { path: "/docs", sidebar: true },
  { path: "/docs/doc-1", sidebar: true },
  { path: "/trash", sidebar: true },
  { path: "/settings", sidebar: false },
  { path: "/p/project-onboarding", sidebar: false },
  { path: "/t/KAN-142", sidebar: false },
];

/** How many rows in the column draw themselves as current, for this route and scope. */
function litRows(pathname: string, scope: Scope): string[] {
  const selection = currentSelection(pathname, scope);
  return [
    ...SCOPE_ROWS.filter((row) => isCurrentScope(row, selection)).map((row) =>
      row.kind === "all" ? "All tickets" : `${row.kind}:${row.id}`,
    ),
    // `isNavRow` and not the whole list: `NAV_ITEMS` is the catalogue of route *names*
    // as well as of rows, so `/inbox` is still in it — as a name for the breadcrumb —
    // while the column draws no row for it. Counting it here would be counting a row
    // nobody can see.
    ...NAV_ITEMS.filter(isNavRow)
      .filter((item) => isCurrentItem(item, selection))
      .map((item) => item.id),
  ];
}

describe("currentSelection", () => {
  it("never lights two rows, on any route, under any scope", () => {
    for (const route of ROUTES) {
      for (const scope of SCOPES) {
        const lit = litRows(route.path, scope);
        expect(lit.length, `${route.path} under ${JSON.stringify(scope)} lit ${lit.join(", ")}`).toBeLessThanOrEqual(1);
      }
    }
  });

  it("lights exactly one row on every route the column offers", () => {
    for (const route of ROUTES.filter((each) => each.sidebar)) {
      for (const scope of SCOPES) {
        // A scope naming a row the column is not drawing is the one exception, and only
        // at `/`: the list is showing a team that has no row, so no row is its.
        const drawn = SCOPE_ROWS.some((row) => isCurrentScope(row, { kind: "scope", scope }));
        const expected = route.path === "/" && !drawn ? 0 : 1;
        expect(litRows(route.path, scope).length, `${route.path} under ${JSON.stringify(scope)}`).toBe(expected);
      }
    }
  });

  it("lights nothing on a route the column does not offer, whatever the scope", () => {
    // `/t/[key]`, `/p/[id]`, `/settings` and now `/inbox` are somewhere you went — the
    // last of them because the bell is how you get there, not a row. The project page
    // still *sets* the scope — the composer seeds from it — and that must no longer be
    // enough to light the project's row: the reader is not looking at the list.
    for (const route of ROUTES.filter((each) => !each.sidebar)) {
      for (const scope of SCOPES) {
        expect(litRows(route.path, scope), `${route.path} under ${JSON.stringify(scope)}`).toEqual([]);
      }
    }
  });

  it("reads the scope at the root and the route everywhere else", () => {
    expect(currentSelection("/", { kind: "team", id: "team-core" })).toEqual({
      kind: "scope",
      scope: { kind: "team", id: "team-core" },
    });
    // The scope is still a team here, and the answer is the route regardless.
    expect(currentSelection("/trash", { kind: "team", id: "team-core" })).toEqual({
      kind: "view",
      id: "trash",
    });
  });

  it("counts a record and its index as one destination", () => {
    // The reported symptom of the opposite: opening a saved view put the `Saved views`
    // row out, because `pathname === item.href` compared `/views/abc` to `/views`.
    const views = NAV_ITEMS.find((item) => item.id === "views");
    expect(views).toBeDefined();
    expect(isCurrentItem(views!, currentSelection("/views/abc", { kind: "all" }))).toBe(true);

    // And the sidebar's own href for the cycle is `/cycles/current`, which is not the
    // path a reader ends up on once the server has resolved which cycle that is.
    const cycle = NAV_ITEMS.find((item) => item.id === "cycle");
    expect(cycle).toBeDefined();
    expect(isCurrentItem(cycle!, currentSelection("/cycles/24", { kind: "all" }))).toBe(true);
  });

  it("reads a route off its first segment", () => {
    expect(routeOf("/")).toBe("");
    expect(routeOf("/cycles/24")).toBe("cycles");
    expect(routeOf("/t/KAN-142")).toBe("t");
  });
});

describe("navHref", () => {
  const item = (id: string) => {
    const found = NAV_ITEMS.find((each) => each.id === id);
    if (!found) throw new Error(`No nav item "${id}"`);
    return found;
  };

  it("carries the selected team on to the routes that are about one team", () => {
    const scope: Scope = { kind: "team", id: "team-core" };
    expect(navHref(item("cycle"), scope)).toBe("/cycles/current?team=team-core");
    expect(navHref(item("triage"), scope)).toBe("/triage?team=team-core");
    expect(navHref(item("workload"), scope)).toBe("/workload?team=team-core");
    expect(navHref(item("views"), scope)).toBe("/views?team=team-core");
    expect(navHref(item("docs"), scope)).toBe("/docs?team=team-core");
  });

  it("leaves the instance-wide routes alone", () => {
    const scope: Scope = { kind: "team", id: "team-core" };
    expect(navHref(item("trash"), scope)).toBe("/trash");
    expect(navHref(item("inbox"), scope)).toBe("/inbox");
  });

  it("carries nothing from a project or from the whole instance", () => {
    // A project can span teams, so there is no honest team to forward — which is the
    // same reason `useOrganiseTeam` falls through to its fallback on a project scope.
    expect(navHref(item("cycle"), { kind: "project", id: "project-sync" })).toBe("/cycles/current");
    expect(navHref(item("cycle"), { kind: "all" })).toBe("/cycles/current");
  });
});

describe("breadcrumbOf", () => {
  it("says nothing at the root", () => {
    expect(breadcrumbOf("/")).toEqual([]);
  });

  it("names a destination that belongs to nobody in one word", () => {
    expect(breadcrumbOf("/trash")).toEqual(["Trash"]);
    expect(breadcrumbOf("/settings")).toEqual(["Settings"]);
    expect(breadcrumbOf("/inbox")).toEqual(["Inbox"]);
  });

  it("does not claim the ticket list as the parent of the documents", () => {
    // The old header read `Tickets / Documents`, with `Tickets` a link home. `/docs` is
    // not a child of the list, and the crumb invited a click that lost the reader's place.
    expect(breadcrumbOf("/docs")).toEqual(["Documents"]);
    expect(breadcrumbOf("/docs/doc-1", { leaf: "Cycle 22 notes" })).toEqual([
      "Documents",
      "Cycle 22 notes",
    ]);
  });

  it("puts the team first on the four routes that are about one team", () => {
    expect(breadcrumbOf("/cycles/24", { team: "Core", leaf: "Cycle 24" })).toEqual([
      "Core",
      "Cycle 24",
    ]);
    expect(breadcrumbOf("/workload", { team: "Core" })).toEqual(["Core", "Workload"]);
    expect(breadcrumbOf("/triage", { team: "Core" })).toEqual(["Core", "Triage"]);
    expect(breadcrumbOf("/views", { team: "Core" })).toEqual(["Core", "Saved views"]);
    expect(breadcrumbOf("/views/v1", { team: "Core", leaf: "Urgent debt" })).toEqual([
      "Core",
      "Urgent debt",
    ]);
  });

  it("names the record before the page has resolved it", () => {
    // The crumb is drawn on the first paint, before the cycle report or the view has
    // landed. A blank bar that fills in is worse than a word that gets more specific.
    expect(breadcrumbOf("/cycles/24", { team: "Core" })).toEqual(["Core", "Cycle"]);
    expect(breadcrumbOf("/views/v1", { team: "Core" })).toEqual(["Core", "Saved view"]);
  });

  it("walks a ticket down through its team and its project", () => {
    expect(
      breadcrumbOf("/t/KAN-142", { team: "Core", project: "Onboarding", leaf: "KAN-142" }),
    ).toEqual(["Core", "Onboarding", "KAN-142"]);
    // A ticket no project claims skips that crumb rather than drawing an empty one.
    expect(breadcrumbOf("/t/KAN-142", { team: "Core", leaf: "KAN-142" })).toEqual([
      "Core",
      "KAN-142",
    ]);
    expect(breadcrumbOf("/p/p1", { team: "Core", leaf: "Sync engine" })).toEqual([
      "Core",
      "Sync engine",
    ]);
  });

  it("draws no crumb for a route it has never heard of", () => {
    // The shell wraps a fixed set of routes; anything else gets silence rather than a
    // heading invented out of a URL fragment.
    expect(breadcrumbOf("/nowhere")).toEqual([]);
    expect(breadcrumbOf("/nowhere/deeper")).toEqual([]);
  });
});
