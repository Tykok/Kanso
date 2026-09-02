import { FACET_ORDER } from "@/components/organise/facets";
import type { ViewFilters } from "@/lib/api";
import type { FilterCatalog, FilterNames, FilterSource } from "./types";

/**
 * Filters back to text — the other direction, and the only file that reads `ViewFilters`
 * as its input.
 *
 * It shares no code with `parse.ts` and deliberately so: a writer built out of the
 * parser's tables would be able to agree with it about a token neither of them spells the
 * way the reader typed it. What holds the two honest is the round-trip property test,
 * which lives beside this half in `format.test.ts` and is the load-bearing test of the
 * whole language.
 */

/**
 * Filters to text, in `FACET_ORDER`.
 *
 * The order is read out of `facets.ts` for exactly the reason `chips.ts` reads it: so the
 * line does not reshuffle on every edit. It matters more here than on the strip — the
 * strip redraws under the reader's eyes, the text redraws under their caret.
 *
 * Values inside a facet keep the order they are stored in, and are not sorted for the same
 * reason. `status:todo,in_progress` came back as `status:in_progress,todo` would move a
 * word somebody was about to delete.
 *
 * ## `@me` is printed and `current` is not
 *
 * Both are sugar on the way in. Only one is true on the way out. `assignee:@me` holds this
 * reader's own id, so printing `@me` claims nothing the filter does not do — and for
 * somebody else opening the same shared view, [FilterNames.me] is *their* id and the
 * handle is printed instead, which is also correct. `cycle:current` holds the id of
 * whichever cycle was current when it was typed; printing `current` back would claim the
 * filter follows the cycle boundary, and it does not. So a cycle always prints its number.
 */
export function format(filters: ViewFilters, names: FilterNames = {}): string {
  const written = new Set<keyof ViewFilters>();
  const tokens: string[] = [];

  for (const facet of FACET_ORDER) {
    if (written.has(facet)) continue;
    const token = tokenFor(facet, filters, names, written);
    if (token !== undefined) tokens.push(token);
  }

  return tokens.join(" ");
}

/** The reverse lookups [format] needs, built from the same catalogue `parse` read. */
export function namesFrom(catalog: FilterCatalog): FilterNames {
  const lookup = (source: FilterSource) => (id: string) =>
    catalog.options[source]?.find((option) => option.id === id)?.token ?? id;

  return {
    project: lookup("project"),
    person: lookup("assignee"),
    cycle: lookup("cycle"),
    label: lookup("label"),
    me: catalog.me,
  };
}

function tokenFor(
  facet: keyof ViewFilters,
  filters: ViewFilters,
  names: FilterNames,
  written: Set<keyof ViewFilters>,
): string | undefined {
  const spell = (resolve: ((id: string) => string) | undefined, ids: string[] | undefined) =>
    // An empty list is absent, which is `filterParams`' rule and `withFacet`'s: a facet
    // nobody is answering has no token, the same way it has no chip and no query parameter.
    ids === undefined || ids.length === 0
      ? undefined
      : ids.map((id) => resolve?.(id) ?? id).join(",");

  switch (facet) {
    case "project":
      return prefixed("project", spell(names.project, filters.project));
    case "status":
      return prefixed("status", spell(undefined, filters.status));
    case "statusNot":
      return prefixed("-status", spell(undefined, filters.statusNot));
    case "priority":
      return prefixed("priority", spell(undefined, filters.priority));
    case "assignee":
      return prefixed(
        "assignee",
        spell(
          (id) => (names.me !== undefined && id === names.me ? "@me" : (names.person?.(id) ?? id)),
          filters.assignee,
        ),
      );
    case "unassigned":
      return filters.unassigned ? "assignee:none" : undefined;
    case "cycle":
      return prefixed("cycle", spell(names.cycle, filters.cycle));
    case "label":
      return prefixed("label", spell(names.label, filters.label));
    case "openedForDays":
      return filters.openedForDays === undefined ? undefined : `open:>${filters.openedForDays}d`;
    case "unestimated":
      return filters.unestimated ? "estimate:none" : undefined;
    // One token for two facets, because `estimate:3..8` is how a range is typed and
    // `estimate:3.. estimate:..8` would be the same question asked twice. The pair is
    // claimed here at whichever bound `FACET_ORDER` reaches first, so a lone `estimateMax`
    // still gets a token of its own.
    case "estimateMin":
    case "estimateMax": {
      const { estimateMin, estimateMax } = filters;
      if (estimateMin === undefined && estimateMax === undefined) return undefined;
      written.add("estimateMin");
      written.add("estimateMax");
      return `estimate:${estimateMin ?? ""}..${estimateMax ?? ""}`;
    }
  }
}

const prefixed = (key: string, values: string | undefined) =>
  values === undefined ? undefined : `${key}:${values}`;
