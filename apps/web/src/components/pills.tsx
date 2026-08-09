import type { Mirror, TicketPriority, TicketStatus } from "@/lib/api";
import type { ActionContext } from "@/lib/actions";
import { Menu } from "./menu";
import { menuItems } from "./menu-items";

const STATUS_ACTIONS = [
  "ticket.status.backlog",
  "ticket.status.todo",
  "ticket.status.in_progress",
  "ticket.status.in_review",
  "ticket.status.done",
  "ticket.status.canceled",
];

const PRIORITY_ACTIONS = [
  "ticket.priority.none",
  "ticket.priority.low",
  "ticket.priority.medium",
  "ticket.priority.high",
  "ticket.priority.urgent",
];

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

/**
 * With a `ctx`, the pill is the control: clicking what you are already reading is one
 * gesture, where a shared row menu would be three. Without one it stays a label — the
 * setup wizard's preview renders rows nobody can act on.
 */
export function StatusPill({ status, ctx }: { status: TicketStatus; ctx?: ActionContext }) {
  const body = (
    <>
      <span className="dot" />
      <span style={{ color: "var(--text-dim)" }}>{STATUS_LABELS[status]}</span>
    </>
  );

  if (!ctx) {
    return (
      <span className="status" data-status={status} style={{ color: STATUS_COLORS[status] }}>
        {body}
      </span>
    );
  }

  return (
    <span className="status status-menu" data-status={status} style={{ color: STATUS_COLORS[status] }}>
      <Menu
        label={`Status: ${STATUS_LABELS[status]}`}
        trigger={null}
        items={menuItems(ctx, STATUS_ACTIONS)}
      />
      {body}
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

export function PriorityMark({ priority, ctx }: { priority: TicketPriority; ctx?: ActionContext }) {
  const { glyph, color, label } = PRIORITY_GLYPHS[priority];

  if (!ctx) {
    return (
      <span className="priority" style={{ color }} title={label}>
        {glyph}
      </span>
    );
  }

  return (
    <span className="priority priority-menu" style={{ color }} title={label}>
      <Menu
        label={`Priority: ${label}`}
        trigger={null}
        items={menuItems(ctx, PRIORITY_ACTIONS)}
      />
      <span aria-hidden="true">{glyph}</span>
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
