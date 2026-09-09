import { describe, expect, it } from "vitest";
import type { Team, Ticket } from "./api";
import {
  boardShape,
  bucketLabel,
  labelOf,
  optionsFor,
  vocabularyOf,
  CATEGORY_LABELS,
} from "./statuses";

/**
 * A team's own words, read by the screens that print them — `KAN-28`.
 *
 * The keys are the same everywhere and stay that way: what a team owns is the *word* and
 * the *order*. So every function here answers with a label or a sequence, and none of them
 * can change what a status is called on the wire.
 */

const SIX = [
  { key: "backlog", label: "Backlog", category: "backlog", position: 0 },
  { key: "todo", label: "Todo", category: "unstarted", position: 1 },
  { key: "in_progress", label: "In progress", category: "started", position: 2 },
  { key: "in_review", label: "In review", category: "started", position: 3 },
  { key: "done", label: "Done", category: "completed", position: 4 },
  { key: "canceled", label: "Canceled", category: "canceled", position: 5 },
] as const;

const team = (id: string, overrides: Partial<Team> = {}): Team =>
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
    statuses: SIX.map((status) => ({ ...status })),
    ...overrides,
  }) as Team;

const support = team("support", {
  statuses: [
    { key: "todo", label: "Qualifié", category: "unstarted", position: 0 },
    { key: "backlog", label: "Boîte", category: "backlog", position: 1 },
    { key: "in_progress", label: "En cours", category: "started", position: 2 },
    { key: "in_review", label: "Attente client", category: "started", position: 3 },
    { key: "done", label: "Résolu", category: "completed", position: 4 },
    { key: "canceled", label: "Sans suite", category: "canceled", position: 5 },
  ],
});

const kanso = team("kanso");

describe("the word a row is drawn with", () => {
  it("is its own team's, not the scope's", () => {
    // A cross-team list draws rows from several vocabularies at once, and each row has to
    // read as its own team reads it.
    expect(labelOf([kanso, support], "support", "in_review")).toBe("Attente client");
    expect(labelOf([kanso, support], "kanso", "in_review")).toBe("In review");
  });

  it("falls back to Kanso's own word for a draft, which has no team to ask", () => {
    expect(labelOf([kanso, support], undefined, "todo")).toBe("Todo");
  });

  it("falls back to the key when no catalogue can name it", () => {
    // A saved view older than a rename, a team not loaded yet: a blank pill would read as
    // a status that failed to load.
    expect(labelOf([], "kanso", "in_progress")).toBe("In progress");
    expect(labelOf([], "kanso", "shipped" as never)).toBe("shipped");
  });
});

describe("the vocabulary a screen offers", () => {
  it("is the team's list, in the team's order, when the scope names one team", () => {
    const vocabulary = vocabularyOf([kanso, support], { kind: "team", id: "support" });

    expect(vocabulary.map((status) => status.label)).toEqual([
      "Qualifié",
      "Boîte",
      "En cours",
      "Attente client",
      "Résolu",
      "Sans suite",
    ]);
  });

  it("is the five categories when the scope spans teams", () => {
    const vocabulary = vocabularyOf([kanso, support], { kind: "all" });

    // The header of a list holding two vocabularies is the fact both agree on. The server
    // buckets a wider scope by category for the same reason, and this has to match it or
    // the headers would name buckets that are not there.
    expect(vocabulary.map((status) => status.key)).toEqual([
      "backlog",
      "unstarted",
      "started",
      "completed",
      "canceled",
    ]);
    expect(vocabulary.map((status) => status.label)).toEqual([
      "Backlog",
      "Not started",
      "In flight",
      "Done",
      "Canceled",
    ]);
  });

  it("falls back to Kanso's six when the team is not loaded", () => {
    expect(vocabularyOf([], { kind: "team", id: "kanso" }).map((status) => status.key)).toEqual([
      "backlog",
      "todo",
      "in_progress",
      "in_review",
      "done",
      "canceled",
    ]);
  });

  it("reads a project scope as the wider one, since a project spans teams", () => {
    // `KAN-9`: a project belongs to no team in particular, so its list can hold two
    // vocabularies exactly like the all-teams one.
    expect(vocabularyOf([kanso, support], { kind: "project", id: "p1" })[1].key).toBe("unstarted");
  });
});

describe("a bucket header from the grouped endpoint", () => {
  it("is the team's word when the bucket is a status", () => {
    expect(bucketLabel([kanso, support], { kind: "team", id: "support" }, "done")).toBe("Résolu");
  });

  it("is the category's word when the bucket is a category", () => {
    expect(bucketLabel([kanso, support], { kind: "all" }, "started")).toBe("In flight");
  });

  it("names the five categories in words a reader would use", () => {
    // Not `unstarted` and `completed`, which are the wire's words for a reading Kanso
    // does internally — a header is read by somebody who never chose them.
    expect(CATEGORY_LABELS.unstarted).toBe("Not started");
    expect(CATEGORY_LABELS.started).toBe("In flight");
    expect(CATEGORY_LABELS.completed).toBe("Done");
  });
});

