"use client";

import { PriorityMark, StatusPill, SyncBadge } from "@/components/pills";
import type { Mirror, Preferences, TicketPriority, TicketStatus } from "@/lib/api";

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

/**
 * The real row components, not a drawing of them: whatever accent and density do to
 * the list, they do here first. Hidden from assistive tech — it repeats choices the
 * controls above already state in words.
 */
export function PreferencePreview({ preferences }: { preferences: Preferences }) {
  const { accent, density, sidebarVisible, showSyncBadges, showStatusBar } = preferences;

  return (
    <div className="setup-preview" data-accent={accent} data-density={density} aria-hidden="true">
      <div className="setup-preview-shell" data-sidebar={sidebarVisible}>
        {sidebarVisible && (
          <div className="setup-preview-side">
            <span className="nav-label">Teams</span>
            <span className="setup-preview-nav" data-current="true">
              Core
            </span>
            <span className="setup-preview-nav">Platform</span>
          </div>
        )}

        <div className="setup-preview-main">
          <div className="setup-preview-rows">
            {ROWS.map((row, position) => (
              <div key={row.id} className="row" data-selected={position === 0}>
                <span className="row-id">{row.id}</span>
                <PriorityMark priority={row.priority} />
                <StatusPill status={row.status} />
                <span className="row-title">{row.title}</span>
                <span className="row-meta">
                  {showSyncBadges && <SyncBadge mirror={row.mirror} />}
                </span>
              </div>
            ))}
          </div>

          {showStatusBar && (
            <div className="statusbar">
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
