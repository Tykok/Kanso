import { describe, expect, it } from "vitest";
import { filterParams, type ViewFilters } from "@/lib/api";
import {
  FACETS,
  composableFacets,
  facetByKey,
  filterEntries,
  savedViewFilters,
  withFacet,
} from "./facets";

/**
 * Composing a filter, which nothing in the application could do before.
 *
 * The server serves twelve facets and refuses a thirteenth on the way in, so the whole
 * of this module is one gate and three edits: which questions may be asked, and how an
 * answer is added, changed and taken back off. `chips.test.ts` covers the other
 * direction — what a composed set looks like once it is on screen.
 *
 * `served` is spelled out in each test rather than imported from anywhere. That is the
 * point: the list is the *server's*, it arrives over the wire, and a test that read it
 * from a constant beside the code under test would be asserting that two copies of one
 * vocabulary agree with each other.
 */
const SERVED = [
  "status",
  "statusNot",
  "priority",
  "project",
  "assignee",
  "unassigned",
  "cycle",
  "label",
  "openedForDays",
  "unestimated",
  "estimateMin",
  "estimateMax",
];

const facet = (key: keyof ViewFilters) => facetByKey(key);

describe("composableFacets", () => {
  it("offers the facets the server says it serves", () => {
    expect(composableFacets(SERVED).map((one) => one.key)).toEqual(FACETS.map((one) => one.key));
  });

  // The whole reason the list travels rather than being written down here. A facet the
  // server does not serve is a 400 on the way in, so offering it would be offering a
  // chip that cannot become an answer.
  it("cannot compose a facet the server does not serve", () => {
    const offered = composableFacets(["status", "priority"]);

    expect(offered.map((one) => one.key)).toEqual(["status", "priority"]);
    expect(offered.some((one) => one.key === "label")).toBe(false);
  });

  // The other direction of the same drift: a name the server grows that no control here
  // knows how to draw. It is left out rather than guessed at — an unlabelled row with no
  // answers to pick from teaches nothing — and it shows up as a facet nobody can reach,
  // which is a visible gap rather than a wrong list.
  it("ignores a served name it has no control for", () => {
    expect(composableFacets([...SERVED, "labelColour"]).map((one) => one.key)).toEqual(
      FACETS.map((one) => one.key),
    );
  });

  it("offers nothing at all before the served list has landed", () => {
    expect(composableFacets([])).toEqual([]);
  });

  // Same left-to-right as the chip strip, which reads them out of this list, so the
  // order a filter is offered in is the order it is drawn in afterwards.
  it("keeps the strip's order rather than the server's", () => {
    expect(composableFacets(["estimateMax", "project", "status"]).map((one) => one.key)).toEqual([
      "project",
      "status",
      "estimateMax",
    ]);
  });
});

describe("withFacet", () => {
  it("adds a value to a facet that takes several", () => {
    expect(withFacet({}, facet("status"), "todo")).toEqual({ status: ["todo"] });
  });

  // One chip, two answers — the shape `chipsOf` draws as `Priority Urgent, High`. A
  // second answer must not replace the first, or a multi-select is a radio button.
  it("joins a second answer to the same facet rather than replacing the first", () => {
    const once = withFacet({}, facet("priority"), "urgent");

    expect(withFacet(once, facet("priority"), "high")).toEqual({ priority: ["urgent", "high"] });
  });

  it("takes an answer back off when it is chosen again", () => {
    const both: ViewFilters = { priority: ["urgent", "high"] };

    expect(withFacet(both, facet("priority"), "urgent")).toEqual({ priority: ["high"] });
  });

  // The key has to be *absent*, not present and empty — the rule `withoutChip` already
  // holds to. `{ status: [] }` is a filter on the wire that the screen is not drawing.
  it("deletes the facet when its last answer comes off", () => {
    expect(withFacet({ status: ["todo"] }, facet("status"), "todo")).toEqual({});
  });

  it("sets a boolean facet, which is its own answer", () => {
    expect(withFacet({}, facet("unassigned"), true)).toEqual({ unassigned: true });
  });

  // `false` is not "no" on the wire, it is a chip nobody asked for. See `filterParams`.
  it("deletes a boolean facet rather than sending it off", () => {
    expect(withFacet({ unassigned: true }, facet("unassigned"), false)).toEqual({});
  });

  it("sets a numeric bound", () => {
    expect(withFacet({}, facet("openedForDays"), 3)).toEqual({ openedForDays: 3 });
  });

  // Zero is a legitimate bound and the absence of one is `undefined`, exactly as in
  // `chipsOf`. A falsy check here would make `estimateMax 0` unaskable.
  it("keeps a bound of zero", () => {
    expect(withFacet({}, facet("estimateMax"), 0)).toEqual({ estimateMax: 0 });
  });

  it("deletes a numeric bound asked with nothing", () => {
    expect(withFacet({ estimateMin: 5 }, facet("estimateMin"), undefined)).toEqual({});
  });

  // Refused rather than dropped is the server's rule for `estimateMin=soon`; here there
  // is nothing to refuse to, so the honest reading of an unreadable number is no filter
  // at all — never a silently widened list under a chip that says otherwise.
  it("refuses a bound that is not a number", () => {
    expect(withFacet({ estimateMin: 5 }, facet("estimateMin"), Number.NaN)).toEqual({});
  });

  it("leaves the other facets alone and does not mutate what it was given", () => {
    const filters: ViewFilters = { project: ["p1"], unassigned: true };

    expect(withFacet(filters, facet("status"), "done")).toEqual({
      project: ["p1"],
      unassigned: true,
      status: ["done"],
    });
    expect(filters).toEqual({ project: ["p1"], unassigned: true });
  });
});

