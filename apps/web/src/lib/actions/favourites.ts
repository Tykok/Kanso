import type { FavouriteTarget } from "../api";
import type { Action, ActionContext } from "./types";

/**
 * KAN-10 — the Favourites section at the top of the sidebar.
 *
 * **One action, not two.** The row menus draw the other reversible gesture as a pair —
 * `team.archive` and `team.unarchive`, each with its own `when` — and that shape is wrong
 * for a *key*. `resolveShortcut` answers with one action per key before `when` is ever
 * consulted, so the half of a pair that did not hold the key would be unreachable from
 * the keyboard exactly when it was the applicable half, which is every second press. So
 * `s` is one toggle, and the direction is said where a reader is actually looking: the
 * star button on each pinned row, in its `aria-label` and its `title`.
 *
 * **`s`, for the star it draws.** The word would give `f`, which was taken twice over
 * when this was written — `f` was `organise.sortBy` and `F` was `organise.addFilter`, both
 * in the shared bucket, so `indexActions` would have refused a second claim at module
 * load. §6.4 respelled those two as `Mod+o` and `Mod+f` and handed `f` back, and `s` stays
 * anyway: the thing this pass protects is a reader's muscle memory, and moving a key that
 * works to a marginally better mnemonic spends exactly the goodwill the rename was for.
 * `s` is also not a bad one — a favourite *is* drawn as a star.
 */

/**
 * What "here" is, for the purpose of pinning it.
 *
 * Three questions in a fixed order, and the order is the whole of it.
 *
 * [ActionContext.favourite] first, because a caller that names its target is never
 * guessing: a sidebar row's menu is about *that* row whatever page it is opened on.
 *
 * Then the route, then the scope, because a saved view and a document are both opened with
 * a scope still selected behind them — reading the scope on those two routes would pin the
 * team in the sidebar and call it pinning the view.
 *
 * Read off `window.location` rather than from a router, the same way `organise.ts` decides
 * where its keys apply: this module is imported by a test suite running under
 * `environment: "node"`, and by the registry, which is a module and has no hooks.
 *
 * `undefined` means there is nothing here to pin — the unscoped list, or the index of
 * either route, which names no record. The key then stays inert, because `when` is the
 * same question.
 */
export function favouriteTarget(ctx: ActionContext): FavouriteTarget | undefined {
  if (ctx.favourite) return ctx.favourite;

  const segments =
    typeof window === "undefined" ? [] : window.location.pathname.split("/").filter(Boolean);

  if (segments.length === 2 && segments[0] === "views") return { kind: "view", id: segments[1] };
  if (segments.length === 2 && segments[0] === "docs") return { kind: "doc", id: segments[1] };

  if (ctx.scope.kind === "team") return { kind: "team", id: ctx.scope.id };
  if (ctx.scope.kind === "project") return { kind: "project", id: ctx.scope.id };
  return undefined;
}

export const favouriteActions: readonly Action[] = [
  {
    id: "favourite.toggle",
    /**
     * Names the thing, not the direction, because one static string has to be true of
     * both halves of a toggle. The palette says "Favourite" whichever way the press will
     * go; the row's own star says which, and it is the one on screen while somebody is
     * deciding.
     */
    label: "Favourite",
    defaultKeys: ["s"],
    group: "view",
    when: (ctx) => favouriteTarget(ctx) !== undefined,
    run: (ctx) => {
      const target = favouriteTarget(ctx);
      if (target) ctx.toggleFavourite(target);
    },
  },
];
