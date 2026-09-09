import { describe, expect, it } from "vitest";
import { FACET_ORDER } from "./facets";
import { format, parse, suggest, type FilterCatalog } from "@/lib/filter-query";
import type { ViewFilters } from "@/lib/api";
import {
  applySuggestion,
  filterOptions,
  filterToken,
  highlights,
  withoutFacet,
} from "./filter-text";

/**
 * The box's four text answers, asserted without rendering one.
 *
 * The point of this file is that none of these four needs a browser: a caret is an
 * integer, an underline is a pair of offsets, and a chip's `×` is a string edit. Every
 * assertion that could be made through `parse` is made through `parse` — the two agreeing
 * about where a word starts is the one risk in `filter-text.ts`, and a comment claiming it
 * would be worth less than these three lines.
 */

/** Enough of a catalogue to make the errors and the spans real. */
const CATALOG: FilterCatalog = {
  served: [...FACET_ORDER],
  options: {
    project: [{ token: "onboarding", id: "p-1", label: "Onboarding" }],
    label: [{ token: "bug", id: "l-1", label: "bug" }],
  },
};

describe("highlights", () => {
  it("underlines the offending word and nothing else", () => {
    const text = "status:todo,urgnet";
    const runs = highlights(text, parse(text, CATALOG).errors);

    expect(runs.map((run) => run.text)).toEqual(["status:todo,", "urgnet"]);
    expect(runs[0].error).toBeUndefined();
    expect(runs[1].error?.code).toBe("unknown-value");
  });

  /**
   * The runs are the whole line, joined back up character for character. The mirror behind
   * the input is positioned by nothing but its own text, so a dropped run would move every
   * underline after it.
   */
  it("gives back the whole line however many words are wrong", () => {
    const text = "nope:1 status:todo also:2";
    const runs = highlights(text, parse(text, CATALOG).errors);

    expect(runs.map((run) => run.text).join("")).toBe(text);
    expect(runs.filter((run) => run.error).map((run) => run.text)).toEqual(["nope", "also"]);
  });

  it("says nothing about a line that parsed", () => {
    const text = "status:todo label:bug";
    expect(highlights(text, parse(text, CATALOG).errors)).toEqual([{ text }]);
  });
});

describe("applySuggestion", () => {
  it("writes a key with its colon and leaves the caret ready for the answer", () => {
    const text = "stat";
    const asked = suggest(text, 4, CATALOG);
    const key = asked.items.find((item) => item.label === "status");
    if (!key) throw new Error("`stat` offered no `status`");

    expect(applySuggestion(text, asked, key.insert)).toEqual({ text: "status:", caret: 7 });
  });

  /**
   * The span is the word, so accepting an answer over a typo is what corrects it — the
   * misspelling is replaced rather than being left in front of the answer.
   */
  it("replaces the misspelled word rather than appending to it", () => {
    const text = "status:odo";
    const asked = suggest(text, text.length, CATALOG);
    const todo = asked.items.find((item) => item.id === "status:todo");
    if (!todo) throw new Error("`odo` offered no `todo`");

    const { text: next, caret } = applySuggestion(text, asked, todo.insert);
    expect(next).toBe("status:todo ");
    // Past the space, which is where `suggest` answers with the keys again — the whole of
    // what lets a reader who never types compose a second filter with a second click.
    expect(caret).toBe(12);
    expect(suggest(next, caret, CATALOG).kind).toBe("key");
  });

  it("keeps the minus sign on a negated token", () => {
    const text = "-stat";
    const asked = suggest(text, 5, CATALOG);
    expect(applySuggestion(text, asked, asked.items[0].insert)).toEqual({
      text: "-status:",
      caret: 8,
    });
  });

  it("adds no space in the middle of a line, where one is already there", () => {
    const text = "status:odo label:bug";
    const asked = suggest(text, 10, CATALOG);
    const todo = asked.items.find((item) => item.id === "status:todo");
    if (!todo) throw new Error("`odo` offered no `todo`");

    expect(applySuggestion(text, asked, todo.insert)).toEqual({
      text: "status:todo label:bug",
      caret: 11,
    });
  });
});

