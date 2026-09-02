import { NAV_ITEMS, type NavItem } from "@/components/nav-items";
import type { Scope } from "@/store/ui";

/**
 * What is selected in the sidebar — one answer, for every row that asks.
 *
 * The column used to draw its selection from two independent sources. Its "Views" rows
 * compared `pathname` to their own `href`; its "All tickets" row and every team and
 * project row compared `useUi().scope` to their own subject. Neither consulted the other,
 * so `/docs` lit **Documents** *and* **All tickets** at the same time, and clicking a
 * project from `/cycles/current` lit a row on a page that was not drawing it. Two rows
 * lit is not a styling accident; it is two questions wearing one answer.
 *
 * So there is one question now, asked here, and the rule is short enough to state in
 * full: **the route wins, except at the root, where the scope is all there is.** Every
 * row calls [isCurrentItem] or [isCurrentScope] against the same [NavSelection], which
 * makes "exactly one row is lit" a property of this function rather than a coincidence
 * three components have to keep agreeing on. `nav.test.ts` asserts it over every route
 * crossed with every shape a scope can take.
 *
 * Pure, and deliberately so: no hooks, no `window`, no queries. The pathname and the
 * scope are the whole input, which is what lets the matrix be a unit test instead of a
 * browser.
 */
export type NavSelection =
  /** A route: the cycle, triage, the trash, a document. Its id is the route's first segment. */
  | { kind: "view"; id: string }
  /** The list at `/`, showing everything, one team, or one project. */
  | { kind: "scope"; scope: Scope };

/**
 * The first segment of a path — `/cycles/24` → `cycles`, `/` → `""`.
 *
 * The first segment and not the whole path, because a record and its index are the same
 * destination as far as the column is concerned: `/views/abc` lights `Saved views`, and
 * `/cycles/24` lights `Cycle` however the sidebar happens to spell its own href
 * (`/cycles/current`). Comparing whole paths is what made the Views rows go dark the
 * moment a reader opened one of them.
 */
export function routeOf(pathname: string): string {
  return pathname.split("/").filter(Boolean)[0] ?? "";
}

/** The one rule, in two lines. Everything else in this file reads its answer. */
export function currentSelection(pathname: string, scope: Scope): NavSelection {
  const route = routeOf(pathname);
  return route === "" ? { kind: "scope", scope } : { kind: "view", id: route };
}

/**
 * Whether a Views row is the selected one.
 *
 * Note what is *not* here: no comparison against the scope, and no second chance for a
 * team. On any route but `/` the scope is still whatever the reader last picked — it has
 * to be, since sixty-five files read it — and this function ignores it completely. That
 * is the fix.
 */
export function isCurrentItem(item: NavItem, selection: NavSelection): boolean {
  return selection.kind === "view" && selection.id === routeOf(item.href);
}

/**
 * Whether a row that names one *record* is the selected one — a pinned saved view, a
 * pinned document.
 *
 * Record-level, where [isCurrentItem] is section-level, and both are right for their own
 * caller: a pin names `/views/abc` and nothing else, while the `Saved views` row above it
 * names every `/views/…` there is. So the two are lit together when a pinned view is
 * open, which is the same destination named twice rather than two selections — the
 * Favourites section exists precisely to name a destination a second time, higher up.
 *
 * Trivial as an expression and worth a name anyway: this is the fourth place in the app
 * that used to answer "is this row the current one" with a rule of its own, and the point
 * of this module is that there is nowhere else left to write one.
 */
export function isCurrentRecord(pathname: string, href: string): boolean {
  return pathname === href;
}

/**
 * Whether a scope row — `All tickets`, a team, a project — is the selected one.
 *
 * False on every route but `/`, whatever the scope holds. A team row lit while a cycle
 * report is on screen was the second half of the same bug: the row said "you are looking
 * at this team" about a page that was looking at something else.
 */
export function isCurrentScope(target: Scope, selection: NavSelection): boolean {
  return selection.kind === "scope" && sameScope(selection.scope, target);
}

/**
 * Whether two scopes name the same thing.
 *
 * Its own function because two callers need it and neither is a row: the shell tags an
 * error strip with the scope it was reported against, so that changing scope stops the
 * sentence applying rather than leaving a message about a team nobody is looking at.
 *
 * Compared as one string. Two of the three members carry an id and one does not, so
 * comparing them field by field needs a narrowing branch that says nothing — the same
 * trick `keys.tickets` plays on the same value for the same reason.
 */
export function sameScope(a: Scope, b: Scope): boolean {
  return scopeKey(a) === scopeKey(b);
}

const scopeKey = (scope: Scope) => (scope.kind === "all" ? "all" : `${scope.kind}:${scope.id}`);

