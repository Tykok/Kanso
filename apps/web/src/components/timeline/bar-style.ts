/**
 * The bar's colour, its accessible name and the slack strip that follows it — pulled
 * out of `bar.tsx` because all four are pure functions of props `bar.tsx` already
 * has, and that file was already past the file-size guideline before this task
 * touched it. Kept here rather than folded into `timeline-geometry.ts`: nothing in
 * this file computes a position or a width in days, which is what that module is
 * for and what this task does not touch.
 */
import type { CSSProperties } from "react";
import type { TicketStatus } from "@/lib/api";
import { STATUS_COLORS, STATUS_LABELS } from "@/lib/status";

/**
 * The state a bar is drawn in. `critical` — on the schedule's critical path, no
 * slack left — carries no colour of its own: the drawing shows it exactly like an
 * ordinary bar of the same status, and lets the chain of arrows leaving it say
 * "critical" instead. `late` is the one state this component still colours by
 * itself, because it is worse than "no slack" — it has already run out — and a
 * ticket that overran its own deadline needs to be seen without following a chain
 * of arrows to notice it.
 */
export type BarState = "normal" | "critical" | "late";

/**
 * Late, composed from `--urgent` rather than written as a second colour — a
 * repeating hatch, not a flat fill, so the state survives a colour-blind reader and
 * a greyscale screenshot alike. Inline style, not a Tailwind arbitrary value: a
 * `repeating-linear-gradient` wrapped in a bracketed class is unreadable, and this
 * is no less a token reference for being spelled in JS.
 */
const LATE_STRIPE = "color-mix(in srgb, var(--urgent) 70%, black)";
export const LATE_STYLE: CSSProperties = {
  backgroundImage: `repeating-linear-gradient(45deg, var(--urgent), var(--urgent) 6px, ${LATE_STRIPE} 6px, ${LATE_STRIPE} 12px)`,
};

/**
 * An ordinary ticket bar's own colour: a pale tint of its status, exactly the hue a
 * dot or a pill draws elsewhere, so nothing about a bar needs a legend of its own —
 * the accent-left stripe from `settings`' preview and the list's selected row is
 * the same device, coloured by status instead of by selection.
 *
 * A ticket with an incoming dependency the schedule no longer respects wears the
 * urgent colour instead, whatever its own status — see `violated` on `BarProps`.
 * That fact outranks "todo" or "in review" as the one worth seeing first, which is
 * why it is read here rather than layered on top as a second, separate mark.
 */
export function ticketTint(
  status: TicketStatus | undefined,
  violated: boolean | undefined,
): CSSProperties {
  const color = violated ? "var(--urgent)" : status ? STATUS_COLORS[status] : "var(--faint)";
  return {
    backgroundColor: `color-mix(in oklch, ${color} 14%, var(--background))`,
    boxShadow: `inset 2px 0 0 ${color}`,
  };
}

/**
 * Which edges were deduced rather than posted. Two arbitrary properties, not four
 * stacked data-attribute variants: `border-*-style` has no Tailwind utility of its
 * own, only `border-style` for all four sides at once.
 */
export function derivedBorderClass(derived?: "start" | "end" | "both"): string {
  if (derived === "both") return "[border-left-style:dashed] [border-right-style:dashed]";
  if (derived === "start") return "[border-left-style:dashed]";
  if (derived === "end") return "[border-right-style:dashed]";
  return "";
}

/**
 * Said once here rather than carried by colour alone: `critical` no longer has one
 * of its own (see `BarState`), and `late` and `violated` both still do, but a
 * screen reader gets no colour either way. `slackMinutes` is the same story: the
 * strip that draws it (below) is `aria-hidden`, on purpose — a second focusable
 * node per bar would be worse — so the one clause `slackTitle` already writes for
 * its `title` is folded in here instead of being left to disappear with the strip.
 * Absent or non-positive slack adds nothing, not an empty clause: a ticket with no
 * dependencies has none to report, and a kind that never draws the strip (a
 * project) should never have its name mention it either.
 */
export function barAccessibleName({
  name,
  status,
  state,
  violated,
  slackMinutes,
}: {
  name: string;
  status?: TicketStatus;
  state: BarState;
  violated?: boolean;
  slackMinutes?: number;
}): string {
  return [
    name,
    status && STATUS_LABELS[status],
    state === "late" ? "overdue" : state === "critical" ? "critical path" : null,
    violated && state !== "late" ? "dependency not respected" : null,
    slackMinutes && slackMinutes > 0 ? slackTitle(slackMinutes) : null,
  ]
    .filter(Boolean)
    .join(" — ");
}

/**
 * The slack strip: a hatched extension of a ticket's bar, from where it ends to
 * where its next dependency actually forces something to move. "La marge est
 * dessinée plutôt que sous-entendue" — the prototype draws it rather than leaving
 * it implied, and this is that, minus the drawing's own pixel scale, which does
 * not match this app's `PX_PER_DAY` and was never meant to.
 *
 * A neutral hatch, not a status colour: slack is a fact about the schedule, not
 * about the ticket, and `--rule` — "a stated divider" per `tokens.css` — is the one
 * token already reserved for exactly that kind of mark.
 */
const SLACK_STRIPE = "var(--rule)";
export const SLACK_STYLE: CSSProperties = {
  backgroundImage: `repeating-linear-gradient(45deg, transparent 0 3px, ${SLACK_STRIPE} 3px 6px)`,
};

/**
 * The strip's width in pixels. `slackMinutes` is a duration, not a date — unlike
 * every other measurement in this file, converting it does not go through
 * `timeline-geometry.ts`'s day-string helpers, which would round-trip it through a
 * calendar day and back for no reason. Zero once slack runs out or the ticket has
 * none to report (`slackMinutes` is absent for a ticket with no dependencies).
 */
export function slackWidthPx(slackMinutes: number | undefined, pxPerDay: number): number {
  if (!slackMinutes || slackMinutes <= 0) return 0;
  return (slackMinutes / 1440) * pxPerDay;
}

/** Whole days, rounded — the same granularity the prototype's own caption uses
 *  ("4 jours de marge"), not the exact minute count nobody schedules by. */
export function slackTitle(slackMinutes: number): string {
  const days = Math.max(1, Math.round(slackMinutes / 1440));
  return `${days} day${days === 1 ? "" : "s"} of slack`;
}
