import { describe, expect, it } from "vitest";
import { readableError } from "./sync-error";

// The homelab's own answer, 2026-09-25, trimmed to the lines that matter.
const REFUSED = [
  "Notion API 400 (validation_error): body failed validation. Fix one:",
  "body.properties.Start.title should be defined, instead was `undefined`.",
  "body.properties.Start.rich_text should be defined, instead was `undefined`.",
  "body.properties.Name.id should be defined, instead was `undefined`.",
].join("\n");

describe("a push error, read by a person", () => {
  it("names the refused properties in the order Notion listed them", () => {
    expect(readableError(REFUSED)).toEqual({
      summary: "Notion refused the page: Start, Name are invalid",
      detail: REFUSED,
    });
  });

  it("keeps a property name that has a space in it whole", () => {
    const raw =
      "Notion API 400 (validation_error): body failed validation. Fix one:\n" +
      "body.properties.Kanso ID.rich_text should be defined, instead was `undefined`.";
    expect(readableError(raw)?.summary).toBe("Notion refused the page: Kanso ID is invalid");
  });

  it("stops at three names", () => {
    const raw = ["Notion API 400 (validation_error): x", ...["A", "B", "C", "D"].map(
      (n) => `body.properties.${n}.date should be defined, instead was \`undefined\`.`,
    )].join("\n");
    expect(readableError(raw)?.summary).toBe("Notion refused the page: A, B, C are invalid");
  });

  it("leaves a one-line error as it is, with nothing to unfold", () => {
    expect(readableError("The target page is locked by another workspace")).toEqual({
      summary: "The target page is locked by another workspace",
    });
  });

  it("keeps the first line of anything else, and the rest behind it", () => {
    expect(readableError("Connection reset\n\tat java.net.Socket")).toEqual({
      summary: "Connection reset",
      detail: "Connection reset\n\tat java.net.Socket",
    });
  });

  it("cuts a very long first line", () => {
    const summary = readableError("x".repeat(400))?.summary ?? "";
    expect(summary.length).toBe(160);
    expect(summary.endsWith("…")).toBe(true);
  });

  it("has nothing to say about nothing", () => {
    expect(readableError(undefined)).toBeNull();
    expect(readableError("   ")).toBeNull();
  });
});