/**
 * The rows of the "add a filter" list.
 *
 * One flat list of question-and-answer pairs, because that is the only shape a single
 * search field can narrow: somebody looking for the `sync` label knows the word `sync`
 * and not that it is a label, and a menu of facets would make them find `Label` first.
 */
describe("filterEntries", () => {
  const options = {
    status: [
      { value: "todo", label: "Todo" },
      { value: "in_progress", label: "In progress" },
      { value: "done", label: "Done" },
    ],
    label: [{ value: "l1", label: "sync" }],
  };

  const only = (key: keyof ViewFilters) => [facet(key)];

  it("offers one row per answer, named as the whole question", () => {
    expect(filterEntries(only("status"), options, {}, "").map((row) => row.label)).toEqual([
      "Status is Todo",
      "Status is In progress",
      "Status is Done",
    ]);
  });

  it("marks the answers already composed", () => {
    const rows = filterEntries(only("status"), options, { status: ["done"] }, "");

    expect(rows.filter((row) => row.chosen).map((row) => row.label)).toEqual(["Status is Done"]);
  });

  // The same row both adds an answer and takes it off, so the list never has to teach a
  // second gesture — and the chip strip's `×` stays another way of saying it, not the
  // only one.
  it("hands back a row that undoes itself", () => {
    const [todo] = filterEntries(only("status"), options, {}, "");
    const composed = withFacet({}, todo.facet, todo.value);
    const [again] = filterEntries(only("status"), options, composed, "");

    expect(withFacet(composed, again.facet, again.value)).toEqual({});
  });

  // A boolean facet is its own answer, so it is one row rather than a question with a
  // yes and a no — `Unassigned no` is not a filter anybody composes.
  it("offers a boolean facet as a single row", () => {
    expect(filterEntries(only("unassigned"), {}, {}, "")).toMatchObject([
      { label: "Unassigned", value: true, chosen: false },
    ]);
    expect(filterEntries(only("unassigned"), {}, { unassigned: true }, "")).toMatchObject([
      { label: "Unassigned", value: false, chosen: true },
    ]);
  });

  it("offers a numeric facet the bounds worth asking for", () => {
    expect(filterEntries(only("openedForDays"), {}, {}, "").map((row) => row.label)).toEqual([
      "Open for more than 1 days",
      "Open for more than 3 days",
      "Open for more than 7 days",
      "Open for more than 14 days",
      "Open for more than 30 days",
    ]);
  });

  // The server takes any whole number and the six sizes are a suggestion, not a
  // vocabulary — `estimateMin: 4` is the legitimate question "bigger than a 3". A control
  // that offered only the scale would be narrower than the endpoint behind it.
  it("takes a bound nobody suggested, typed into the search field", () => {
    const rows = filterEntries(only("estimateMin"), {}, {}, "4");

    expect(rows.map((row) => row.label)).toEqual(["Points at least 4"]);
    expect(withFacet({}, rows[0].facet, rows[0].value)).toEqual({ estimateMin: 4 });
  });

  /**
   * The bug a unit suite was green through, found by driving the real screen: the number
   * is read off the *end* of what was typed, not from the whole field.
   *
   * Typing `points at least 4` used to offer nothing at all. The words had narrowed the
   * list to that one facet, and `Number("points at least 4")` is `NaN`, so the row that
   * would have matched was never built — the only way to a bound off the scale was to
   * clear the field and type a bare `4`, which nobody does after reading sentences.
   */
  it("reads the bound off the end of the sentence somebody typed", () => {
    const rows = filterEntries(only("estimateMin"), {}, {}, "points at least 4");

    expect(rows.map((row) => row.label)).toEqual(["Points at least 4"]);
  });

  it("reads a bound off the end of any facet's own wording", () => {
    const rows = filterEntries(only("openedForDays"), {}, {}, "open for more than 10");

    expect(rows.map((row) => row.label)).toEqual(["Open for more than 10 days"]);
  });

  // Whole numbers only. The server takes an `Int`, so `4.5` is a row that composes into
  // a 400, and `-2` one that composes into a bound nothing can ever match.
  it("offers no row for something that is not a whole number", () => {
    expect(filterEntries(only("estimateMin"), {}, {}, "points 4.5")).toEqual([]);
    expect(filterEntries(only("estimateMin"), {}, {}, "points -2")).toEqual([]);
  });

  it("does not offer a typed bound twice when it is already suggested", () => {
    expect(filterEntries(only("estimateMin"), {}, {}, "5").map((row) => row.label)).toEqual([
      "Points at least 5",
    ]);
  });

  it("finds an answer by a word of it, whichever facet it belongs to", () => {
    const rows = filterEntries([facet("status"), facet("label")], options, {}, "sync");

    expect(rows.map((row) => row.label)).toEqual(["Label is sync"]);
  });

  // Every word anywhere, in any order — so the negated facet is reachable by the two
  // words that tell it apart from the one beside it.
  it("narrows on every word typed rather than on a prefix", () => {
    const rows = filterEntries([facet("status"), facet("statusNot")], options, {}, "done not");

    expect(rows.map((row) => row.label)).toEqual(["Status is not Done"]);
  });

  // The other side of the gate, from the drawing's point of view: a facet whose answers
  // have not arrived — an unscoped list has no cycles and no labels — is simply not
  // offered, rather than offered with nothing behind it.
  it("says nothing about a facet whose answers have not loaded", () => {
    expect(filterEntries(only("cycle"), {}, {}, "")).toEqual([]);
  });
});

