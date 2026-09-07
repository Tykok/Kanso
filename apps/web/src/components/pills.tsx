import type { Mirror, TicketPriority, TicketStatus } from "@/lib/api";
import { PRIORITY_ACTIONS, type ActionContext } from "@/lib/actions";
import { cn } from "@/lib/utils";
import { PRIORITY_LABELS, STATUS_LABELS } from "@/lib/status";
import { StatusDot } from "./ui/status-dot";
import { PriorityMark as PriorityGlyph } from "./ui/priority-mark";
import { Menu } from "./menu";
import { useMenuItems } from "./menu-items";

const STATUS_ACTIONS = [
  "ticket.status.backlog",
  "ticket.status.todo",
  "ticket.status.in_progress",
  "ticket.status.in_review",
  "ticket.status.done",
  "ticket.status.canceled",
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
export function StatusPill({
  status,
  label,
  ctx,
}: {
  status: TicketStatus;
  /**
   * The word this ticket's *own team* uses — `KAN-28`, and `lib/statuses.ts` resolves it.
   *
   * A prop and not a lookup in here: a cross-team list draws rows from several
   * vocabularies at once, so the word belongs to the row rather than to the screen, and
   * the row is what knows which team it came from. Left out, the pill reads Kanso's own
   * word, which is right for every team that has renamed nothing and for the previews
   * that draw rows nobody can act on.
   */
  label?: string;
  ctx?: ActionContext;
}) {
  // Above the early return, because it is a hook: `useMenuItems` answers with an empty
  // list for the label-only case, which is the same nothing the branch below draws.
  const items = useMenuItems(ctx, STATUS_ACTIONS);
  const body = (
    <>
      <StatusDot status={status} />
      <span className="text-muted-foreground">{label ?? STATUS_LABELS[status]}</span>
    </>
  );

  if (!ctx) {
    return (
      <span data-testid="status-pill" className="inline-flex items-center gap-[7px] text-12">
        {body}
      </span>
    );
  }

  return (
    <Menu
      label={`Status: ${label ?? STATUS_LABELS[status]}`}
      // The pill IS the trigger, rather than a box with an invisible button laid over
      // it: one element, so there is nothing left to keep aligned. A `<button>` and not
      // the `<span>` above, because Radix hands the child the trigger's props and only
      // a button is focusable and fires on Enter and Space.
      asChild
      trigger={
        <button
          type="button"
          data-testid="status-pill"
          data-chip
          className="inline-flex items-center gap-[7px] text-12"
        >
          {body}
        </button>
      }
      items={items}
    />
  );
}

export const statusLabel = (status: TicketStatus) => STATUS_LABELS[status];

/** The interactive twin of `ui/priority-mark.tsx`'s glyph: same drawing, plus a menu
 *  when there is a `ctx` to run its actions against. */
export function PriorityMark({ priority, ctx }: { priority: TicketPriority; ctx?: ActionContext }) {
  const items = useMenuItems(ctx, [...PRIORITY_ACTIONS]);
  const label = PRIORITY_LABELS[priority];

  if (!ctx) {
    return <PriorityGlyph priority={priority} />;
  }

  return (
    <Menu
      label={`Priority: ${label}`}
      asChild
      trigger={
        <button type="button" title={label} data-chip className="w-3.5 shrink-0 text-center">
          <PriorityGlyph priority={priority} />
        </button>
      }
      items={items}
    />
  );
}

/** What the badge says. Exported so a test names the same string the screen draws. */
export const NO_TEAM_LABEL = "No team";

/**
 * True when this ticket shows the badge instead of an identifier.
 *
 * Reads `teamId` rather than `identifier`, though today the two are null together: the
 * team is the fact, and the missing identifier is its consequence. A row that somehow had
 * one without the other should still say which of the two it is missing.
 */
// `== null` and not `=== null`: the server omits nulls, so a draft arrives with the key
// absent rather than explicitly empty, and the strict test would answer false for every
// one of them.
export const hasNoTeam = (ticket: { teamId?: string | null }) => ticket.teamId == null;

const NO_TEAM_PILL =
  "inline-flex items-center rounded-sm border border-dashed border-border px-1.5 font-sans text-11 whitespace-nowrap";

/**
 * Where `KAN-142` goes, and what stands there when there is no team to make one.
 *
 * A dashed outline rather than a solid pill, borrowing the "add a label" affordance from
 * `ticket-labels.tsx`: dashed already means *not filled in yet* everywhere else on this
 * screen, which is exactly what this is. It is deliberately not a link — a ticket with no
 * team has nothing to navigate to that the row itself does not already open.
 *
 * One component for every surface that used to interpolate `ticket.identifier`, so the
 * answer for a ticket without one is given once instead of eight times.
 */
export function TicketIdentifier({
  ticket,
  className,
  ...rest
}: {
  ticket: { identifier?: string | null; teamId?: string | null };
  className?: string;
} & React.HTMLAttributes<HTMLSpanElement>) {
  if (!hasNoTeam(ticket) && ticket.identifier) {
    return (
      <span className={className} {...rest}>
        {ticket.identifier}
      </span>
    );
  }
  return (
    <span
      className={cn(className, NO_TEAM_PILL)}
      data-no-team=""
      title="This ticket belongs to no team yet"
      {...rest}
    >
      {NO_TEAM_LABEL}
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
