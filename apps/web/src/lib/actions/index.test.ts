import { describe, expect, it } from "vitest";
import { boardActions } from "./board";
import { coreActions } from "./core";
import { docsActions } from "./docs";
import { favouriteActions } from "./favourites";
import { ACTIONS } from "./index";
import { DEFAULT_MERGE, resolveShortcut } from "../shortcuts";
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
      ...favouriteActions,
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
    const at = (chord: string, mode: "list" | "timeline") =>
      resolveShortcut(chord, mode, DEFAULT_MERGE.index);
    expect(at("n", "list")?.id).toBe("ticket.moveDown");
    // Claimed by the chart alone, so the list must not answer it.
    expect(at("[", "list")).toBeUndefined();
    expect(at("[", "timeline")?.id).toBe("timeline.zoomOut");
  });

  /**
   * Six files contribute keys and none of them sees the others' diffs, so the one thing a
   * composition test can prove that a per-file one cannot is that the *whole* set is
   * dispatchable: every default is a chord in canonical spelling, and no two claim a
   * bucket. `indexActions` throws on both at module load — importing this file at all is
   * most of the proof — and `mergeBindings` reports nothing for the defaults, which is the
   * same claim said by a function that never throws.
   */
  it("lays every default down without a refusal", () => {
    expect(DEFAULT_MERGE.rejected).toEqual([]);
  });
});
