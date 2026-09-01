import type { TicketDuration } from "./api";

/**
 * How long a ticket will take, in words — including the three ways of saying it cannot be
 * said.
 *
 * A pure function in its own module, like `velocity.ts` and for the same reason: the copy
 * *is* the feature here. Three different absences rendered as the same blank field is the
 * bug this exists to prevent, and a blank field is only distinguishable from a broken one
 * by what is written in its place.
 */
export type DurationCopy = {
  /** The range, or null when there is none to show. Never a single date. */
  value: string | null;
  /**
   * Why the range says what it says, or why there is none.
   *
   * Always present. An absence with no sentence beside it reads as something that failed
   * to load, and the reader cannot tell which of the three fixes is theirs to make.
   */
  note: string;
};

const days = (n: number) => `${n} working ${n === 1 ? "day" : "days"}`;

export function durationCopy(duration: TicketDuration): DurationCopy {
  switch (duration.basis) {
    case "no_estimate":
      return {
        value: null,
        // Named first because it is the cheapest fix and nobody else's decision.
        note: "Not sized yet. Give this ticket an estimate and Kanso can say how long it should take.",
      };

    case "no_assignee":
      return {
        value: null,
        note:
          "Nobody is on this yet. A duration comes from somebody's pace, so this is an" +
          " unowned ticket rather than a slow one.",
      };

    case "no_velocity":
      return {
        value: null,
        note:
          "Nobody on this ticket has a velocity yet. Declare one in Settings › Velocity," +
          " or close a cycle with finished, sized work in it.",
      };

    case "estimated": {
      const { lowWorkingDays: low, highWorkingDays: high, points, assignees, withoutVelocity } = duration;
      return {
        // The tilde and the range together, because either alone gets read as a date.
        value: low === high ? `~${days(low)}` : `~${low} to ${days(high)}`,
        note:
          withoutVelocity > 0
            ? `${points} points, and ${withoutVelocity} of ${assignees} assignees` +
              ` ${withoutVelocity === 1 ? "has" : "have"} no velocity yet — the real range is` +
              " shorter than this one."
            : assignees === 1
              ? `${points} points at the assignee's pace. An estimate, not a deadline.`
              : `${points} points at the ${assignees} assignees' combined pace. An estimate, not a deadline.`,
      };
    }
  }
}
