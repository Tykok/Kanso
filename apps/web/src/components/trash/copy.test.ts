import { describe, expect, it } from "vitest";
import type { TrashItem } from "@/lib/api";
import { countdown, deletedAgo, isExpiring, paneSentence, restoreLabel, typeLabel } from "./copy";

const item = (over: Partial<TrashItem> = {}): TrashItem => ({
  kind: "ticket",
  id: "t1",
  label: "KAN-121 · SVG seal",
  parent: { kind: "project", id: "p1", name: "Product" },
  holds: [],
  daysLeft: 28,
  ...over,
});

describe("the trash's countdown", () => {
  it("prints the drawing's own unit", () => {
    expect(countdown(28)).toBe("28 j");
    expect(countdown(1)).toBe("1 j");
    expect(countdown(0)).toBe("0 j");
  });

  /**
   * The drawing paints `9 j` faint and `2 j` in --urgent, so the threshold is somewhere
   * between three and eight and a week is the only number in there anyone would name.
   */
  it("turns urgent within the last week", () => {
    expect(isExpiring(9)).toBe(false);
    expect(isExpiring(7)).toBe(true);
    expect(isExpiring(1)).toBe(true);
  });

  /**
   * Read off `daysLeft` rather than from the timestamp: the server computed the
   * countdown against the clock the sweep actually uses, so deriving the elapsed side
   * from the remaining one means the pane and the column cannot disagree — and the
   * client does no date arithmetic at all, which is the rule the rest of this codebase
   * already keeps for days.
   */
  it("says how long ago without touching a Date", () => {
    expect(deletedAgo(30, 30)).toBe("today");
    expect(deletedAgo(29, 30)).toBe("1 day ago");
    expect(deletedAgo(28, 30)).toBe("2 days ago");
  });
});

describe("the trash's labels", () => {
  it("names all four types, including the three no branch has landed", () => {
    expect(typeLabel("ticket")).toBe("Ticket");
    expect(typeLabel("doc")).toBe("Document");
    expect(typeLabel("view")).toBe("View");
    expect(typeLabel("folder")).toBe("Folder");
  });

  /** "Restore into Product": the parent is named, not implied. */
  it("names the parent a restore puts the thing back into", () => {
    expect(restoreLabel(item().parent)).toBe("Restore into Product");
  });

  it("still offers a restore when nothing above it has a name", () => {
    expect(restoreLabel(undefined)).toBe("Restore");
  });
});

describe("the detail pane's sentence", () => {
  it("says when, and nothing more when the thing holds nothing", () => {
    expect(paneSentence(item({ daysLeft: 28 }), 30)).toBe("Deleted 2 days ago.");
  });

  /**
   * The drawing's own load-bearing detail: deleting a document that mentioned two
   * tickets deletes neither ticket, and the pane has to say so before anybody presses
   * the red button. Driven by `cascades`, which the server sends as a fact — not by a
   * sentence typed into a component that could outlive the behaviour.
   */
  it("says what goes with it, and what does not", () => {
    const doc = item({
      kind: "doc",
      label: "Cycle notes 22",
      holds: [
        { kind: "blocks", count: 4, cascades: true },
        { kind: "mentionedTickets", count: 2, cascades: false },
      ],
    });

    expect(paneSentence(doc, 30)).toBe(
      "Deleted 2 days ago. Held 4 blocks and 2 mentioned tickets — the tickets were not " +
        "deleted, only the reference goes.",
    );
  });

  it("counts one of something in the singular", () => {
    const doc = item({
      kind: "doc",
      holds: [{ kind: "mentionedTickets", count: 1, cascades: false }],
    });

    expect(paneSentence(doc, 30)).toBe(
      "Deleted 2 days ago. Held 1 mentioned ticket — the ticket was not deleted, only the " +
        "reference goes.",
    );
  });

  it("adds no rider when everything it holds goes with it", () => {
    const doc = item({ kind: "doc", holds: [{ kind: "blocks", count: 4, cascades: true }] });
    expect(paneSentence(doc, 30)).toBe("Deleted 2 days ago. Held 4 blocks.");
  });

  /** An archive has no countdown at all, which is the whole distinction of the slice. */
  it("says a decision was made rather than inventing a clock", () => {
    expect(paneSentence(item({ daysLeft: undefined }), 30)).toBe(
      "Archived. There is no countdown on it: somebody put this away on purpose.",
    );
  });
});
