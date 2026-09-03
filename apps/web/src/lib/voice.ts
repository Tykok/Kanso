/**
 * Whose page this is, as the five words that change.
 *
 * Pronouns and an agreement, not a rewrite. Screen 41 points the personal figures at
 * somebody else, and two sets of sentences — one for yourself, one for a colleague — would
 * be two places for somebody to add "below the team average" to, with only one of them ever
 * reviewed. `lib/velocity.ts` already argues against forking its caption per screen for the
 * same reason, and now reads this instead of forking.
 *
 * A module of its own because both files need it and `lib/progress.ts` already imports
 * `lib/velocity.ts`: defining it in either would make the pair circular.
 *
 * A name and never a pronoun for the third person. "They are carrying 14 days of work" on a
 * page headed by a name reads as a sentence about the reader for the first second of every
 * visit, and the first second is when a productivity number lands.
 */
export type Voice = {
  /**
   * Whether the subject is the reader.
   *
   * A flag and not an identity comparison against [YOURS], because two sentences in
   * `lib/velocity.ts` genuinely differ by more than their pronouns: "declare one in your
   * preferences" is an instruction the reader can act on, and on a colleague's page there
   * is nothing for them to do. Everything else swaps words; those two swap the advice.
   */
  own: boolean;
  /** `You` / `Ana Ruiz` — the head of a sentence. */
  subject: string;
  /** `are` / `is`, agreeing with [subject]. */
  are: string;
  /** `you` / `Ana Ruiz` — the subject as an object. */
  object: string;
  /** `your` / `their`. */
  possessive: string;
  /**
   * `You declared` / `Ana Ruiz declared`.
   *
   * Carried whole rather than composed from [subject], because the verb agrees in the
   * present and not in the past — "Ana Ruiz declared" and "You declared" differ in the
   * pronoun alone, while "is carrying" and "are carrying" differ in the verb. A caller
   * assembling this from the parts would get one of the two wrong.
   */
  declared: string;
  /** `you wrote it` / `they wrote it` — the past tense, agreed. */
  wrote: string;
};

/** The default, because the page a person opens on themselves is the common case. */
export const YOURS: Voice = {
  own: true,
  subject: "You",
  are: "are",
  object: "you",
  possessive: "your",
  declared: "You declared",
  wrote: "you wrote it",
};

export function about(name: string): Voice {
  return {
    own: false,
    subject: name,
    are: "is",
    object: name,
    possessive: "their",
    declared: `${name} declared`,
    wrote: "they wrote it",
  };
}
