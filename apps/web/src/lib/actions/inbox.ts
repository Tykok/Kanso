import { claimed, runClaim } from "./claims";
import type { Action } from "./types";

/**
 * Slice D — 14, 15 and 24.
 *
 * This file used to hold one action and a paragraph explaining why `⇧e` could not be the
 * second: "this registry gives an action exactly what `ActionContext` carries, and what it
 * carries is the ticket list's world […] it has no router and no inbox mutation". That was
 * true and is still true. What changed is that an action no longer has to be *runnable*
 * from the context to be in the registry — the page that can run it claims it, and the
 * gap the branch report recorded is closed without the context growing an inbox mutation
 * that thirty other actions would then be able to reach. See `./claims.ts`.
 *
 * The import is the exception it always was: `openDialog` is in the context, and
 * `{ kind: "importMap" }` is a value `store/ui.ts` carries, so it is reachable from the
 * palette anywhere the shell mounts the dialog — which it now does on every route.
 */
export const inboxActions: readonly Action[] = [
  {
    id: "notion.import",
    writes: true,
    label: "Import from Notion…",
    group: "app",
    // Instance configuration, so the same gate every other configuration action uses.
    // A member seeing an import they cannot run is an offer the server would refuse.
    when: (ctx) => ctx.canConfigure,
    run: (ctx) => ctx.openDialog({ kind: "importMap" }),
  },
  /**
   * `⇧e` — and it is `"Shift+e"` rather than the `"E"` the old inbox handler tested,
   * because the shift is a prefix now and not the case of a letter (`./chords.ts`).
   *
   * The shared bucket rather than a mode of its own: nothing else wants `Shift+e`, and the
   * inbox is a route rather than a drawing. `when` is the claim, so the key is inert and
   * the palette silent on every screen with nothing to mark read — which is the same
   * answer the old handler gave by only existing on one page, said once instead of twice.
   *
   * Never gated on the unread count, which the button beside it is not either: that count
   * is up to a minute stale, and a control that is sometimes dead for reasons the reader
   * cannot see is worse than one whose press occasionally changes nothing.
   */
  {
    id: "inbox.markAllRead",
    writes: true,
    label: "Mark everything read",
    defaultKeys: ["Shift+e"],
    group: "app",
    when: () => claimed("inbox.markAllRead"),
    run: () => runClaim("inbox.markAllRead"),
  },
];
