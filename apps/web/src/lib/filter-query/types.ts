import { FACET_ORDER, type FacetSource } from "@/components/organise/facets";
import type { ViewFilters } from "@/lib/api";

/**
 * What the language is made of — every shape its three functions speak in, and nothing
 * that does anything.
 *
 * The rule for this file is that it holds no reader, no writer and no completion: the
 * types, and the two maps that are pure statements of fact about `ViewFilters`. `parse.ts`,
 * `format.ts` and `suggest.ts` all import from here, which is what keeps the three
 * concerns separable in the head as well as on disk.
 */

/**
 * The eight words that can appear before a colon.
 *
 * Eight, not twelve, because three facets are answers rather than questions: `unassigned`
 * is `assignee:none`, `unestimated` is `estimate:none`, and `statusNot` is `status`
 * negated. Spelling them as their own keys — `unassigned:true` — would make the language
 * mirror the type instead of the sentence, and nobody asks "unassigned true".
 */
export type FilterKey =
  | "project"
  | "status"
  | "priority"
  | "assignee"
  | "cycle"
  | "label"
  | "open"
  | "estimate";

/**
 * The sources whose answers are rows and therefore have to be handed in.
 *
 * `FacetSource` minus its two closed vocabularies: `status` and `priority` are literals in
 * `lib/api/core.ts` and are read straight from there, which is the same split
 * `filter-composer.tsx` already makes when it fills in its options.
 */
export type FilterSource = Exclude<FacetSource, "status" | "priority">;

/**
 * One named answer: the word that is typed, and the id `ViewFilters` stores.
 *
 * Two fields rather than `FacetOption`'s one, because a filter's *text* and a filter's
 * *value* are different strings — `project:onboarding` stores a uuid — and the reverse
 * lookup `format` needs would otherwise have to guess that `Design system` is spelled
 * `design-system`. The catalogue decides the spelling; nothing here invents one.
 *
 * A `token` must hold no whitespace, comma or colon, since those are the three characters
 * the grammar uses. There is no quoting: `project:"design system"` would be a fourth
 * character with meaning, and slugging a name is something the screen building the
 * catalogue can do once for all four sources.
 */
export type FilterOption = {
  token: string;
  id: string;
  /** What the completion row reads — the real name. Defaults to the token. */
  label?: string;
};

/**
 * Everything the language cannot know by itself.
 *
 * `served` is `GET /api/tickets/filters`, already cached, which answers exactly this
 * question. An empty one offers nothing rather than everything, for `composableFacets`'
 * reason: before the list has landed, no facet is *known* to be answerable.
 */
export type FilterCatalog = {
  served: readonly string[];
  options: Partial<Record<FilterSource, readonly FilterOption[]>>;
  /** Who `@me` is. Absent — a reader not yet loaded — and `assignee:@me` is not offered. */
  me?: string;
  /** Which cycle the word `current` names right now. */
  currentCycleId?: string;
};

/**
 * The ids a filter stores, back into the words that were typed.
 *
 * Structurally a `ChipNames` with one field added, so a screen can build one object and
 * hand it to both `chipsOf` and `format`. The difference is what the functions are
 * expected to return: a chip prints `Design system`, and a token has to print
 * `design-system` or the line it lands in will not parse. `namesFrom` derives the whole
 * thing from a catalogue, which is how the two halves of the round trip are kept from
 * drifting apart by hand.
 *
 * All four are optional. A filter set of statuses and bounds needs none of them, and a
 * missing resolver prints the raw id — useless to read, but true, and better than a throw
 * inside something a reader is typing into.
 */
export type FilterNames = {
  project?: (id: string) => string;
  person?: (id: string) => string;
  cycle?: (id: string) => string;
  label?: (id: string) => string;
  /**
   * The reader's own id, printed `@me`.
   *
   * `@me` is canonical rather than sugar, because it stays *true*: the id in the filter is
   * this reader's, so printing `@me` claims nothing the filter does not do. `current` is
   * the opposite case and is treated the opposite way — see `format`.
   */
  me?: string;
};

export type FilterErrorCode =
  /** The word before the colon is no filter, or a bare word is not one either. */
  | "unknown-key"
  /** A known key with nothing after its colon, and the reader has moved on. */
  | "missing-value"
  /** A word this key does not take. Carries `validFor` when another key does take it. */
  | "unknown-value"
  /** `-priority:high`. `statusNot` is the only negation `ViewFilters` has a key for. */
  | "not-negatable";

