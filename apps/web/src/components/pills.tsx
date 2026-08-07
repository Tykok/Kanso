import type { Mirror, TicketPriority, TicketStatus } from "@/lib/api";

const STATUS_LABELS: Record<TicketStatus, string> = {
  backlog: "Backlog",
  todo: "Todo",
  in_progress: "In progress",
  in_review: "In review",
  done: "Done",
  canceled: "Canceled",
};

const STATUS_COLORS: Record<TicketStatus, string> = {
  backlog: "var(--status-backlog)",
  todo: "var(--status-todo)",
  in_progress: "var(--status-progress)",
  in_review: "var(--status-review)",
  done: "var(--status-done)",
  canceled: "var(--status-canceled)",
};

export function StatusPill({ status }: { status: TicketStatus }) {
  return (
    <span className="status" data-status={status} style={{ color: STATUS_COLORS[status] }}>
      <span className="dot" />
      <span style={{ color: "var(--text-dim)" }}>{STATUS_LABELS[status]}</span>
    </span>
  );
}

export const statusLabel = (status: TicketStatus) => STATUS_LABELS[status];

const PRIORITY_GLYPHS: Record<TicketPriority, { glyph: string; color: string; label: string }> = {
  none: { glyph: "·", color: "var(--text-faint)", label: "No priority" },
  low: { glyph: "▁", color: "var(--low)", label: "Low" },
  medium: { glyph: "▄", color: "var(--medium)", label: "Medium" },
  high: { glyph: "█", color: "var(--high)", label: "High" },
  urgent: { glyph: "!", color: "var(--urgent)", label: "Urgent" },
};

export function PriorityMark({ priority }: { priority: TicketPriority }) {
  const { glyph, color, label } = PRIORITY_GLYPHS[priority];
  return (
    <span className="priority" style={{ color }} title={label}>
      {glyph}
    </span>
  );
}

/**
 * The mirror runs behind by design, so its state is shown per row rather than
 * implied. "Pending" is the normal state for a second or two after every edit;
 * "failed" is the one worth acting on.
 */
export function SyncBadge({ mirror }: { mirror: Mirror }) {
  if (mirror.state === "disabled") return null;

  const title = {
    pending: "Queued for Notion",
    synced: mirror.syncedAt ? `Mirrored to Notion at ${new Date(mirror.syncedAt).toLocaleTimeString()}` : "Mirrored",
    failed: "Notion push failed — see the sync status",
    disabled: "",
  }[mirror.state];

  return (
    <span className="sync-badge" data-state={mirror.state} title={title}>
      {mirror.state === "synced" ? "Notion" : mirror.state}
    </span>
  );
}
