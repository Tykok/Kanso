import { describe, expect, it } from "vitest";
import { PRIORITY_COLORS, PRIORITY_GLYPHS, STATUS_COLORS, STATUS_LABELS } from "./status";
import { TICKET_PRIORITIES, TICKET_STATUSES } from "./api";

describe("status and priority tables", () => {
  it("covers every status", () => {
    for (const status of TICKET_STATUSES) {
      expect(STATUS_LABELS[status]).toBeTruthy();
      expect(STATUS_COLORS[status]).toMatch(/^var\(--status-/);
    }
  });

  it("covers every priority with a glyph and a colour of its own", () => {
    for (const priority of TICKET_PRIORITIES) {
      expect(PRIORITY_GLYPHS[priority]).toBeTruthy();
      expect(PRIORITY_COLORS[priority]).toMatch(/^var\(--(urgent|priority-)/);
    }
    expect(new Set(Object.values(PRIORITY_COLORS)).size).toBe(TICKET_PRIORITIES.length);
  });
});
