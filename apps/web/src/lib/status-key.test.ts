import { describe, expect, it } from "vitest";
import { statusKeyOf } from "./status-key";

/**
 * The table both derivations are pinned against — `KAN-90`.
 *
 * `dev.kanso.domain.StatusKeyTest` asserts the same pairs in Kotlin. The copy that drifts
 * therefore fails a test on the side that drifted, rather than producing a screen that
 * refuses a word the server would accept — which is the failure a pre-check exists to
 * avoid and the only one it can cause.
 */
export const statusKeyTable: [label: string, key: string | null][] = [
  ["Backlog", "backlog"],
  ["In Progress", "in_progress"],
  ["in progress", "in_progress"],
  ["IN  PROGRESS", "in_progress"],
  // The case the mirror cares about: a French team's words are keys an ASCII column holds.
  ["Livré", "livre"],
  ["Qualifié", "qualifie"],
  ["En chantier", "en_chantier"],
  // Punctuation and symbols fold to one separator, and the ends are trimmed rather than
  // left as underscores — `_devis_` would collide with any other such label.
  ["Devis / estimation", "devis_estimation"],
  ["  Devis  ", "devis"],
  ["…Devis…", "devis"],
  ["A/B", "a_b"],
  // Digits survive, so `V2` is a status somebody can name.
  ["Phase 2", "phase_2"],
  // Nothing nameable: no key exists, and the field is invalid rather than stored under a
  // row of bare underscores that a second such label would collide with.
  ["…", null],
  ["///", null],
  ["", null],
];

describe("statusKeyOf", () => {
  for (const [label, key] of statusKeyTable) {
    it(`derives ${key === null ? "no key" : `"${key}"`} from "${label}"`, () => {
      expect(statusKeyOf(label)).toBe(key);
    });
  }

  // The property the primary key relies on: two spellings of one word are one row.
  it("folds two spellings of one word onto one key", () => {
    expect(statusKeyOf("En cours")).toBe(statusKeyOf("en-cours"));
    expect(statusKeyOf("Livré")).toBe(statusKeyOf("livre"));
  });
});