describe("the statuses a ticket may be moved to", () => {
  it("are its own team's, in its own team's order", () => {
    // The control beside a ticket offers what that ticket can actually hold, which is its
    // team's catalogue — `tickets_status_fk` would refuse anything else.
    expect(optionsFor([kanso, support], "support").map((row) => row.label)).toEqual([
      "Qualifié",
      "Boîte",
      "En cours",
      "Attente client",
      "Résolu",
      "Sans suite",
    ]);
  });

  it("are Kanso's six for a draft, which is what its composer offered", () => {
    // Not the categories: a draft holds a status, and `unstarted` is not one.
    expect(optionsFor([kanso, support], undefined).map((row) => row.key)).toEqual([
      "backlog",
      "todo",
      "in_progress",
      "in_review",
      "done",
      "canceled",
    ]);
  });

  it("are Kanso's six while the team is still loading", () => {
    expect(optionsFor([], "support").map((row) => row.key)).toHaveLength(6);
  });
});
/**
 * A team whose words are its own, and which has nothing at all in one category.
 *
 * `atelier` is the case the rebase has to refuse: four statuses, none of them
 * `unstarted`. A card of this team dropped on "Not started" has no key to be given, and
 * inventing the nearest one would move work somewhere nobody asked for.
 */
const atelier = team("atelier", {
  statuses: [
    { key: "boite", label: "Boîte", category: "backlog", position: 0 },
    { key: "devis", label: "Devis", category: "backlog", position: 1 },
    { key: "en_cours", label: "En cours", category: "started", position: 2 },
    { key: "livre", label: "Livré", category: "completed", position: 3 },
  ],
});

/** Only the two fields the board's shape reads off a row. */
const row = (teamId: string | undefined, status: string) =>
  ({ teamId, status }) as Pick<Ticket, "teamId" | "status">;

describe("the shape a board is drawn in", () => {
  it("stacks by status, and writes the column's own key, when the scope names one team", () => {
    const shape = boardShape([kanso, atelier], { kind: "team", id: "atelier" });

    expect(shape.vocabulary.map((column) => column.key)).toEqual([
      "boite",
      "devis",
      "en_cours",
      "livre",
    ]);
    expect(shape.bucketOf(row("atelier", "devis"))).toBe("devis");
    // Nothing to rebase: the column *is* a status of the only team on the board.
    expect(shape.rebase(row("atelier", "devis"), "livre")).toBe("livre");
  });

  it("stacks by category when the scope spans teams", () => {
    const shape = boardShape([kanso, atelier], { kind: "all" });

    expect(shape.vocabulary.map((column) => column.key)).toEqual([
      "backlog",
      "unstarted",
      "started",
      "completed",
      "canceled",
    ]);
  });

  it("places a row by what its own team means, not by what the scope's other team means", () => {
    // The whole reason a card can go missing today: `devis` is in no vocabulary but
    // `atelier`'s, and a board that stacked by key would have nowhere to put it.
    const shape = boardShape([kanso, atelier], { kind: "all" });

    expect(shape.bucketOf(row("atelier", "devis"))).toBe("backlog");
    expect(shape.bucketOf(row("kanso", "in_review"))).toBe("started");
  });

  it("rebases a drop onto the first status the row's team has in that category", () => {
    // Two teams, one gesture, two different keys written — which is what makes a
    // category column droppable at all.
    const shape = boardShape([kanso, atelier], { kind: "all" });

    expect(shape.rebase(row("atelier", "devis"), "completed")).toBe("livre");
    expect(shape.rebase(row("kanso", "todo"), "completed")).toBe("done");
  });

  it("rebases to the team's own order and not to Kanso's", () => {
    // `atelier` put `boite` before `devis`; a team that ordered them the other way round
    // would get `devis`. The first by `position` is the one the team reads first.
    const shape = boardShape([kanso, atelier], { kind: "all" });

    expect(shape.rebase(row("atelier", "en_cours"), "backlog")).toBe("boite");
  });

  it("refuses a drop into a category the row's team has no status in", () => {
    // `undefined` rather than the nearest key: a team that removed every unstarted
    // status said something, and the board honours it by not moving the card.
    const shape = boardShape([kanso, atelier], { kind: "all" });

    expect(shape.rebase(row("atelier", "devis"), "unstarted")).toBeUndefined();
  });

  it("refuses a drop for a row whose team it cannot ask", () => {
    // A team still loading, or a draft with no team at all: there is no catalogue to
    // pick a key out of, and Kanso's six are not this row's to be given.
    const shape = boardShape([kanso, atelier], { kind: "all" });

    expect(shape.rebase(row("gone", "todo"), "completed")).toBeUndefined();
    expect(shape.rebase(row(undefined, "todo"), "completed")).toBeUndefined();
  });

  it("reads a project scope as the wider one", () => {
    const shape = boardShape([kanso, atelier], { kind: "project", id: "p1" });

    expect(shape.bucketOf(row("atelier", "devis"))).toBe("backlog");
  });
});
