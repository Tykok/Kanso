import { EFFORT_POINTS, type ViewFilters } from "@/lib/api";

/**
 * The questions a filter may ask, and the three edits a control makes to a set of them.
 *
 * `chips.ts` draws a composed filter set; this is where one comes from. The two halves
 * are deliberately separate modules — a chip is read by a screen that cannot compose
 * (a view somebody else shared), and the strip's order is the one thing they share, so
 * it lives here and `chips.ts` reads it out.
 *
 * ## What is *not* here
 *
 * The list of facets the server will answer. That arrives from `GET /api/tickets/filters`
 * and is passed into [composableFacets], which is the only door onto [FACETS]. Writing
 * the twelve names down here as the offered list is precisely the two-copies-of-one-
 * vocabulary problem the server's own gate exists to make unnecessary: the copy drifts,
 * and the symptom is a chip drawn on screen that the server answers with a 400.
 *
 * What is here is a *control* per name — how the question reads, what shape of answer it
 * takes, and where the answers come from. That is knowledge about a screen, and the
 * server has none. The two lists meet in [composableFacets] and only what is in both is
 * offered: a name the server serves and no control here can draw is left out rather than
 * guessed at, and a control here for a name the server has stopped serving is unreachable
 * rather than broken.
 */

/**
 * The three shapes twelve facets actually come in.
 *
 * One control draws all three, because they differ only in where the answers come from:
 * `many` offers a list to pick from and keeps every pick, `flag` offers exactly one
 * answer which is the question itself, `number` offers a few useful bounds and takes any
 * other typed in. Six bespoke controls would be six places for the keyboard to work
 * differently.
 */
export type FacetKind = "many" | "flag" | "number";

/**
 * Which list of answers a `many` facet is picked from. Named rather than carried, because
 * four of the six are fetched — the surface drawing the control has the queries, and this
 * module is imported by a test suite with no network.
 */
export type FacetSource =
  "status" | "category" | "priority" | "project" | "assignee" | "cycle" | "label";

export type Facet = {
  key: keyof ViewFilters;
  /** The question, as the "add a filter" list asks it: `Status is`, `Open for more than`. */
  label: string;
  kind: FacetKind;
  /** Where a `many` facet's answers come from. */
  source?: FacetSource;
  /** A `number` facet's offered bounds — never the only ones it will take. */
  suggested?: readonly number[];
  /** What a `number` facet's answer is measured in, printed after it. */
  unit?: string;
};

/**
 * Every facet the client can draw, left to right as the chip strip has them — so the
 * order a question is offered in is the order it is drawn in once it has been asked.
 *
 * The `satisfies` is what keeps this honest: it is checked against every key of
 * `ViewFilters`, which is itself the mirror of the server's `SERVED`. A facet added to
 * the type and forgotten here does not compile.
 */
const CONTROLS = {
  project: { label: "Project is", kind: "many", source: "project" },
  status: { label: "Status is", kind: "many", source: "status" },
  // Its own facet and not a mode of `status`, because "not done" is the useful question
  // and the drawing writes it `Statut ≠ Done`.
  statusNot: { label: "Status is not", kind: "many", source: "status" },
  // `Meaning`, not `Category`: the column is called `category` and no reader chose that
  // word, nor `unstarted` and `completed` under it. The question it asks is the one a
  // scope spanning teams can ask and `Status is` cannot — two vocabularies, one meaning.
  category: { label: "Meaning is", kind: "many", source: "category" },
  priority: { label: "Priority is", kind: "many", source: "priority" },
  assignee: { label: "Assignee is", kind: "many", source: "assignee" },
  unassigned: { label: "Unassigned", kind: "flag" },
  cycle: { label: "Cycle is", kind: "many", source: "cycle" },
  label: { label: "Label is", kind: "many", source: "label" },
  // Days rather than a date, because the question is "how long has this been sitting
  // there" and the answer to that stays true tomorrow. A date would not.
  openedForDays: { label: "Open for more than", kind: "number", suggested: [1, 3, 7, 14, 30], unit: "days" },
  unestimated: { label: "Unestimated", kind: "flag" },
  // Offered off the estimate scale because that is what a ticket can be sized at, but a
  // bound is a comparison and not a vocabulary — `4` is the legitimate question "bigger
  // than a 3", and the server takes it. The control lets one be typed for that reason.
  estimateMin: { label: "Points at least", kind: "number", suggested: EFFORT_POINTS },
  estimateMax: { label: "Points at most", kind: "number", suggested: EFFORT_POINTS },
} satisfies Record<keyof ViewFilters, Omit<Facet, "key">>;

