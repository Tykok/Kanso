import { describe, expect, it } from "vitest";
import {
  DEFAULT_TARGET,
  importCounts,
  importPlan,
  type ImportMapping,
  type NotionSource,
} from "./import-map";

/** The drawing's own four databases, and its own page counts. */
const SOURCES: NotionSource[] = [
  { id: "eng", name: "Engineering tasks", pages: 248 },
  { id: "design", name: "Design docs", pages: 36 },
  { id: "meetings", name: "Meeting notes", pages: 112 },
  { id: "archive", name: "Archive 2023", pages: 891 },
];

const DRAWN: ImportMapping = {
  eng: "tickets",
  design: "documents",
  meetings: "documents",
  archive: "ignore",
};

describe("what the mapping adds up to", () => {
  it("reproduces the drawing: 396 pages kept out of 1287", () => {
    expect(importCounts(SOURCES, DRAWN)).toEqual({
      kept: 396,
      total: 1287,
      teams: 0,
      projects: 0,
      tickets: 1,
      folders: 2,
      ignored: 1,
    });
  });

  it("recounts the moment one row changes, which is the point of the number", () => {
    const withArchive = importCounts(SOURCES, { ...DRAWN, archive: "documents" });
    expect(withArchive.kept).toBe(1287);
    expect(withArchive.folders).toBe(3);
    expect(withArchive.ignored).toBe(0);
  });

  it("ignores anything nobody has mapped", () => {
    // Nothing is imported by default. An import whose default is to write is an
    // import that gets confirmed by accident, and this one confirms 1287 pages.
    expect(DEFAULT_TARGET).toBe("ignore");
    expect(importCounts(SOURCES, {})).toEqual({
      kept: 0,
      total: 1287,
      teams: 0,
      projects: 0,
      tickets: 0,
      folders: 0,
      ignored: 4,
    });
  });

  it("counts nothing out of nothing rather than dividing by it", () => {
    expect(importCounts([], {})).toEqual({
      kept: 0,
      total: 0,
      teams: 0,
      projects: 0,
      tickets: 0,
      folders: 0,
      ignored: 0,
    });
  });

  it("drops a mapping for a database that is no longer there", () => {
    // The list can be re-searched while the mapping is still open; a stale id in it
    // must not be counted as a database to import.
    const counts = importCounts(SOURCES, { ...DRAWN, gone: "tickets" });
    expect(counts.tickets).toBe(1);
  });

  it("counts the four kinds separately, because the screen names them separately", () => {
    const counts = importCounts(SOURCES, {
      eng: "tickets",
      design: "documents",
      meetings: "projects",
      archive: "teams",
    });
    expect(counts).toEqual({
      kept: 1287,
      total: 1287,
      teams: 1,
      projects: 1,
      tickets: 1,
      folders: 1,
      ignored: 0,
    });
  });
});

describe("the plan that is sent once the preview is confirmed", () => {
  it("names only what is being written, and what each thing becomes", () => {
    expect(importPlan(SOURCES, DRAWN)).toEqual([
      { sourceId: "eng", name: "Engineering tasks", target: "tickets", pages: 248 },
      { sourceId: "design", name: "Design docs", target: "documents", pages: 36 },
      { sourceId: "meetings", name: "Meeting notes", target: "documents", pages: 112 },
    ]);
  });

  it("is empty when nothing is mapped, so the button has something to be disabled by", () => {
    expect(importPlan(SOURCES, {})).toEqual([]);
  });
});
