/**
 * Actions whose body only the page on screen can supply.
 *
 * Six gestures were hardcoded in four `keydown` handlers because `ActionContext` cannot
 * carry them: marking the inbox read needs an inbox mutation, the four triage rulings need
 * the queue's cursor and a similarity list, extending a saved view's selection needs that
 * selection, and opening a ticket in its own page needs the router. Every one of them was
 * recorded in a branch report as "a gap in the context".
 *
 * The gap is real and widening the context is the wrong way to close it. `ActionContext`
 * is what a registry action may *act on* — the loaded rows, the cursor, the mutations
 * every screen shares — and it is handed to thirty-odd actions, every one of which would
 * then be able to reach an inbox mutation that means nothing to it. `favourites.ts` and
 * `organise.ts` already read `window.location` rather than take a router for exactly this
 * reason: the context stays the ticket list's world.
 *
 * So the page claims the action instead. `usePageActions` (in `components/shell/
 * use-shell-keys.ts`) registers a body on mount and withdraws it on the way out; the
 * action's `run` looks the body up here, and its `when` is "is anybody able to do this",
 * which is true on exactly one route and false everywhere else. Three things fall out:
 *
 *  - the key is in the registry, so `?` lists it and §6.5 can remap it;
 *  - the palette can run it, which none of the six could be before — `⇧e` was reachable
 *    only by pressing `⇧e` on the one screen that read it;
 *  - `when` stops being a `window.location.pathname` test. "The page that can do this is
 *    mounted" is the question those tests were approximating, and it is now asked
 *    directly.
 *
 * A module-level map, and read at event time rather than closed over — the same argument
 * `PageShell.onEscape` makes for itself: a page could instead race the shell with its own
 * `window` listener, but registration order is not a contract anybody can rely on, and
 * `saved-view.tsx` re-registered its listener on every render. Read out of a map when the
 * key is pressed, order stops existing as a question.
 *
 * One page is mounted at a time, so an id is claimed once. A second claim on a live id
 * overwrites and is reported by [claim]'s own guard, because two pages answering one key
 * is the doubled-dispatch bug this slice exists to remove, one level up.
 */
const CLAIMS = new Map<string, () => void>();

/**
 * Registers [run] as what [id] does while the caller is on screen, and answers with the
 * withdrawal. The caller is an effect, so the withdrawal is its cleanup.
 */
export function claim(id: string, run: () => void): () => void {
  CLAIMS.set(id, run);
  return () => {
    // Only if it is still ours. Two mounts of one page overlap during a route change —
    // React mounts the next before the previous unmounts — and a blind `delete` in the
    // outgoing cleanup would take the incoming page's claim with it.
    if (CLAIMS.get(id) === run) CLAIMS.delete(id);
  };
}

/**
 * Whether somebody on screen can perform [id]. This is `when` for a claimed action: with
 * nobody claiming it the key is inert and the palette does not offer it, which is the
 * honest answer — there is nothing there to mark read.
 */
export function claimed(id: string): boolean {
  return CLAIMS.has(id);
}

/** Runs the claim, or nothing. `when` has already asked, and a race is not worth a throw. */
export function runClaim(id: string): void {
  CLAIMS.get(id)?.();
}

/** Test-only: the map outlives a file under `environment: "node"`, which shares one realm. */
export function clearClaims(): void {
  CLAIMS.clear();
}
