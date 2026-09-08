import { labelOfKey, type Vocabulary } from "@/lib/statuses";
import type { StatusCategory, Ticket, TicketStatus } from "@/lib/api";
import { PRIORITY_LABELS, STATUS_LABELS } from "@/lib/status";

/**
 * The board's arithmetic, with no React in it.
 *
 * Vitest runs under `environment: "node"`, so this is where the board's behaviour is
 * actually proved: what the six columns hold, where each key lands, and what a card is
 * called when it is read rather than looked at. `view.tsx` above it only draws.
 */

/**
 * One column of the board — with the word above it and what it means, since `KAN-90`.
 *
 * [label] and [category] are carried rather than looked up: the columns *are* the team's
 * catalogue now, so the answers arrive with them, and a component that went back to a
 * table of Kanso's six would draw a blank header over a team's own column.
 */
export type BoardColumn = {
  status: TicketStatus;
  label: string;
  category: StatusCategory;
  tickets: Ticket[];
};

/** Which way a key moves the cursor. Named rather than `±1` twice over: `down` and */
/** `right` are not the same step, and a signed number cannot say which axis it is on. */
export type BoardDirection = "up" | "down" | "left" | "right";

/**
 * **The team's own columns, all of them, in the team's own order** — `KAN-90`.
 *
 * It was Kanso's six in `DEFAULT_STATUSES` order, which is what a board could be while
 * every team read the same words. A team that adds a seventh status would have had a
 * column missing and a key `7` that moved a card nowhere; one that removed a status would
 * have had a column no ticket can ever be in.
 *
 * Every column and not only the occupied ones, which is unchanged and still not cosmetic:
 * the order is the order `1`–`n` moves a card into, so a board that dropped its empty
 * columns would stop lining up with its own keys the moment one emptied — and there would
 * be no `Done` to drag onto until something was already done.
 *
 * [vocabulary] is the team's catalogue — `statuses.optionsFor`, which answers Kanso's six
 * for a scope with no team to ask, so a draft board still draws.
 *
 * **A board spanning teams is an open question.** `optionsFor` gives it the six, which
 * is what it drew before this change and is right for every team that has invented
 * nothing — but a card in a team with its own words then has no column, and dragging
 * onto `done` writes a key that team may not have. The columns cannot be the five
 * categories either: dropping a card on `unstarted` has no status to write. The two
 * honest answers are to rebase on drop, or to offer columns only for a single team,
 * and neither is `KAN-90`'s to pick — the ticket is about a team defining its own list.
 */
export function boardColumns(vocabulary: readonly Vocabulary[], tickets: Ticket[]): BoardColumn[] {
  const columns: BoardColumn[] = vocabulary.map((row) => ({
    status: row.key,
    label: row.label,
    category: row.category,
    tickets: [],
  }));
  const byStatus = new Map(columns.map((column) => [column.status, column]));
  for (const ticket of tickets) {
    // Order inside a column is the order the response arrived in — the server's own
    // sort. Re-sorting here would mean the board and the list disagree about which
    // ticket is first, with nothing on either screen to explain the difference.
    byStatus.get(ticket.status)?.tickets.push(ticket);
  }
  return columns;
}

/**
 * Where [id] sits on the board, or `undefined` if it is not on it.
 *
 * Exported since the columns were virtualised. A card below the fold has no element, so
 * the card can no longer scroll itself into view when the cursor lands on it — the column
 * has to be asked which index it is and told to scroll there, and this is the question.
 */
export function locateCard(
  columns: BoardColumn[],
  id: string | undefined,
): { column: number; row: number } | undefined {
  if (!id) return undefined;
  for (let column = 0; column < columns.length; column++) {
    const row = columns[column].tickets.findIndex((ticket) => ticket.id === id);
    if (row !== -1) return { column, row };
  }
  return undefined;
}

/**
 * The card [direction] means, or `undefined` when there is none.
 *
 * `undefined` at the ends rather than a wrap: a cursor that comes back round is a lost
 * place, the same rule the chart's zoom already follows. Sideways, an empty column is
 * passed over instead of stopped in — the cursor cannot be drawn in a column with no
 * cards, so stopping there would leave `l` looking broken — and the row is clamped when
 * the column it lands in is shorter, which keeps the cursor visible rather than nowhere.
 */
export function boardMove(
  columns: BoardColumn[],
  selectedId: string | undefined,
  direction: BoardDirection,
): string | undefined {
  const at = locateCard(columns, selectedId);
  if (!at) return undefined;

  if (direction === "up" || direction === "down") {
    const row = at.row + (direction === "down" ? 1 : -1);
    return columns[at.column].tickets[row]?.id;
  }

  const step = direction === "right" ? 1 : -1;
  for (let column = at.column + step; column >= 0 && column < columns.length; column += step) {
    const { tickets } = columns[column];
    if (tickets.length === 0) continue;
    return tickets[Math.min(at.row, tickets.length - 1)].id;
  }
  return undefined;
}

/**
 * The step from one row to another in the list's own order.
 *
 * `ActionContext` offers `move(delta)` and no way to name a ticket, because every other
 * screen's cursor walks one list. The board's does not — its order is six columns, not
 * one sequence — so a board key resolves the card it means and then asks for the step
 * that lands on it. Zero when either end is missing: a key that cannot see both ends
 * must not move the cursor blindly.
 */
export function deltaTo(
  tickets: Ticket[],
  fromId: string | undefined,
  toId: string | undefined,
): number {
  const from = tickets.findIndex((ticket) => ticket.id === fromId);
  const to = tickets.findIndex((ticket) => ticket.id === toId);
  return from === -1 || to === -1 ? 0 : to - from;
}

/**
 * What a card is called when it is read rather than looked at.
 *
 * The card draws 12px of title over a 10px identifier and two glyphs; read in DOM order
 * that is three fragments and no sentence, and the two things the eye takes for free —
 * which column it is in, how loud the priority glyph is — are exactly what a screen
 * reader gets from neither position nor colour. A priority nobody set is left out
 * rather than announced as "No priority", which is noise on every card in a fresh board.
 */
export function cardLabel(ticket: Ticket): string {
  const priority = ticket.priority === "none" ? "" : `, ${PRIORITY_LABELS[ticket.priority]}`;
  // A ticket with no team has no identifier to read out, and "null" is worse than
  // silence — so the sentence opens with the fact instead of with a name.
  const named = ticket.identifier ?? "No team";
  return `${named}, ${labelOfKey(ticket.status)}${priority}: ${ticket.title}`;
}
