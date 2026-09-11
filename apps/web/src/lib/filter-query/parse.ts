import { CATEGORY_LABELS, labelOfKey } from "@/lib/statuses";
import { facetByKey } from "@/components/organise/facets";
import { EFFORT_POINTS, TICKET_PRIORITIES, DEFAULT_STATUSES, type ViewFilters } from "@/lib/api";
import { PRIORITY_LABELS } from "../status";
import { CATEGORY_ORDER, inOrder, WORKFLOW_ORDER } from "../status-order";
import {
  FILTER_KEYS,
  NEGATED_FACET,
  type FilterCatalog,
  type FilterError,
  type FilterKey,
  type FilterSource,
  type ParsedQuery,
} from "./types";

/**
 * Text to filters: the tokeniser, and the eight readers that turn a word into a facet.
 *
 * The readers live here rather than in a file of their own because they *are* the parser —
 * `KEYS` below is the whole of what the language accepts, and splitting the table from the
 * loop that walks it would put the halves of one decision in two places. `suggest.ts`
 * borrows both, which is why a handful of things here are exported without appearing in
 * `index.ts`: a completion is a question about a token, so it needs the same scanner and
 * the same list of answers, and reimplementing either would be the drift this arrangement
 * exists to prevent.
 *
 * ## Why the vocabulary is not written down here
 *
 * The statuses come from `DEFAULT_STATUSES` and their sequence from `WORKFLOW_ORDER`; the
 * priorities from `TICKET_PRIORITIES`; the offered bounds from `facets.ts`' own
 * `suggested`. Every word this file could have typed out itself is read from the file that
 * already owns it. That is not tidiness — `facets.ts` sets out at length what happens when
 * a vocabulary exists twice, and a parser is the worst place for the second copy: a status
 * this file did not know about would not be a missing row in a menu, it would be a word
 * the reader typed correctly and got underlined in red for.
 *
 * The projects, people, cycles and labels cannot be read from anywhere — they are rows, and
 * this module has no network. They arrive in a `FilterCatalog`, which is also where `@me`
 * and `current` are resolved, so nothing here holds an id or a handle.
 */

// --- the eight keys --------------------------------------------------------

/** One answer a key takes, as both halves of the language see it. */
export type Answer = { token: string; label: string; filters: ViewFilters };

export type KeyDef = {
  /** How an error names what this key takes: `"urgnet" is not a priority`. */
  noun: string;
  answers: (catalog: FilterCatalog, negated: boolean) => Answer[];
  /**
   * The answers that are not a vocabulary but a comparison — `open:>9d`, `estimate:4..`.
   *
   * `facets.ts` makes this argument about its own number controls and it holds here: `4`
   * is the legitimate question "bigger than a 3" and the server has no scale to refuse it
   * against, so a language that only took the six offered sizes would be narrower than
   * the endpoint behind it.
   */
  freeform?: (value: string) => ViewFilters | undefined;
  /** Whether a half-typed comparison is on its way somewhere legal. See [parse]'s grace. */
  partial?: (value: string) => boolean;
  /** Overrides the question a key row shows, where one key spans several facets. */
  detail?: (negated: boolean) => string;
};

/**
 * The statuses, sequenced as the work flows.
 *
 * Membership from `DEFAULT_STATUSES`, sequence from `WORKFLOW_ORDER`, joined by `inOrder` —
 * which is `status-order.ts`' whole doctrine and not a flourish. The two lists are spelled
 * identically today, and the day they are not, a seventh status appears in the completion
 * list at the end rather than being unspellable.
 */
const STATUSES = inOrder(DEFAULT_STATUSES, WORKFLOW_ORDER);

const named = (
  catalog: FilterCatalog,
  source: FilterSource,
  toFilters: (id: string) => ViewFilters,
): Answer[] =>
  (catalog.options[source] ?? []).map((option) => ({
    token: option.token,
    label: option.label ?? option.token,
    filters: toFilters(option.id),
  }));

const labelOf = (catalog: FilterCatalog, source: FilterSource, id: string) =>
  catalog.options[source]?.find((option) => option.id === id)?.label;

const openFacet = facetByKey("openedForDays");

