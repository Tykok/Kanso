import type { CycleTime, CycleTimePoint, Wip } from "./api";
import { MIN_FOR_A_TREND, type Trend } from "./progress";
import { YOURS, type Voice } from "./voice";

/**
 * The decisions KAN-23 makes about what may be drawn, as functions rather than as JSX.
 *
 * `lib/progress.ts` is the neighbour and the precedent: a decision inside a component is a
 * decision nobody can test, and every branch below is a *refusal* of some kind. Nothing here
 * recomputes a number — the median is the server's — it only turns hours into words and
 * decides when there is not enough to draw.
 *
 * **Every rule in the head of `lib/progress.ts` holds here, and one of them harder.** Cycle
 * time is the figure on this screen most likely to be read as a grade, because unlike a
 * velocity it has an obvious direction: lower looks better. So there is no target anywhere
 * below, no adjective, no comparison to anybody else, and the captions say "taller is
 * slower" rather than "worse" — a cycle where the team took on two hard tickets is not a
 * cycle where they did badly, and the number cannot tell the difference.
 *
 * **Hours never become working days.** The pace on the same screen is points per working day
 * and this is elapsed wall-clock. Dividing one by the other would produce a number with no
 * meaning at all, so the two units stay apart and [duration] is the only place either is
 * spelled.
 */

/**
 * `3 hours` / `2.5 days` / `40 minutes` — elapsed time, at the resolution it can support.
 *
 * Three bands, and the thresholds are about what a reader can act on rather than about
 * arithmetic. Under an hour is minutes, because "0.3 hours" is a number nobody thinks in.
 * Under two days is hours, because that is the range where "31 hours" and "1.3 days" say the
 * same thing and the first is the one somebody can picture. Beyond that it is days to one
 * decimal, for the reason `lib/progress.ts` gives its own days helper: the input is a median
 * over a handful of tickets, so a second decimal would be a precision the sample cannot
 * support and the kind of number somebody puts in a commitment.
 *
 * Calendar days, and the caption beside every use of this says so. A ticket that sat over a
 * weekend really did take three days to reach whoever was waiting for it, and Kanso has no
 * per-person working calendar to subtract — public holidays are per country and time off is
 * per person, which is `VelocityService`'s own reason for not guessing at either.
 */
export function duration(hours: number): string {
  if (hours < 1) {
    const minutes = Math.max(1, Math.round(hours * 60));
    return `${minutes} minute${minutes === 1 ? "" : "s"}`;
  }
  if (hours < 48) {
    const rounded = Math.round(hours);
    return `${rounded} hour${rounded === 1 ? "" : "s"}`;
  }
  const days = Math.round((hours / 24) * 10) / 10;
  return `${days} day${days === 1 ? "" : "s"}`;
}

/**
 * One unit for a whole series, chosen from its biggest value.
 *
 * [duration] is right for a figure in a sentence and **wrong for the labels of a chart**,
 * which is a mistake this feature shipped for one afternoon and a screenshot caught. Three
 * bars labelled `7.6 days`, `45 hours`, `38 hours` are three units in one axis: the reader
 * compares 45 against 7.6, gets the ranking backwards, and the bars beneath them say the
 * opposite. A chart therefore picks one unit off its tallest bar, every label is a bare
 * number in it, and the unit is named once in the caption — which is what an axis is for.
 *
 * The threshold is [duration]'s own: below two days a series reads in hours, at or above it
 * in days to one decimal. So the caption of a chart and the sentence above it never disagree
 * about which unit this work is measured in.
 */
export type DurationScale = {
  /** `hours` / `days` — for the caption, not for each label. */
  unit: string;
  /** The bare number a label draws. */
  format: (hours: number) => string;
};

export function durationScale(values: readonly number[]): DurationScale {
  // `Math.max` of nothing is `-Infinity`, which would pick a unit off a series that has no
  // values — the same trap `burndown.ts`'s `heights` guards with the same zero.
  const tallest = Math.max(0, ...values);
  if (tallest < 48) return { unit: "hours", format: (hours) => `${Math.round(hours)}` };
  return { unit: "days", format: (hours) => `${Math.round((hours / 24) * 10) / 10}` };
}

/**
 * The headline, or what it cannot say — and it never says nothing.
 *
 * Four branches, and the three that are not the happy one all name *why*, because an empty
 * field on a screen of numbers reads as something that failed to load. That is the same
 * argument `loadSentence` makes for its own four.
 *
 * The unmeasured tail is named in the same breath as the median rather than in a footnote
 * somewhere else on the page. A median over four of a person's eleven delivered tickets is a
 * different claim from a median over all eleven, and the reader has to be told which one they
 * are looking at while they are looking at it.
 */
export function cycleTimeSentence(cycleTime: CycleTime, voice: Voice = YOURS): string {
  const { medianHours, measured, unmeasured } = cycleTime;

  if (medianHours === undefined) {
    if (unmeasured === 0) {
      return (
        `Nothing has been delivered in the cycles below, so there is no cycle time to` +
        ` measure for ${voice.object} yet.`
      );
    }
    // The one branch that is a diagnosis rather than an absence: the work exists, and the
    // board is what has no record of it starting.
    return (
      `${unmeasured} delivered ticket${unmeasured === 1 ? "" : "s"} never recorded a start, so` +
      ` Kanso cannot say how long ${voice.possessive} work takes. A ticket moved straight to` +
      ` done has no time in progress to measure.`
    );
  }

  const headline =
    `Half of ${voice.possessive} delivered tickets took ${duration(medianHours)} or less,` +
    ` from the moment work started to the moment they were done.`;
  const sample = ` Measured over ${measured} ticket${measured === 1 ? "" : "s"}.`;
  const tail =
    unmeasured === 0
      ? ""
      : ` ${unmeasured} more never recorded a start, so ${unmeasured === 1 ? "it is" : "they are"} not in that.`;

  return headline + sample + tail;
}

