import { labelOfKey } from "@/lib/statuses";
import type { Notification, TicketStatus } from "@/lib/api";
import { STATUS_LABELS } from "@/lib/status";

/**
 * What an inbox row says, in one place.
 *
 * The server sends the kind, the actor, the thing and whatever number the kind needs;
 * the sentence is composed here. Copy belongs beside the rest of the interface's copy —
 * a Kotlin service assembling English is a service that has to be redeployed to fix a
 * typo, and it is also the one place with no way to know the reader is looking at a
 * document rather than a ticket.
 *
 * Pure, and its own module rather than a helper inside the row component, because this
 * is the part of screen 14 that can be wrong in a way nobody notices: a `status_moved`
 * row that forgets to name the status still renders.
 */

export type RowCopy = {
  /** The bold line. Always present: a row with no sentence is a row with no reason. */
  sentence: string;
  /** The line under it — the ticket's title, the mirror's reason, the excerpt. */
  detail?: string;
};

const quoted = (value: string) => `“${value}”`;

/**
 * What to call the system that refused a push.
 *
 * Closed here the way `STATUS_LABELS` is, and falling back to the wire value rather
 * than to "the mirror": a destination this build has never heard of is one a newer
 * server added, and printing its own name is closer to true than printing Notion's.
 */
const DESTINATION_LABELS: Record<string, string> = { notion: "Notion mirror" };

function destinationLabel(raw: unknown): string {
  if (typeof raw !== "string" || !raw) return DESTINATION_LABELS.notion;
  return DESTINATION_LABELS[raw] ?? raw;
}

/** A status the vocabulary knows, written as the rest of the app writes it. */
function statusLabel(raw: unknown): string | undefined {
  if (typeof raw !== "string") return undefined;
  // Not a lookup that throws: this value came off a payload written by an older
  // build, and a row that renders "moved to triaged" is better than one that crashes
  // the list it is in.
  return labelOfKey(raw);
}

export function rowCopy(notification: Notification): RowCopy {
  const actor = notification.actor?.displayName;
  const { subject, payload } = notification;

  switch (notification.kind) {
    case "assigned":
      return {
        sentence: actor ? `${actor} assigned this ticket to you` : "This ticket was assigned to you",
        detail: subject,
      };

    case "status_moved": {
      const to = statusLabel(payload.to);
      const where = to ? ` to ${to}` : "";
      return {
        sentence: actor ? `${actor} moved this ticket${where}` : `This ticket was moved${where}`,
        detail: subject,
      };
    }

    case "mentioned": {
      const where = subject ? ` in ${quoted(subject)}` : "";
      const excerpt = typeof payload.excerpt === "string" ? payload.excerpt : undefined;
      return {
        sentence: actor ? `${actor} mentioned you${where}` : `You were mentioned${where}`,
        detail: excerpt ? quoted(excerpt) : subject,
      };
    }

    case "comment_replied":
      return { sentence: "Your comment received a reply", detail: subject };

    case "project_slipped": {
      const days = typeof payload.days === "number" ? payload.days : undefined;
      const by = days === undefined ? "" : ` by ${days} ${days === 1 ? "day" : "days"}`;
      const name = subject ? ` ${quoted(subject)}` : "";
      return {
        sentence: `The project${name} slipped${by}`,
        detail: "Recalculated from the critical path",
      };
    }

    case "sync_failed":
      return {
        // The outbox serves more than Notion now, so the row names who refused rather
        // than assuming. An older server sends no destination and only ever meant
        // Notion, which is what the fallback says.
        sentence: `The ${destinationLabel(payload.destination)} refused this write`,
        // The far side's own words, not a paraphrase: "the target page is locked by
        // another workspace" is actionable and "sync failed" is not.
        detail: typeof payload.error === "string" && payload.error
          ? payload.error
          : "The change is kept in the queue.",
      };

    case "conflict": {
      const field = typeof payload.field === "string" ? payload.field : "value";
      return { sentence: `Two versions of the ${field}`, detail: subject };
    }
  }
}

/**
 * The two headings the drawing has: `Unread` and `Earlier`.
 *
 * Read state, not age. Grouping by time would put an unread row from last week under
 * "Earlier", which is the one place someone looking for what they missed would not
 * look.
 */
export function groupOf(notification: Notification): "unread" | "earlier" {
  return notification.readAt ? "earlier" : "unread";
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * `4 min ago`, `1 h ago`, `yesterday`, `2 days` — the drawing's own four shapes.
 *
 * Takes `now` rather than reading the clock, so the test can name a moment and so the
 * whole list is timed against one instant instead of drifting across a render.
 */
export function relativeTime(iso: string, now: Date): string {
  const elapsed = now.getTime() - new Date(iso).getTime();

  // A negative elapsed time is a browser clock behind the server's, not the future.
  if (elapsed < MINUTE) return "just now";
  if (elapsed < HOUR) return `${Math.floor(elapsed / MINUTE)} min ago`;
  if (elapsed < DAY) return `${Math.floor(elapsed / HOUR)} h ago`;

  const days = Math.floor(elapsed / DAY);
  return days === 1 ? "yesterday" : `${days} days`;
}
