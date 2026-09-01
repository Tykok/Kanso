import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Ticket, TicketStatus } from "../api";
import { useBoard } from "@/store/board";
import { resolveShortcut, type ActionContext } from "./index";
import { boardActions, boardKeysAwaitingTheGuard } from "./board";

function ticket(identifier: string, status: TicketStatus): Ticket {
  return {
    id: identifier.toLowerCase(),
    identifier,
    number: Number(identifier.split("-")[1]),
    teamId: "team-core",
    title: `Title of ${identifier}`,
    status,
    priority: "none",
    assigneeIds: [],
    docIds: [],
    archived: false,
    mirror: { state: "disabled" },
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
  };
}

/** Only what the board's five actions read; everything else throws if it is reached. */
function context(overrides: Partial<ActionContext> = {}): ActionContext {
  const unreachable = () => {
    throw new Error("A board action reached a part of the context it has no business in");
  };
  return {
    scope: { kind: "team", id: "team-core" },
    teams: [],
    projects: [],
    tickets: [],
    canConfigure: true,
    view: "board",
    zoom: "day",
    dependencies: [],
    open: vi.fn(),
    close: vi.fn(),
    openDialog: unreachable,
    setScope: unreachable,
    setZoom: unreachable,
    move: vi.fn(),
    focusFilter: unreachable,
    startRename: unreachable,
    patchTicket: vi.fn(),
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

/** Looks in both lists: three of the five are written but not yet dispatched. */
const actionById = (id: string) => {
  const found = [...boardActions, ...boardKeysAwaitingTheGuard].find(
    (action) => action.id === id,
  );
  if (!found) throw new Error(`No board action "${id}"`);
  return found;
};

describe("the board's keys against the shared registry", () => {
  /**
   * `h` and `l` are the two the board may have today: nothing owns them in the shared
   * bucket, so claiming them for `board` shadows nothing. The chart owns its own pair and
   * must keep them, which is the point of asking per mode rather than globally.
   */
  it("claims the column keys on the board without taking them from the chart", () => {
    expect(resolveShortcut("h", "board")?.id).toBe("board.columnLeft");
    expect(resolveShortcut("l", "board")?.id).toBe("board.columnRight");
    expect(resolveShortcut("ArrowLeft", "board")?.id).toBe("board.columnLeft");

    expect(resolveShortcut("h", "timeline")?.id).toBe("timeline.shiftEarlier");
    expect(resolveShortcut("l", "timeline")?.id).toBe("timeline.shiftLater");
    // Nothing owns `h` in the list, and the board taking it must not have changed that.
    expect(resolveShortcut("h", "list")).toBeUndefined();
  });

  /**
   * The three the board answers itself. The registry always allowed it — a `board:` bucket
   * wins over `any:` — and `core.test.ts`'s shadowing guard refused it until the
   * integration pass decided the premise did not hold here: on a board there are no rows
   * to walk, so the shared `j` would move a cursor nothing draws. The guard still refuses
   * by default and now carries these three ids as a written-down exception.
   */
  it("answers j, k and ↵ itself, and leaves the list's own alone", () => {
    expect(resolveShortcut("j", "board")?.id).toBe("board.moveDown");
    expect(resolveShortcut("k", "board")?.id).toBe("board.moveUp");
    expect(resolveShortcut("Enter", "board")?.id).toBe("board.open");

    // The shared entries are untouched: shadowing is per mode, not a reassignment.
    expect(resolveShortcut("j", "list")?.id).toBe("ticket.moveDown");
    expect(resolveShortcut("Enter", "list")?.id).toBe("ticket.open");

    expect(boardActions.map((action) => action.id)).toEqual([
      "board.columnLeft",
      "board.columnRight",
      "board.moveUp",
      "board.moveDown",
      "board.open",
    ]);
  });

  /**
   * `1`–`6` are core's and stay core's whatever happens to that guard: moving a card
   * between columns *is* a status change, and a second implementation of one patch is
   * the thing this split was meant to prevent.
   */
  it("borrows the six status keys rather than re-registering them", () => {
    expect(resolveShortcut("1", "board")?.id).toBe("ticket.status.backlog");
    expect(resolveShortcut("6", "board")?.id).toBe("ticket.status.canceled");
    expect(
      [...boardActions, ...boardKeysAwaitingTheGuard].some((action) =>
        /^[1-6]$/.test(action.shortcut ?? ""),
      ),
    ).toBe(false);
  });
});

describe("stepping the cursor", () => {
  // Board order and list order deliberately disagree: KAN-9 is last in the list and
  // first in the `in_progress` column, which is the case a delta computed from the list
  // alone would get wrong.
  const rows = [
    ticket("KAN-1", "backlog"),
    ticket("KAN-2", "backlog"),
    ticket("KAN-9", "in_progress"),
  ];

  it("asks for the step that lands on the card the direction means", () => {
    const ctx = context({ tickets: rows, selected: rows[0] });
    actionById("board.moveDown").run(ctx);
    // KAN-2 is index 1 and KAN-1 is index 0 in the list: one step.
    expect(ctx.move).toHaveBeenCalledWith(1);
  });

  it("crosses to the next non-empty column by the step the list needs", () => {
    const ctx = context({ tickets: rows, selected: rows[0] });
    actionById("board.columnRight").run(ctx);
    // `todo`, `in_review` and the rest are empty; KAN-9 is two along in the list.
    expect(ctx.move).toHaveBeenCalledWith(2);
  });

  /** Past the edge the key is inert: the cursor stays where it is rather than jumping. */
  it("moves nothing at the edges of the board", () => {
    const top = context({ tickets: rows, selected: rows[0] });
    actionById("board.moveUp").run(top);
    expect(top.move).not.toHaveBeenCalled();

    const right = context({ tickets: rows, selected: rows[2] });
    actionById("board.columnRight").run(right);
    expect(right.move).not.toHaveBeenCalled();
  });
});

describe("opening a card", () => {
  beforeEach(() => useBoard.getState().openHandled());

  /**
   * The action states the intent and stops. Which of the panel and the page `↵` gives is
   * `preferences.openTicket`, which lives in the query cache and needs a hook to read —
   * and this module is imported by the unit suite under `environment: "node"`, where
   * there is neither a router nor a cache to reach for.
   */
  it("records which card was asked for rather than deciding how to show it", () => {
    const rows = [ticket("KAN-1", "backlog")];
    const ctx = context({ tickets: rows, selected: rows[0] });

    actionById("board.open").run(ctx);

    expect(useBoard.getState().requestedOpen).toBe("KAN-1");
    // Emphatically not this: the panel is one of two answers and not the action's to give.
    expect(ctx.open).not.toHaveBeenCalled();
  });

  it("is cleared by the view that acted on it, so the same card can be opened twice", () => {
    useBoard.getState().requestOpen("KAN-1");
    useBoard.getState().openHandled();
    expect(useBoard.getState().requestedOpen).toBeUndefined();
  });
});