/**
 * The same headline for a team, and the branch that could not be a [Voice].
 *
 * "no cycle time to measure for Mobile yet" is fine, but "their work" is not: a team's
 * delivered tickets include the ones nobody was assigned, which is the whole difference
 * between this read and a person's — the same difference `teamLoadSentence` exists for.
 */
export function teamCycleTimeSentence(cycleTime: CycleTime, teamName: string): string {
  const { medianHours, measured, unmeasured } = cycleTime;

  if (medianHours === undefined) {
    return unmeasured === 0
      ? `${teamName} has delivered nothing in the cycles below, so there is no cycle time to measure.`
      : `${unmeasured} of ${teamName}'s delivered tickets never recorded a start, so Kanso` +
          ` cannot say how long its work takes.`;
  }

  const tail =
    unmeasured === 0
      ? ""
      : ` ${unmeasured} more never recorded a start, so ${unmeasured === 1 ? "it is" : "they are"} not in that.`;
  return (
    `Half of the tickets ${teamName} delivered took ${duration(medianHours)} or less, from the` +
    ` moment work started to the moment they were done. Measured over ${measured}` +
    ` ticket${measured === 1 ? "" : "s"}.${tail}`
  );
}

/**
 * What is in flight, and the one number on this screen somebody can act on today.
 *
 * The oldest is named and the median is not, when they differ. A median age describes the
 * board and the oldest describes a *ticket* — and the ticket is the thing a reader can go and
 * open. Naming both when they are the same would be one sentence saying one thing twice.
 *
 * No target and no "too much", which is the refusal this sentence exists to make. A WIP limit
 * is a decision a team takes about itself, and a tool that shipped an opinion about the right
 * number would be grading a board it knows nothing about. So the sentence reports and stops.
 */
export function wipSentence(wip: Wip, voice: Voice = YOURS): string {
  const { tickets, points, unestimated } = wip.load;

  if (tickets === 0) {
    return `${voice.subject} ${voice.are} not holding anything in progress or in review.`;
  }

  const weight = points > 0 ? ` worth ${points} point${points === 1 ? "" : "s"}` : "";
  const blind =
    unestimated === 0
      ? ""
      : ` ${unestimated} of them carry no estimate, so ${unestimated === 1 ? "it is" : "they are"} not in that weight.`;
  const opening =
    `${voice.subject} ${voice.are} holding ${tickets} ticket${tickets === 1 ? "" : "s"} in flight` +
    `${weight}.${blind}`;

  if (wip.oldestAgeHours === undefined) {
    // Absent ages with a non-empty plate is the honest reading of a board whose moves
    // predate the activity log, or one filled by an import.
    return `${opening} None of them recorded when work started, so Kanso cannot say how long they have been in flight.`;
  }

  const oldest = ` The oldest has been in flight ${duration(wip.oldestAgeHours)}`;
  const middle =
    wip.medianAgeHours === undefined || sameDuration(wip.medianAgeHours, wip.oldestAgeHours)
      ? "."
      : `, and half of them ${duration(wip.medianAgeHours)} or less.`;
  const unrecorded =
    wip.unmeasured === 0
      ? ""
      : ` ${wip.unmeasured} recorded no start, so ${wip.unmeasured === 1 ? "it is" : "they are"} not in that.`;

  return opening + oldest + middle + unrecorded;
}

/**
 * Whether two hour figures would print the same words.
 *
 * Compared after formatting rather than before, which is the only comparison that matters
 * here: the sentence exists to avoid saying "the oldest has been in flight 3 days, and half
 * of them 3 days or less", and two figures three minutes apart print identically.
 */
function sameDuration(a: number, b: number): boolean {
  return duration(a) === duration(b);
}

/**
 * Whether the cycle-time trend may be drawn, reusing screen 40's threshold exactly.
 *
 * [MIN_FOR_A_TREND] rather than a second constant, because it is the same claim about the
 * same cycles: two points is the fewest that can have a direction, and this chart sits
 * directly beneath the delivered-points one. Two thresholds would let one chart appear while
 * its neighbour showed a waiting message, over identical history.
 *
 * Counted over the cycles that have a median, not over the cycles. A team with six closed
 * cycles that delivered something in one of them has one point, and six hatched gaps are not
 * five sixths of a trend.
 */
export function cycleTimeTrend(trend: readonly CycleTimePoint[]): Trend {
  const measurable = trend.filter((point) => point.cycleTime.medianHours !== undefined);
  if (measurable.length >= MIN_FOR_A_TREND) return { drawable: true, cycles: trend.length };

  if (measurable.length === 0) {
    return {
      drawable: false,
      waiting:
        "No closed cycle has a measurable cycle time yet, so there is nothing to chart." +
        " A cycle needs at least one delivered ticket whose start was recorded.",
    };
  }

  // The one cycle's figure goes into the sentence, for the reason `trend` puts its own
  // there: refusing to draw a direction is not a reason to withhold a number.
  const only = measurable[0];
  return {
    drawable: false,
    waiting:
      `One closed cycle can be measured so far: a median of` +
      ` ${duration(only.cycleTime.medianHours ?? 0)} in cycle ${only.number}.` +
      " One bar is not a trend — one more measurable cycle and this becomes a chart.",
  };
}
