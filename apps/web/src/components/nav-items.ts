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
 * in the same commit that makes the screen real. Nothing else about this file is a slice's
 * to change.
 *
 * "All tickets" is deliberately absent: it selects a scope rather than navigating, so it
 * has no href to put here and stays where it is.
 */
export type NavItem = {
  id: string;
  label: string;
  href: string;
  /** False while the route is still slice 0's placeholder. Its own branch flips it. */
  live: boolean;
  /**
   * Which count the row carries, for the rows that carry one. A name rather than a
   * number: the count comes from a query the owning slice adds, and slice 0 has no
   * business fetching a badge for a screen that does not exist yet.
   */
  badge?: "inbox" | "triage" | "docs" | "trash";
};

export const NAV_ITEMS: readonly NavItem[] = [
  { id: "inbox", label: "Inbox", href: "/inbox", live: false, badge: "inbox" },
  { id: "triage", label: "Triage", href: "/triage", live: false, badge: "triage" },
  { id: "cycle", label: "Cycle", href: "/cycles/current", live: false },
  { id: "views", label: "Saved views", href: "/views", live: false },
  { id: "workload", label: "Workload", href: "/workload", live: false },
  { id: "docs", label: "Documents", href: "/docs", live: true, badge: "docs" },
  { id: "trash", label: "Trash", href: "/trash", live: false, badge: "trash" },
];
