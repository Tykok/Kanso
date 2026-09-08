import { describe, expect, it } from "vitest";
import {
  categoryOf,
  PRIORITY_COLORS,
  PRIORITY_GLYPHS,
  STATUS_CATEGORIES,
  STATUS_CATEGORY,
  STATUS_COLORS,
  STATUS_LABELS,
} from "./status";
import { TICKET_PRIORITIES, DEFAULT_STATUSES } from "./api";

describe("status and priority tables", () => {
  it("covers every status", () => {
    for (const status of DEFAULT_STATUSES) {
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

describe("status categories", () => {
  it("files every status under exactly one category, the same way the API does", () => {
    expect(STATUS_CATEGORY).toEqual({
      backlog: "backlog",
      todo: "unstarted",
      in_progress: "started",
      in_review: "started",
      done: "completed",
      canceled: "canceled",
    });
  });

  it("calls review started work, which is the whole reason the category exists", () => {
    expect(categoryOf("in_review")).toBe(categoryOf("in_progress"));
  });

  it("leaves no category nothing can be in", () => {
    expect(new Set(DEFAULT_STATUSES.map(categoryOf))).toEqual(new Set(STATUS_CATEGORIES));
  });
});
