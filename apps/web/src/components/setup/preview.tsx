"use client";

import { PriorityMark } from "@/components/ui/priority-mark";
// `<Row>` is task 6's extraction (shell and list) — not yet in this worktree at the
// time this file was written, since the two branches are isolated by design. Written
// against its documented interface (task-6-report.md) rather than left unconverted:
// see the note on `PreferencePreview` below.
import { Row } from "@/components/ui/row";
import { StatusDot } from "@/components/ui/status-dot";
import type { Mirror, Preferences, TicketPriority, TicketStatus } from "@/lib/api";
import { STATUS_LABELS } from "@/lib/status";

type PreviewRow = {
  id: string;
  title: string;
  status: TicketStatus;
  priority: TicketPriority;
  mirror: Mirror;
};

const ROWS: PreviewRow[] = [
  {
    id: "KAN-14",
    title: "Mirror ticket updates to Notion",
    status: "in_progress",
    priority: "high",
    mirror: { state: "pending" },
  },
  {
    id: "KAN-15",
    title: "Rate-limit the login endpoint",
    status: "todo",
    priority: "none",
    mirror: { state: "synced" },
  },
];

const SYNC_COLOR: Record<string, string> = {
  pending: "text-status-progress",
  failed: "text-urgent",
  synced: "text-status-done",
};

/** The mirror state, in the same three words the real list badges it with — and
 *  the same colour: pending amber, failed red, synced green. */
function PreviewSyncBadge({ mirror }: { mirror: Mirror }) {
  if (mirror.state === "disabled") return null;
  return (
    <span className={`text-11 uppercase tracking-wide ${SYNC_COLOR[mirror.state] ?? "text-faint"}`}>
      {mirror.state === "synced" ? "Notion" : mirror.state}
    </span>
  );
}

/**
 * The real row, not a drawing of it: `<Row>` is the exact component the ticket list
 * renders — same height, padding, radius and selected treatment, both governed by
 * the density and accent the controls above set — so whatever they do to a row on
 * the list, they do here first, before a save. `cursor-default` overrides `<Row>`'s
 * own `cursor-pointer` (tailwind-merge lets a caller's utility beat a conflicting
 * default): nothing here is clickable, and `aria-hidden` on the outer frame already
 * says this repeats choices the controls above state in words rather than adding one.
 */
export function PreferencePreview({ preferences }: { preferences: Preferences }) {
  const { accent, density, sidebarVisible, showSyncBadges, showStatusBar } = preferences;

  return (
    <div
      className="overflow-hidden rounded-md border border-border bg-background"
      data-accent={accent}
      data-density={density}
      aria-hidden="true"
    >
      <div className={sidebarVisible ? "grid grid-cols-[112px_1fr]" : "grid grid-cols-1"}>
        {sidebarVisible && (
          <div className="flex min-w-0 flex-col gap-0.5 border-r border-border p-2">
            <span className="px-1.5 pb-1 text-11 uppercase tracking-wide text-faint">Teams</span>
            <span className="rounded-md bg-accent-soft px-1.5 py-0.5 text-12 font-medium text-foreground">
              Core
            </span>
            <span className="rounded-md px-1.5 py-0.5 text-12 text-muted-foreground">Platform</span>
          </div>
        )}

        <div className="min-w-0">
          <div className="flex flex-col gap-row px-2 py-2">
            {ROWS.map((row, position) => (
              <Row
                key={row.id}
                selected={position === 0}
                className="grid cursor-default grid-cols-[52px_14px_auto_1fr_70px]"
              >
                <span className="text-11 text-faint" style={{ fontFamily: "var(--font-mono)" }}>
                  {row.id}
                </span>
                <PriorityMark priority={row.priority} />
                <span className="flex items-center gap-1.5">
                  <StatusDot status={row.status} />
                  <span className="text-12 text-muted-foreground">{STATUS_LABELS[row.status]}</span>
                </span>
                <span className="min-w-0 truncate text-13">{row.title}</span>
                <span className="text-right">
                  {showSyncBadges && <PreviewSyncBadge mirror={row.mirror} />}
                </span>
              </Row>
            ))}
          </div>

          {showStatusBar && (
            <div className="flex items-center gap-3 border-t border-border px-2.5 py-1.5">
              <span>
                <kbd>j</kbd> <kbd>k</kbd> move
              </span>
              <span>
                <kbd>c</kbd> new
              </span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
