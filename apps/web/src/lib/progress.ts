import type { EffectiveVelocity, OpenLoad, ProgressReaders, TeamPace } from "./api";
import type { DeliveredCycle } from "./api";
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
 *
 * **The same sentences, in the third person.** Screen 41 points this page at somebody else,
 * and every rule above holds harder there: a reader who is not the subject has no other way
 * to know that the flat chart in front of them is one closed cycle rather than a quiet
 * quarter. So the branches are not duplicated for the other-person view — [Voice] swaps the
 * pronouns and nothing else, which is the only version of this where a comparator cannot be
 * added to one copy and not the other.
 */

/**
 * Whose page this is, as the four words that change.
 *
 * Pronouns and an agreement, not a rewrite. Two sets of sentences — one for yourself and
 * one for a colleague — would be two places for somebody to add "below the team average"
 * to, and only one of them would be reviewed.
 *
 * A name and never a pronoun for the third person: "they are carrying 14 days of work" in
 * a page whose heading is a name reads as a sentence about the reader for the first second
 * of every visit, and the first second is when a productivity number lands.
 */
export type Voice = {
  /** `You` / `Ana Ruiz` — the head of a sentence. */
  subject: string;
  /** `are` / `is`, agreeing with `subject`. */
  are: string;
  /** `you` / `Ana Ruiz` — the subject as an object. */
  object: string;
  /** `your` / `their`. */
  possessive: string;
};

/** The default, because the page a person opens on themselves is the common case. */
export const YOURS: Voice = { subject: "You", are: "are", object: "you", possessive: "your" };

export function about(name: string): Voice {
  return { subject: name, are: "is", object: name, possessive: "their" };
}

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
        "No cycle has closed in this team yet, so there is nothing to chart. Points" +
        " delivered appear here as soon as two cycles have closed.",
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
export function loadSentence(
  load: OpenLoad,
  velocity: EffectiveVelocity,
  voice: Voice = YOURS,
): string {
  const { tickets, points: open, unestimated } = load.load;

  if (tickets === 0) {
    return `Nothing open is assigned to ${voice.object} in this team.`;
  }

  if (load.workingDays === undefined) {
    return (
      `${voice.subject} ${voice.are} carrying ${points(open)} across ${count(tickets)}.` +
      ` Kanso has no pace for ${voice.object} yet, so it will not guess how long that is.` +
      blindSpot(unestimated, tickets, voice)
    );
  }

  // "at the pace in force" rather than "at your velocity": which of the two numbers is in
  // force is `velocityCaption`'s sentence, printed above this one, and repeating the
  // arbitration here would be a second place for it to be got wrong.
  return (
    `${voice.subject} ${voice.are} carrying ${points(open)} across ${count(tickets)} — about` +
    ` ${days(load.workingDays)} of work at the ${rate(velocity.perWorkingDay ?? 0)}` +
    ` in force.${blindSpot(unestimated, tickets, voice)}`
  );
}

/**
 * The same sentence for a team's plate, and the one branch that could not be a [Voice].
 *
 * "Nothing open is assigned to Mobile" would be false: a team's plate counts the tickets
 * nobody is assigned, which is the whole difference between this read and a person's. So
 * the empty branch says something else, and the rest of the shape is the personal
 * sentence's — same helpers, same rounding, same refusal to guess without a pace.
 */
export function teamLoadSentence(load: OpenLoad, pace: TeamPace, teamName: string): string {
  const { tickets, points: open, unestimated } = load.load;

  if (tickets === 0) {
    return `${teamName} has nothing open.`;
  }

  const carrying = `${teamName} is carrying ${points(open)} across ${count(tickets)}`;
  const blind = unestimated === 0 ? "" : ` ${unestimated} of them carry no estimate, so they are not in that.`;

  if (load.workingDays === undefined) {
    return `${carrying}. Kanso has measured no pace for this team, so it will not guess how long that is.${blind}`;
  }
  return (
    `${carrying} — about ${days(load.workingDays)} of work at the` +
    ` ${rate(pace.perWorkingDay ?? 0)} it has been delivering.${blind}`
  );
}

/**
 * Who else can read this page, as the sentence the ticket asks for.
 *
 * Guard-rail two, and it costs one sentence, which was the ticket's own argument for it:
 * individual productivity figures readable without the subject knowing is the kind of
 * detail that decides whether a team adopts a tool.
 *
 * Built from the server's answer rather than from the rule restated here. The rule has
 * three branches and an ancestry clause, and a client that re-derived it would be wrong the
 * first time somebody was made an administrator of a parent team.
 *
 * [subjectName] is null on your own page. Nobody is named twice: the lists arriving from
 * the server already exclude the subject, and a team whose only administrator is the
 * subject is already absent from them.
 */
export function readersSentence(readers: ProgressReaders, subjectName: string | null): string {
  const others = [
    ...readers.instanceAdmins.map((person) => person.displayName),
    ...readers.teams.map((team) => `the administrators of ${team.name}`),
  ];
  const whose = subjectName ?? "you";

  // Said plainly rather than left blank. A page that says nothing about who reads it is
  // indistinguishable from a page whose readers nobody computed.
  if (others.length === 0) {
    return subjectName === null
      ? "Nobody else can read this page."
      : `Nobody but ${whose} can read this page.`;
  }

  return `Readable by ${whose}, and by ${list(others)}.`;
}

/** `a`, `a and b`, `a, b and c` — the Oxford-free join the rest of the app uses. */
function list(items: readonly string[]): string {
  if (items.length === 1) return items[0];
  return `${items.slice(0, -1).join(", ")} and ${items[items.length - 1]}`;
}

/** `2 points per working day` / `1 point per working day`. */
function rate(value: number): string {
  return `${points(value)} per working day`;
}

/**
 * What the sum cannot speak for, or nothing at all.
 *
 * Never `0 unestimated`, which would be a sentence about an absence of a problem. And it
 * names the whole so the reader can size the gap: "2 of your 3" is a plate the number
 * barely describes, "2 of your 40" is a footnote.
 */
function blindSpot(unestimated: number, tickets: number, voice: Voice): string {
  if (unestimated === 0) return "";
  return ` ${unestimated} of ${voice.possessive} ${tickets} carry no estimate, so they are not in that.`;
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
