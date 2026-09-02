import { describe, expect, it } from "vitest";
import { ACTIONS } from "@/lib/actions";
import { SHORTCUT_SECTIONS } from "./shortcut-sections";

/**
 * The claim the help overlay cannot make for itself.
 *
 * `?` is the one screen whose purpose is to tell somebody which keys exist, so a key the
 * registry answers and this sheet omits is worse than a missing feature: the reader has
 * been told, by the authority on the subject, that it is not there. The board went that
 * way for a whole slice — five actions with `mode: "board"`, three headings, and `h` `l`
 * reachable only by having read the source.
 *
 * So the test is on the registry and not on a hardcoded list of modes: a sixth drawing
 * added later fails here, by name, in the file that would otherwise silently drop it.
 */
describe("the help sheet's headings", () => {
  it("cover every mode the registry declares", () => {
    const declared = new Set(ACTIONS.map((action) => action.mode));
    const headed = new Set(SHORTCUT_SECTIONS.map((section) => section.mode));
    const missing = [...declared].filter((mode) => !headed.has(mode));
    expect(missing).toEqual([]);
  });

  it("names the board, whose keys were the ones lost", () => {
    expect(SHORTCUT_SECTIONS.some((section) => section.mode === "board")).toBe(true);
  });

  /**
   * Two headings for one mode would print the same rows twice, and a heading for a mode
   * nothing declares prints nothing at all — the overlay drops an empty section, so the
   * second failure is invisible on screen and only a test can hold the line.
   */
  it("names each mode once, and names no mode the registry does not use", () => {
    const modes = SHORTCUT_SECTIONS.map((section) => section.mode);
    expect(new Set(modes).size).toBe(modes.length);

    const declared = new Set(ACTIONS.map((action) => action.mode));
    expect(modes.filter((mode) => !declared.has(mode))).toEqual([]);
  });

  it("puts the keys that work everywhere first, so no view repeats them", () => {
    expect(SHORTCUT_SECTIONS[0]?.mode).toBeUndefined();
  });
});
