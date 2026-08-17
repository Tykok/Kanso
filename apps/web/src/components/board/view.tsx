"use client";

/**
 * Screen 04, the kanban board. Slice A fills this.
 *
 * The stub exists so that `app/page.tsx` can be frozen for the whole fan-out: the board is
 * a third drawing of the scoped tickets query rather than a route, so it has to be reached
 * from the page every other branch is forbidden to touch. Slice 0 cuts the hole; slice A
 * replaces the body and nothing else.
 *
 * `reportError` is the prop `TimelineView` already takes, so the page's two chart branches
 * read alike rather than each having invented its own way to fail.
 */
export function BoardView({
  reportError,
}: {
  reportError: (message: string | null) => void;
}) {
  void reportError;
  return <div className="empty">Board (04) — slice A, not built yet.</div>;
}
