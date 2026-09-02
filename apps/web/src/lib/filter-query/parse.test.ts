import { describe, expect, it } from "vitest";
import { FACET_ORDER } from "@/components/organise/facets";
import { filterParams } from "@/lib/api";
import { CANONICAL, CATALOG } from "./catalog.fixture";
import { filterKeyOf, parse, suggest, type FilterCatalog } from ".";

/**
 * The reading half, asserted one claim at a time.
 *
 * Two things here are load-bearing and the rest is detail. **Every facet of `ViewFilters`
 * has a spelling** — a facet the language cannot say is a filter the reader cannot ask for,
 * and there is no other list that would notice. **A half-typed line still compiles** —
 * anything else and the list would empty itself on every keystroke that had not finished,
 * which is most of them.
 *
 * The third load-bearing claim, the round trip, needs `format` and lives with it.
 */

const filtersOf = (text: string, catalog: FilterCatalog = CATALOG) => parse(text, catalog).filters;
const errorsOf = (text: string, catalog: FilterCatalog = CATALOG) => parse(text, catalog).errors;

// --- the twelve facets, one at a time --------------------------------------

describe("every facet has a spelling", () => {
  it("status", () => {
    expect(filtersOf("status:todo,in_progress")).toEqual({ status: ["todo", "in_progress"] });
  });

  // The negation, and the reason it is a key of its own rather than a mode of `status`:
  // `ViewFilters.statusNot` is what the server matches on and what the chip prints `≠`.
  it("statusNot", () => {
    expect(filtersOf("-status:done")).toEqual({ statusNot: ["done"] });
  });

  it("priority", () => {
    expect(filtersOf("priority:urgent,high")).toEqual({ priority: ["urgent", "high"] });
  });

  // `none` is a priority called "No priority" — the one place the word means an answer
  // rather than the absence of one, which `assignee:none` and `estimate:none` both use it
  // for. Nothing in the language special-cases it, and this is what says so.
  it("priority:none is a priority and not an absence", () => {
    expect(filtersOf("priority:none")).toEqual({ priority: ["none"] });
  });

  it("project, by name into the id the filter stores", () => {
    expect(filtersOf("project:onboarding")).toEqual({ project: ["p-onboarding"] });
  });

  it("assignee", () => {
    expect(filtersOf("assignee:amara")).toEqual({ assignee: ["u-amara"] });
  });

  it("unassigned, which is assignee:none", () => {
    expect(filtersOf("assignee:none")).toEqual({ unassigned: true });
  });

  it("cycle", () => {
    expect(filtersOf("cycle:24")).toEqual({ cycle: ["c-24"] });
  });

  it("label, by id and not by name — two teams may both own the word", () => {
    expect(filtersOf("label:sync")).toEqual({ label: ["l-sync"] });
  });

  it("openedForDays", () => {
    expect(filtersOf("open:>7d")).toEqual({ openedForDays: 7 });
  });

  it("unestimated, which is estimate:none", () => {
    expect(filtersOf("estimate:none")).toEqual({ unestimated: true });
  });

  it("estimateMin, as a range with an open top", () => {
    expect(filtersOf("estimate:3..")).toEqual({ estimateMin: 3 });
  });

  it("estimateMax, as a range with an open bottom", () => {
    expect(filtersOf("estimate:..8")).toEqual({ estimateMax: 8 });
  });

  /**
   * The claim the twelve cases above cannot make between them: that there is no
   * *thirteenth*. `FACET_KEY`'s `satisfies` refuses a facet nobody gave a key to at compile
   * time; this is the runtime half, that the key it was given actually reads the word.
   */
  it("leaves no facet of ViewFilters unspellable", () => {
    expect(FACET_ORDER.filter((facet) => filterKeyOf(facet) === undefined)).toEqual([]);
    expect(Object.keys(filtersOf(CANONICAL)).sort()).toEqual([...FACET_ORDER].sort());
  });
});

// --- what goes on the wire -------------------------------------------------

/**
 * The output has to survive `filterParams` unchanged, because that is the only thing the
 * language is for. `filters.test.ts` pins the spelling; this pins that a typed line
 * arrives at it in one piece.
 */
describe("filterParams over a parsed line", () => {
  it("puts every facet of a typed line on the wire", () => {
    const search = filterParams(filtersOf(CANONICAL));

    expect([...new Set(search.keys())]).toEqual([...FACET_ORDER]);
    expect(search.getAll("status")).toEqual(["todo", "in_progress"]);
    expect(search.getAll("statusNot")).toEqual(["done"]);
    expect(search.get("project")).toBe("p-onboarding");
    expect(search.get("assignee")).toBe("u-tykok");
    expect(search.get("unassigned")).toBe("true");
    expect(search.get("openedForDays")).toBe("7");
    expect(search.get("estimateMin")).toBe("3");
    expect(search.get("estimateMax")).toBe("8");
  });
});

// --- errors, per token, with spans ----------------------------------------