describe("withoutFacet, on the line", () => {
  it("takes out the token the facet is asked through", () => {
    const filters = { status: ["todo" as const], label: ["l-1"] };
    expect(withoutFacet("status:todo label:bug", filters, "status").text).toBe("label:bug");
    expect(withoutFacet("status:todo label:bug", filters, "label").text).toBe("status:todo");
  });

  /**
   * The one place two facets share a key and are told apart by a sign. `Status ≠ Done`'s
   * `×` must not take the positive question with it, and the positive one's must not take
   * the negation.
   */
  it("tells a negated token from its positive twin", () => {
    const text = "status:todo -status:done priority:high";
    const filters = {
      status: ["todo" as const],
      statusNot: ["done" as const],
      priority: ["high" as const],
    };
    expect(withoutFacet(text, filters, "statusNot").text).toBe("status:todo priority:high");
    expect(withoutFacet(text, filters, "status").text).toBe("-status:done priority:high");
  });

  /**
   * The claim that matters, and the reason it is asserted through `parse` rather than
   * against a string: `filter-text.ts` has word boundaries of its own, and what has to be
   * true is that the *language* reads the result as the same question minus one facet.
   */
  it("leaves a line the language reads as the same question, one facet lighter", () => {
    const text = "project:onboarding status:todo -status:done label:bug estimate:3..8";
    const before = parse(text, CATALOG).filters;
    const after = parse(withoutFacet(text, before, "label").text, CATALOG);

    expect(after.errors).toEqual([]);
    expect(after.filters).toEqual({ ...before, label: undefined });
  });

  it("leaves a half-typed word alone, since no chip is drawn for one", () => {
    // `status` on its own asks nothing and produces no chip, so nothing about a chip's `×`
    // has any business deleting what a reader is in the middle of typing.
    expect(withoutFacet("label:bug status", { label: ["l-1"] }, "status").text).toBe(
      "label:bug status",
    );
  });

  it("leaves single spaces behind, wherever the token was", () => {
    const filters = { status: ["todo" as const] };
    expect(withoutFacet("a:1 status:todo b:2", filters, "status").text).toBe("a:1 b:2");
    expect(withoutFacet("status:todo label:bug", filters, "status").text).toBe("label:bug");
    expect(withoutFacet("label:bug status:todo", filters, "status").text).toBe("label:bug");
    expect(withoutFacet("status:todo", filters, "status").text).toBe("");
  });

  it("takes out every token for the facet, not just the first", () => {
    expect(
      withoutFacet(
        "status:todo label:bug status:done",
        { status: ["todo", "done"], label: ["l-1"] },
        "status",
      ).text,
    ).toBe("label:bug");
  });

  /**
   * `NEGATED` in `filter-text.ts` is a second copy of `NEGATED_FACET`, which the language's
   * barrel does not export. This is what keeps the copy honest: `format` is the authority on
   * which facets wear a `-`, and it is asked about all twelve.
   */
  it("agrees with `format` about which facets are negated", () => {
    const probe: Record<keyof ViewFilters, ViewFilters> = {
      status: { status: ["todo"] },
      statusNot: { statusNot: ["done"] },
      category: { category: ["started"] },
      priority: { priority: ["high"] },
      project: { project: ["p-1"] },
      assignee: { assignee: ["u-1"] },
      unassigned: { unassigned: true },
      cycle: { cycle: ["c-1"] },
      label: { label: ["l-1"] },
      openedForDays: { openedForDays: 7 },
      unestimated: { unestimated: true },
      estimateMin: { estimateMin: 3 },
      estimateMax: { estimateMax: 8 },
    };

    for (const facet of Object.keys(probe) as (keyof ViewFilters)[]) {
      const written = format(probe[facet]);
      const negated = written.startsWith("-");
      expect(negated, `${facet} is written "${written}"`).toBe(facet === "statusNot");
      // And the copy is used the way the sign says: the token `format` writes is the token
      // the `×` finds. Asked alone, so nothing survives it and the line empties.
      const gone = withoutFacet(written, probe[facet], facet);
      expect(gone.text, `${facet} removed "${written}"`).toBe("");
      expect(gone.filters).toEqual({});
    }
  });
});