/**
 * Every key of the language: what it is called in an error, what answers it takes, and the
 * two comparisons that are not a vocabulary at all.
 *
 * Exported for `suggest.ts` and for nothing else — the same eight readers answer "what
 * does this word mean" and "what could this word be", and two tables would drift.
 */
export const KEYS: Record<FilterKey, KeyDef> = {
  project: {
    noun: "project",
    answers: (catalog) => named(catalog, "project", (id) => ({ project: [id] })),
  },

  status: {
    noun: "status",
    answers: (_catalog, negated) =>
      STATUSES.map((status) => ({
        token: status,
        label: labelOfKey(status),
        filters: negated ? { statusNot: [status] } : { status: [status] },
      })),
    detail: (negated) => facetByKey(negated ? "statusNot" : "status").label,
  },

  meaning: {
    noun: "meaning",
    // The wire's word as the token, exactly as `status` above spells `in_progress` — a
    // closed vocabulary is typed by its key and read by its label. `CATEGORY_ORDER` and
    // not `Object.keys`, so the suggestion list is offered in the order every other
    // surface stacks them in.
    answers: () =>
      CATEGORY_ORDER.map((category) => ({
        token: category,
        label: CATEGORY_LABELS[category],
        filters: { category: [category] },
      })),
  },

  priority: {
    noun: "priority",
    // `priority:none` is a priority called "No priority" and not the absence of an answer,
    // which is the one place the sentinel `none` means something other than "nothing".
    // Its own vocabulary entry, so nothing has to special-case the word.
    answers: () =>
      TICKET_PRIORITIES.map((priority) => ({
        token: priority,
        label: PRIORITY_LABELS[priority],
        filters: { priority: [priority] },
      })),
  },

  assignee: {
    noun: "person",
    answers: (catalog) => [
      ...(catalog.me
        ? [
            {
              token: "@me",
              label: labelOf(catalog, "assignee", catalog.me) ?? "Me",
              filters: { assignee: [catalog.me] },
            },
          ]
        : []),
      ...named(catalog, "assignee", (id) => ({ assignee: [id] })),
      { token: "none", label: facetByKey("unassigned").label, filters: { unassigned: true } },
    ],
  },

  cycle: {
    noun: "cycle",
    answers: (catalog) => [
      ...named(catalog, "cycle", (id) => ({ cycle: [id] })),
      ...(catalog.currentCycleId
        ? [
            {
              token: "current",
              label: labelOf(catalog, "cycle", catalog.currentCycleId) ?? "Current cycle",
              filters: { cycle: [catalog.currentCycleId] },
            },
          ]
        : []),
    ],
  },

  label: {
    noun: "label",
    answers: (catalog) => named(catalog, "label", (id) => ({ label: [id] })),
  },

  open: {
    noun: "age",
    answers: () =>
      (openFacet.suggested ?? []).map((days) => ({
        token: `>${days}d`,
        label: `${openFacet.label} ${days}${openFacet.unit ? ` ${openFacet.unit}` : ""}`,
        filters: { openedForDays: days },
      })),
    // Four spellings of one question, because there is no second reading available for a
    // bare number after `open:` and a reader who leaves the `>` or the `d` off has still
    // said the only thing this key can mean. `format` writes one of the four.
    freeform: (value) => {
      const asked = /^>?(\d+)d?$/.exec(value);
      return asked ? { openedForDays: Number(asked[1]) } : undefined;
    },
    partial: (value) => /^>?\d*d?$/.test(value),
  },

  estimate: {
    noun: "estimate",
    answers: () => [
      { token: "none", label: facetByKey("unestimated").label, filters: { unestimated: true } },
      ...EFFORT_POINTS.map((points) => ({
        token: `${points}..`,
        label: `${facetByKey("estimateMin").label} ${points}`,
        filters: { estimateMin: points },
      })),
    ],
    // `3..8`, `3..`, `..8` and `5`. The open ends are not an invention on top of the
    // spec's range: `ViewFilters` takes either bound alone, the chip strip prints `≥ 5`
    // and `≤ 5` separately, and a `format` that could not write one of them alone would
    // turn "points at most 8" into no text at all the moment a chip came off.
    freeform: (value) => {
      const range = /^(\d*)\.\.(\d*)$/.exec(value);
      if (range) {
        const [, min, max] = range;
        if (min === "" && max === "") return undefined;
        return {
          ...(min === "" ? {} : { estimateMin: Number(min) }),
          ...(max === "" ? {} : { estimateMax: Number(max) }),
        };
      }
      // A bare number is the range with both ends the same — "exactly a 5", which the
      // server answers and `5..5` already spells. Erroring on it instead would put a red
      // underline under the first character of every range anybody types.
      if (/^\d+$/.test(value)) return { estimateMin: Number(value), estimateMax: Number(value) };
      return undefined;
    },
    partial: (value) => /^[\d.]*$/.test(value),
    detail: () => "Points, a range, or none",
  },
};