/**
 * One complaint about one word.
 *
 * The span is the *offending word*, not the token that holds it. Underlining the whole of
 * `status:todo,urgnet` tells the reader the wrong thing three times over: `status` is a
 * filter, `todo` is a status, and only the last six characters are the mistake.
 */
export type FilterError = {
  code: FilterErrorCode;
  /** Offsets into the text handed to `parse`, so the input can underline exactly this. */
  start: number;
  end: number;
  /** The text between the offsets, so a message can be built without re-slicing. */
  text: string;
  /** The key the word was offered to, when there was one. */
  key?: FilterKey;
  /** Set when the word is a legal answer to a *different* key — the near miss worth naming. */
  validFor?: FilterKey;
  message: string;
};

export type ParsedQuery = { filters: ViewFilters; errors: FilterError[] };

export type Suggestion = {
  /** Stable across a retype, so the highlight does not jump as the list narrows. */
  id: string;
  /** The text to splice over `[start, end)`. Carries its own colon when one is needed. */
  insert: string;
  /** What the row reads: `Todo`, `Onboarding`, `status`. */
  label: string;
  /** The question behind it, in `facets.ts`' words: `Status is`, `Open for more than`. */
  detail?: string;
};

export type FilterSuggestions = {
  /** Whether these are keys or answers to one. */
  kind: "key" | "value";
  /** The key being answered, when [kind] is `value` and the key is one this language has. */
  key?: FilterKey;
  /** The span an accepted suggestion replaces — the word under the caret, possibly empty. */
  start: number;
  end: number;
  items: Suggestion[];
};

// --- which key each facet is asked through ---------------------------------

/**
 * The map the rest of the language hangs off: every facet of `ViewFilters`, and the word a
 * reader types to reach it.
 *
 * The `satisfies` is what keeps it honest, and it is `facets.ts`' own guard on its
 * `CONTROLS`: checked against every key of `ViewFilters`, which is itself the mirror of the
 * server's `SERVED`, so a facet added to the type and forgotten here does not compile.
 *
 * Twelve entries onto eight keys, so it is deliberately not one-to-one. `status` appears
 * twice, and [NEGATED_FACET] is what tells the two apart.
 */
const FACET_KEY = {
  project: "project",
  status: "status",
  statusNot: "status",
  priority: "priority",
  assignee: "assignee",
  unassigned: "assignee",
  cycle: "cycle",
  label: "label",
  openedForDays: "open",
  unestimated: "estimate",
  estimateMin: "estimate",
  estimateMax: "estimate",
} satisfies Record<keyof ViewFilters, FilterKey>;

/**
 * The facet a key compiles to with a `-` in front of it.
 *
 * One entry, and there will only ever be as many as `ViewFilters` has `…Not` keys. A key
 * absent from here cannot be negated at all, and that is not a gap to be filled later:
 * `-priority:high` is a question the server has no column for, so refusing it is the
 * truthful answer rather than a missing feature.
 */
export const NEGATED_FACET: Partial<Record<FilterKey, keyof ViewFilters>> = {
  status: "statusNot",
};

/** The key a facet is asked through — what a chip's `×` has to rewrite. */
export const filterKeyOf = (facet: keyof ViewFilters): FilterKey | undefined => FACET_KEY[facet];

/**
 * The facets a key compiles to, in the order the strip draws them — which is what the
 * `served` gate is checked against, since the server serves facets and not keys.
 */
export const facetsOf = (key: FilterKey, negated = false): (keyof ViewFilters)[] => {
  const negatedFacet = NEGATED_FACET[key];
  if (negated) return negatedFacet ? [negatedFacet] : [];
  return FACET_ORDER.filter((facet) => FACET_KEY[facet] === key && facet !== negatedFacet);
};

/**
 * The eight keys, in the order the strip draws the facets they answer.
 *
 * Read out of `FACET_ORDER` rather than written again, for the reason `chips.ts` reads it:
 * a hand-written list here would be the third copy of the strip's left-to-right, and the
 * one nothing checks.
 */
export const FILTER_KEYS: readonly FilterKey[] = [
  ...new Set(FACET_ORDER.map((facet) => FACET_KEY[facet])),
];
