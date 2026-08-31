import { describe, expect, it } from "vitest";
import type { NotionPeopleView } from "@/lib/api";
import { buildAssignments, isSuggested, preselectedAccount, seenPeopleRows } from "./import-people";

describe("preselectedAccount", () => {
  it("prefers the confirmed link over the suggestion", () => {
    expect(preselectedAccount({ userId: "u1", suggestedUserId: "u2" })).toBe("u1");
  });

  it("falls back to the suggestion with no confirmed link", () => {
    expect(preselectedAccount({ suggestedUserId: "u2" })).toBe("u2");
  });

  it("is empty with neither", () => {
    expect(preselectedAccount({})).toBe("");
  });
});

describe("isSuggested", () => {
  it("is true for a guess nobody has confirmed", () => {
    expect(isSuggested({ suggestedUserId: "u2" })).toBe(true);
  });

  it("is false once the account is confirmed, even if it matches the guess", () => {
    expect(isSuggested({ userId: "u2", suggestedUserId: "u2" })).toBe(false);
  });

  it("is false with no suggestion at all", () => {
    expect(isSuggested({})).toBe(false);
  });
});

describe("buildAssignments", () => {
  it("sends an untouched row's confirmed link, not a suggestion it also carries", () => {
    const rows = [{ id: "n1", userId: "u1", suggestedUserId: "u2" }];
    expect(buildAssignments(rows, {})).toEqual({ n1: "u1" });
  });

  it("never sends an untouched row's suggestion when there is no confirmed link", () => {
    const rows = [{ id: "n2", suggestedUserId: "u2" }];
    expect(buildAssignments(rows, {})).toEqual({});
  });

  it("sends what the reader set, confirmed link or not", () => {
    const rows = [{ id: "n1", userId: "u1" }, { id: "n2" }];
    expect(buildAssignments(rows, { n1: "u3", n2: "u4" })).toEqual({ n1: "u3", n2: "u4" });
  });

  it("sends null for a row the reader explicitly cleared", () => {
    const rows = [{ id: "n1", userId: "u1" }];
    expect(buildAssignments(rows, { n1: null })).toEqual({ n1: null });
  });

  it("omits a row with no confirmed link and no edit, rather than sending null", () => {
    const rows = [{ id: "n1" }, { id: "n2", userId: "u1" }];
    const result = buildAssignments(rows, {});
    expect(Object.keys(result)).toEqual(["n2"]);
    expect(result).toEqual({ n2: "u1" });
  });
});

describe("seenPeopleRows", () => {
  const view: NotionPeopleView = {
    available: true,
    people: [
      { notion: { id: "n1", name: "Ada", email: "ada@x.com" }, userId: "u1" },
      { notion: { id: "n2", name: "Bo" }, suggestedUserId: "u2" },
    ],
  };

  it("joins people-seen's rows against the standing correspondence", () => {
    const rows = seenPeopleRows(
      [{ id: "n1", name: "Ada" }, { id: "n2", name: "Bo" }],
      view,
    );
    expect(rows).toEqual([
      { id: "n1", name: "Ada", email: "ada@x.com", userId: "u1", suggestedUserId: undefined },
      { id: "n2", name: "Bo", email: undefined, userId: undefined, suggestedUserId: "u2" },
    ]);
  });

  it("still rows a person the correspondence has never heard of", () => {
    const rows = seenPeopleRows([{ id: "n3", name: "Cy" }], view);
    expect(rows).toEqual([
      { id: "n3", name: "Cy", email: undefined, userId: undefined, suggestedUserId: undefined },
    ]);
  });

  it("sorts by name so a refetch cannot reshuffle the list", () => {
    const rows = seenPeopleRows([{ id: "n2", name: "Bo" }, { id: "n1", name: "Ada" }], view);
    expect(rows.map((row) => row.id)).toEqual(["n1", "n2"]);
  });

  it("answers no rows for an unavailable correspondence, not an unlinked one", () => {
    const rows = seenPeopleRows(
      [{ id: "n4", name: "Dee" }],
      { available: false, reason: "nope", people: [] },
    );
    expect(rows).toEqual([
      { id: "n4", name: "Dee", email: undefined, userId: undefined, suggestedUserId: undefined },
    ]);
  });
});
