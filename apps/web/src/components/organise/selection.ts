/**
 * Screen 21's selection, as two functions over id arrays.
 *
 * Pure, and outside the component, for the reason `timeline/row.ts` is: the vitest run has
 * no DOM, and what needed testing was never the rendering. It is what "extend" means once
 * the cursor has moved and the list underneath has been refiltered.
 *
 * [extend] returns the selection **in list order** rather than in the order rows were
 * reached, because a range gesture that walked up and then down would otherwise leave the
 * array in an order no reader could predict. [toggle] appends, since a single row cannot
 * be out of order with itself.
 */

/**
 * `x` on the focused row — and, since a set of ids is a set of ids, the label picker's own
 * toggle in `ticket-labels.tsx`. Two implementations of "is it in the list, take it out"
 * would be two chances to disagree.
 */
export function toggle(selected: readonly string[], id: string): string[] {
  return selected.includes(id) ? selected.filter((each) => each !== id) : [...selected, id];
}

/**
 * `⇧↑` / `⇧↓`: keep the selection, add the row the cursor is moving onto, and add the row
 * it is leaving so that a range grows from wherever it started.
 *
 * [delta] is ±1 rather than a target id: the caller moves the cursor by the same delta in
 * the same keypress, and passing an id would let the two disagree about which row is next
 * after a refetch reordered the list.
 *
 * Ids the list no longer holds are dropped. A row filtered away between two keypresses is
 * not something the strip should still be about to edit.
 */
export function extend(
  rows: readonly string[],
  selected: readonly string[],
  cursorId: string | undefined,
  delta: number,
): string[] {
  const live = selected.filter((id) => rows.includes(id));
  if (cursorId === undefined) return order(rows, live);

  const from = rows.indexOf(cursorId);
  if (from === -1) return order(rows, live);

  const to = from + delta;
  // At the ends of the list the gesture adds the row it is on and stops. Wrapping would
  // make `⇧↓` at the bottom select the top of the page, which nobody would predict.
  if (to < 0 || to >= rows.length) return order(rows, [...live, cursorId]);

  return order(rows, [...live, cursorId, rows[to]]);
}

const order = (rows: readonly string[], selected: readonly string[]): string[] => {
  const chosen = new Set(selected);
  return rows.filter((id) => chosen.has(id));
};
