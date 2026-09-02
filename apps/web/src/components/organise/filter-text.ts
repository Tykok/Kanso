import {
  filterKeyOf,
  format,
  type FilterError,
  type FilterKey,
  type FilterNames,
  type FilterOption,
} from "@/lib/filter-query";
import type { ViewFilters } from "@/lib/api";

/**
 * The four answers the filter box needs that are about *text* rather than about React.
 *
 * `lib/filter-query/` reads a line, writes a line and says what could come next. None of
 * those three is what an editable box also has to know: which characters to underline,
 * where the caret lands after a completion is accepted, which word a chip's `×` rewrites,
 * and how a row fetched from the server is spelled as a token in the first place. All four
 * are pure functions of strings, so they are here and tested here rather than being
 * asserted through a rendered input.
 *
 * They are deliberately *not* in `lib/filter-query/`: that folder is the language, and a
 * language does not know that its errors get underlined or that a chip exists.
 */

// --- underlining -----------------------------------------------------------

/** One run of the line, and the complaint about it if there is one. */
export type Highlight = { text: string; error?: FilterError };

/**
 * The line cut into runs, so the box can draw a wavy underline under the bad words.
 *
 * The whole line comes back, error-free runs included, because the mirror behind the input
 * has to be character-for-character the same text as the input: a run left out would shift
 * everything after it and the underline would land under the wrong word.
 *
 * Overlapping complaints cannot happen today — `parse` reports one error per word — but a
 * later error that spanned a whole token would overlap the value errors inside it, so the
 * first complaint wins rather than the segments being allowed to double up and desynchronise
 * the mirror.
 */
export function highlights(text: string, errors: readonly FilterError[]): Highlight[] {
  const sorted = errors
    .filter((error) => error.start < error.end && error.start >= 0 && error.end <= text.length)
    .sort((a, b) => a.start - b.start);

  const runs: Highlight[] = [];
  let at = 0;
  for (const error of sorted) {
    if (error.start < at) continue;
    if (error.start > at) runs.push({ text: text.slice(at, error.start) });
    runs.push({ text: text.slice(error.start, error.end), error });
    at = error.end;
  }
  if (at < text.length) runs.push({ text: text.slice(at) });
  return runs;
}

// --- accepting a completion ------------------------------------------------

/**
 * A suggestion spliced over the span `suggest` handed back, and where the caret ends up.
 *
 * The span is the whole word under the caret, which is what makes accepting `todo` over
 * `status:tdoo` a *correction* rather than an append — so this replaces rather than
 * inserts, and the caret goes to the end of what was written, not to the end of the line.
 *
 * A key's `insert` carries its own colon and is not a finished word, so nothing is added
 * after it: the reader (or their next click) is about to answer it. A finished value typed
 * at the end of the line gets a space, and the caret goes past it — which is the whole of
 * what makes the box composable by pointer alone. With the caret after the space, `suggest`
 * is outside every token and offers the eight keys again, so a reader who never types can
 * click key, value, key, value to the end of a question.
 *
 * Mid-line there is no space to add: whatever follows already separates the words, and a
 * completion in the middle of a line is a correction rather than the next step of a
 * composition.
 */
export function applySuggestion(
  text: string,
  span: { start: number; end: number },
  insert: string,
): { text: string; caret: number } {
  const before = text.slice(0, span.start);
  const after = text.slice(span.end);
  const space = after === "" && !insert.endsWith(":");
  return {
    text: `${before}${insert}${space ? " " : ""}${after}`,
    caret: before.length + insert.length + (space ? 1 : 0),
  };
}

// --- a chip's × ------------------------------------------------------------

/**
 * The facets `format` writes with a `-` in front of them.
 *
 * `NEGATED_FACET` in `lib/filter-query/types.ts` is the same fact and is not exported past
 * that folder's barrel, so this is a second copy of one entry — and the test beside this
 * file pins it against `format` rather than against the copy, so the two cannot drift
 * without something going red.
 */
const NEGATED: ReadonlySet<keyof ViewFilters> = new Set<keyof ViewFilters>(["statusNot"]);

/**
 * A chip's `×`: the facet gone from the question, and its token rewritten in the line.
 *
 * Both truths are edited, each in its own vocabulary — the question loses a facet, the line
 * loses a word — and neither is derived from the other. That is the whole of §7's "one
 * direction each" applied to the one gesture that has to touch both.
 *
 * The two tempting one-liners are both wrong:
 *
 *  - **Rewrite the line and re-read it.** Then a chip's `×` depends on the rest of the line
 *    parsing. A saved view whose catalogue has not landed prints its ids, `parse` reads none
 *    of them, and taking the Label chip off would silently take the project filter with it.
 *  - **Drop the facet and reformat the whole line.** That throws away whatever the reader
 *    had half-typed, and turns their `cycle:current` into `cycle:24` under their caret.
 *
 * ## Why the token is rewritten and not just deleted
 *
 * A key can answer more than one facet, and then one token holds both: `estimate:3..8` is
 * `estimateMin` and `estimateMax`, and `assignee:@me assignee:none` is `assignee` beside
 * `unassigned`. Deleting by key would take the survivor with it and leave the chips claiming
 * a filter the line no longer holds — a disagreement that goes unnoticed until `↵` drops it.
 * So the key's tokens are replaced by whatever `format` still writes for that key, which is
 * the language's own answer to "what is left to say here".
 */
