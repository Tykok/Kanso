import { describe, expect, it } from "vitest";
import { checklist, emptyReason } from "./first-session";

describe("the four gestures the checklist tracks", () => {
  const facts = {
    teams: 0,
    tickets: 0,
    advanced: false,
    notionConfigured: false,
  };

  it("counts nothing done on an instance with no team", () => {
    const state = checklist(facts);
    expect(state.done).toBe(0);
    expect(state.total).toBe(4);
    expect(state.complete).toBe(false);
    expect(state.steps.map((step) => step.done)).toEqual([false, false, false, false]);
  });

  it("reproduces the drawing's 1 of 4: the team exists, nothing else has happened", () => {
    const state = checklist({ ...facts, teams: 1 });
    expect(state.done).toBe(1);
    expect(state.steps.map((step) => [step.label, step.done])).toEqual([
      ["Create the team", true],
      ["Create a ticket", false],
      ["Move it along", false],
      ["Connect Notion", false],
    ]);
  });

  it("points at the first thing still undone, which is what the drawing inks fully", () => {
    expect(checklist({ ...facts, teams: 1 }).next?.id).toBe("ticket");
    expect(checklist({ ...facts, teams: 1, tickets: 3 }).next?.id).toBe("advance");
    expect(checklist({ ...facts, teams: 1, tickets: 3, advanced: true }).next?.id).toBe("notion");
  });

  it("is complete, and has nothing to point at, once all four are done", () => {
    const state = checklist({ teams: 1, tickets: 3, advanced: true, notionConfigured: true });
    expect(state.complete).toBe(true);
    expect(state.done).toBe(4);
    // The drawing is explicit that the checklist disappears; `complete` is what the
    // sidebar reads to stop rendering it, so nothing has to remember to hide it.
    expect(state.next).toBeUndefined();
  });

  it("does not require the four to be done in order", () => {
    // Somebody who connected Notion in the wizard has that one done before their
    // first ticket exists, and a checklist that unticked it would be lying.
    const state = checklist({ ...facts, teams: 1, notionConfigured: true });
    expect(state.done).toBe(2);
    expect(state.next?.id).toBe("ticket");
  });
});

describe("why a list is empty", () => {
  it("blames the filter, and says how much is outside it", () => {
    expect(emptyReason({ total: 14, filter: "urgent", ticketsAnywhere: 14 })).toEqual({
      kind: "filtered",
      filter: "urgent",
      outside: 14,
    });
  });

  it("does not blame a filter that is only whitespace", () => {
    expect(emptyReason({ total: 0, filter: "   ", ticketsAnywhere: 0 }).kind).toBe("firstRun");
  });

  it("teaches the three gestures when the instance has no tickets at all", () => {
    expect(emptyReason({ total: 0, filter: "", ticketsAnywhere: 0 })).toEqual({ kind: "firstRun" });
  });

  it("says the scope is empty when work exists elsewhere", () => {
    // Not the three-gesture screen: somebody who has 40 tickets and opened a new
    // team does not need to be taught what `c` does.
    expect(emptyReason({ total: 0, filter: "", ticketsAnywhere: 40 })).toEqual({ kind: "emptyScope" });
  });

  it("blames the filter even in a scope that is otherwise empty of nothing", () => {
    // A filter typed into a scope holding one ticket: the filter is still the reason,
    // and "nothing here" would send the reader looking for a ticket they can see the
    // count of in the top bar.
    expect(emptyReason({ total: 1, filter: "zzz", ticketsAnywhere: 1 })).toEqual({
      kind: "filtered",
      filter: "zzz",
      outside: 1,
    });
  });
});
