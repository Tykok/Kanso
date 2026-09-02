import type { DeliveredCycle, EffectiveVelocity, OpenLoad } from "./api";
import { formatRate } from "./velocity";

/**
 * The two refusals screen 40 is built around, as functions rather than as JSX.
 *
 * The ticket names both of them as design traps, which is another way of saying they are
 * the part of this screen that is a *decision* — and a decision inside a component is a
 * decision nobody can test. `velocity.ts` makes the same argument for the sentence naming
 * which pace is in force, and this module is its neighbour: nothing here re-derives a
 * number, it only decides what may be drawn and puts the rest into words.
 *
 * **A single bar is not a trend.** One closed cycle is a measurement and Kanso says so
 * elsewhere, but drawn as a lone bar in a chart it invites a reading a chart cannot
 * support — a direction — and there is no direction in one point. So under
 * [MIN_FOR_A_TREND] cycles the screen shows what it is waiting for, with the figure it
 * does have written into the sentence so the wait is not also a loss of information.
 *
 * **Nothing here may read as a grade.** "5 points per working day" is meaningful compared
 * to the same person three cycles ago and meaningless compared to anybody else, so every
 * sentence below is in the second person, about this person, over time. There is no
 * ranking, no team average to sit beside, no adjective — no "good", no "slow", no target
 * to have missed. That is not squeamishness: a productivity number with a comparator
 * attached changes what people do with their tickets, and the numbers stop describing the
 * work. The team view is explicitly aggregates-only for the same reason.
 */

/**
 * Closed cycles needed before the delivered-points chart is drawn at all.
 *
 * Two, which is the fewest that can have a direction. Not three to match the velocity's
 * own window: withholding a chart of evidence that already exists is its own kind of lie,
 * and the bars carry `countedTowardsVelocity` so a chart wider than the measurement can
 * say which part the number came from.
 */
export const MIN_FOR_A_TREND = 2;

export type Trend =
  | { drawable: true; cycles: number }
  /** What the screen prints instead of a chart. Never blank, and never an empty box. */
  | { drawable: false; waiting: string };

export function trend(delivered: readonly DeliveredCycle[]): Trend {
  if (delivered.length >= MIN_FOR_A_TREND) return { drawable: true, cycles: delivered.length };

  if (delivered.length === 0) {
    return {
      drawable: false,
      waiting:
        "No cycle has closed in this team yet, so there is nothing to chart. Points you" +
        " deliver appear here as soon as two cycles have closed.",
    };
  }

  // The one cycle's figure goes into the sentence. Refusing the chart is a refusal to draw
  // a direction, not a refusal to say what happened — and a screen that said "not enough
  // data" while holding a number would be hiding it.
  const only = delivered[0];
  return {
    drawable: false,
    waiting:
      `One closed cycle so far: ${points(only.points)} in cycle ${only.number}.` +
      " One bar is not a trend — one more closed cycle and this becomes a chart.",
  };
}

/**
 * The plate, weighed against this person's own pace.
 *
 * The sentence the ticket asks for in as many words, and the only place the two halves of
 * this screen meet: a number of points is inert, and a number of days is what somebody can
 * act on. Its four branches are four genuinely different situations, and the three that
 * are not the happy one all say *why* they cannot say more — an empty field reads as
 * something that failed to load.
 */
export function loadSentence(load: OpenLoad, velocity: EffectiveVelocity): string {
  const { tickets, points: open, unestimated } = load.load;

  if (tickets === 0) {
    return "Nothing open is assigned to you in this team.";
  }

  if (load.workingDays === undefined) {
    return (
      `You are carrying ${points(open)} across ${count(tickets)}.` +
      ` Kanso has no pace for you yet, so it will not guess how long that is.${blindSpot(unestimated, tickets)}`
    );
  }

  // "at the pace in force" rather than "at your velocity": which of the two numbers is in
  // force is `velocityCaption`'s sentence, printed above this one, and repeating the
  // arbitration here would be a second place for it to be got wrong.
  return (
    `You are carrying ${points(open)} across ${count(tickets)} — about` +
    ` ${days(load.workingDays)} of work at the ${formatRate(velocity.perWorkingDay ?? 0)} points` +
    ` per working day in force.${blindSpot(unestimated, tickets)}`
  );
}

/**
 * What the sum cannot speak for, or nothing at all.
 *
 * Never `0 unestimated`, which would be a sentence about an absence of a problem. And it
 * names the whole so the reader can size the gap: "2 of your 3" is a plate the number
 * barely describes, "2 of your 40" is a footnote.
 */
function blindSpot(unestimated: number, tickets: number): string {
  if (unestimated === 0) return "";
  return ` ${unestimated} of your ${tickets} carry no estimate, so they are not in that.`;
}

/** `5 points` / `1 point` / `4.5 points`. Fractional, because a shared ticket splits. */
function points(value: number): string {
  return `${formatRate(value)} point${formatRate(value) === "1" ? "" : "s"}`;
}

function count(value: number): string {
  return `${value} ticket${value === 1 ? "" : "s"}`;
}

/**
 * `6.5 working days`, to one decimal.
 *
 * One decimal and no more. The pace underneath is a mean over three cycles, so `6.47 days`
 * would be a precision the input cannot support and the kind of number somebody puts in a
 * commitment — which is the same argument `TicketDuration` makes by refusing to carry a
 * point estimate at all.
 */
function days(value: number): string {
  const rounded = Math.round(value * 10) / 10;
  return `${rounded} working day${rounded === 1 ? "" : "s"}`;
}
