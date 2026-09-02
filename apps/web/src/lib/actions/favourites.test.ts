import { afterEach, describe, expect, it, vi } from "vitest";
import { ACTIONS, type ActionContext, type ShortcutMode } from "./index";
import { DEFAULT_MERGE, resolveShortcut } from "../shortcuts";

const resolve = (chord: string, mode: ShortcutMode) =>
  resolveShortcut(chord, mode, DEFAULT_MERGE.index);
import { favouriteActions, favouriteTarget } from "./favourites";

/** Only what the one favourite action reads; everything else throws if it is reached. */
function context(overrides: Partial<ActionContext> = {}): ActionContext {
  const unreachable = () => {
    throw new Error("The favourite action reached a part of the context it has no business in");
  };
  return {
    scope: { kind: "all" },
    teams: [],
    projects: [],
    tickets: [],
    canConfigure: true,
    canWrite: true,
    view: "list",
    zoom: "day",
    dependencies: [],
    open: unreachable,
    close: unreachable,
    openDialog: unreachable,
    setScope: unreachable,
    setZoom: unreachable,
    move: unreachable,
    focusFilter: unreachable,
    startRename: unreachable,
    patchTicket: unreachable,
    deleteTicket: unreachable,
    unarchive: unreachable,
    recentre: unreachable,
    startLink: unreachable,
    startUnlink: unreachable,
    toggleFavourite: vi.fn(),
    logout: unreachable,
    ...overrides,
  };
}

/**
 * The registry is a module and `favouriteTarget` reads the route the same way
 * `organise.ts` does, so the route is a global the test has to set. Restored afterwards:
 * `environment: "node"` shares one `globalThis` across every file in the run.
 */
const original = globalThis.window;
function at(pathname: string) {
  (globalThis as { window?: unknown }).window = { location: { pathname } };
}
afterEach(() => {
  if (original === undefined) delete (globalThis as { window?: unknown }).window;
  else (globalThis as { window?: unknown }).window = original;
});

const toggle = () => {
  const found = favouriteActions.find((action) => action.id === "favourite.toggle");
  if (!found) throw new Error("No favourite.toggle action");
  return found;
};

describe("the key the favourite gesture answers", () => {
  /**
   * The whole reason `s` and not `f`: when this was written `f` was `organise.sortBy` and
   * `F` was `organise.addFilter`, both in the shared bucket, and `indexActions` throws at
   * module load on a second claim. Importing this file at all is most of the proof that
   * `s` was free; these say which action it now belongs to, in every mode, so that a later
   * slice claiming it fails here with a sentence rather than by quietly winning the
   * bucket.
   */
  it("is `s`, in every mode, and it is the toggle", () => {
    expect(resolve("s", "list")?.id).toBe("favourite.toggle");
    expect(resolve("s", "board")?.id).toBe("favourite.toggle");
    expect(resolve("s", "timeline")?.id).toBe("favourite.toggle");
    expect(resolve("s", "savedView")?.id).toBe("favourite.toggle");
    expect(resolve("s", "triage")?.id).toBe("favourite.toggle");
  });

  /**
   * §6.4 respelled the two keys `s` was chosen around — `Mod+o` orders and `Mod+f` filters
   * now — so `f` and `F` reach nothing at all, and `s` stayed put anyway. A key that works
   * is not moved for a better mnemonic: muscle memory is the thing this pass protects.
   */
  it("kept its key when the two that crowded it gave theirs up", () => {
    expect(resolve("f", "list")).toBeUndefined();
    expect(resolve("F", "list")).toBeUndefined();
    expect(resolve("Mod+o", "savedView")?.id).toBe("organise.sortBy");
    expect(resolve("Mod+f", "list")?.id).toBe("organise.addFilter");
  });

  it("is in the composed registry, so the palette lists it", () => {
    expect(ACTIONS.some((action) => action.id === "favourite.toggle")).toBe(true);
  });
});

describe("what `s` is about, here", () => {
  it("is the team or project the sidebar is scoped to", () => {
    at("/");
    expect(favouriteTarget(context({ scope: { kind: "team", id: "team-core" } }))).toEqual({
      kind: "team",
      id: "team-core",
    });
    expect(favouriteTarget(context({ scope: { kind: "project", id: "p-1" } }))).toEqual({
      kind: "project",
      id: "p-1",
    });
  });

  /**
   * The route wins over the scope, and it has to: a saved view and a document are both
   * opened *with* a scope still selected behind them, so reading the scope on those two
   * routes would pin the team in the sidebar and call it pinning the view.
   */
  it("is the saved view or the document when one is open", () => {
    at("/views/v-7");
    expect(favouriteTarget(context({ scope: { kind: "team", id: "team-core" } }))).toEqual({
      kind: "view",
      id: "v-7",
    });

    at("/docs/d-3");
    expect(favouriteTarget(context({ scope: { kind: "team", id: "team-core" } }))).toEqual({
      kind: "doc",
      id: "d-3",
    });
  });

  /**
   * The sidebar's own rows, which are the one caller whose ctx names something the page is
   * not showing. Without this the `⋯` menu on a team row, opened while a saved view is on
   * screen, would pin the view it is sitting in front of.
   */
  it("is whatever a row said it was, over both the route and the scope", () => {
    at("/views/v-7");
    const ctx = context({
      scope: { kind: "team", id: "team-core" },
      favourite: { kind: "project", id: "p-9" },
    });
    expect(favouriteTarget(ctx)).toEqual({ kind: "project", id: "p-9" });
  });

  it("is nothing on the index of either, which names no record", () => {
    at("/views");
    expect(favouriteTarget(context())).toBeUndefined();
    at("/docs");
    expect(favouriteTarget(context())).toBeUndefined();
  });

  it("is nothing at all when the list is unscoped, and the key stays inert", () => {
    at("/");
    const ctx = context({ scope: { kind: "all" } });
    expect(favouriteTarget(ctx)).toBeUndefined();
    expect(toggle().when(ctx)).toBe(false);
  });
});

describe("pressing it", () => {
  it("hands the target to the one gesture the context carries", () => {
    at("/");
    const toggleFavourite = vi.fn();
    const ctx = context({ scope: { kind: "project", id: "p-1" }, toggleFavourite });

    expect(toggle().when(ctx)).toBe(true);
    toggle().run(ctx);

    expect(toggleFavourite).toHaveBeenCalledWith({ kind: "project", id: "p-1" });
  });

  /**
   * One action rather than the `team.archive`/`team.unarchive` pair the row menus use for
   * the other reversible gesture. `resolveShortcut` answers with one action per key
   * *before* `when` is consulted, so the half of a pair that did not hold `s` would be
   * unreachable from the keyboard exactly when it was the applicable half — which is
   * every second press.
   */
  it("is one action that flips, not two that each do half", () => {
    expect(favouriteActions).toHaveLength(1);
  });
});
