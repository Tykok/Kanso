import { describe, expect, it } from "vitest";
import { filterParams, type ViewFilters } from "./organise";

/**
 * A saved view's filters, spelled as the query string `GET /api/tickets` reads.
 *
 * The list endpoint used to serve four filters and a saved view nine, so a chip could
 * only be honoured on one of the two screens. They are one predicate behind one
 * vocabulary now, and this is the whole of the difference between the two doors: the
 * view sends jsonb, the list sends these names.
 */
describe("filterParams", () => {
  it("sends a facet under the name the server serves it by", () => {
    expect(filterParams({ statusNot: ["done"] }).toString()).toBe("statusNot=done");
  });

  // Repeated rather than comma-joined: `MultiValueMap` on the other side reads repeats,
  // and one chip with two values means "either", which is what two entries say.
  it("repeats the key for a facet asked with two values", () => {
    expect(filterParams({ priority: ["urgent", "high"] }).getAll("priority")).toEqual([
      "urgent",
      "high",
    ]);
  });

  it("writes a boolean facet as the bare word the server reads back as true", () => {
    expect(filterParams({ unassigned: true }).toString()).toBe("unassigned=true");
  });

  // A chip the reader has taken off is a chip that does not travel. `unassigned=false`
  // would be a filter on the wire that the screen is not drawing.
  it("says nothing about a facet that is off or empty", () => {
    const filters: ViewFilters = { unassigned: false, status: [], project: undefined };

    expect(filterParams(filters).toString()).toBe("");
  });

  it("carries the three estimate questions the list could not ask before", () => {
    expect(filterParams({ unestimated: true }).toString()).toBe("unestimated=true");
    expect(filterParams({ estimateMin: 5, estimateMax: 8 }).toString()).toBe(
      "estimateMin=5&estimateMax=8",
    );
  });

  it("carries every facet of one full question at once", () => {
    const search = filterParams({
      statusNot: ["done", "canceled"],
      priority: ["urgent"],
      label: ["l1"],
      cycle: ["c1"],
      openedForDays: 3,
      unassigned: true,
    });

    expect([...new Set(search.keys())]).toEqual([
      "statusNot",
      "priority",
      "label",
      "cycle",
      "openedForDays",
      "unassigned",
    ]);
    expect(search.getAll("statusNot")).toEqual(["done", "canceled"]);
    expect(search.get("openedForDays")).toBe("3");
    expect(search.get("unassigned")).toBe("true");
    expect(search.get("label")).toBe("l1");
  });

  // The two names `GET /api/tickets` has always taken. They are not in `ViewFilters` and
  // must not be: the server aliases them onto `project` and `assignee`, so the web has one
  // spelling and the compatibility lives on the server where the old callers are.
  it("has no alias of its own to keep in step", () => {
    const filters = { project: ["p1"], assignee: ["u1"] } satisfies ViewFilters;

    expect(filterParams(filters).toString()).toBe("project=p1&assignee=u1");
  });
});
