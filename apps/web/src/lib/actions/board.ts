import { boardColumns, boardMove, deltaTo, type BoardDirection } from "@/components/board/columns";
import { useBoard } from "@/store/board";
import type { Action, ActionContext } from "./types";

/**
 * Slice A — screen 04's keys.
 *
 * All of them are `mode: "board"`. `resolveShortcut` asks the mode's own bucket before
 * the shared one, so a key can mean something on the board and exactly what it always
 * did everywhere else. `indexActions` proves the two buckets never collide — it runs at
 * module load and throws on an ambiguity rather than letting one resolve by accident.
 *
 * `1`–`6` are deliberately absent. Moving a card between columns *is* a status change,
 * and `ticket.status.*` in `core.ts` already is one; registering the six again here would
 * be a second implementation of one patch.
 */

const hasSelection = (ctx: ActionContext) => ctx.selected !== undefined;

/** Offered whenever there is a card to move from; [step] is inert past the board's edge. */
const canStep = (ctx: ActionContext) => hasSelection(ctx) && ctx.tickets.length > 1;

/**
 * One key, spelled once.
 *
 * The cursor is `ui.selectedId` — the same ticket is selected in all three drawings — and
 * the only way an action has to move it is `ctx.move(delta)`, a step along the *list's*
 * order. Board order is six columns and not one sequence, so the card is resolved on the
 * board and the step that reaches it is worked out in the list. A move with nowhere to go
 * answers zero, which is what makes `l` at the right-hand edge inert rather than wrong.
 */
const step = (direction: BoardDirection) => (ctx: ActionContext) => {
  const target = boardMove(boardColumns(ctx.tickets), ctx.selected?.id, direction);
  const delta = deltaTo(ctx.tickets, ctx.selected?.id, target);
  if (delta !== 0) ctx.move(delta);
};

const columnLeft: Action = {
  id: "board.columnLeft",
  label: "Previous column",
  shortcut: "h ArrowLeft",
  mode: "board",
  group: "ticket",
  when: canStep,
  run: step("left"),
};

const columnRight: Action = {
  id: "board.columnRight",
  label: "Next column",
  shortcut: "l ArrowRight",
  mode: "board",
  group: "ticket",
  when: canStep,
  run: step("right"),
};

const cardDown: Action = {
  id: "board.moveDown",
  label: "Next card in this column",
  shortcut: "j ArrowDown",
  mode: "board",
  group: "ticket",
  when: canStep,
  run: step("down"),
};

const cardUp: Action = {
  id: "board.moveUp",
  label: "Previous card in this column",
  shortcut: "k ArrowUp",
  mode: "board",
  group: "ticket",
  when: canStep,
  run: step("up"),
};

const openCard: Action = {
  id: "board.open",
  label: "Open card",
  shortcut: "Enter",
  mode: "board",
  group: "ticket",
  when: hasSelection,
  /**
   * States the intent and stops. Which of the panel and the page `↵` gives is
   * `preferences.openTicket`, which lives in the query cache and needs a hook to read,
   * and getting to the page needs the router — neither of which a module the unit suite
   * imports under `environment: "node"` may reach for. `BoardView` has both and decides;
   * `store/board.ts` carries the one value between them.
   */
  run: (ctx) => {
    if (ctx.selected) useBoard.getState().requestOpen(ctx.selected.identifier);
  },
};

/**
 * Whether the board may answer `j`, `k` and `↵` itself.
 *
 * It should: the drawing prints `j k carte` and `↵ ouvrir` in the board's own footer,
 * a column is not the list — `/api/tickets` orders by `updated_at DESC`, so core's `j`
 * hops between columns by recency — and `↵` is the one key that has to read
 * `preferences.openTicket`. The registry permits all three: a `board:` bucket wins over
 * `any:`, which is the whole point of `mode`, and `indexActions` raises nothing.
 *
 * What stands in the way is one expectation in `lib/actions/core.test.ts` — "lets no
 * mode-specific key shadow one that works everywhere" — whose own comment says the
 * mechanism is legal and that "nothing does it today". Slice A is the screen that
 * changes that, and `core.test.ts` is slice 0's file, read-only to this branch. Flipping
 * this constant and relaxing that one expectation is the whole edit; the three actions
 * above are written, tested and waiting for it.
 *
 * Until then the board keeps core's `j`, `k` and `↵`, and the preference is honoured on
 * the two paths slice A does own: a card's double-click, and `↵` in the palette.
 */
const BOARD_MAY_SHADOW_SHARED_KEYS = true;

export const boardActions: readonly Action[] = [
  columnLeft,
  columnRight,
  ...(BOARD_MAY_SHADOW_SHARED_KEYS ? [cardUp, cardDown, openCard] : []),
];

/**
 * The three above, reachable by name. They dispatch now: the integration pass decided the
 * guard's premise did not hold for them — `j`, `k` and `↵` on the board are the same
 * intent as in the list ("next item", "open this"), drawn differently, which is what
 * `mode` exists for. `core.test.ts` carries them as a written-down exception rather than
 * relaxing the rule, so the next key that wants to shadow one still has to argue for it.
 */
export const boardKeysAwaitingTheGuard: readonly Action[] = [cardUp, cardDown, openCard];
