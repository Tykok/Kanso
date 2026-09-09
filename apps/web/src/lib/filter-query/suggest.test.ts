import { describe, expect, it } from "vitest";
import { CATALOG } from "./catalog.fixture";
import { FILTER_KEYS, suggest, type FilterCatalog } from ".";

/**
 * Completion — the caret, the span an accepted row replaces, and the `served` gate.
 *
 * The two claims worth stating up front: a caret in the **middle** of a line completes the
 * word it is in and not the tail of the text, and the returned span *replaces* that word
 * rather than being appended to it. An input built on either wrong assumption looks correct
 * until somebody goes back to fix a typo.
 */

describe("suggest", () => {
  it("offers the nine keys on an empty line, in the strip's order", () => {
    const at = suggest("", 0, CATALOG);

    expect(at).toMatchObject({ kind: "key", start: 0, end: 0 });
    expect(at.items.map((item) => item.label)).toEqual([...FILTER_KEYS]);
    expect(FILTER_KEYS).toEqual([
      "project",
      "status",
      // Between `status` and `priority`, because `FACET_ORDER` puts `Meaning is` beside
      // the two status facets — the strip's order is the order a question is offered in.
      "meaning",
      "priority",
      "assignee",
      "cycle",
      "label",
      "open",
      "estimate",
    ]);
  });

  it("reads its question out of facets.ts rather than holding its own words", () => {
    const items = suggest("", 0, CATALOG).items;

    expect(items.find((item) => item.label === "status")?.detail).toBe("Status is");
    expect(items.find((item) => item.label === "open")?.detail).toBe("Open for more than");
  });

  it("offers only the negatable key after a minus", () => {
    const at = suggest("-", 1, CATALOG);

    expect(at.items.map((item) => item.insert)).toEqual(["status:"]);
    expect(at.items[0]?.detail).toBe("Status is not");
    // The span starts after the minus, so accepting the row keeps the reader's sign.
    expect([at.start, at.end]).toEqual([1, 1]);
  });

  it("offers values once the colon is there, scoped to their own key", () => {
    expect(suggest("label:", 6, CATALOG).items.map((item) => item.id)).toEqual([
      "label:bug",
      "label:sync",
    ]);
    expect(suggest("project:", 8, CATALOG).items.map((item) => item.id)).toEqual([
      "project:onboarding",
      "project:sync",
    ]);
  });

  it("offers the statuses as the work flows, with the words the app already prints", () => {
    const at = suggest("status:", 7, CATALOG);

    expect(at.items.map((item) => item.insert)).toEqual([
      "backlog",
      "todo",
      "in_progress",
      "in_review",
      "done",
      "canceled",
    ]);
    expect(at.items[2]?.label).toBe("In progress");
  });

  it("offers the answers that are their own facet beside the rows", () => {
    expect(suggest("assignee:", 9, CATALOG).items.map((item) => item.insert)).toEqual([
      "@me",
      "tykok",
      "amara",
      "none",
    ]);
    expect(suggest("cycle:", 6, CATALOG).items.map((item) => item.insert)).toEqual([
      "23",
      "24",
      "current",
    ]);
    expect(suggest("estimate:", 9, CATALOG).items[0]).toMatchObject({
      insert: "none",
      label: "Unestimated",
    });
  });

  it("offers the bounds facets.ts already suggests, spelled as tokens", () => {
    expect(suggest("open:", 5, CATALOG).items.map((item) => item.insert)).toEqual([
      ">1d",
      ">3d",
      ">7d",
      ">14d",
      ">30d",
    ]);
    expect(suggest("open:", 5, CATALOG).items[2]?.label).toBe("Open for more than 7 days");
  });

  /**
   * The caret in the middle of a line, which is the case an input that only ever completed
   * its own tail would get wrong — a reader who goes back to widen `status:todo` must get
   * the statuses, not the eight keys.
   */
  it("completes the word the caret is in, not the last word on the line", () => {
    const text = "status:to priority:high";
    const at = suggest(text, 9, CATALOG);

    expect(at).toMatchObject({ kind: "value", key: "status", start: 7, end: 9 });
    expect(at.items.map((item) => item.insert)).toEqual(["todo"]);
  });

  it("completes a value in the middle of a comma list", () => {
    const text = "status:todo,in_re,done priority:high";
    const at = suggest(text, 15, CATALOG);

    expect([at.start, at.end]).toEqual([12, 17]);
    expect(at.items.map((item) => item.insert)).toEqual(["in_review"]);
  });

  it("offers keys again when the caret is in the gap between two tokens", () => {
    const text = "status:todo  priority:high";
    const at = suggest(text, 12, CATALOG);

    expect(at).toMatchObject({ kind: "key", start: 12, end: 12 });
    expect(at.items).toHaveLength(9);
  });

  /**
   * The caret in the key half of a token that already has a colon. The insert drops the
   * colon, because the token has one — otherwise accepting a row would write `priority::todo`.
   */
  it("does not add a second colon when completing a key that already has one", () => {
    const at = suggest("stat:todo", 4, CATALOG);

    expect(at).toMatchObject({ kind: "key", start: 0, end: 4 });
    expect(at.items.map((item) => item.insert)).toEqual(["status"]);
  });

  // `filterEntries`' rule, for its reason: what somebody knows is the answer, not that
  // `in_progress` begins with an `i`.
  it("matches anywhere in the token or in the name", () => {
    expect(suggest("status:progress", 15, CATALOG).items.map((item) => item.insert)).toEqual([
      "in_progress",
    ]);
    expect(suggest("assignee:okonkwo", 16, CATALOG).items.map((item) => item.insert)).toEqual([
      "amara",
    ]);
  });

  /**
   * The gate, and the only place it applies. An empty `served` offers nothing rather than
   * everything, for `composableFacets`' reason: before the list has landed, no facet is
   * *known* to be answerable.
   */
  it("offers nothing before the served list has landed", () => {
    const cold: FilterCatalog = { ...CATALOG, served: [] };

    expect(suggest("", 0, cold).items).toEqual([]);
    expect(suggest("status:", 7, cold).items).toEqual([]);
  });

  it("offers only the keys whose facets the server answers", () => {
    const narrow: FilterCatalog = { ...CATALOG, served: ["status", "label"] };

    expect(suggest("", 0, narrow).items.map((item) => item.label)).toEqual(["status", "label"]);
  });

  it("drops the one answer of a key whose own facet is not served", () => {
    const narrow: FilterCatalog = { ...CATALOG, served: ["assignee"] };

    expect(suggest("assignee:", 9, narrow).items.map((item) => item.insert)).toEqual([
      "@me",
      "tykok",
      "amara",
    ]);
  });

  it("offers nothing for a key that is not one of the eight", () => {
    const at = suggest("foo:ba", 6, CATALOG);

    expect(at).toMatchObject({ kind: "value", start: 4, end: 6 });
    // No key, rather than the unknown word passed through as one: the input has nothing to
    // scope a list by, and saying `key: "foo"` would invite it to try.
    expect(at.key).toBeUndefined();
    expect(at.items).toEqual([]);
  });

  it("clamps a caret outside the text rather than reading off the end", () => {
    expect(suggest("status:", 99, CATALOG).items).toHaveLength(6);
    expect(suggest("status:", -3, CATALOG).kind).toBe("key");
  });
});