export const FACETS: readonly Facet[] = Object.entries(CONTROLS).map(([key, control]) => ({
  key: key as keyof ViewFilters,
  ...control,
}));

/** The strip's own left-to-right, read out of the one list that holds it. */
export const FACET_ORDER: readonly (keyof ViewFilters)[] = FACETS.map((facet) => facet.key);

/** Throws on a key no control exists for: a caller naming a dead facet is a bug. */
export function facetByKey(key: keyof ViewFilters): Facet {
  const facet = FACETS.find((candidate) => candidate.key === key);
  if (!facet) throw new Error(`No filter control for "${key}"`);
  return facet;
}

/**
 * The gate: the facets that can actually be asked for, which is the intersection of what
 * the server serves and what this file can draw.
 *
 * [served] is `GET /api/tickets/filters`, and an empty one offers nothing rather than
 * everything — before the list has landed, no facet is known to be answerable, and
 * offering twelve on the strength of a request still in flight is the guess this whole
 * arrangement exists to avoid.
 */
export function composableFacets(served: readonly string[]): Facet[] {
  const answerable = new Set(served);
  return FACETS.filter((facet) => answerable.has(facet.key));
}

/**
 * One answer added, changed or taken back off.
 *
 * `undefined` clears the facet, and so does the last answer of a `many` coming off, a
 * `flag` turned off, and a bound that is not a number. The key is *deleted* rather than
 * emptied every time, which is the rule `withoutChip` already holds to: the server
 * validates the keys it is sent, and `{ status: [] }` is a chip nobody is drawing still
 * riding along on every read and every write.
 */
export function withFacet(
  filters: ViewFilters,
  facet: Facet,
  value: string | number | boolean | undefined,
): ViewFilters {
  const next = { ...filters };
  const clear = () => {
    delete next[facet.key];
    return next;
  };

  if (value === undefined) return clear();

  if (facet.kind === "flag") {
    if (value !== true) return clear();
    // The cast is the price of one function over three: `ViewFilters` is a union of
    // shapes per key and TypeScript cannot narrow it from `facet.kind`, which is a
    // runtime fact. `CONTROLS` above is what makes the pairing true, and it is checked.
    (next as Record<string, unknown>)[facet.key] = true;
    return next;
  }

  if (facet.kind === "number") {
    const asked = Number(value);
    if (!Number.isFinite(asked)) return clear();
    (next as Record<string, unknown>)[facet.key] = asked;
    return next;
  }

  // A `many` facet toggles: the control offers one list and the same row both adds an
  // answer and takes it off, so nothing has to teach two gestures for one question.
  const answer = String(value);
  const current = (filters[facet.key] as string[] | undefined) ?? [];
  const remaining = current.includes(answer)
    ? current.filter((one) => one !== answer)
    : [...current, answer];
  if (remaining.length === 0) return clear();
  (next as Record<string, unknown>)[facet.key] = remaining;
  return next;
}

/** The answers a `many` facet currently holds — what the control ticks. */
export function facetAnswers(filters: ViewFilters, facet: Facet): string[] {
  if (facet.kind === "many") return (filters[facet.key] as string[] | undefined) ?? [];
  const value = filters[facet.key];
  return value === undefined || value === false ? [] : [String(value)];
}

/** One answer a facet offers, already resolved to the name a reader recognises. */
export type FacetOption = { value: string; label: string };

/**
 * One row of the "add a filter" list: a whole question and one of its answers.
 *
 * A flat list of facet-and-answer pairs rather than a menu of facets that opens a second
 * menu of answers. The second menu is a second place for the keyboard to be, and this
 * list is searchable in one field — typing `urg` reaches `Priority is Urgent` without
 * anyone having to know that urgency is a priority.
 */
