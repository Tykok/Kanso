"use client";

import { labelOfKey } from "@/lib/statuses";
import { PriorityMark } from "@/components/ui/priority-mark";
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
  const { accent, density, sidebarMode, showSyncBadges, showStatusBar, showViewControls } =
    preferences;

  // Only `pinned` spends a column. `hover` draws the hot zone the real sidebar slides out
  // of — a 12px strip in the app, scaled to 6px here — so the reader can see that "on
  // hover" is not the same nothing as "hidden"; the column itself is a fixed overlay in
  // the real shell and has no place in a static tile.
  const columns =
    sidebarMode === "pinned"
      ? "grid grid-cols-[112px_1fr]"
      : sidebarMode === "hover"
        ? "grid grid-cols-[6px_1fr]"
        : "grid grid-cols-1";

  return (
    <div
      className="overflow-hidden rounded-md border border-border bg-background"
      data-accent={accent}
      data-density={density}
      aria-hidden="true"
    >
      <div className={columns}>
        {sidebarMode === "pinned" && (
          <div className="flex min-w-0 flex-col gap-0.5 border-r border-border p-2">
            <span className="px-1.5 pb-1 text-11 uppercase tracking-wide text-faint">Teams</span>
            <span className="rounded-md bg-accent-soft px-1.5 py-0.5 text-12 font-medium text-foreground">
              Core
            </span>
            <span className="rounded-md px-1.5 py-0.5 text-12 text-muted-foreground">Platform</span>
          </div>
        )}

        {sidebarMode === "hover" && <div className="border-r border-border bg-accent-soft" />}

        <div className="min-w-0">
          {/* The two buttons a saved view pushes into `shell/topbar.tsx`'s slot — the bar
              holds them, the page owns them, because Group and Order are menus only a
              saved view stores a choice for. Filter used to be a third and is gone: the
              box it focused sits under the bar on the same screen. Named here rather than
              rendered: the real controls open two menus, and a tile that did that on a
              click would be a second implementation of them rather than a preview of
              this setting. */}
          {showViewControls && (
            <div className="flex items-center gap-1.5 border-b border-border px-2 py-1.5">
              {["Group", "Order"].map((control) => (
                <span
                  key={control}
                  className="rounded-md border border-border px-1.5 py-0.5 text-11 text-muted-foreground"
                >
                  {control}
                </span>
              ))}
            </div>
          )}

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
                  <span className="text-12 text-muted-foreground">{labelOfKey(row.status)}</span>
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
                <kbd>n</kbd> <kbd>p</kbd> move
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
