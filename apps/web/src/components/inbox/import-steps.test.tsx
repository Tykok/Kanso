import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { NotionImportSource } from "@/lib/api";
import { importCounts, type ImportMapping } from "./import-map";
import { StepOne } from "./import-step-one";
import { StepTwo } from "./import-step-two";
import { StepThree } from "./import-step-three";

/**
 * The three aggregate sentences of the import dialog, read together.
 *
 * `import-targets.test.ts` already pins the rule; what it cannot see is which number each
 * sentence hangs its noun on, and that is the whole of this defect. Three sites said `pages`
 * unconditionally while the per-row count beside them said "1 page", so a one-page workspace
 * read "1 page" on one line and "1 of 1 pages kept" on the next — an incoherence a *fix*
 * created, which is why this file renders all three steps and not one.
 *
 * The four-page numbers are here on purpose too: `e2e/import.spec.ts` asserts those three
 * strings verbatim, and that suite belongs to another branch. Pinned here, a change to this
 * wording fails in two seconds instead of in somebody else's docker build.
 */

/**
 * Step two draws a relation hint that asks the server for a base's schema, and `RelationHint`
 * returns `null` until that answers — so the hook is stubbed at its resting state rather than
 * a client stood up for it. A real `QueryClient` also works and was tried; it lets the fetch
 * start, and happy-dom aborts it at teardown, so three passing tests printed three stack
 * traces. A suite whose green run looks like a failure is one nobody reads.
 */
vi.mock("@/lib/queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/queries")>();
  return { ...actual, useImportSchema: () => ({ data: undefined }) };
});

const source = (pages: number, pagesExact: boolean): NotionImportSource => ({
  id: "base",
  name: "Tasks",
  databaseId: "db",
  pages,
  pagesExact,
});

/** One base, kept as tickets — the shortest plan in which `kept` and `total` are both it. */
const MAPPING: ImportMapping = { base: "tickets" };

/** Everything the three steps need and none of them read for a page count. */
const noop = () => {};

function steps(pages: number, exact: boolean) {
  const sources = [source(pages, exact)];
  const counts = importCounts(sources, MAPPING);
  const { container: one } = render(<StepOne loading={false} sources={sources} onNext={noop} />);
  const { container: two } = render(
    <StepTwo
      sources={sources}
      mapping={MAPPING}
      kept={MAPPING}
      mappings={{}}
      counts={counts}
      planEmpty={false}
      teams={[]}
      teamId=""
      teamRequired={false}
      onTeam={noop}
      onCycle={noop}
      onSuggest={noop}
      onNext={noop}
      onBack={noop}
    />,
  );
  const { container: three } = render(
    <StepThree
      sources={sources}
      mapping={MAPPING}
      counts={counts}
      onConfirm={noop}
      onClose={noop}
      onBack={noop}
      pending={false}
    />,
  );
  return {
    one: one.textContent ?? "",
    two: two.textContent ?? "",
    three: three.textContent ?? "",
  };
}

describe("the import dialog's page counts, across the three steps that total them", () => {
  it("says page once and pages nowhere for a one-page workspace", () => {
    const { one, two, three } = steps(1, true);

    // The per-row count, `pageCount` since it was written, and the three sentences that
    // used to disagree with it.
    expect(one).toContain("1 database, 1 page in all.");
    expect(two).toContain("1 of 1 page kept");
    expect(three).toContain("1 page out of 1.");

    // Said once more as the incoherence itself: nothing on any of the three screens calls
    // one page a plural.
    expect(one + two + three).not.toContain("1 pages");
  });

  it("keeps the plural on a bounded one, on every screen that shows the bound", () => {
    const { one, two, three } = steps(1, false);

    // The frontier `import-targets.test.ts` guards from the other side. `1+` is a lower
    // bound: "1+ page" would promise exactly what the `+` is there to deny, so a singular
    // here would be worse than the wart this change removed.
    expect(two).toContain("1 of 1+ pages kept");
    expect(three).toContain("1+ pages out of 1+.");

    // Step one words its bound in prose instead, and so keeps its own plural. What matters
    // is that it does not quietly become a count.
    expect(one).toContain("at least 1 pages in all.");
    expect(one).not.toContain("1 page in all.");
  });

  it("prints the three strings e2e/import.spec.ts asserts", () => {
    const { one, two, three } = steps(4, true);

    // Verbatim, including the punctuation: these belong to a suite this branch may not edit.
    // Step one's spec reads "3 databases, 4 pages in all." off a three-base workspace; only
    // the half after the comma is this rule's, and only that half is pinned here.
    expect(one).toContain("4 pages in all.");
    expect(two).toContain("4 of 4 pages kept");
    expect(three).toContain("4 pages out of 4.");
  });
});
