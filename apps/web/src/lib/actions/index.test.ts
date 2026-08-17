import { describe, expect, it } from "vitest";
import { boardActions } from "./board";
import { coreActions } from "./core";
import { docsActions } from "./docs";
import { ACTIONS, resolveShortcut } from "./index";
import { inboxActions } from "./inbox";
import { organiseActions } from "./organise";
import { publikActions } from "./publik";
import { trashActions } from "./trash";

/**
 * The registry is now composed from seven files, six of which are written by branches
 * that never see each other's diffs. These assertions are about the *composition* — that
 * nothing is dropped and nothing is shadowed — rather than about any action's behaviour,
 * which `core.test.ts` covers.
 */
describe("the composed action registry", () => {
  it("holds every action every slice contributes", () => {
    const contributed = [
      ...coreActions,
      ...boardActions,
      ...docsActions,
      ...organiseActions,
      ...inboxActions,
      ...trashActions,
      ...publikActions,
    ];
    expect(ACTIONS).toHaveLength(contributed.length);
    for (const action of contributed) {
      expect(ACTIONS.some((candidate) => candidate.id === action.id)).toBe(true);
    }
  });

  /**
   * `indexActions` already throws on both of these at module load, so importing this file
   * at all is most of the proof. The assertions are here so that a future refactor which
   * loses that guard fails with a sentence naming what it lost, rather than with the
   * registry quietly answering the wrong action.
   */
  it("gives every action a distinct id", () => {
    const ids = ACTIONS.map((action) => action.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("still resolves a core key, in the mode that owns it", () => {
    expect(resolveShortcut("j", "list")?.id).toBe("ticket.moveDown");
    // Claimed by the chart alone, so the list must not answer it.
    expect(resolveShortcut("[", "list")).toBeUndefined();
    expect(resolveShortcut("[", "timeline")?.id).toBe("timeline.zoomOut");
  });
});