describe("withoutFacet", () => {
  it("takes the facet off the question and its word out of the line", () => {
    expect(
      withoutFacet("status:todo label:bug", { status: ["todo"], label: ["l-1"] }, "status"),
    ).toEqual({ text: "label:bug", filters: { label: ["l-1"] } });
  });

  /**
   * The case a delete-by-key gets wrong. One token holds both bounds, so removing the lower
   * one has to *rewrite* it — otherwise the chips would go on drawing `Points ≤ 8` over a
   * line that no longer says it, and `↵` would quietly drop the bound.
   */
  it("rewrites a token that answers two facets rather than deleting it", () => {
    expect(
      withoutFacet("estimate:3..8 label:bug", { estimateMin: 3, estimateMax: 8 }, "estimateMin"),
    ).toEqual({ text: "estimate:..8 label:bug", filters: { estimateMax: 8 } });
  });

  /** The other pair sharing a key, and the same claim: `assignee:@me` stays put. */
  it("keeps a key's other answers where they were", () => {
    expect(
      withoutFacet(
        "assignee:@me assignee:none priority:high",
        { assignee: ["u-1"], unassigned: true, priority: ["high"] },
        "unassigned",
        { me: "u-1" },
      ),
    ).toEqual({
      text: "assignee:@me priority:high",
      filters: { assignee: ["u-1"], priority: ["high"] },
    });
  });

  /**
   * The reader's own words are not touched — not the half-typed tail, and not a spelling the
   * canonical form would have replaced. A `×` is one facet's business.
   */
  it("leaves the rest of the line exactly as it was typed", () => {
    const gone = withoutFacet(
      "cycle:current status:todo priority:hi",
      { cycle: ["c-1"], status: ["todo"] },
      "status",
      { cycle: () => "24" },
    );
    expect(gone.text).toBe("cycle:current priority:hi");
  });

  /**
   * The reason the filters are edited rather than re-read from the rewritten line: before
   * the catalogue lands, the line prints ids and `parse` reads none of them. Removing one
   * chip must not take the others with it.
   */
  it("does not depend on the rest of the line being readable", () => {
    expect(
      withoutFacet("project:p-1 label:l-1", { project: ["p-1"], label: ["l-1"] }, "label"),
    ).toEqual({ text: "project:p-1", filters: { project: ["p-1"] } });
  });

  /**
   * A filter the reader never typed — a stored view before its text has been touched at all
   * cannot happen, but a scope-answered facet or a question composed by clicking can leave
   * the line short of it. It goes where they would have put it.
   */
  it("appends what is left when the line never held the key", () => {
    expect(
      withoutFacet("label:bug", { estimateMin: 3, estimateMax: 8 }, "estimateMin"),
    ).toEqual({ text: "label:bug estimate:..8", filters: { estimateMax: 8 } });
  });
});

describe("filterOptions", () => {
  it("slugs a name into a word that can be typed", () => {
    expect(filterToken("Étiquette synchro")).toBe("etiquette-synchro");
    expect(filterToken("Notion sync!")).toBe("notion-sync");
  });

  /**
   * Two rows with one word between them is the one way the round trip can lie about which
   * record a view is about, so the second gets a number rather than the first's id.
   */
  it("gives two rows of the same name two tokens", () => {
    expect(filterOptions([{ id: "a", name: "Sync" }, { id: "b", name: "sync" }])).toEqual([
      { token: "sync", id: "a", label: "Sync" },
      { token: "sync-2", id: "b", label: "sync" },
    ]);
  });

  it("falls back to the id for a name that slugs to nothing", () => {
    expect(filterOptions([{ id: "l-7", name: "···" }])).toEqual([
      { token: "l-7", id: "l-7", label: "···" },
    ]);
  });
});
