import { describe, expect, it } from "vitest";
import type { Team } from "./api";
import { bucketLabel, labelOf, optionsFor, vocabularyOf, CATEGORY_LABELS } from "./statuses";

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
