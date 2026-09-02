import { describe, expect, it } from "vitest";
import type { ViewFilters } from "@/lib/api";
import { CANONICAL, CATALOG, NAMES } from "./catalog.fixture";
import { format, parse, type FilterCatalog } from ".";

/**
 * The writing half — and the round trip, which is the load-bearing test of the language.
 *
 * The round trip spans both halves, so it could have gone on either side. It lives here
 * because it is a claim about **`format`**: `parse` is free to accept four spellings of
 * "seven days" and does, so the only thing that can make `format(parse(t)) === t` fail is
 * `format` choosing a different one — or being unable to write a facet at all, which is the
 * failure that matters. `parse.test.ts` asserts what each token *means*; this asserts that
 * every meaning has exactly one canonical way back out.
 *
 * Without it, the text and the chip strip could each be the source of truth and quietly
 * disagree: a chip removed would rewrite the line into something the reader did not type,
 * and nothing else in the suite would notice.
 */

const filtersOf = (text: string, catalog: FilterCatalog = CATALOG) => parse(text, catalog).filters;
const errorsOf = (text: string, catalog: FilterCatalog = CATALOG) => parse(text, catalog).errors;

describe("the round trip", () => {
  it("returns canonical text unchanged through filters and back", () => {
    const { filters, errors } = parse(CANONICAL, CATALOG);

    expect(errors).toEqual([]);
    expect(format(filters, NAMES)).toBe(CANONICAL);
  });

  // Each facet alone, because the whole line passing could hide a token that only works
  // in the company of another.
  it.each([
    "project:sync",
    "status:backlog,todo",
    "-status:done,canceled",
    "priority:none",
    "assignee:amara",
    "assignee:@me",
    "assignee:none",
    "cycle:23",
    "label:bug",
    "open:>30d",
    "estimate:none",
    "estimate:5..",
    "estimate:..13",
    "estimate:2..8",
  ])("holds for %s alone", (text) => {
    expect(format(filtersOf(text), NAMES)).toBe(text);
  });

  it("writes nothing at all for a filter set nobody has answered", () => {
    expect(format({}, NAMES)).toBe("");
  });

  /**
   * `FACET_ORDER`, whatever order the keys were stored in — so the line does not reshuffle
   * on every edit. It matters more here than on the chip strip: the strip redraws under the
   * reader's eyes, the text redraws under their caret.
   */
  it("writes the facets in FACET_ORDER and not in the order they were set", () => {
    const scrambled: ViewFilters = {
      estimateMax: 8,
      label: ["l-bug"],
      status: ["todo"],
      project: ["p-sync"],
      unassigned: true,
    };

    expect(format(scrambled, NAMES)).toBe(
      "project:sync status:todo assignee:none label:bug estimate:..8",
    );
  });

  it("keeps the values of one facet in the order they were typed", () => {
    expect(format(filtersOf("status:done,backlog,todo"), NAMES)).toBe("status:done,backlog,todo");
  });

  // An empty list is absent — `filterParams`' rule and `withFacet`'s. A facet nobody is
  // answering has no token, the same way it has no chip and no query parameter.
  it("says nothing about an empty facet", () => {
    expect(format({ status: [], unassigned: false, priority: ["low"] }, NAMES)).toBe("priority:low");
  });

  it("prints the raw id when no resolver was handed over, rather than throwing", () => {
    expect(format({ project: ["p-onboarding"] })).toBe("project:p-onboarding");
  });

  /**
   * `@me` survives a round trip and `current` does not, and the asymmetry is the point.
   * `assignee:@me` holds this reader's own id, so `@me` stays true. `cycle:current` holds
   * the id of whichever cycle was current when it was typed; printing `current` back would
   * claim the filter follows the cycle boundary, and it does not.
   */
  it("resolves @me through the catalogue and prints it back", () => {
    expect(filtersOf("assignee:@me")).toEqual({ assignee: ["u-tykok"] });
    expect(format({ assignee: ["u-tykok"] }, NAMES)).toBe("assignee:@me");
  });

  it("prints the handle of somebody who is not the reader", () => {
    expect(format({ assignee: ["u-tykok"] }, { ...NAMES, me: "u-amara" })).toBe("assignee:tykok");
  });

  it("has no @me at all for a reader the catalogue does not know yet", () => {
    const anonymous: FilterCatalog = { ...CATALOG, me: undefined };

    expect(filtersOf("assignee:@me", anonymous)).toEqual({});
    expect(errorsOf("assignee:@me", anonymous)[0]?.code).toBe("unknown-value");
  });

  it("resolves cycle:current but prints the cycle's own number", () => {
    expect(filtersOf("cycle:current")).toEqual({ cycle: ["c-24"] });
    expect(format(filtersOf("cycle:current"), NAMES)).toBe("cycle:24");
  });

  // Four spellings of one question. Only `>7d` is written back.
  it.each(["open:>7d", "open:7d", "open:>7", "open:7"])("reads %s as seven days", (text) => {
    expect(filtersOf(text)).toEqual({ openedForDays: 7 });
    expect(format(filtersOf(text), NAMES)).toBe("open:>7d");
  });

  it("reads a bare estimate as the range with both ends the same", () => {
    expect(filtersOf("estimate:5")).toEqual({ estimateMin: 5, estimateMax: 5 });
    expect(format(filtersOf("estimate:5"), NAMES)).toBe("estimate:5..5");
  });
});
