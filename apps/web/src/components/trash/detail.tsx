"use client";

import type { TrashItem } from "@/lib/api";
import { paneSentence, restoreLabel } from "./copy";

/**
 * The pane for the selected row, and its three exits.
 *
 * There is no confirmation on "Delete for good", and that is the drawing's own answer as
 * well as the right one: the trash **is** the confirmation, thirty days of it, and a
 * modal asking "are you sure" about a thing somebody already threw away once teaches
 * people to dismiss modals. `disposition-dialog.tsx` makes a person retype a team's name
 * because destroying a team destroys everything under it and there was no grace period at
 * all; one row that has been sitting in a bin for a month is not that.
 *
 * An archive gets the same pane with no exits. Un-archiving is done where the thing lives
 * — the sidebar's own row menu for a team or a project, the list for a ticket — and a
 * second path to it from here would be a second answer to "where do I put this back".
 */
export function TrashDetail({
  item,
  retentionDays,
  busy,
  onRestore,
  onArchive,
  onPurge,
}: {
  item: TrashItem;
  retentionDays: number;
  busy: boolean;
  onRestore: () => void;
  onArchive: () => void;
  onPurge: () => void;
}) {
  const archived = item.daysLeft === undefined;

  return (
    <section
      data-testid="trash-detail"
      aria-label={`Details for ${item.label}`}
      className="mt-group flex flex-col gap-2.5 rounded-panel bg-background px-4 py-3.5"
    >
      <span className="text-13 font-medium">{item.label}</span>
      <span className="text-12 text-muted-foreground">{paneSentence(item, retentionDays)}</span>

      {archived ? null : (
        <div className="flex gap-2">
          <button
            type="button"
            disabled={busy}
            onClick={onRestore}
            className="button button-primary shrink-0 text-12 font-medium whitespace-nowrap"
          >
            {restoreLabel(item.parent)}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={onArchive}
            className="button shrink-0 text-12 whitespace-nowrap text-muted-foreground"
          >
            Archive instead
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={onPurge}
            className="shrink-0 rounded-md px-3 py-1.5 text-12 whitespace-nowrap text-urgent hover:bg-accent"
          >
            Delete for good
          </button>
        </div>
      )}
    </section>
  );
}
