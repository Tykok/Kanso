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
    <div className="flex gap-5 px-6 pt-[22px] pb-3" role="tablist" aria-label="Inbox">
      {INBOX_TABS.map((tab) => {
        const current = tab === active;
        const count = counts[tab];
        return (
          <button
            key={tab}
            type="button"
            role="tab"
            data-testid="inbox-tab"
            aria-selected={current}
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
