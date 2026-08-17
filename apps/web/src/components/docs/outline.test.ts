import { describe, expect, it } from "vitest";
import type { DocBlock, DocFolder, DocPage } from "@/lib/api";
import { docTree, editedLabel, tableOfContents } from "./outline";

const folder = (id: string, name: string, parentId?: string): DocFolder => ({
  id,
  teamId: "team",
  parentId,
  name,
  position: 0,
});

const page = (id: string, title: string, folderId?: string): DocPage => ({
  id,
  teamId: "team",
  folderId,
  title,
  createdAt: "2026-08-17T10:00:00Z",
  updatedAt: "2026-08-17T10:00:00Z",
});

const block = (id: string, kind: DocBlock["kind"], content: DocBlock["content"]): DocBlock => ({
  id,
  pageId: "page",
  position: 0,
  kind,
  content,
  ticketIds: [],
});

describe("docTree", () => {
  it("puts a folder's pages under it and its sub-folders after them", () => {
    const rows = docTree(
      [folder("product", "Product"), folder("cycles", "Cycle notes", "product")],
      [page("contract", "Sync contract", "product"), page("note", "Cycle 24", "cycles")],
    );

    expect(rows.map((row) => [row.kind, row.label, row.depth])).toEqual([
      ["folder", "Product", 0],
      ["page", "Sync contract", 1],
      ["folder", "Cycle notes", 1],
      ["page", "Cycle 24", 2],
    ]);
  });

  it("draws a page whose folder is not on screen at the root rather than hiding it", () => {
    const rows = docTree([], [page("loose", "Runbook", "gone")]);
    expect(rows.map((row) => [row.kind, row.label, row.depth])).toEqual([["page", "Runbook", 0]]);
  });

  // Screen 22 draws it this way round: under "Produit" come its two pages, and only
  // then the collapsed "Notes de cycle". Pages before sub-folders, at every level —
  // `sidebar-tree.tsx` orders a team's projects before its sub-teams for the same
  // reason, which is that a nested branch pushes everything after it a long way down.
  it("orders pages before sub-folders, each by name", () => {
    const rows = docTree(
      [folder("z", "Zeta"), folder("a", "Alpha")],
      [page("p", "Aardvark")],
    );
    expect(rows.map((row) => row.label)).toEqual(["Aardvark", "Alpha", "Zeta"]);
  });
});

describe("tableOfContents", () => {
  it("keeps the headings, in order, and nothing else", () => {
    const entries = tableOfContents([
      block("p1", "paragraph", { text: "Postgres wins" }),
      block("h1", "heading", { text: "The path of a write" }),
      block("c1", "checkbox", { text: "Echo suppression", checked: false }),
      block("h2", "heading", { text: "What the mapping loses" }),
    ]);

    expect(entries).toEqual([
      { id: "h1", text: "The path of a write" },
      { id: "h2", text: "What the mapping loses" },
    ]);
  });

  it("drops a heading with nothing written in it yet", () => {
    expect(tableOfContents([block("h1", "heading", { text: "  " })])).toEqual([]);
  });
});

describe("editedLabel", () => {
  const now = new Date("2026-08-17T12:00:00Z");

  it("names the editor and how long ago, as the footer draws it", () => {
    const edited = { ...page("p", "Mirror"), updatedAt: "2026-08-17T11:57:00Z", editedById: "u1" };
    expect(editedLabel(edited, { u1: "Tykok" }, now)).toBe("Edited by Tykok 3 min ago");
  });

  it("says just how long ago when the editor is nobody it knows", () => {
    const edited = { ...page("p", "Mirror"), updatedAt: "2026-08-17T11:00:00Z" };
    expect(editedLabel(edited, {}, now)).toBe("Edited 1 h ago");
  });

  // A page saved a second ago should not read "0 min ago": the floor is the smallest
  // unit the footer has, and "just now" is what a reader means by it.
  it("reads 'just now' under a minute", () => {
    const edited = { ...page("p", "Mirror"), updatedAt: "2026-08-17T11:59:40Z" };
    expect(editedLabel(edited, {}, now)).toBe("Edited just now");
  });

  it("counts in days past a day, the way screen 22 does", () => {
    const edited = { ...page("p", "Mirror"), updatedAt: "2026-08-14T12:00:00Z" };
    expect(editedLabel(edited, {}, now)).toBe("Edited 3 d ago");
  });
});
