import type { Mirror, TicketPriority, TicketStatus } from "@/lib/api";
import type { ActionContext } from "@/lib/actions";
import { cn } from "@/lib/utils";
import { STATUS_LABELS } from "@/lib/status";
import { StatusDot } from "./ui/status-dot";
import { PriorityMark as PriorityGlyph } from "./ui/priority-mark";
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

/**
 * With a `ctx`, the pill is the control: clicking what you are already reading is one
 * gesture, where a shared row menu would be three. Without one it stays a label — the
 * setup wizard's preview renders rows nobody can act on.
 *
 * The drawing itself — the dot, its fill, its colour — belongs to `StatusDot` (Task 4).
 * This keeps only the label and, with a `ctx`, the menu that turns the pair into a
 * control.
 */
export function StatusPill({ status, ctx }: { status: TicketStatus; ctx?: ActionContext }) {
  const body = (
    <>
      <StatusDot status={status} />
      <span className="text-muted-foreground">{STATUS_LABELS[status]}</span>
    </>
  );

  // `.status` carries no styling of its own in this app any more, but the setup
  // wizard's read-only preview (setup/preview.tsx, another task's file) renders this
  // same component and narrows its width through `.setup-preview .status` — a rule
  // that has nothing left to reach for once list.css is gone unless the class stays.
  if (!ctx) {
    return (
      <span data-testid="status-pill" className="status inline-flex items-center gap-[7px] text-12">
        {body}
      </span>
    );
  }

  return (
    <Menu
      label={`Status: ${STATUS_LABELS[status]}`}
      // The pill IS the trigger, rather than a box with an invisible button laid over
      // it: one element, so there is nothing left to keep aligned. A `<button>` and not
      // the `<span>` above, because Radix hands the child the trigger's props and only
      // a button is focusable and fires on Enter and Space.
      asChild
      trigger={
        <button
          type="button"
          data-testid="status-pill"
          className="status inline-flex items-center gap-[7px] text-12"
        >
          {body}
        </button>
      }
      items={menuItems(ctx, STATUS_ACTIONS)}
    />
  );
}

export const statusLabel = (status: TicketStatus) => STATUS_LABELS[status];

const PRIORITY_LABELS: Record<TicketPriority, string> = {
  none: "No priority",
  low: "Low",
  medium: "Medium",
  high: "High",
  urgent: "Urgent",
};

/** The interactive twin of `ui/priority-mark.tsx`'s glyph: same drawing, plus a menu
 *  when there is a `ctx` to run its actions against. */
export function PriorityMark({ priority, ctx }: { priority: TicketPriority; ctx?: ActionContext }) {
  const label = PRIORITY_LABELS[priority];

  if (!ctx) {
    return <PriorityGlyph priority={priority} />;
  }

  return (
    <Menu
      label={`Priority: ${label}`}
      asChild
      trigger={
        <button type="button" title={label} className="w-3.5 shrink-0 text-center">
          <PriorityGlyph priority={priority} />
        </button>
      }
      items={menuItems(ctx, PRIORITY_ACTIONS)}
    />
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

  // A plain colour, not a chip: the badge used to be a bordered pill, and the
  // drawing shows none of that weight — just the word, in the colour of the status
  // it echoes. `--status-progress`, `--status-done` and `--urgent` are already the
  // right hues (amber, green, red); nothing new needed naming for this.
  const color = {
    pending: "text-status-progress",
    synced: "text-status-done",
    failed: "text-urgent",
    disabled: "",
  }[mirror.state];

  return (
    <span
      // `.sync-badge` carries no styling of its own any more — everything visible
      // comes from the utilities below — but `[data-sync-badges="off"] .sync-badge`
      // in globals.css still reaches for it by name to hide the badge entirely, so
      // the class stays as that hook's target.
      className={cn("sync-badge text-11 tracking-[0.05em] whitespace-nowrap uppercase", color)}
      data-state={mirror.state}
      title={title}
    >
      {mirror.state === "synced" ? "Notion" : mirror.state}
    </span>
  );
}
