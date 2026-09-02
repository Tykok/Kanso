import { facetByKey } from "@/components/organise/facets";
import type { ViewFilters } from "@/lib/api";
import { keyDef, KEYS, scan, split, type Answer, type KeyDef, type Span } from "./parse";
import {
  facetsOf,
  FILTER_KEYS,
  NEGATED_FACET,
  type FilterCatalog,
  type FilterKey,
  type FilterSuggestions,
} from "./types";

/**
 * What could go where the caret is — and the one place `served` is allowed to gate.
 *
 * This file borrows the tokeniser and the answer table from `parse.ts` rather than holding
 * its own, because a completion is a question *about a token*: a second scanner would be a
 * second opinion about where a word starts, and the two would disagree the first time
 * either was fixed. What is genuinely this file's own is the span arithmetic — which word
 * an accepted row replaces — and the gate.
 */

/**
 * Keys before the colon, answers after it.
 *
 * The caret is an offset and not "the end of the text", which is the whole point — a
 * reader who goes back to widen `status:todo` in the middle of a line gets the statuses,
 * not the eight keys. The word under the caret is found by scanning, so nothing depends on
 * the input having told us what it thinks it typed last.
 *
 * The returned span is the word the caret is in, and an accepted suggestion *replaces* it
 * rather than being appended. That is what makes a correction work: with the caret after
 * `status:tdoo`, accepting `todo` has to remove the typo. The filtering is done on the
 * whole word for the same reason.
 *
 * `insert` carries its own colon when the token does not have one yet, so the input never
 * has to know whether it is completing `stat` or the key half of `stat:todo`.
 *
 * `served` gates here and only here. A key none of whose facets the server answers is not
 * offered, and neither is `assignee:none` on a server that does not serve `unassigned` —
 * so the intersection `composableFacets` computes for the chip control is the same
 * intersection the language offers, without either file knowing about the other.
 */
export function suggest(text: string, caret: number, catalog: FilterCatalog): FilterSuggestions {
  const at = Math.max(0, Math.min(caret, text.length));
  const token = scan(text).find((one) => one.start <= at && at <= one.end);

  if (!token) return keySuggestions({ text: "", start: at, end: at }, false, false, catalog);

  const parts = split(token);
  if (!parts.colon || at <= parts.key.end) {
    return keySuggestions(parts.key, parts.negated, parts.colon, catalog);
  }

  const key = parts.key.text as FilterKey;
  const def = keyDef(parts.key.text);
  const word =
    parts.values.find((value) => value.start <= at && at <= value.end) ??
    parts.values[parts.values.length - 1];

  if (!def || (parts.negated && NEGATED_FACET[key] === undefined)) {
    return { kind: "value", start: word.start, end: word.end, items: [] };
  }

  const items = def
    .answers(catalog, parts.negated)
    .filter((answer) => isServed(answer.filters, catalog) && matches(word.text, answer))
    .map((answer) => ({
      id: `${key}:${answer.token}`,
      insert: answer.token,
      label: answer.label,
      detail: detailOf(key, def, parts.negated),
    }));

  return { kind: "value", key, start: word.start, end: word.end, items };
}

function keySuggestions(
  typed: Span,
  negated: boolean,
  colon: boolean,
  catalog: FilterCatalog,
): FilterSuggestions {
  const answerable = new Set(catalog.served);
  const items = FILTER_KEYS.filter(
    (key) =>
      facetsOf(key, negated).some((facet) => answerable.has(facet)) &&
      key.includes(typed.text.toLowerCase()),
  ).map((key) => ({
    id: `key:${key}`,
    // No `-` in the insert: the token already wears it, and the span deliberately starts
    // after it so that a reader who typed `-sta` keeps their minus sign.
    insert: colon ? key : `${key}:`,
    label: key,
    detail: detailOf(key, KEYS[key], negated),
  }));

  return { kind: "key", start: typed.start, end: typed.end, items };
}

/**
 * The question a row shows, in `facets.ts`' words rather than this file's.
 *
 * The fallback reads the key's *unnegated* first facet even when the row is negated, which
 * is never wrong in practice: `status` is the only negatable key and it overrides `detail`
 * outright. Asking for the negated facet here would only make the expression able to be
 * empty, and `facetByKey` throws on nothing.
 */
const detailOf = (key: FilterKey, def: KeyDef, negated: boolean) =>
  def.detail?.(negated) ?? facetByKey(facetsOf(key)[0]).label;

/** Whether the server answers every facet an answer would compile to. */
const isServed = (filters: ViewFilters, catalog: FilterCatalog) => {
  const answerable = new Set(catalog.served);
  return Object.keys(filters).every((facet) => answerable.has(facet));
};

/**
 * Anywhere in the token or in the name, which is `filterEntries`' rule and for its reason:
 * what somebody knows is the answer — `progress`, `Urgent`, a person's name — not that
 * `in_progress` begins with an `i`. The offered order is kept rather than ranked, so the
 * list narrows in place instead of resorting under the highlight.
 */
function matches(typed: string, answer: Answer): boolean {
  const word = typed.trim().toLowerCase();
  if (word === "") return true;
  return answer.token.toLowerCase().includes(word) || answer.label.toLowerCase().includes(word);
}