/**
 * The routes that are *about one team* and take it in the query string.
 *
 * `useOrganiseTeam` resolves the team from `?team=` first, and its four-step fallback is
 * why these links carry it: without it, going from a team's list to that team's cycle
 * kept the subject only until the next page load, after which the fallback silently
 * resolved a different team's cycle with nothing on screen admitting the substitution.
 *
 * `views` is in this list where the spec's prose names only four routes. It belongs:
 * `views-index.tsx` calls `useOrganiseTeam()` exactly as the cycle, the queue and the
 * chart do, so leaving it out would make the saved-views index the one row in the column
 * that resolves a stranger's team after a reload.
 */
const CARRIES_TEAM = new Set(["cycles", "triage", "workload", "views", "docs"]);

/** A view link, with the team it was clicked from riding along. */
export function navHref(item: NavItem, scope: Scope): string {
  if (scope.kind !== "team" || !CARRIES_TEAM.has(routeOf(item.href))) return item.href;
  // `?team=` and not a path segment: the team is context, not the subject. `/cycles/24`
  // names a cycle; which team's cycle it is is the question `useOrganiseTeam` answers.
  return `${item.href}?team=${scope.id}`;
}

/**
 * What each route is called, and what it hangs under.
 *
 * `team` marks the routes that are about one team, so its name is the first crumb.
 * `record` is what a row of that route is called when the page has not supplied a better
 * name yet. `index` marks the one route whose own index page is a crumb a reader can
 * climb to: `/docs` is a real destination above `/docs/[id]`, where `Cycle` above
 * `Cycle 24` would be a link back to the same page.
 *
 * The labels themselves are not written here. They are read off `NAV_ITEMS`, because the
 * row and the crumb name the same destination and two copies of "Saved views" would
 * eventually disagree about it.
 */
const CRUMBS: Record<string, { team?: boolean; project?: boolean; index?: boolean; record?: string }> = {
  me: {},
  inbox: {},
  triage: { team: true },
  cycles: { team: true, record: "Cycle" },
  views: { team: true, record: "Saved view" },
  workload: { team: true },
  docs: { index: true, record: "Document" },
  trash: {},
  settings: {},
  t: { team: true, project: true, record: "Ticket" },
  p: { team: true, record: "Project" },
};

/**
 * The three destinations with no entry in `NAV_ITEMS`, and so no label to borrow.
 *
 * `me` was a fourth until §5 gave it a row: it read "My work" here while the column said
 * "My view", and `indexLabel` below asks `NAV_ITEMS` first — so the crumb was already
 * right and this line was a second name for one destination, waiting to be found by
 * somebody wondering why the two disagreed. `/inbox` deliberately does not join this map
 * even though it is no longer a row: `NAV_ITEMS` keeps its entry as `row: false` so the
 * word `Inbox` has one home, which is the same argument that keeps every other crumb's
 * label out of this file.
 */
const UNROWED: Record<string, string> = {
  settings: "Settings",
  t: "Ticket",
  p: "Project",
};

const indexLabel = (route: string) =>
  NAV_ITEMS.find((item) => routeOf(item.href) === route)?.label ?? UNROWED[route] ?? route;

/**
 * The names only the page knows: the team the screen resolved, the project a ticket is
 * filed in, and the record's own title. Everything else is derived from the route.
 */
export type CrumbNames = { team?: string; project?: string; leaf?: string };

/**
 * The breadcrumb, derived: `Trash`, `Documents`, `Core / Cycle 24`, `Core / Workload`.
 *
 * Four routes used to draw a `Back` link instead, and it was a `<Link href="/">` — so it
 * did not go back, it went home, and on `/docs/[id]` there was not even that. The bar
 * says where you are and the `×` beside it is how you leave; a crumb is not a way out.
 *
 * `/docs` reads `Documents` and never `Tickets / Documents`. The old header claimed the
 * ticket list as its parent, which it is not: a crumb that names a parent the reader
 * cannot climb to is worse than no crumb, because it invites exactly one click and then
 * loses their place.
 *
 * Empty at `/`. Home has nothing above it, and the list already says what it is showing
 * in its own heading — a crumb there would be the same word twice.
 */
export function breadcrumbOf(pathname: string, names: CrumbNames = {}): string[] {
  const segments = pathname.split("/").filter(Boolean);
  const route = segments[0];
  if (route === undefined) return [];

  const spec = CRUMBS[route];
  // A route this file has never heard of gets no crumb rather than a guess. A guess would
  // be a heading in the app's own chrome invented from a URL fragment.
  if (spec === undefined) return [];

  const crumbs: string[] = [];
  if (spec.team && names.team) crumbs.push(names.team);

  // An index page: its own label is the leaf.
  if (segments.length === 1) {
    crumbs.push(indexLabel(route));
    return crumbs;
  }

  if (spec.project && names.project) crumbs.push(names.project);
  if (spec.index) crumbs.push(indexLabel(route));
  crumbs.push(names.leaf ?? spec.record ?? indexLabel(route));
  return crumbs;
}