/** A word before a colon, if it is one of the eight. The one lookup that may fail. */
export const keyDef = (word: string): KeyDef | undefined =>
  Object.hasOwn(KEYS, word) ? KEYS[word as FilterKey] : undefined;

// --- scanning --------------------------------------------------------------

export type Span = { text: string; start: number; end: number };

/**
 * The tokens, with their offsets. Whitespace is the only separator, and there is no
 * escape for it: every error and every completion is a span into the text the reader can
 * see, so the scanner must never move a character.
 */
export function scan(text: string): Span[] {
  const tokens: Span[] = [];
  const word = /\S+/g;
  let found = word.exec(text);
  while (found) {
    tokens.push({ text: found[0], start: found.index, end: found.index + found[0].length });
    found = word.exec(text);
  }
  return tokens;
}

export type Split = {
  negated: boolean;
  key: Span;
  /** Whether a colon has been typed. Its absence is a reader mid-word, usually. */
  colon: boolean;
  /** The comma-separated answers. Empty ones are kept: a caret can be inside one. */
  values: Span[];
};

/** One token, cut at its `-`, its colon and its commas, every piece keeping its offsets. */
export function split(token: Span): Split {
  const negated = token.text.startsWith("-");
  const body = negated ? token.text.slice(1) : token.text;
  const bodyStart = token.start + (negated ? 1 : 0);
  const colonAt = body.indexOf(":");

  if (colonAt === -1) {
    return {
      negated,
      key: { text: body, start: bodyStart, end: token.end },
      colon: false,
      values: [],
    };
  }

  const rest = body.slice(colonAt + 1);
  const restStart = bodyStart + colonAt + 1;
  const values: Span[] = [];
  let at = 0;
  for (const piece of rest.split(",")) {
    values.push({ text: piece, start: restStart + at, end: restStart + at + piece.length });
    at += piece.length + 1;
  }

  return {
    negated,
    key: { text: body.slice(0, colonAt), start: bodyStart, end: bodyStart + colonAt },
    colon: true,
    values,
  };
}

// --- parse -----------------------------------------------------------------

/**
 * Text to filters, and a complaint per word that could not be one.
 *
 * ## The tail is never an error
 *
 * A reader mid-word is the normal case. So a word that runs to the **end of the input** is
 * given the benefit of the doubt whenever it is a prefix of something legal: `stat` is on
 * its way to `status`, `status:` to an answer, `status:to` to `todo`, `estimate:3.` to a
 * range. It contributes no filter and no error.
 *
 * The grace is positional and not caret-based, and the position is "runs to the end of the
 * text" rather than "is the last token" — so a trailing space commits the word, and
 * `status:to priority:high` does complain, because the reader typed past it. There is no
 * caret here on purpose: [parse]'s job is to compile the line, `suggest`'s is to know
 * where the caret is, and giving both the same argument would invite a caller to pass one
 * a stale copy of the other.
 *
 * ## Nothing that parsed is ever thrown away
 *
 * Errors accumulate beside the filters rather than replacing them. Half a line is what a
 * line looks like while it is being typed, and a list that emptied itself on every
 * keystroke that had not finished would be unusable — which is the whole reason the
 * errors carry spans instead of this function returning a failure.
 *
 * ## What is deliberately *not* gated
 *
 * `served` — the facets `GET /api/tickets/filters` says the server will answer — gates what
 * `suggest` offers and nothing else. This reads a token whether or not the server serves
 * it. `facets.ts` gates hard and says why, and this is the same rule applied to a different
 * surface: refusing a word because a cached list has not landed yet would underline correct
 * text in red on first paint, and the gate that actually matters is still
 * `savedViewFilters` on the way out.
 */
