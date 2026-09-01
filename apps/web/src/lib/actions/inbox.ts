import type { Action } from "./types";

/**
 * Slice D — 14, 15 and 24.
 *
 * One action, and it is worth explaining why only one. This registry gives an action
 * exactly what `ActionContext` carries, and what it carries is the ticket list's world:
 * the scope, the loaded rows, the overlays, `patchTicket`. It has no router and no inbox
 * mutation, so `⇧e` (mark everything read) and following a row to its ticket cannot be
 * written here — those live in `app/inbox/page.tsx`'s own key handler, which is recorded
 * in the branch report as a gap in the context rather than papered over with a store.
 *
 * The import is the exception: `openDialog` is in the context, and `{ kind: "importMap" }`
 * is a value slice 0 pre-cut into `store/ui.ts` for exactly this. It is therefore
 * reachable from ⌘K anywhere — as soon as something renders it. `app/inbox/page.tsx`
 * does; `app/page.tsx` is read-only to this branch and needs the one line reported.
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
];
