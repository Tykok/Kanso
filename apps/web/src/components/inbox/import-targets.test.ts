import { describe, expect, it } from "vitest";
import { TARGET_LABELS, pageCount } from "./import-targets";
import { DEFAULT_TARGET } from "./import-map";

describe("how a page count is printed", () => {
  it("prints a finished count as itself", () => {
    expect(pageCount(248, true)).toBe("248 pages");
  });

  it("prints a bounded count as at least that many", () => {
    // Notion answers no total for a data source, so the server walks the pages and stops
    // at its bound. "2000 pages" about a base holding nine thousand is the number this
    // exists to keep off a confirm button.
    expect(pageCount(2000, false)).toBe("2000+ pages");
  });

  it("has a word for every answer the mapping can take", () => {
    expect(Object.keys(TARGET_LABELS).sort()).toEqual(["documents", "ignore", "project"]);
    expect(TARGET_LABELS[DEFAULT_TARGET]).toBe("Ignore");
  });
});