export function parse(text: string, catalog: FilterCatalog): ParsedQuery {
  let filters: ViewFilters = {};
  const errors: FilterError[] = [];

  for (const token of scan(text)) {
    const parts = split(token);
    const key = parts.key.text as FilterKey;
    const def = keyDef(parts.key.text);

    if (!def) {
      // A bare `-` or a half-typed key at the very end is somebody reaching for a filter.
      const reaching =
        !parts.colon && token.end === text.length && isKeyPrefix(parts.key.text, parts.negated);
      if (!reaching) {
        errors.push({
          code: "unknown-key",
          ...parts.key,
          message: `No filter is spelled "${parts.key.text}"`,
        });
      }
      continue;
    }

    if (parts.negated && NEGATED_FACET[key] === undefined) {
      errors.push({
        code: "not-negatable",
        start: token.start,
        end: parts.key.end,
        text: `-${key}`,
        key,
        message: `Only "status" can be negated; "-${key}" cannot`,
      });
      continue;
    }

    const answers = def.answers(catalog, parts.negated);
    const given = parts.values.filter((value) => value.text !== "");

    if (given.length === 0) {
      if (token.end !== text.length) {
        errors.push({
          code: "missing-value",
          ...parts.key,
          key,
          message: `"${key}" needs a value, as in "${key}:${answers[0]?.token ?? "…"}"`,
        });
      }
      continue;
    }

    for (const value of given) {
      const answer = answers.find((candidate) => candidate.token === value.text);
      if (answer) {
        filters = merge(filters, answer.filters);
        continue;
      }
      const freeform = def.freeform?.(value.text);
      if (freeform) {
        filters = merge(filters, freeform);
        continue;
      }
      // Still being typed, by the same rule the key half gets.
      if (value.end === text.length && isValuePrefix(def, answers, value.text)) continue;

      const validFor = keyTaking(value.text, key, catalog);
      errors.push({
        code: "unknown-value",
        ...value,
        key,
        validFor,
        message: validFor
          ? `"${value.text}" is not a ${def.noun}; "${validFor}:${value.text}" is`
          : `"${value.text}" is not a ${def.noun}`,
      });
    }
  }

  return { filters, errors };
}

const isKeyPrefix = (typed: string, negated: boolean) =>
  FILTER_KEYS.some(
    (key) => key.startsWith(typed) && (!negated || NEGATED_FACET[key] !== undefined),
  );

const isValuePrefix = (def: KeyDef, answers: Answer[], typed: string) =>
  answers.some((answer) => answer.token.startsWith(typed)) || (def.partial?.(typed) ?? false);

/** The key that *would* have taken this word — `status:urgent`'s "did you mean priority". */
function keyTaking(value: string, except: FilterKey, catalog: FilterCatalog): FilterKey | undefined {
  return FILTER_KEYS.find(
    (key) => key !== except && KEYS[key].answers(catalog, false).some((one) => one.token === value),
  );
}

/**
 * Two tokens' worth of filters, added together.
 *
 * `status:todo status:done` is one facet with two answers and not the second overwriting
 * the first, because that is what the facet means: `filterParams` repeats the key and the
 * server reads the repeats as "either". Scalars have no union, so the last one typed wins.
 *
 * The cast is `facets.ts`' cast and has its reason: `ViewFilters` is a union of shapes per
 * key and TypeScript cannot narrow it from a string read out of an object at runtime. What
 * makes the pairing true is [KEYS] above, whose `answers` are checked against `ViewFilters`.
 */
function merge(base: ViewFilters, added: ViewFilters): ViewFilters {
  const next: Record<string, unknown> = { ...base };
  for (const [key, value] of Object.entries(added)) {
    const current = next[key];
    if (Array.isArray(current) && Array.isArray(value)) {
      next[key] = [...current, ...value.filter((one) => !current.includes(one))];
    } else {
      next[key] = value;
    }
  }
  return next as ViewFilters;
}
