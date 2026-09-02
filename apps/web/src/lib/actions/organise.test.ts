import { afterEach, describe, expect, it, vi } from "vitest";
import { permits, type ActionContext } from "./index";
import { organiseActions } from "./organise";

/** Only what the filter key reads; anything else throws if it is reached. */
function context(overrides: Partial<ActionContext> = {}): ActionContext {
  const unreachable = () => {
    throw new Error("The filter action reached a part of the context it has no business in");
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
    openDialog: vi.fn(),
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
    toggleFavourite: unreachable,
    logout: unreachable,
    ...overrides,
  };
}

/**
 * The registry is a module and these actions read the route off `window`, so the route is
 * a global the test has to set — the same arrangement `favourites.test.ts` documents.
 * Restored afterwards: `environment: "node"` shares one `globalThis` across the whole run.
 */
const original = globalThis.window;
function at(pathname: string) {
  (globalThis as { window?: unknown }).window = { location: { pathname } };
}
afterEach(() => {
  if (original === undefined) delete (globalThis as { window?: unknown }).window;
  else (globalThis as { window?: unknown }).window = original;
});

const addFilter = () => {
  const found = organiseActions.find((action) => action.id === "organise.addFilter");
  if (!found) throw new Error("No organise.addFilter action");
  return found;
};

/**
 * `Mod+f` opening a dialog that nothing draws is not a dead key — it is a dead *keyboard*.
 * The dispatcher stands down entirely while `dialog.kind !== "none"`, so every chart key
 * goes with it, silently, until Escape closes something that had never appeared. (It was
 * `F` when this was written; §6.4 respelled the chord and changed nothing about the trap.)
 *
 * These four cases are the boundary in both directions. The two that must hold are as
 * load-bearing as the one that must not: a filter refused on the list, or on a saved view
 * because the store still remembered the chart, would be the same bug wearing the other
 * sign.
 */
describe("the filter key, and the one drawing that cannot draw it", () => {
  it("is offered on the list", () => {
    at("/");
    expect(permits(addFilter(), context({ view: "list" }))).toBe(true);
  });

  it("is offered on the board, which draws the same rows in columns", () => {
    at("/");
    expect(permits(addFilter(), context({ view: "board" }))).toBe(true);
  });

  it("is refused on the timeline, where nothing mounts the dialog it opens", () => {
    at("/");
    expect(permits(addFilter(), context({ view: "timeline" }))).toBe(false);
  });

  /**
   * `view` is one store value shared by every route, so a reader who left `/` on the
   * chart arrives at a saved view still carrying `timeline`. The saved view has no chart
   * and mounts its own composer, so the drawing is not its question at all.
   */
  it("is offered on a saved view even when the store still holds the chart", () => {
    at("/views/v1");
    expect(permits(addFilter(), context({ view: "timeline" }))).toBe(true);
  });

  it("is not offered anywhere else", () => {
    at("/trash");
    expect(permits(addFilter(), context({ view: "list" }))).toBe(false);
  });
});
