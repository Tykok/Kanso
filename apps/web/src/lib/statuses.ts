import { DEFAULT_STATUSES, type Team, type TicketStatus } from "./api";
import {
  categoryOf,
  seededColour,
  seededLabel,
  STATUS_CATEGORY,
  STATUS_LABELS,
  type StatusCategory,
} from "./status";
import { CATEGORY_ORDER } from "./status-order";
import type { Scope } from "@/store/ui";

/**
 * Which words a screen prints for a status, and in what order — `KAN-28`.
 *
 * The keys never move: `DEFAULT_STATUSES` is still the vocabulary on the wire, still a
 * literal, and a saved view built last month still addresses `in_progress`. What a team
 * owns is the *word* and the *order*, and both arrive on `Team.statuses` — so everything
 * here is a lookup into that, with Kanso's own six as the answer when there is nothing to
 * look in: a draft with no team, a team not fetched yet, a status a catalogue has never
 * heard of.
 *
 * Pure, and in `lib` rather than in a component, for the reason `status-order.ts` beside it
 * gives about its own halves: these are decisions several screens make and none of them
 * owns.
 */

/** One entry of a scope's vocabulary — a status of a team, or a category across teams. */
export type Vocabulary = { key: string; label: string; category: StatusCategory };

/**
 * The five categories as a reader would say them.
 *
 * Not `unstarted` and `completed`, which are the wire's words for a reading Kanso does
 * internally. A category only ever reaches a screen as the header of a scope spanning
 * teams, and whoever reads that header never chose those words.
 */
export const CATEGORY_LABELS: Record<StatusCategory, string> = {
  backlog: "Backlog",
  unstarted: "Not started",
  started: "In flight",
  completed: "Done",
  canceled: "Canceled",
};

/**
 * The word [teamId]'s own catalogue uses for [status].
 *
 * The row's team and not the scope's: a cross-team list draws rows from several
 * vocabularies at once, and a row has to read as its own team reads it. A draft has no
 * team to ask and answers with Kanso's word, which is what its composer offered.
 */
export function labelOf(
  teams: readonly Team[],
  teamId: string | undefined,
  status: TicketStatus,
): string {
  const own = teams.find((team) => team.id === teamId)?.statuses.find((row) => row.key === status);
  // The key itself, last: a status no catalogue can name is a saved view older than a
  // rename or a team still loading, and a blank pill reads as one that failed to load.
  return own?.label ?? seededLabel(status) ?? status;
}

/**
 * A readable word for a status key, with no team to ask — the public pages' fallback.
 *
 * Kanso's own label when it ships the key, and the key itself otherwise: a public page
 * has no catalogue to fetch, and a team's `devis` printed as `devis` is worse than
 * nothing only in the sense that nothing would have been blank. The roadmap's *cards*
 * are the only readers, and the column above them is a category with a proper word.
 */
export function labelOfKey(status: TicketStatus): string {
  return seededLabel(status) ?? status;
}

/**
 * A colour for a status key with no team to ask — the read for a row drawn outside a
 * team-scoped screen.
 *
 * Kanso's own token when it ships the key, and a neutral rule otherwise. Deliberately not
 * a guess at the meaning: without the owning team's catalogue there is nothing to guess
 * from, and a word drawn in `done` green because it happened to hash there would be worse
 * than one drawn in grey. Where the category *is* in hand — the charts, the board's own
 * columns — `colourOf` uses it.
 */
export function colourOfKey(status: TicketStatus): string {
  return seededColour(status) ?? "var(--faint)";
}

/**
 * What [status] means for [teamId] — the client half of `StatusCategories.categoryOf`.
 *
 * The row's team and not the scope's, for the same reason [labelOf] reads that way: a
 * cross-team list draws rows from several vocabularies at once, and two teams may declare
 * one key in two categories.
 *
 * The same fallback chain as the server's, in the same order and for the same reasons: the
 * team's catalogue, then Kanso's six for a draft with no team to ask, then `unstarted` —
 * unreachable rather than lenient. A row whose category cannot be resolved is a team still
 * loading or a saved view older than a removal, and counting it as unstarted work leaves a
 * chart one row short rather than throwing inside a render.
 */
export function categoryOfTicket(
  teams: readonly Team[],
  teamId: string | undefined,
  status: TicketStatus,
): StatusCategory {
  const own = teams.find((team) => team.id === teamId)?.statuses.find((row) => row.key === status);
  return own?.category ?? STATUS_CATEGORY[status] ?? "unstarted";
}

/**
 * The statuses one ticket may actually be moved to, in its team's order.
 *
 * Its team's catalogue, because `tickets_status_fk` would refuse anything else — the
 * control has to offer what the row can hold. A ticket with no team answers Kanso's six,
 * which is what its composer offered it: *not* the categories, because a draft holds a
 * status and `unstarted` is not one. That is the whole difference from [vocabularyOf],
 * which answers what a *screen* stacks by.
 */
export function optionsFor(teams: readonly Team[], teamId: string | undefined): Vocabulary[] {
  const own = teams.find((team) => team.id === teamId)?.statuses;
  if (own) return own.map((row) => ({ key: row.key, label: row.label, category: row.category }));
  return DEFAULT_STATUSES.map((key) => ({
    key,
    label: STATUS_LABELS[key],
    category: categoryOf(key),
  }));
}

/**
 * The statuses this scope can offer, in the order it reads them.
 *
 * One team means its own list, in its own order — `team_statuses.position`. Anything wider
 * means the five categories, because a scope holding two vocabularies has no single word
 * for a bucket and the server groups it that way for the same reason. A project scope is
 * the wider one: `KAN-9` gives a project no team in particular.
 */
export function vocabularyOf(teams: readonly Team[], scope: Scope): Vocabulary[] {
  if (scope.kind === "team") {
    const own = teams.find((team) => team.id === scope.id)?.statuses;
    if (own) {
      return own.map((row) => ({ key: row.key, label: row.label, category: row.category }));
    }
    // Not fetched yet. Kanso's six rather than nothing: a menu with no items reads as a
    // control that is broken, and this one is right for every team that has not renamed.
    return optionsFor(teams, scope.id);
  }
  return CATEGORY_ORDER.map((category) => ({
    key: category,
    label: CATEGORY_LABELS[category],
    category,
  }));
}

/**
 * A bucket from the grouped endpoint, named.
 *
 * The endpoint buckets by status inside one team and by category across teams, and this is
 * the same decision read from the same scope — so a header always names a bucket that is
 * actually there.
 */
export function bucketLabel(teams: readonly Team[], scope: Scope, key: string): string {
  return vocabularyOf(teams, scope).find((row) => row.key === key)?.label ?? key;
}
