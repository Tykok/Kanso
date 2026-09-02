import { FACET_ORDER } from "@/components/organise/facets";
import { namesFrom } from "./format";
import type { FilterCatalog } from "./types";

/**
 * One team's rows, as the input's queries would hand them over — shared by the three
 * suites rather than typed out in each.
 *
 * Not a dumping ground for helpers: it is here because the round-trip claim in
 * `format.test.ts` is only meaningful if `parse` and `format` were asked about the *same*
 * vocabulary, and three copies of a catalogue would let that stop being true without a
 * single test going red. `.fixture.ts` and not `.test.ts`, so vitest's
 * `src/**\/*.{test,spec}.ts` does not collect it as a suite of its own.
 *
 * `sync` is deliberately both a project and a label: the two are told apart only by the key
 * in front of them, and a value list that leaked across keys would pass every other
 * assertion in this folder. `me` is `tykok`'s id, so `@me` and a handle name the same person
 * and `format` has to choose between them.
 */
export const CATALOG: FilterCatalog = {
  served: [...FACET_ORDER],
  options: {
    project: [
      { token: "onboarding", id: "p-onboarding", label: "Onboarding" },
      { token: "sync", id: "p-sync", label: "Notion sync" },
    ],
    assignee: [
      { token: "tykok", id: "u-tykok", label: "E. Treport" },
      { token: "amara", id: "u-amara", label: "A. Okonkwo" },
    ],
    cycle: [
      { token: "23", id: "c-23", label: "Cycle 23" },
      { token: "24", id: "c-24", label: "Cycle 24" },
    ],
    label: [
      { token: "bug", id: "l-bug", label: "bug" },
      { token: "sync", id: "l-sync", label: "sync" },
    ],
  },
  me: "u-tykok",
  currentCycleId: "c-24",
};

export const NAMES = namesFrom(CATALOG);

/**
 * One line carrying all twelve facets, in `FACET_ORDER`, spelled the way `format` writes
 * them. The round trip and the coverage claim are both made against this.
 */
export const CANONICAL =
  "project:onboarding status:todo,in_progress -status:done priority:urgent,high " +
  "assignee:@me assignee:none cycle:24 label:bug open:>7d estimate:none estimate:3..8";
