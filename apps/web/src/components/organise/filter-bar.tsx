"use client";

import type { ReactNode } from "react";
import { Kbd } from "@/components/ui/kbd";
import type { ViewFilters } from "@/lib/api";
import { chipsOf, withoutChip, type ChipNames } from "./chips";

/**
 * The strip across the top of a list: the chips of the question it is asking, and the
 * one control that adds another.
 *
 * Screen 21 drew this for a saved view and it was read-only in the one direction that
 * mattered — the `×` could take a filter off and nothing anywhere could put one on. The
 * strip is the same on both surfaces because the question is: the main list is an unsaved
 * saved view, and a chip means what it means wherever it is drawn.
 *
 * The `+ Filter` button and the `F` key are the same door. The button carries the key
 * beside it for the reason `menu.tsx` gives about its own hints: the menus are where a
 * keyboard-first application's keyboard is discovered, and nobody opens the help sheet
 * to find out that a filter can be added without the mouse.
 */
export function FilterBar({
  filters,
  names,
  onFilters,
  onAdd,
  onSave,
  empty,
  children,
}: {
  filters: ViewFilters;
  names: ChipNames;
  onFilters: (next: ViewFilters) => void;
  onAdd: () => void;
  /**
   * "Save this question", where there is one to save. Absent on a saved view, which is
   * already the answer to it — offering it there would be a second view of the same
   * question, made by a button that reads as if it were saving an edit.
   */
  onSave?: () => void;
  /** What to say with no chips up. The two surfaces are asking about different rooms. */
  empty?: ReactNode;
  /** The trailing controls this surface owns — a saved view's group-by and sort. */
  children?: ReactNode;
}) {
  const chips = chipsOf(filters, names);

  return (
    <div className="flex flex-wrap items-center gap-2 px-6 pt-[18px] pb-3.5">
      {chips.map((chip) => (
        <span
          key={chip.key}
          data-testid="filter-chip"
          className="inline-flex items-center gap-2 rounded-md border border-border bg-card px-2.5 py-1 text-12 text-muted-foreground"
        >
          {chip.label} {chip.value && <span className="text-foreground">{chip.value}</span>}
          <button
            type="button"
            aria-label={`Remove ${chip.label} filter`}
            className="text-faint hover:text-foreground"
            onClick={() => onFilters(withoutChip(filters, chip.key))}
          >
            ×
          </button>
        </span>
      ))}

      <button
        type="button"
        data-testid="add-filter"
        className="inline-flex items-center gap-1.5 rounded-md border border-dashed border-border px-2.5 py-1 text-12 text-muted-foreground hover:border-solid hover:text-foreground"
        onClick={onAdd}
      >
        + Filter <Kbd className="border-b">F</Kbd>
      </button>

      {/* Only with a question to save. An empty filter set would make a view holding
          everything in the team, which is the list somebody is already looking at. */}
      {onSave && chips.length > 0 && (
        <button
          type="button"
          data-testid="save-as-view"
          className="rounded-md px-2 py-1 text-12 text-faint hover:text-foreground"
          onClick={onSave}
        >
          Save as view
        </button>
      )}

      {chips.length === 0 && empty}

      <span className="flex-1" />
      {children}
    </div>
  );
}
