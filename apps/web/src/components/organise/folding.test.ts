import { describe, expect, it } from "vitest";
import { foldGroups, type FoldableTicket } from "./folding";

const t = (id: string, parentId?: string): FoldableTicket => ({ id, parentId: parentId ?? null });

const group = (key: string, count: number, tickets: FoldableTicket[]) => ({
  key,
  label: key,
  count,
  tickets,
});

const ids = <T extends { id: string }>(rows: T[]) => rows.map((row) => row.id);

describe("foldGroups", () => {
  it("orders children directly under their parent", () => {
    const groups = [group("todo", 3, [t("parent"), t("other"), t("child", "parent")])];

    const folded = foldGroups(groups, new Set());

    expect(ids(folded.groups[0].tickets)).toEqual(["parent", "child", "other"]);
    expect(folded.nested).toEqual(new Set(["child"]));
  });

  it("keeps the server's order within each level", () => {
    const groups = [
      group("todo", 5, [
        t("p1"),
        t("p2"),
        t("p1-a", "p1"),
        t("p2-a", "p2"),
        t("p1-b", "p1"),
      ]),
    ];

    // Parents keep their order among parents and siblings among siblings. No row
    // overtakes a row it is unrelated to, which is the sense in which `sortBy` survives.
    expect(ids(foldGroups(groups, new Set()).groups[0].tickets)).toEqual([
      "p1",
      "p1-a",
      "p1-b",
      "p2",
      "p2-a",
    ]);
  });

  it("hides a collapsed parent's children and nothing else", () => {
    const groups = [group("todo", 4, [t("parent"), t("a", "parent"), t("b", "parent"), t("other")])];

    const folded = foldGroups(groups, new Set(["parent"]));

    expect(ids(folded.groups[0].tickets)).toEqual(["parent", "other"]);
  });

  it("leaves the bucket count alone whatever is folded", () => {
    const groups = [group("todo", 340, [t("parent"), t("a", "parent"), t("b", "parent")])];

    // The count answers "how many tickets match in this bucket", not "how many rows can
    // I see" — it is already uncapped against a 200-row page. Recomputing it from the
    // surviving rows is exactly the disagreement KAN-59 was about.
    expect(foldGroups(groups, new Set()).groups[0].count).toBe(340);
    expect(foldGroups(groups, new Set(["parent"])).groups[0].count).toBe(340);
  });

  it("draws a chevron only where there is something to fold", () => {
    const groups = [
      group("todo", 3, [t("has-children"), t("child", "has-children"), t("childless")]),
    ];

    const folded = foldGroups(groups, new Set());

    expect(folded.foldable.get("has-children")).toBe(1);
    expect(folded.foldable.has("childless")).toBe(false);
  });

  it("draws a child flat when its parent is in another bucket", () => {
    // Grouping is by status and a child has its own status, so this is the common case,
    // not the edge one. Nothing re-buckets it: that would make the bucket disagree with
    // its own count.
    const groups = [
      group("todo", 1, [t("parent")]),
      group("done", 1, [t("child", "parent")]),
    ];

    const folded = foldGroups(groups, new Set(["parent"]));

    expect(ids(folded.groups[1].tickets)).toEqual(["child"]);
    expect(folded.nested.has("child")).toBe(false);
    expect(folded.foldable.has("parent")).toBe(false);
  });

  it("survives a row claiming itself as its parent", () => {
    // The server refuses this and so does `tickets_not_own_parent_chk`, but a row that
    // arrived from a cache written by an older client must not make the list vanish into
    // a loop.
    const groups = [group("todo", 1, [t("self", "self")])];

    expect(ids(foldGroups(groups, new Set()).groups[0].tickets)).toEqual(["self"]);
  });

  it("leaves an empty bucket empty", () => {
    expect(foldGroups([group("todo", 0, [])], new Set()).groups[0].tickets).toEqual([]);
  });
});
