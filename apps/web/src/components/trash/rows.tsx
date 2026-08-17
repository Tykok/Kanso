"use client";

import type { TrashItem } from "@/lib/api";
import { cn } from "@/lib/utils";
import { countdown, isExpiring, typeLabel } from "./copy";

/**
 * The table's own grid, shared by the column header and every row so the two cannot
 * drift: selection mark, item, type, who threw it away, how long it has left.
 *
 * Five columns and the last four widths come from the drawing. The mark is 18px because
 * it is a mark, not a control — see [TrashRow].
 */
const COLS = "grid-cols-[18px_1fr_110px_110px_88px]";

export function TrashColumns({ deletedColumn }: { deletedColumn: string }) {
  return (
    <div
      className={cn(
        "grid items-center gap-3 px-row-x h-7 text-11 tracking-[0.08em] text-faint uppercase",
        COLS,
      )}
    >
      <span />
      <span>Item</span>
      <span>Type</span>
      <span>{deletedColumn}</span>
      <span>Left</span>
    </div>
  );
}

/**
 * One line of screen 26, in either tab.
 *
 * The mark in the first column is drawn as the checkbox the drawing paints, and it is
 * `aria-hidden` decoration: the whole row is one button, and this table has a **detail
 * pane** rather than a bulk-edit strip — a real checkbox would promise a multiple
 * selection that nothing here acts on. Bulk selection over a list of rows is slice C's
 * subject, on its own screen.
 *
 * The accessible name carries what the four columns say, because a name of "KAN-121 · SVG
 * seal" alone would leave a screen reader with no way to tell the row about to be emptied
 * from the one with four weeks left — which is the single most important thing on the row.
 */
export function TrashRow({
  item,
  selected,
  retentionDays,
  onSelect,
}: {
  item: TrashItem;
  selected: boolean;
  retentionDays: number;
  onSelect: () => void;
}) {
  const left = item.daysLeft === undefined ? undefined : countdown(item.daysLeft);
  const expiring = item.daysLeft !== undefined && isExpiring(item.daysLeft);

  return (
    <button
      type="button"
      data-testid="trash-row"
      data-selected={selected}
      aria-pressed={selected}
      aria-label={[
        item.label,
        typeLabel(item.kind),
        item.deletedBy ? `deleted by ${item.deletedBy.displayName}` : undefined,
        left ? `${left} left of ${retentionDays} days` : "archived",
      ]
        .filter(Boolean)
        .join(", ")}
      onClick={onSelect}
      className={cn(
        "grid w-full items-center gap-3 px-row-x h-row rounded-md text-left",
        COLS,
        selected ? "bg-accent-soft text-foreground" : "text-muted-foreground hover:bg-accent",
      )}
    >
      <span
        aria-hidden
        className={cn(
          "grid size-[13px] place-items-center rounded-sm text-[9px]",
          selected ? "bg-primary text-primary-foreground" : "border border-border",
        )}
      >
        {selected ? "✓" : ""}
      </span>
      <span className="truncate">{item.label}</span>
      <span className="text-12">{typeLabel(item.kind)}</span>
      <span className="truncate text-12">{item.deletedBy?.displayName ?? "—"}</span>
      <span className={cn("text-11", expiring ? "text-urgent" : "text-faint")}>{left ?? "—"}</span>
    </button>
  );
}
