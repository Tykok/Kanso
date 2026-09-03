import type { EffectiveVelocity } from "./api";
import { YOURS, type Voice } from "./voice";

/**
 * What the screen says about where a number came from.
 *
 * A pure function, in its own module, for the reason `inbox/first-session.ts` is: the
 * decision is the feature and a component is the one place it cannot be tested. The
 * caption is not decoration — a date whose origin nobody can name is a date nobody should
 * act on — so it is asserted, sentence by sentence, in `velocity.test.ts`.
 *
 * Nothing here re-derives which source won. The server arbitrates and sends its verdict;
 * this file only puts it into words. A second copy of the rule living in the client is
 * exactly how the caption ends up disagreeing with the number above it.
 */
export type VelocityCaption = {
  /** The sentence naming the source in force. Always present — never an icon, never blank. */
  inForce: string;
  /**
   * The number that lost, kept beside the one that won. Null when there is only one.
   *
   * A gap between the two is the most useful thing this feature produces, and it only
   * exists if the loser is still shown.
   */
  reference: string | null;
};

/** At most two decimals, and no trailing zeroes: `1`, `0.8`, `1.33`. */
export function formatRate(rate: number): string {
  return String(Math.round(rate * 100) / 100);
}

/** `1 point per working day` / `0.8 points per working day`. */
function rateWords(rate: number): string {
  return `${formatRate(rate)} point${rate === 1 ? "" : "s"} per working day`;
}

/** `1 closed cycle` / `3 closed cycles`. */
function cycleCount(n: number): string {
  return `${n} closed ${n === 1 ? "cycle" : "cycles"}`;
}

export function velocityCaption(
  velocity: EffectiveVelocity,
  /**
   * Whose pace this is. Screen 41 aims this caption at a colleague, and every sentence
   * below was written in the second person — "measured from *your* last 2 closed cycles"
   * under a heading naming somebody else is the failure this parameter exists to prevent.
   * It was found on screen rather than by a test, which is what looking at the app is for.
   */
  voice: Voice = YOURS,
): VelocityCaption {
  const { perWorkingDay, source, declared, measured, measuredCycles, cyclesUntilMeasured } = velocity;

  if (source === "none" || perWorkingDay === undefined) {
    return {
      inForce:
        // "Declare one below" until this caption got a second reader. It is true on the
        // settings screen, where the field is directly under it, and a lie on the progress
        // page, which has no field on it at all — and a caption that points at nothing is
        // worse than one that names the screen. Reworded rather than forked: the argument
        // for one sentence is that two screens showing this number must explain it the same
        // way, and a per-screen variant is exactly the drift that argument is against.
        // The one branch that is not a swap of pronouns. "Declare one in your preferences"
        // is an instruction, and on a colleague's page there is nothing for the reader to
        // do — pointing them at their own preferences would be advice about the wrong
        // person. So the second person keeps the instruction and the third states the fact.
        voice.own
          ? "No velocity yet, so Kanso is not estimating any dates for you. Declare one in" +
            " your preferences, or close a cycle with finished, sized work in it."
          : `No velocity yet, so Kanso is not estimating any dates for ${voice.object}. It` +
            " takes a declared velocity, or a closed cycle with finished, sized work in it.",
      reference: null,
    };
  }

  // The number in force is named, not only its source. The field above this caption holds
  // the *declared* value whatever wins, so a caption that said "measured" without saying
  // what was measured would leave the only visible number the one that is not being used.
  if (source === "declared") {
    const more = cyclesUntilMeasured;
    return {
      inForce:
        `Kanso is planning with the ${rateWords(perWorkingDay)} ${voice.object} declared —` +
        ` ${more} more closed ${more === 1 ? "cycle" : "cycles"} and it will use the measured one.`,
      // Shown while it is still losing: watching the two converge, or not, is the point.
      reference:
        measured === undefined
          ? null
          : `Measured so far: ${formatRate(measured)}, over ${cycleCount(measuredCycles)}.`,
    };
  }

  return {
    inForce:
      measuredCycles === 1
        ? // Nothing was declared, so one cycle is all there is. Saying it is thin is more
          // honest than showing the number bare, and more useful than showing nothing.
          `Kanso is planning with ${rateWords(perWorkingDay)}, measured from ${voice.possessive}` +
          " last closed cycle. One cycle is thin — it will settle as more close."
        : `Kanso is planning with ${rateWords(perWorkingDay)}, measured from ${voice.possessive}` +
          ` last ${cycleCount(measuredCycles)}.`,
    reference:
      declared === undefined
        ? null
        : `${voice.declared} ${formatRate(declared)}. Kanso keeps that as ${voice.wrote} — a` +
          " lasting gap is worth knowing about, not correcting.",
  };
}
