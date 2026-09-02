"use client";

import { INBOX_TABS, type InboxCounts, type InboxTab } from "@/lib/api";
import { cn } from "@/lib/utils";

const LABELS: Record<InboxTab, string> = {
  all: "All",
  assigned: "Assigned",
  mentions: "Mentions",
  failures: "Failures",
};

/**
 * The four tabs, each carrying its own count.
 *
 * A 2px inset rule under the active one rather than a filled pill: the drawing marks it
 * the way the sidebar marks a current row, and a second selected-looking surface on a
 * screen that already has selected rows would compete with them.
 *
 * `Failures` prints its count in `--urgent` and not the faint ink every other tab uses.
 * It is the one number on this strip that is a problem rather than a quantity — but
 * only when it is not zero, because a red 0 is an alarm about nothing.
 */
export function InboxTabs({
  active,
  counts,
  onSelect,
}: {
  active: InboxTab;
  counts: InboxCounts;
  onSelect: (tab: InboxTab) => void;
}) {
  return (
    /*
     * `role="group"` with `aria-pressed`, like the command palette's search strip — and
     * deliberately not the `tablist`/`tab` the trash view keeps properly. Of the two ways
     * this repo already does this, only one is available here: `role="tab"` promises a
     * keyboard `aria-controls` pointing at a `tabpanel`, and the panel is the row list in
     * `app/inbox/page.tsx`, which this branch does not own and so cannot give an id. The
     * choice was between a role kept in full and a role worn with two of its three
     * promises missing, and a `tablist` whose tabs control nothing is worse for a screen
     * reader than four honest toggle buttons: it announces a widget whose panel can never
     * be found. Four tab stops is then correct rather than a bug — a group of buttons is
     * exactly what this is, so no roving `tabIndex` and no arrow keys are owed. When the
     * panel side gains an id, this becomes the trash view's version verbatim.
     */
    <div className="flex gap-5 px-6 pt-[22px] pb-3" role="group" aria-label="Inbox">
      {INBOX_TABS.map((tab) => {
        const current = tab === active;
        const count = counts[tab];
        return (
          <button
            key={tab}
            type="button"
            data-testid="inbox-tab"
            aria-pressed={current}
            className={cn(
              "pb-[5px] text-12",
              current
                ? "font-medium text-foreground shadow-[inset_0_-2px_0_var(--primary)]"
                : "text-muted-foreground hover:text-foreground",
            )}
            onClick={() => onSelect(tab)}
          >
            {LABELS[tab]}{" "}
            <span className={cn(tab === "failures" && count > 0 ? "text-urgent" : "text-faint")}>
              {count}
            </span>
          </button>
        );
      })}
    </div>
  );
}