export type FilterEntry = {
  /** Stable across a retype, so the highlight does not jump when the list narrows. */
  id: string;
  facet: Facet;
  /** What [withFacet] is called with. `undefined` on a row that is already on. */
  value: string | number | boolean | undefined;
  label: string;
  chosen: boolean;
};

/**
 * The rows to draw, narrowed by what has been typed.
 *
 * Every row is a toggle: choosing one that is already on takes it off, which is why
 * [FilterEntry.value] is `undefined` there. One gesture for adding and removing means the
 * list never has to explain itself, and the chip strip's `×` stays the *other* way of
 * saying the same thing rather than the only one.
 *
 * A `number` facet offers a few bounds worth asking for and takes any other typed in:
 * `estimateMin: 4` is the legitimate question "bigger than a 3" and the server has no
 * scale to refuse it against, so a control that only offered the six sizes would be
 * narrower than the endpoint behind it.
 *
 * The number is read off the *end* of what was typed, not from the whole of it, and that
 * is not a detail. Reading the whole field meant `4` offered `Points at least 4` and
 * `points at least 4` offered nothing at all: the words had narrowed the list to one
 * facet and then `Number("points at least 4")` was `NaN`, so the row that would have
 * matched was never built. Nobody finds a bound by typing a bare number into a field
 * that has just been showing them sentences — they type the sentence.
 */
export function filterEntries(
  facets: readonly Facet[],
  options: Partial<Record<FacetSource, readonly FacetOption[]>>,
  filters: ViewFilters,
  query: string,
): FilterEntry[] {
  const typed = query.trim();
  // Digits only: the server takes whole numbers, and `4.5` or `-2` would be a row that
  // composes into a 400 — or, worse, into a bound nothing can match.
  const trailing = /(?:^|\s)(\d+)$/.exec(typed)?.[1];
  const asNumber = trailing === undefined ? Number.NaN : Number(trailing);
  const rows: FilterEntry[] = facets.flatMap((facet): FilterEntry[] => {
    if (facet.kind === "flag") {
      const chosen = filters[facet.key] === true;
      return [{ id: facet.key, facet, value: !chosen, label: facet.label, chosen }];
    }
    if (facet.kind === "number") {
      const current = filters[facet.key];
      const bounds = [...(facet.suggested ?? [])];
      // The typed number, offered as its own row when it is not already one of the
      // suggestions. Also when it *is* the current answer, so a bound somebody set by
      // typing can be taken off the same way it went on.
      if (Number.isFinite(asNumber) && !bounds.includes(asNumber)) bounds.push(asNumber);
      return bounds.map((bound) => ({
        id: `${facet.key}:${bound}`,
        facet,
        value: current === bound ? undefined : bound,
        label: `${facet.label} ${bound}${facet.unit ? ` ${facet.unit}` : ""}`,
        chosen: current === bound,
      }));
    }
    const answers = facetAnswers(filters, facet);
    return (options[facet.source!] ?? []).map((option) => ({
      id: `${facet.key}:${option.value}`,
      facet,
      value: option.value,
      label: `${facet.label} ${option.label}`,
      chosen: answers.includes(option.value),
    }));
  });

  if (typed === "") return rows;
  // Every word, in any order, anywhere in the row: `done not` finds `Status is not Done`.
  // A prefix match would make the facet's own name the only way in, and the answer is
  // what somebody knows — `sync`, `Urgent`, a person's name.
  const words = typed.toLowerCase().split(/\s+/);
  return rows.filter((row) => {
    const haystack = row.label.toLowerCase();
    return words.every((word) => haystack.includes(word));
  });
}

/**
 * The composed set as "save this question" hands it over.
 *
 * The same gate on the way out as on the way in, and not belt and braces: the filters
 * live in a store that outlives any one screen, and a set composed before the served
 * list landed — or against a server that has since been rolled back — would be written
 * into a saved view that then 400s. A view is written once and read every time it is
 * opened, so the wrong moment to find out is later.
 */
export function savedViewFilters(filters: ViewFilters, served: readonly string[]): ViewFilters {
  const answerable = new Set(served);
  return Object.fromEntries(
    Object.entries(filters).filter(([key]) => answerable.has(key)),
  ) as ViewFilters;
}