export function withoutFacet(
  text: string,
  filters: ViewFilters,
  facet: keyof ViewFilters,
  names: FilterNames = {},
): { text: string; filters: ViewFilters } {
  const next = { ...filters };
  delete next[facet];

  const key = filterKeyOf(facet);
  if (key === undefined) return { text, filters: next };

  const negated = NEGATED.has(facet);
  const survivors = tokens(format(next, names)).filter(
    (token) => token.key === key && token.negated === negated,
  );
  return { text: splice(text, key, negated, survivors.map((token) => token.text)), filters: next };
}

/**
 * One token, and which key it asks through.
 *
 * ## A second scanner, and why it is not a second opinion
 *
 * `parse.ts` says a scanner in a second file would be a second opinion about where a word
 * starts. This splits on `\S+` because that is the *whole* of the language's own rule:
 * whitespace separates, and `types.ts` states there is no quoting and no plan for any — so
 * there is no judgement here to disagree with. What the test does about it is stronger than
 * a comment: it asserts the result through `parse`, so the two agreeing is checked rather
 * than claimed.
 *
 * A word with no colon has no key. A bare `status` is a reader mid-word, not a question
 * being asked — it draws no chip, so no chip's `×` has any business deleting it.
 */
type Token = { text: string; start: number; end: number; key?: string; negated: boolean };

function tokens(text: string): Token[] {
  const found: Token[] = [];
  const word = /\S+/g;
  let match = word.exec(text);
  while (match) {
    const negated = match[0].startsWith("-");
    const body = negated ? match[0].slice(1) : match[0];
    const colonAt = body.indexOf(":");
    found.push({
      text: match[0],
      start: match.index,
      end: match.index + match[0].length,
      key: colonAt === -1 ? undefined : body.slice(0, colonAt),
      negated,
    });
    match = word.exec(text);
  }
  return found;
}

/**
 * The key's tokens replaced by `kept`, in place.
 *
 * In place, because the line redraws under the reader's caret: a token that moved to the end
 * every time a range lost a bound would be the strip's reshuffling problem, on a surface
 * where it also moves the cursor. With nothing kept the words come out along with one of the
 * spaces around them — so removing the middle of three leaves no double space, and removing
 * the last leaves no trailing one, which would otherwise turn the word before it back into
 * the graced tail.
 */
function splice(text: string, key: FilterKey, negated: boolean, kept: readonly string[]): string {
  const mine = tokens(text).filter((token) => token.key === key && token.negated === negated);
  const rest = kept.join(" ");

  if (mine.length === 0) {
    if (rest === "") return text;
    // Nothing to replace — the reader never typed this key, and the question holds it
    // anyway. Appending is the only place a token may appear somewhere the reader did not
    // put it, and it is where they would have typed it themselves: at the end.
    return text === "" ? rest : `${text.replace(/\s+$/, "")} ${rest}`;
  }

  let written = "";
  let at = 0;
  mine.forEach((token, index) => {
    let from = token.start;
    let end = token.end;
    const replacement = index === 0 ? rest : "";
    if (replacement === "") {
      if (/\s/.test(text[end] ?? "")) end += 1;
      else if (/\s/.test(text[from - 1] ?? "")) from -= 1;
    }
    written += text.slice(at, from) + replacement;
    at = end;
  });
  return written + text.slice(at);
}

// --- spelling the catalogue ------------------------------------------------

/**
 * A fetched row's name, as a word that can appear in the line.
 *
 * `FilterOption` requires a token holding no whitespace, comma or colon, and says the
 * screen building the catalogue is what decides the spelling. This is that decision:
 * accents folded, everything that is not a letter or a digit becomes a hyphen. `Étiquette
 * synchro` is typed `etiquette-synchro`, and the completion row still reads the real name,
 * because `label` on the option is what a reader sees and `token` is only what they type.
 */
export function filterToken(name: string): string {
  return name
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

/**
 * Rows to options, with every token distinct.
 *
 * Two projects called `Sync` would otherwise be one word, and the round trip would be a
 * lie in the worst possible way: `format` would print the second project's id as `sync`
 * and `parse` would read it back as the first one, so a saved view would quietly change
 * which project it was about. The second gets `sync-2`, which is ugly and true.
 *
 * A row whose name slugs to nothing — `···` — is spelled by its id. Unreadable, and still
 * better than a token that cannot be typed or an option silently missing from the list.
 */
export function filterOptions(rows: readonly { id: string; name: string }[]): FilterOption[] {
  const taken = new Map<string, number>();
  return rows.map((row) => {
    const base = filterToken(row.name) || row.id;
    const seen = (taken.get(base) ?? 0) + 1;
    taken.set(base, seen);
    return { token: seen === 1 ? base : `${base}-${seen}`, id: row.id, label: row.name };
  });
}
