import { boardShape, type Vocabulary } from "@/lib/statuses";
import { describe, expect, it } from "vitest";
import {
  DEFAULT_STATUSES,
  type StatusCategory,
  type Team,
  type TeamStatus,
  type Ticket,
  type TicketPriority,
  type TicketStatus,
} from "@/lib/api";
import { STATUS_CATEGORY, STATUS_LABELS } from "@/lib/status";
import { boardColumns, boardDrop, boardMove, cardLabel, deltaTo, locateCard } from "./columns";

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
    customFields: {},
    // Required on `Ticket` for the reason `customFields` beside it is: the server always
    // sends the key, so a factory that omits it is not a ticket the API can produce.
    pullRequests: [],
    archived: false,
    mirror: { state: "disabled" },
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    ...extra,
  };
}

/**
 * The vocabulary a board is drawn from — the team's catalogue, since `KAN-90`.
 *
 * Written out rather than taken from `DEFAULT_STATUSES`, because that constant is no
 * longer what a board draws: it is what a team is *seeded* with. This fixture is that
 * seed, so every case below says what it always said; `invented` adds a seventh, which is
 * the case none of them could express before.
 */
const seeded: Vocabulary[] = [
  { key: "backlog", label: "Backlog", category: "backlog" },
  { key: "todo", label: "Todo", category: "unstarted" },
  { key: "in_progress", label: "In progress", category: "started" },
  { key: "in_review", label: "In review", category: "started" },
  { key: "done", label: "Done", category: "completed" },
  { key: "canceled", label: "Canceled", category: "canceled" },
];

/** A team that put a word of its own between the backlog and the queue. */
const invented: Vocabulary[] = [
  { key: "boite", label: "Boîte", category: "backlog" },
  { key: "devis", label: "Devis", category: "backlog" },
  { key: "en_cours", label: "En cours", category: "started" },
  { key: "livre", label: "Livré", category: "completed" },
];

/** Two real teams, for the cases that need a `boardShape` rather than a hand-written bucket. */
const asTeam = (id: string, statuses: TeamStatus[]): Team =>
  ({
    id,
    name: `Team ${id}`,
    key: id.toUpperCase(),
    archived: false,
    ticketCount: 0,
    mirror: { state: "pending" },
    createdAt: "2026-09-01T10:00:00Z",
    updatedAt: "2026-09-01T10:00:00Z",
    editable: true,
    statuses,
  }) as Team;

const kansoTeam = asTeam(
  "team",
  DEFAULT_STATUSES.map((key, position) => ({
    key,
    label: STATUS_LABELS[key],
    category: STATUS_CATEGORY[key],
    position,
  })),
);

const atelierTeam = asTeam("atelier", [
  { key: "boite", label: "Boîte", category: "backlog", position: 0 },
  { key: "devis", label: "Devis", category: "backlog", position: 1 },
  { key: "en_cours", label: "En cours", category: "started", position: 2 },
  { key: "livre", label: "Livré", category: "completed", position: 3 },
]);

/** How a board scoped to one team stacks — `boardShape`'s narrow half, spelled out. */
const byStatus = (row: Ticket) => row.status;

/** The five columns a scope spanning teams draws, in `CATEGORY_ORDER`. */
const categories: Vocabulary[] = [
  { key: "backlog", label: "Backlog", category: "backlog" },
  { key: "unstarted", label: "Not started", category: "unstarted" },
  { key: "started", label: "In flight", category: "started" },
  { key: "completed", label: "Done", category: "completed" },
  { key: "canceled", label: "Canceled", category: "canceled" },
];

/**
 * Rows from two teams that share no word — what `boardShape`'s wide half hands over.
 *
 * `atelier` reads `devis` as backlog and `en_cours` as started; `kanso` reads its own
 * six. Spelled as a lookup rather than taken from `boardShape` so this file keeps
 * proving the arithmetic alone: `statuses.test.ts` proves where the readings come from.
 */
const MEANS: Record<string, StatusCategory> = {
  devis: "backlog",
  en_cours: "started",
  todo: "unstarted",
  in_progress: "started",
};

const categoryBucket = (row: Pick<Ticket, "teamId" | "status">) => MEANS[row.status];

const crossTeam = [
  ticket("ATL-1", "devis", { teamId: "atelier" }),
  ticket("KAN-2", "todo"),
  ticket("ATL-3", "en_cours", { teamId: "atelier" }),
  ticket("KAN-4", "in_progress"),
];