/**
 * The composed set on the wire.
 *
 * This is where a mistake in the two above becomes visible as a request: the list
 * endpoint and a saved view are one question asked through two doors, and this is the
 * door the main list goes through.
 */
describe("a composed set as the list endpoint reads it", () => {
  it("asks every facet under the name the server serves it by", () => {
    let filters: ViewFilters = {};
    filters = withFacet(filters, facet("statusNot"), "done");
    filters = withFacet(filters, facet("priority"), "urgent");
    filters = withFacet(filters, facet("priority"), "high");
    filters = withFacet(filters, facet("label"), "l1");
    filters = withFacet(filters, facet("unassigned"), true);
    filters = withFacet(filters, facet("openedForDays"), 3);

    const search = filterParams(filters);

    expect(search.getAll("statusNot")).toEqual(["done"]);
    expect(search.getAll("priority")).toEqual(["urgent", "high"]);
    expect(search.get("label")).toBe("l1");
    expect(search.get("unassigned")).toBe("true");
    expect(search.get("openedForDays")).toBe("3");
  });

  // The unfiltered list, and it has to be *literally* unfiltered: an empty key left
  // behind by the last removal would still travel, and `?status=` is a question the
  // server would then be answering.
  it("asks nothing at all once the last filter comes off", () => {
    let filters = withFacet(withFacet({}, facet("status"), "todo"), facet("unassigned"), true);
    filters = withFacet(filters, facet("status"), "todo");
    filters = withFacet(filters, facet("unassigned"), false);

    expect(filters).toEqual({});
    expect(filterParams(filters).toString()).toBe("");
  });
});

/**
 * "Save this question" — the list is an unsaved saved view, and this is the moment it
 * stops being unsaved.
 */
describe("savedViewFilters", () => {
  it("hands the composed set to the dialog as it stands", () => {
    let filters: ViewFilters = {};
    filters = withFacet(filters, facet("project"), "p1");
    filters = withFacet(filters, facet("statusNot"), "done");
    filters = withFacet(filters, facet("estimateMin"), 5);

    expect(savedViewFilters(filters, SERVED)).toEqual({
      project: ["p1"],
      statusNot: ["done"],
      estimateMin: 5,
    });
  });

  // The same gate on the way out as on the way in. A view is written once and read
  // every time it is opened, so a key the server would refuse must not reach the write:
  // the refusal is a dialog that will not close, where the wrong list is silent.
  it("refuses to store a facet the server does not serve", () => {
    const filters = { status: ["todo"], labelColour: ["indigo"] } as unknown as ViewFilters;

    expect(savedViewFilters(filters, ["status"])).toEqual({ status: ["todo"] });
  });

  it("saves the empty question as an empty question", () => {
    expect(savedViewFilters({}, SERVED)).toEqual({});
  });
});
