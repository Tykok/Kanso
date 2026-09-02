/**
 * The filter language — the query somebody types, compiled to `ViewFilters` and nothing else.
 *
 * ```
 * status:todo,in_progress assignee:@me -status:done estimate:none
 * ```
 *
 * Three pure functions and one direction each. `parse` turns text into filters, `format`
 * turns filters into text, `suggest` answers "what could go here". No hooks, no queries, no
 * `window`: the text, the caret and a catalogue are the whole input, which is what lets the
 * language be a unit test rather than a browser.
 *
 * ## One truth
 *
 * The text is what the reader edits, `ViewFilters` is what the query sends, and the chip
 * strip stays a *rendering* of `ViewFilters`. So `format` must be able to write every facet
 * `parse` can read, or removing a chip would silently drop part of the line. The round-trip
 * property test over a line carrying all twelve facets is what holds the two halves
 * together; it is not a nicety.
 *
 * ## Why four files and not one
 *
 * It was one, at 756 lines, and the whole of it was within this repo's norms in the
 * absolute. `lib/actions/` is the precedent that argues the other way: it was a single
 * 467-line array and it was split, because one expression that several concerns all have to
 * reach into is the expression nobody edits twice. Three public functions with three
 * genuinely separate jobs is that situation, so:
 *
 * - `types.ts` — every shape the three speak in, plus the two maps that state which facet
 *   is reached through which key. No reader, no writer, no completion.
 * - `parse.ts` — the tokeniser and the eight readers. `KEYS` there is the whole of what the
 *   language accepts.
 * - `format.ts` — the other direction, sharing no code with the parser on purpose.
 * - `suggest.ts` — the span arithmetic, and the one place `served` is allowed to gate.
 *
 * `suggest.ts` imports the tokeniser and the answer table from `parse.ts`, which is the one
 * edge between the three and is not incidental: a completion is a question *about a token*,
 * and a second scanner would be a second opinion about where a word starts.
 *
 * This barrel exports exactly what the single file exported, so nothing outside the folder
 * can tell the split happened, and a future rearrangement is again nobody else's business.
 */

export type {
  FilterCatalog,
  FilterError,
  FilterErrorCode,
  FilterKey,
  FilterNames,
  FilterOption,
  FilterSource,
  FilterSuggestions,
  ParsedQuery,
  Suggestion,
} from "./types";
export { FILTER_KEYS, filterKeyOf } from "./types";
export { parse } from "./parse";
export { format, namesFrom } from "./format";
export { suggest } from "./suggest";