describe("errors", () => {
  it("names an unknown key and spans exactly the word", () => {
    const [error, ...rest] = errorsOf("foo:bar status:todo");

    expect(rest).toEqual([]);
    expect(error).toMatchObject({ code: "unknown-key", start: 0, end: 3, text: "foo" });
    // And the token beside it still parsed.
    expect(filtersOf("foo:bar status:todo")).toEqual({ status: ["todo"] });
  });

  it("names an unknown value of a known key and spans the value, not the token", () => {
    const [error] = errorsOf("status:xyzzy");

    expect(error).toMatchObject({
      code: "unknown-value",
      key: "status",
      start: 7,
      end: 12,
      text: "xyzzy",
    });
    expect(error.message).toBe('"xyzzy" is not a status');
  });

  /**
   * The near miss worth naming: the word is a real answer, to the wrong question. Getting
   * `"urgent" is not a status` and nothing else would leave the reader retyping it.
   */
  it("says which key a value would have been valid for", () => {
    const [error] = errorsOf("status:urgent");

    expect(error).toMatchObject({ code: "unknown-value", key: "status", validFor: "priority" });
    expect(error.message).toBe('"urgent" is not a status; "priority:urgent" is');
  });

  it("keeps the two names of one word apart", () => {
    const [error] = errorsOf("label:onboarding");

    expect(error).toMatchObject({ validFor: "project", start: 6, end: 16 });
  });

  // `statusNot` is the only negation `ViewFilters` has a key for, so `-priority:high` is
  // not a filter that could be added later — it is a question the server cannot answer.
  it("refuses a negation on a key that has none", () => {
    const [error] = errorsOf("-priority:high");

    expect(error).toMatchObject({ code: "not-negatable", start: 0, end: 9, text: "-priority" });
  });

  it("complains about a key left with no value once the reader has moved past it", () => {
    const [error, ...rest] = errorsOf("status: priority:high");

    expect(rest).toEqual([]);
    expect(error).toMatchObject({ code: "missing-value", key: "status", start: 0, end: 6 });
    expect(filtersOf("status: priority:high")).toEqual({ priority: ["high"] });
  });

  /**
   * The claim that makes the input usable: an unparseable word never takes the line down
   * with it. Two facets on either side of a typo in the middle of a value list, and both
   * of them survive.
   */
  it("never discards the filters that did parse", () => {
    const text = "status:todo,urgnet estimate:none";
    const { filters, errors } = parse(text, CATALOG);

    expect(filters).toEqual({ status: ["todo"], unestimated: true });
    expect(errors).toHaveLength(1);
    expect(errors[0]).toMatchObject({ text: "urgnet", start: 12, end: 18 });
  });

  it("unions the answers of a facet asked twice rather than letting the second win", () => {
    expect(filtersOf("status:todo status:done,todo")).toEqual({ status: ["todo", "done"] });
  });

  it("lets the last of two bounds win, since a scalar has no union", () => {
    expect(filtersOf("open:>3d open:>9d")).toEqual({ openedForDays: 9 });
  });

  /**
   * A facet the server has stopped serving is still read. `facets.ts` gates what is
   * *offered* and `savedViewFilters` gates what is *written*; underlining a correct word
   * because a cached list has not landed yet would be a red line on first paint.
   */
  it("reads a token the catalogue says nothing about", () => {
    const cold: FilterCatalog = { ...CATALOG, served: [] };

    expect(filtersOf("status:todo", cold)).toEqual({ status: ["todo"] });
    expect(errorsOf("status:todo", cold)).toEqual([]);
  });
});

// --- the reader mid-word ---------------------------------------------------

describe("a token still being typed", () => {
  // The whole of the grace: it applies to a word that runs to the end of the input, so a
  // trailing space is what commits it.
  it.each(["stat", "-stat", "status:", "status:to", "status:todo,in_pro", "estimate:3.", "open:>"])(
    "says nothing about %s at the end of the input",
    (text) => {
      expect(errorsOf(text)).toEqual([]);
    },
  );

  it("still offers a completion for a half-typed key", () => {
    const at = suggest("stat", 4, CATALOG);

    expect(at.kind).toBe("key");
    expect(at.items.map((item) => item.insert)).toEqual(["status:"]);
    expect([at.start, at.end]).toEqual([0, 4]);
  });

  it("still offers a completion for a half-typed value", () => {
    const at = suggest("status:to", 9, CATALOG);

    expect(at).toMatchObject({ kind: "value", key: "status", start: 7, end: 9 });
    expect(at.items.map((item) => item.insert)).toEqual(["todo"]);
  });

  it("does complain once a space has committed the word", () => {
    expect(errorsOf("status:to ")).toHaveLength(1);
    expect(errorsOf("status:to priority:high")[0]).toMatchObject({ start: 7, end: 9 });
  });

  it("keeps what parsed before the half-typed tail", () => {
    expect(filtersOf("priority:urgent status:to")).toEqual({ priority: ["urgent"] });
    expect(errorsOf("priority:urgent status:to")).toEqual([]);
  });
});
