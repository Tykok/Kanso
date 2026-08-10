import { dayValue, type KansoInstant } from "./api";

/**
 * Geometry only. No React, no DOM — Vitest runs in `environment: "node"`, so this is
 * the one place on the web side where a rule can be covered by a test.
 *
 * Every date here is handled as a `YYYY-MM-DD` string, never as a `Date`. A bar's
 * column is a civil day, and `new Date("2026-08-12T00:00:00Z")` in Los Angeles is the
 * 11th — which would put the bar one column left for anyone west of UTC, on data they
 * did not touch.
 */

export type Zoom = "day" | "week" | "month";
export const ZOOMS: readonly Zoom[] = ["day", "week", "month"] as const;

/** Pixels per calendar day. The zoom names describe the label density, not the unit. */
export const PX_PER_DAY: Record<Zoom, number> = { day: 28, week: 10, month: 3 };

/**
 * The civil day a bound sits on.
 *
 * Re-exported from the API module rather than written a second time: `dayValue` is
 * the same slice, carrying the same "never `new Date`" rule. Two spellings of the one
 * rule this feature exists to protect is how it eventually gets half-fixed.
 */
export { dayValue as dayKey } from "./api";

const MS_PER_DAY = 86_400_000;

/** A `YYYY-MM-DD` string as a floating instant — the only shape a column can produce. */
const floatingDay = (day: string): KansoInstant => ({ at: `${day}T00:00:00Z`, hasTime: false });

/** Whole days between two `YYYY-MM-DD` strings. Both are read as UTC, so no zone applies. */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / MS_PER_DAY);
}

/**
 * A floating instant [days] later. Stays floating: shifting a bar by columns names a
 * day, and carrying the old hour over would be an arithmetic on a moment nobody moved.
 */
export function addDays(instant: KansoInstant, days: number): KansoInstant {
  const shifted = new Date(Date.parse(`${dayValue(instant)}T00:00:00Z`) + days * MS_PER_DAY);
  return floatingDay(shifted.toISOString().slice(0, 10));
}

export function xOf(instant: KansoInstant, origin: string, zoom: Zoom): number {
  return daysBetween(origin, dayValue(instant)) * PX_PER_DAY[zoom];
}

/**
 * A bar covers its last day rather than stopping at its start, and never shrinks below
 * one column — a milestone carries one bound and would otherwise be zero pixels wide,
 * and a pair held inverted mid-drag would otherwise be drawn backwards.
 */
export function widthOf(start: KansoInstant, end: KansoInstant, zoom: Zoom): number {
  const days = daysBetween(dayValue(start), dayValue(end)) + 1;
  return Math.max(days, 1) * PX_PER_DAY[zoom];
}

export function instantAtX(x: number, origin: string, zoom: Zoom): KansoInstant {
  return addDays(floatingDay(origin), Math.round(x / PX_PER_DAY[zoom]));
}

/** A pixel delta as whole days. Truncates, so a drag shorter than one column moves nothing. */
export function snapDays(dx: number, zoom: Zoom): number {
  return Math.trunc(dx / PX_PER_DAY[zoom]);
}

/**
 * How a bound reads to one person.
 *
 * Both branches go through `Intl` so the two orderings never appear side by side: a
 * hardcoded `dd/mm` next to a locale-formatted `mm/dd` would show an American reader
 * `04/08 → 08/12` for a bar running from August 4th to August 12th, written two ways
 * in one tooltip.
 *
 * The floating branch pins `timeZone: "UTC"`, which is what keeps a day a day. Swapping
 * that for [timezone] is the one edit that breaks this, and the "reads the same in
 * Tokyo and in Los Angeles" test exists to fail loudly when someone tries.
 */
export function boundLabel(instant: KansoInstant, timezone: string): string {
  const parts: Intl.DateTimeFormatOptions = instant.hasTime
    ? { timeZone: timezone, day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }
    : { timeZone: "UTC", day: "2-digit", month: "2-digit" };
  return new Intl.DateTimeFormat(undefined, parts).format(new Date(instant.at));
}

export type AxisTick = { day: string; x: number; label: string };

/** Where the axis draws a label, and what it says. */
export function axisTicks(origin: string, dayCount: number, zoom: Zoom): AxisTick[] {
  const ticks: AxisTick[] = [];
  for (let offset = 0; offset < dayCount; offset += 1) {
    const day = dayValue(addDays(floatingDay(origin), offset));
    const [year, month, dayOfMonth] = day.split("-");
    const isFirst = dayOfMonth === "01";
    // `getUTCDay` on a day parsed as UTC: the weekday of the column, not of the reader.
    const isMonday = new Date(`${day}T00:00:00Z`).getUTCDay() === 1;

    if (zoom === "day" || (zoom === "week" && isMonday) || (zoom === "month" && isFirst)) {
      ticks.push({
        day,
        x: offset * PX_PER_DAY[zoom],
        label: zoom === "month" ? `${month}/${year.slice(2)}` : `${dayOfMonth}/${month}`,
      });
    }
  }
  return ticks;
}
