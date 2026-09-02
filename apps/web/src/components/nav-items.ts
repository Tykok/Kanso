/**
 * The sidebar's destinations, as a list rather than as markup.
 *
 * Eight of the fourteen screens the design bundle draws and the app does not have are
 * reached from this column, and each belongs to a different branch. Written inline in
 * `sidebar.tsx`, that would be six branches editing one JSX block. Here it is one line
 * each, in a list, which is the cheapest conflict git can be handed.
 *
 * `live` is what keeps a half-built route out of the sidebar: slice 0 commits every row
 * pointing at a placeholder page, and the branch that fills a route flips its own boolean
 * in the same commit that makes the screen real.
 *
 * "All tickets" is deliberately absent: it selects a scope rather than navigating, so it
 * has no href to put here and stays where it is.
 *
 * The `href` is where a row goes and also, through `routeOf`, how `lib/nav.ts` decides
 * which row is lit and what the breadcrumb calls the destination. So the *first segment*
 * of each href is load-bearing beyond the link: `/cycles/current` is what the row points
 * at, and `cycles` is what identifies every page under it.
 */
export type NavItem = {
  id: string;
  label: string;
  href: string;
  /** False while the route is still slice 0's placeholder. Its own branch flips it. */
  live: boolean;
  /**
   * False for a route this list only *names*.
   *
   * `/inbox` is the one, and it is not the same statement as `live`: the screen is
   * finished and reachable, the column simply does not offer it any more — §4 moved it to
   * the bell in the top bar, where an unread count belongs beside the other things that
   * are always true of the session. Two flags rather than one because a reader of this
   * file has to be able to tell "not built yet" from "deliberately not a row", and
   * spelling the second as `live: false` would be a lie about a working screen.
   *
   * The entry stays because this list is the catalogue of route *names*, not only of
   * rows: `lib/nav.ts` reads every breadcrumb's label off it, on the stated grounds that
   * the row and the crumb name one destination and two copies of a word eventually
   * disagree about it. Removing the entry would move `Inbox` into that file's `UNROWED`
   * map and give the word a second home for no gain.
   */
  row?: boolean;
  /**
   * Which count the row carries, for the rows that carry one. A name rather than a
   * number: the count comes from a query the owning slice adds, and slice 0 has no
   * business fetching a badge for a screen that does not exist yet.
   */
  badge?: "triage" | "docs" | "trash";
};

export const NAV_ITEMS: readonly NavItem[] = [
  /*
   * `/me` first: it is the personal home, and a reader's own work comes before the
   * instance's. `sidebar.tsx` draws it above "All tickets" for the same reason.
   *
   * There is deliberately no `progress` row beside it. Main carries one — `/progress`,
   * labelled "My progress", with its own `GET /api/me/progress` — and it overlaps this
   * screen by half. The maintainer's ruling is that `/me` is the home and screen 40
   * becomes its **Progress** tab, with `/progress` kept as a redirect so no existing link
   * breaks. So when main's row arrives in this file it is to be deleted, not merged: two
   * rows for one personal home is the second selection axis §2 exists to remove.
   */
  { id: "me", label: "My view", href: "/me", live: true },
  { id: "inbox", label: "Inbox", href: "/inbox", live: true, row: false },
  { id: "triage", label: "Triage", href: "/triage", live: true, badge: "triage" },
  { id: "cycle", label: "Cycle", href: "/cycles/current", live: true },
  { id: "views", label: "Saved views", href: "/views", live: true },
  { id: "workload", label: "Workload", href: "/workload", live: true },
  { id: "docs", label: "Documents", href: "/docs", live: true, badge: "docs" },
  { id: "trash", label: "Trash", href: "/trash", live: true, badge: "trash" },
];

/**
 * Whether the column draws a row for this item at all.
 *
 * Exported rather than written out at each call site because two callers need the same
 * answer and they are a component and a test: `sidebar.tsx` draws what it returns true
 * for, and `nav.test.ts` counts lit rows over exactly that set. A predicate written twice
 * is how the test would come to be counting a row the column stopped drawing.
 */
export const isNavRow = (item: NavItem): boolean => item.live && item.row !== false;