describe("boardColumns", () => {
  /**
   * The whole of `KAN-90` on this file: the columns are the team's, not Kanso's.
   *
   * Four of them, in the order the team chose, with a word Kanso has never shipped among
   * them — and `1`–`4` therefore moves a card into exactly those four. Under the old
   * derivation this team would have seen six columns it does not use, none of which its
   * cards could be in.
   */
  it("draws the team's own columns, in the team's own order", () => {
    const columns = boardColumns(invented, [ticket("KAN-1", "devis")], byStatus);

    expect(columns.map((column) => column.status)).toEqual(["boite", "devis", "en_cours", "livre"]);
    expect(columns.map((column) => column.label)).toEqual(["Boîte", "Devis", "En cours", "Livré"]);
    expect(columns.map((column) => column.tickets.length)).toEqual([0, 1, 0, 0]);
  });

  // The header's word and its colour both come from the column, so a component never has
  // to look a team's word up in a table of Kanso's six and get nothing.
  it("carries the word and the meaning with each column", () => {
    const devis = boardColumns(invented, [], byStatus)[1];
    expect(devis.label).toBe("Devis");
    expect(devis.category).toBe("backlog");
  });

  // A card whose status is in no column is not silently dropped from the count: it has
  // nowhere to go, and the board says so by the numbers not adding up rather than by
  // pretending the ticket does not exist.
  it("leaves a card whose status this vocabulary does not have out of every column", () => {
    const columns = boardColumns(invented, [ticket("KAN-9", "done")], byStatus);
    expect(columns.reduce((total, column) => total + column.tickets.length, 0)).toBe(0);
  });

  /**
   * All six, always, in `DEFAULT_STATUSES` order — which is also the order `1`–`6` moves
   * a card into. A board that drops its empty columns has no `Done` to drag onto until
   * something is already done, and the six keys would stop lining up with the six
   * columns the moment one emptied.
   */
  it("draws one column per status, in the order the number keys move a card", () => {
    const columns = boardColumns(seeded, [ticket("KAN-1", "done")], byStatus);
    expect(columns.map((column) => column.status)).toEqual([...DEFAULT_STATUSES]);
    expect(columns.map((column) => column.tickets.length)).toEqual([0, 0, 0, 0, 1, 0]);
  });

  /**
   * The five columns a scope spanning teams draws, and the reason it can draw any.
   *
   * The vocabulary is categories and the rows are two teams' words, so nothing lines up
   * by key — the card is placed by what [bucketOf] says it means. Under the derivation
   * this replaces, `devis` and `livre` were in no column and simply were not on the
   * board.
   */
  it("stacks two teams' words into one set of category columns", () => {
    const columns = boardColumns(categories, crossTeam, categoryBucket);

    expect(columns.map((column) => column.status)).toEqual([
      "backlog",
      "unstarted",
      "started",
      "completed",
      "canceled",
    ]);
    expect(columns.map((column) => column.tickets.map((row) => row.identifier))).toEqual([
      ["ATL-1"],
      ["KAN-2"],
      ["ATL-3", "KAN-4"],
      [],
      [],
    ]);
  });

  it("leaves no card off a board whose columns are categories", () => {
    // The count is the whole assertion: every status has a category, so a category board
    // is the one board on which the sum always matches what was handed to it.
    const columns = boardColumns(categories, crossTeam, categoryBucket);

    expect(columns.reduce((total, column) => total + column.tickets.length, 0)).toBe(
      crossTeam.length,
    );
  });

  it("keeps the order the server sent inside a column", () => {
    const columns = boardColumns(
      seeded,
      [ticket("KAN-3", "todo"), ticket("KAN-1", "todo"), ticket("KAN-2", "todo")],
      byStatus,
    );
    const todo = columns.find((column) => column.status === "todo");
    expect(todo?.tickets.map((row) => row.identifier)).toEqual(["KAN-3", "KAN-1", "KAN-2"]);
  });
});

describe("boardMove", () => {
  // Two columns with different depths, and one empty between them, which is where every
  // interesting case lives.
  const columns = boardColumns(
    seeded,
    [
      ticket("KAN-1", "backlog"),
      ticket("KAN-2", "backlog"),
      ticket("KAN-3", "backlog"),
      ticket("KAN-7", "in_progress"),
    ],
    byStatus,
  );

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
  const columns = boardColumns(
    seeded,
    [ticket("KAN-1", "backlog"), ticket("KAN-2", "backlog"), ticket("KAN-3", "in_progress")],
    byStatus,
  );

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
      seeded,
      Array.from({ length: 400 }, (_, at) => ticket(`KAN-${at + 1}`, "todo")),
      byStatus,
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

describe("boardDrop", () => {
  /** The wide shape, near enough for this file: two teams, one of them with its own words. */
  const wide = boardShape([kansoTeam, atelierTeam], { kind: "all" });
  const narrow = boardShape([kansoTeam, atelierTeam], { kind: "team", id: "atelier" });

  it("writes the card's own team's word for the column it was dropped on", () => {
    // The gesture is one; the key written is the dropped card's team's. This is what a
    // category column means, and there is no other way for it to mean anything.
    expect(boardDrop(wide, ticket("ATL-1", "devis", { teamId: "atelier" }), "completed")).toEqual({
      status: "livre",
    });
    expect(boardDrop(wide, ticket("KAN-2", "todo"), "completed")).toEqual({ status: "done" });
  });

  it("writes the column's key untouched when the board is one team's", () => {
    expect(boardDrop(narrow, ticket("ATL-1", "devis", { teamId: "atelier" }), "livre")).toEqual({
      status: "livre",
    });
  });

  it("is nothing at all when the card is already in the column it was dropped on", () => {
    // A drop back where it came from is not an edit, and sending it would mark the
    // mirror pending for a change nobody made. On a category board "where it came from"
    // is the category, not the key — `en_cours` dropped on "In flight" has not moved.
    expect(
      boardDrop(wide, ticket("ATL-3", "en_cours", { teamId: "atelier" }), "started"),
    ).toBeUndefined();
    expect(
      boardDrop(narrow, ticket("ATL-3", "en_cours", { teamId: "atelier" }), "en_cours"),
    ).toBeUndefined();
  });

  it("refuses in the column's own words when the team has nothing there", () => {
    // Named by the header the reader is looking at, not by `unstarted`, which is the
    // wire's word and appears on no screen.
    expect(boardDrop(wide, ticket("ATL-1", "devis", { teamId: "atelier" }), "unstarted")).toEqual({
      refusal: "This ticket's team has no status in Not started",
    });
  });

  it("refuses for a card whose team it cannot ask", () => {
    expect(boardDrop(wide, ticket("GON-1", "todo", { teamId: "gone" }), "completed")).toEqual({
      refusal: "This ticket's team has no status in Done",
    });
  });
});
