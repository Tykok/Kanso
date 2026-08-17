/**
 * When a delivered ticket shipped, as the drawing prints it: `18 July`.
 *
 * The year appears only when it is not the current one. A roadmap's delivered column is
 * read as "recently", so repeating this year on every row is noise — and dropping the
 * year altogether would make a two-year-old release look like last month, which is the
 * one thing a date on that column exists to prevent.
 *
 * `now` is a parameter rather than read from the clock, so the boundary is testable and
 * so a server render and the browser that hydrates it cannot disagree about what year
 * it is.
 */
export function deliveredOn(iso: string, now: Date): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";

  const day = at.getUTCDate();
  const month = MONTHS[at.getUTCMonth()];
  const year = at.getUTCFullYear();
  return year === now.getUTCFullYear() ? `${day} ${month}` : `${day} ${month} ${year}`;
}

/**
 * Spelled out rather than taken from `Intl`: the format has to be identical on the
 * server and in the browser, and `Intl` resolves against a locale those two do not
 * necessarily agree on. The dates are read in UTC for the same reason every other
 * instant in Kanso is (see `KansoInstant`): a shipping date that reads as the 17th in
 * Paris and the 18th in Tokyo is a date nobody drew.
 */
const MONTHS = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
] as const;
