/**
 * Screen 08's missing half, and screen 15's first state — the two questions an empty
 * screen has to answer, decided here rather than in the components that draw them.
 *
 * Both are pure and both are the part that can be wrong invisibly: an empty list that
 * blames the wrong thing still renders, and a checklist that unticks a step somebody
 * finished still renders too.
 */

export type ChecklistFacts = {
  /** How many teams exist. The wizard makes one, so this is normally already true. */
  teams: number;
  /**
   * How many tickets have ever been filed, summed from the teams' identifier allocators
   * rather than counted from a list.
   *
   * A high-water mark, and that is the point: the step is "Create a ticket", and deleting
   * the ticket afterwards does not un-create it. Counting rows instead — which is what
   * this used to be handed — made the step untick on a delete and, because the list it
   * counted was scoped and filtered, on a keystroke in the filter box.
   */
  tickets: number;
  /**
   * Whether any ticket **in the instance** has been moved past where the composer leaves
   * it.
   *
   * Answered by the server now, as `Me.workMovedAlong`, which is what let the checklist
   * stop fetching a ticket list to work it out (KAN-65). Instance-wide rather than scoped
   * to whatever the sidebar is pointing at, which is the honest reading of the question:
   * the step asks whether the reader has learnt the gesture, and a step that unticked
   * itself when they clicked a different team was answering a question nobody asked.
   *
   * Still an approximation in one direction, and the honest one available: nothing
   * records "somebody pressed 3". A ticket created straight into `in_progress` satisfies
   * it too, which is fine — that person did move work along, which is what the step is
   * about.
   */
  advanced: boolean;
  notionConfigured: boolean;
};

export type ChecklistStep = { id: string; label: string; done: boolean };

export type ChecklistState = {
  steps: ChecklistStep[];
  done: number;
  total: number;
  /** True once every step is done. What the sidebar reads to stop drawing it. */
  complete: boolean;
  /** The first step still undone — the one the drawing inks fully. */
  next?: ChecklistStep;
};

/**
 * The four steps, in the order the drawing lists them, each answered from state the app
 * already has rather than from a stored "onboarding progress" row.
 *
 * Derived, not recorded, on purpose: a stored flag is a second truth about whether a
 * ticket exists, and it is the one that goes wrong. It also means the checklist is
 * right for somebody who did all four before it was ever shown to them — the steps do
 * not have to be done in order, and none of them unticks.
 */
export function checklist(facts: ChecklistFacts): ChecklistState {
  const steps: ChecklistStep[] = [
    { id: "team", label: "Create the team", done: facts.teams > 0 },
    { id: "ticket", label: "Create a ticket", done: facts.tickets > 0 },
    { id: "advance", label: "Move it along", done: facts.advanced },
    { id: "notion", label: "Connect Notion", done: facts.notionConfigured },
  ];

  const done = steps.filter((step) => step.done).length;
  return {
    steps,
    done,
    total: steps.length,
    complete: done === steps.length,
    next: steps.find((step) => !step.done),
  };
}

export type EmptyReason =
  /** A filter is on and nothing matched it. [outside] is what exists without it. */
  | { kind: "filtered"; filter: string; outside: number }
  /** No ticket has ever been created here. The three gestures go on this screen. */
  | { kind: "firstRun" }
  /** This scope is empty, but the instance is not. Nothing to teach. */
  | { kind: "emptyScope" };

/**
 * Which of the three empty screens to draw.
 *
 * The distinction the drawing insists on is the first one: "it is the filter, not the
 * database". A list that says "nothing here" while fourteen tickets sit one keystroke
 * away has told the reader something false, and the fix is not a better sentence — it
 * is knowing which question was asked.
 */
export function emptyReason(input: {
  /** Tickets in this scope before the filter. */
  total: number;
  filter: string;
  /** Tickets anywhere in the instance. What tells a new install from a new team. */
  ticketsAnywhere: number;
}): EmptyReason {
  const filter = input.filter.trim();
  if (filter && input.total > 0) return { kind: "filtered", filter, outside: input.total };
  if (input.ticketsAnywhere === 0) return { kind: "firstRun" };
  return { kind: "emptyScope" };
}
