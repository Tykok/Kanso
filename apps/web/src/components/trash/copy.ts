import type { TrashHolding, TrashHoldingKind, TrashItem, TrashKind, TrashParent } from "@/lib/api";

/**
 * Every sentence screen 26 says, as functions rather than as JSX.
 *
 * Separated because the drawing's own load-bearing detail is a sentence — "deleting a
 * document that mentioned two tickets deletes neither ticket, only the reference goes" —
 * and a sentence buried in markup is a sentence no test can hold to account. `vitest`
 * runs under `environment: "node"`, so this module is the only part of the screen the
 * suite can reach at all.
 */

const TYPE_LABELS: Record<TrashKind, string> = {
  ticket: "Ticket",
  doc: "Document",
  view: "View",
  folder: "Folder",
};

export const typeLabel = (kind: TrashKind): string => TYPE_LABELS[kind];

/** The drawing's unit, verbatim: `28 j`. */
export const countdown = (daysLeft: number): string => `${daysLeft} j`;

/**
 * Within a week of being emptied, which is when the countdown turns from information
 * into a warning. The drawing paints `9 j` faint and `2 j` in `--urgent`, so the line is
 * somewhere between three and eight, and a week is the only number in that range anybody
 * would name out loud.
 */
export const isExpiring = (daysLeft: number): boolean => daysLeft <= 7;

/**
 * How long ago, derived from what is left rather than from the timestamp.
 *
 * The server computed `daysLeft` against the clock its own retention sweep reads, so
 * taking the elapsed side from it means the pane and the countdown column cannot
 * disagree — and the client does no date arithmetic at all, which is the rule the rest of
 * this codebase already keeps for anything measured in days.
 */
export function deletedAgo(daysLeft: number, retentionDays: number): string {
  const elapsed = Math.max(0, retentionDays - daysLeft);
  if (elapsed === 0) return "today";
  return elapsed === 1 ? "1 day ago" : `${elapsed} days ago`;
}

/** "Restore into Product". The parent is named, never implied. */
export const restoreLabel = (parent: TrashParent | undefined): string =>
  parent ? `Restore into ${parent.name}` : "Restore";

/**
 * What each kind of holding is called, counted, and what the rider calls it afterwards.
 *
 * `rider` is the noun the second half of the sentence uses — "the tickets were not
 * deleted" — which is not the same phrase as the count's ("2 mentioned tickets"): saying
 * "the mentioned tickets were not deleted" reads as though a different, unmentioned set
 * of tickets was.
 */
const HOLDING_COPY: Record<TrashHoldingKind, { one: string; many: string; rider: string }> = {
  blocks: { one: "block", many: "blocks", rider: "block" },
  mentionedTickets: { one: "mentioned ticket", many: "mentioned tickets", rider: "ticket" },
  linkedDocs: { one: "linked document", many: "linked documents", rider: "document" },
};

const counted = (holding: TrashHolding): string => {
  const copy = HOLDING_COPY[holding.kind];
  return `${holding.count} ${holding.count === 1 ? copy.one : copy.many}`;
};

/** "a and b", "a, b and c" — an Oxford-comma-free list, because the counts are short. */
const listed = (parts: string[]): string =>
  parts.length <= 1 ? (parts[0] ?? "") : `${parts.slice(0, -1).join(", ")} and ${parts.at(-1)}`;

/**
 * The rider, and the whole reason this module is tested.
 *
 * Driven by `cascades`, which the server sends as a fact about what the delete reaches —
 * not by a sentence typed into a component, which would outlive the behaviour it
 * describes the first time the behaviour changed.
 */
function rider(holds: TrashHolding[]): string {
  const staying = holds.filter((holding) => !holding.cascades);
  if (staying.length === 0) return "";
  const plural = staying.some((holding) => holding.count !== 1);
  const nouns = listed(
    staying.map((holding) => {
      const noun = HOLDING_COPY[holding.kind].rider;
      return `the ${holding.count === 1 ? noun : `${noun}s`}`;
    }),
  );
  return ` — ${nouns} ${plural ? "were" : "was"} not deleted, only the reference goes.`;
}

/**
 * The detail pane's one paragraph.
 *
 * An archive gets a different sentence rather than a countdown of `undefined`: archived
 * is a decision and deleted is a clock, and the pane is where somebody about to press a
 * red button finds out which of the two they are looking at.
 */
export function paneSentence(item: TrashItem, retentionDays: number): string {
  if (item.daysLeft === undefined) {
    return "Archived. There is no countdown on it: somebody put this away on purpose.";
  }

  const when = `Deleted ${deletedAgo(item.daysLeft, retentionDays)}.`;
  if (item.holds.length === 0) return when;

  const held = `Held ${listed(item.holds.map(counted))}`;
  const tail = rider(item.holds);
  return tail ? `${when} ${held}${tail}` : `${when} ${held}.`;
}
