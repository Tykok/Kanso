import { describe, expect, it } from "vitest";
import { initialsOf, NOBODY } from "./initials";

describe("initialsOf", () => {
  it("takes one initial from each end of a name", () => {
    expect(initialsOf("Léa Martin")).toBe("LM");
    // Three words: the first and the last, not the first two — a middle name is not
    // what distinguishes two people in a team of six.
    expect(initialsOf("Jean Pierre Rey")).toBe("JR");
  });

  it("takes two letters from a single word", () => {
    // A one-letter circle reads as a bullet rather than as a person.
    expect(initialsOf("Tykok")).toBe("TY");
  });

  it("says nobody when there is nobody", () => {
    expect(initialsOf(undefined)).toBe(NOBODY);
    expect(initialsOf(null)).toBe(NOBODY);
    expect(initialsOf("")).toBe(NOBODY);
    expect(initialsOf("   ")).toBe(NOBODY);
  });

  it("does not cut an astral character in half", () => {
    // `"🙂a".slice(0, 2)` is a lone surrogate and renders as a replacement glyph.
    expect(initialsOf("🙂a")).toBe("🙂A");
  });

  it("is insensitive to the surrounding whitespace a display name may carry", () => {
    expect(initialsOf("  Léa   Martin  ")).toBe("LM");
  });
});
