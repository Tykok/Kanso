"use client";

import { useState } from "react";
import type { TrashItem } from "@/lib/api";
import { useArchiveInstead, usePurge, useRestoreFromTrash, useTrash } from "@/lib/queries";
import { cn } from "@/lib/utils";
import { TrashDetail } from "./detail";
import { TrashColumns, TrashRow } from "./rows";

const TABS = ["trash", "archives"] as const;
type Tab = (typeof TABS)[number];

/**
 * Screen 26.
 *
 * One query feeds both tabs, because the drawing prints both counts at once and two
 * queries would let the numbers describe two different moments. The counts are the lengths
 * of the two lists rather than fields of their own, for the same reason.
 *
 * The selection is local state and not `store/ui.ts`'s `selectedId`: that one is the ticket
 * list's cursor, read by the actions registry and by the detail panel, and pointing it at
 * something that is *not in any list* would put every keyboard action in the app on a row
 * nothing else can see.
 */
export function TrashView() {
  const trash = useTrash();
  const [tab, setTab] = useState<Tab>("trash");
  const [selectedId, setSelectedId] = useState<string>();

  const restore = useRestoreFromTrash();
  const archive = useArchiveInstead();
  const purge = usePurge();
  const busy = restore.isPending || archive.isPending || purge.isPending;

  if (trash.isLoading) return <p className="empty">Loading…</p>;
  if (trash.error || !trash.data) {
    return <p className="empty">The trash could not be read. Refresh to try again.</p>;
  }

  /**
   * The two tabs hold disjoint sets, so a cursor from one names nothing in the other;
   * clearing it lets the fallback below pick the new tab's first row.
   */
  const select = (next: Tab) => {
    setTab(next);
    setSelectedId(undefined);
  };

  const { retentionDays } = trash.data;
  const rows: TrashItem[] = tab === "trash" ? trash.data.trash : trash.data.archives;
  // Falls back to the first row rather than showing an empty pane: the drawing always has
  // one row selected, and a table whose pane is blank until you click reads as broken.
  const selected = rows.find((row) => row.id === selectedId) ?? rows[0];

  return (
    <>
      <div className="flex items-center gap-2.5 bg-background px-5 py-3 text-12 text-faint">
        <span>Trash</span>
        <span className="flex-1" />
        <span>Emptied after {retentionDays} days</span>
      </div>

      <div role="tablist" aria-label="Trash and archives" className="flex gap-5 px-5 pt-4 pb-3">
        {TABS.map((id) => (
          <button
            key={id}
            type="button"
            role="tab"
            id={`trash-tab-${id}`}
            aria-controls="trash-panel"
            aria-selected={tab === id}
            // A tab that is not selected is out of the tab order, and the arrow keys walk
            // between them: that is the whole of what `role="tab"` promises a keyboard, and
            // wearing the role without keeping it is worse than using plain buttons.
            tabIndex={tab === id ? 0 : -1}
            onKeyDown={(event) => {
              const delta = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
              if (delta === 0) return;
              event.preventDefault();
              const next = TABS[(TABS.indexOf(tab) + delta + TABS.length) % TABS.length];
              select(next);
              document.getElementById(`trash-tab-${next}`)?.focus();
            }}
            onClick={() => select(id)}
            className={cn(
              "pb-1.5 text-12 capitalize",
              tab === id
                ? "font-medium shadow-[inset_0_-2px_0_var(--primary)]"
                : "text-muted-foreground",
            )}
          >
            {id}{" "}
            <span className="font-normal text-faint">
              {id === "trash" ? trash.data.trash.length : trash.data.archives.length}
            </span>
          </button>
        ))}
      </div>

      <div
        role="tabpanel"
        id="trash-panel"
        aria-labelledby={`trash-tab-${tab}`}
        className="flex min-h-0 flex-col gap-row px-5 pb-5"
      >
        <TrashColumns deletedColumn={tab === "trash" ? "Deleted by" : "Archived"} />
        {rows.length === 0 ? (
          <p className="empty">
            {tab === "trash"
              ? "Nothing has been thrown away."
              : "Nothing has been put away yet."}
          </p>
        ) : (
          rows.map((row) => (
            <TrashRow
              key={`${row.kind}:${row.id}`}
              item={row}
              selected={row.id === selected?.id}
              retentionDays={retentionDays}
              onSelect={() => setSelectedId(row.id)}
            />
          ))
        )}

        {selected ? (
          <TrashDetail
            item={selected}
            retentionDays={retentionDays}
            busy={busy}
            onRestore={() => restore.mutate({ kind: selected.kind, id: selected.id })}
            onArchive={() => archive.mutate({ kind: selected.kind, id: selected.id })}
            onPurge={() => purge.mutate({ kind: selected.kind, id: selected.id })}
          />
        ) : null}
      </div>
    </>
  );
}
