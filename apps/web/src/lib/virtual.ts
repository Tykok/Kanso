/**
 * The index a virtualised list is addressed by. No React, no DOM — `environment: "node"`,
 * like `timeline-geometry.ts` and for the same reason: this is where the rule is proved.
 *
 * A virtualiser speaks integers. It asks what is at 41 and it is told to scroll to 41.
 * Every question this product's keyboard asks is about a ticket instead — `j` moves onto
 * a row, `1`–`6` set the status of the selected one — and the two vocabularies have to
 * convert without loss in both directions, for rows nowhere near the screen as much as
 * for the ones under the cursor. That conversion is all this file is.
 *
 * The **flattened index** was chosen over a virtualiser per group. A per-group one would
 * have needed each group to be its own scroller — and a cursor that runs `j` off the
 * bottom of one group and into the top of the next would then have to scroll two elements
 * to keep itself visible, with the group headers between them belonging to neither. One
 * sequence carrying headers and rows alike means the list has exactly one integer for
 * "where the cursor is", which is the same thing the unvirtualised list had.
 */

/** The shape `organise/grouping.ts` answers with, narrowed to what a flattener reads. */
export type FlatGroup<T> = {
  key: string;
  /** Empty when the answer was not to group at all — which draws no header. */
  label: string;
  count: number;
  tickets: readonly T[];
};

export type FlatRow<T> =
  | { kind: "header"; key: string; label: string; count: number; group: number }
  /** [rank] is the row's place inside its own group, which is what a header counts. */
  | { kind: "row"; key: string; group: number; rank: number; item: T };

/**
 * One sequence out of the groups, a header in front of each that has a label.
 *
 * A group with no label writes no header: `groupTickets(rows, "none")` answers with one
 * unlabelled group, because a flat list is a real choice on the group-by control rather
 * than the absence of one, and a header there would be a blank caption above every
 * ungrouped list in the app.
 */
export function flatten<T>(
  groups: readonly FlatGroup<T>[],
  keyOf: (item: T) => string,
): FlatRow<T>[] {
  const flat: FlatRow<T>[] = [];
  groups.forEach((group, at) => {
    if (group.label !== "") {
      // Prefixed, so a header can never collide with the id of a row: the two share one
      // keyspace here, and React would silently keep the wrong element on a remount.
      flat.push({
        kind: "header",
        key: `group:${group.key}`,
        label: group.label,
        count: group.count,
        group: at,
      });
    }
    group.tickets.forEach((item, rank) => {
      flat.push({ kind: "row", key: keyOf(item), group: at, rank, item });
    });
  });
  return flat;
}

/**
 * Where the row keyed [key] sits, or -1.
 *
 * The single load-bearing function in this file. `j` at the bottom of a long list selects
 * a ticket whose element does not exist, and the only way the cursor stays on screen is
 * for the list to resolve that ticket to an integer regardless. -1 rather than a throw or
 * a zero: a row the filter removed between two keypresses is not a row to scroll to, and
 * scrolling to the top instead would move the list under a cursor that did not ask.
 */
export function flatIndexOf<T>(flat: readonly FlatRow<T>[], key: string | undefined): number {
  if (key === undefined) return -1;
  return flat.findIndex((entry) => entry.kind === "row" && entry.key === key);
}

/** The group and rank at [index], or nothing when that index is a header or off the end. */
export function placeOf<T>(
  flat: readonly FlatRow<T>[],
  index: number,
): { group: number; rank: number } | undefined {
  const entry = flat[index];
  if (entry === undefined || entry.kind !== "row") return undefined;
  return { group: entry.group, rank: entry.rank };
}

/** The same conversion read forwards: the index of a group's [rank]th row, or -1. */
export function flatIndexAt<T>(flat: readonly FlatRow<T>[], group: number, rank: number): number {
  return flat.findIndex(
    (entry) => entry.kind === "row" && entry.group === group && entry.rank === rank,
  );
}

/**
 * What the virtualiser lays the scrollbar out from before it has measured anything.
 *
 * A header is taller than a row, so one number for both would put the scrollbar in the
 * wrong place on every grouped view — and the thumb would then jump as each header came
 * into view and was measured. Off the end it answers a row's height rather than throwing:
 * the virtualiser asks about indexes that are briefly out of range while the data under
 * it is being swapped.
 */
export function sizeAt<T>(
  flat: readonly FlatRow<T>[],
  index: number,
  metrics: { row: number; header: number },
): number {
  return flat[index]?.kind === "header" ? metrics.header : metrics.row;
}
