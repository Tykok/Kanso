import { describe, expect, it } from "vitest";
import { TICKET_STATUSES, type Ticket, type TicketPriority, type TicketStatus } from "@/lib/api";
import { boardColumns, boardMove, cardLabel, deltaTo, locateCard } from "./columns";

/** Only the fields the board reads; the rest of `Ticket` is noise in these assertions. */
function ticket(
  identifier: string,
  status: TicketStatus,
  extra: Partial<Ticket> = {},
): Ticket {
  return {
    id: identifier.toLowerCase(),
    identifier,
    number: Number(identifier.split("-")[1]),
    teamId: "team",
    title: `Title of ${identifier}`,
    status,
    priority: "none",
    assigneeIds: [],
    docIds: [],
    archived: false,
    mirror: { state: "disabled" },
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    ...extra,
  };
}

describe("boardColumns", () => {
  /**
   * All six, always, in `TICKET_STATUSES` order — which is also the order `1`–`6` moves
   * a card into. A board that drops its empty columns has no `Done` to drag onto until
   * something is already done, and the six keys would stop lining up with the six
   * columns the moment one emptied.
   */
  it("draws one column per status, in the order the number keys move a card", () => {
    const columns = boardColumns([ticket("KAN-1", "done")]);
    expect(columns.map((column) => column.status)).toEqual([...TICKET_STATUSES]);
    expect(columns.map((column) => column.tickets.length)).toEqual([0, 0, 0, 0, 1, 0]);
  });

  it("keeps the order the server sent inside a column", () => {
    const columns = boardColumns([
      ticket("KAN-3", "todo"),
      ticket("KAN-1", "todo"),
      ticket("KAN-2", "todo"),
    ]);
    const todo = columns.find((column) => column.status === "todo");
    expect(todo?.tickets.map((row) => row.identifier)).toEqual(["KAN-3", "KAN-1", "KAN-2"]);
  });
});

describe("boardMove", () => {
  // Two columns with different depths, and one empty between them, which is where every
  // interesting case lives.
  const columns = boardColumns([
    ticket("KAN-1", "backlog"),
    ticket("KAN-2", "backlog"),
    ticket("KAN-3", "backlog"),
    ticket("KAN-7", "in_progress"),
  ]);

  it("steps down and up inside one column", () => {
    expect(boardMove(columns, "kan-1", "down")).toBe("kan-2");
    expect(boardMove(columns, "kan-2", "up")).toBe("kan-1");
  });

  /** A cursor that wraps is a lost place, the same rule `zoomBy` already follows. */
  it("stops at the ends rather than wrapping", () => {
    expect(boardMove(columns, "kan-1", "up")).toBeUndefined();
    expect(boardMove(columns, "kan-3", "down")).toBeUndefined();
  });

  /**
   * `todo` sits between `backlog` and `in_progress` and holds nothing. Landing on it
   * would leave `l` looking broken — the cursor cannot be shown in a column with no
   * cards — so the empty column is passed over rather than stopped in.
   */
  it("passes over an empty column instead of stopping in it", () => {
    expect(boardMove(columns, "kan-1", "right")).toBe("kan-7");
    expect(boardMove(columns, "kan-7", "left")).toBe("kan-1");
  });

  /** Row three of a column of three, moving to a column of one: clamped, not lost. */
  it("clamps the row when the next column is shorter", () => {
    expect(boardMove(columns, "kan-3", "right")).toBe("kan-7");
  });

  it("answers nothing at the edges of the board and for a card it cannot find", () => {
    expect(boardMove(columns, "kan-7", "right")).toBeUndefined();
    expect(boardMove(columns, "kan-1", "left")).toBeUndefined();
    expect(boardMove(columns, undefined, "down")).toBeUndefined();
    expect(boardMove(columns, "nobody", "down")).toBeUndefined();
  });
});

describe("deltaTo", () => {
  /**
   * The board's cursor moves through `ActionContext.move`, which takes a step along the
   * *list's* order — the only way an action has to change the selection. Board order and
   * list order are different, so a board key computes the step that lands on the card it
   * actually means.
   */
  const rows = [ticket("KAN-1", "backlog"), ticket("KAN-2", "todo"), ticket("KAN-3", "done")];

  it("is the step from one row to another in the list's own order", () => {
    expect(deltaTo(rows, "kan-1", "kan-3")).toBe(2);
    expect(deltaTo(rows, "kan-3", "kan-1")).toBe(-2);
    expect(deltaTo(rows, "kan-2", "kan-2")).toBe(0);
  });

  it("is zero when either end is not in the list, so no key moves the cursor blindly", () => {
    expect(deltaTo(rows, "kan-1", "nobody")).toBe(0);
    expect(deltaTo(rows, "nobody", "kan-1")).toBe(0);
    expect(deltaTo(rows, undefined, "kan-1")).toBe(0);
  });
});

describe("cardLabel", () => {
  /**
   * A card is 12px of title over a 10px identifier and a glyph; read aloud in that
   * order it is three fragments with no sentence. The name says who, what state, how
   * urgent, and then the title — everything the eye takes from the card's position and
   * colour, which a screen reader gets from neither.
   */
  it("says everything the column and the colour say to the eye", () => {
    expect(cardLabel(ticket("KAN-142", "in_progress", { priority: "urgent" as TicketPriority }))).toBe(
      "KAN-142, In progress, Urgent: Title of KAN-142",
    );
  });

  it("leaves out a priority nobody set rather than saying 'No priority'", () => {
    expect(cardLabel(ticket("KAN-130", "backlog"))).toBe("KAN-130, Backlog: Title of KAN-130");
  });
});

describe("locateCard", () => {
  const columns = boardColumns([
    ticket("KAN-1", "backlog"),
    ticket("KAN-2", "backlog"),
    ticket("KAN-3", "in_progress"),
  ]);

  /**
   * Where the board's scroll-into-view starts. A virtualised column has no element for a
   * card below the fold, so `j` landing on one can only be followed by asking the column
   * which index it is — never by looking the element up in the DOM, which is what the
   * card used to do for itself.
   */
  it("says which column holds a card and how far down it is", () => {
    expect(locateCard(columns, "kan-1")).toEqual({ column: 0, row: 0 });
    expect(locateCard(columns, "kan-2")).toEqual({ column: 0, row: 1 });
    expect(locateCard(columns, "kan-3")).toEqual({ column: 2, row: 0 });
  });

  it("still answers for a card far past the bottom of its column", () => {
    const many = boardColumns(
      Array.from({ length: 400 }, (_, at) => ticket(`KAN-${at + 1}`, "todo")),
    );
    expect(locateCard(many, "kan-400")).toEqual({ column: 1, row: 399 });
  });

  it("is undefined for a card on no column, and for no card at all", () => {
    expect(locateCard(columns, "nobody")).toBeUndefined();
    expect(locateCard(columns, undefined)).toBeUndefined();
  });
});

describe("cardLabel, for a ticket no team has claimed", () => {
  /**
   * The card reads out the identifier first, and a ticket with none would have opened the
   * sentence with the word "null" — worse than silence, because a screen reader says it.
   * The badge the sighted reader sees is the same fact, so the two agree.
   */
  it("opens with the fact instead of with a name it does not have", () => {
    // Absent, not null: the server omits a null field, so this is the shape that arrives.
    const draft = ticket("KAN-1", "todo", {
      identifier: undefined,
      number: undefined,
      teamId: undefined,
    });

    expect(cardLabel(draft)).toBe("No team, Todo: Title of KAN-1");
  });
});
