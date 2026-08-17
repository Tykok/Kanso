import type { DocBlock, DocFolder, DocPage } from "@/lib/api";

/**
 * The three derivations screen 07 and screen 22 need and neither draws: the tree, the
 * table of contents, and the footer's line. Pure functions in a file of their own so
 * they can be read — and tested — without a document on screen.
 */

export type DocTreeRow =
  | { kind: "folder"; id: string; label: string; depth: number; folder: DocFolder }
  | { kind: "page"; id: string; label: string; depth: number; page: DocPage };

/** Indent per level, capped at two — the same cap and the same reason as the sidebar's. */
const MAX_DEPTH = 2;

/**
 * The tree, flattened in reading order: a folder, then its pages, then its sub-folders.
 *
 * Pages before sub-folders because screen 22 draws it that way, and because a nested
 * branch pushes everything after it a long way down the column — `sidebar-tree.tsx`
 * orders a team's projects before its sub-teams for exactly that.
 *
 * A page whose folder is not in [folders] is drawn at the root rather than dropped. The
 * same rule the sidebar applies to a team whose parent is filtered out: a row that
 * exists and cannot be seen is worse than a row in the wrong place.
 */
export function docTree(folders: DocFolder[], pages: DocPage[]): DocTreeRow[] {
  const known = new Set(folders.map((folder) => folder.id));
  const byName = <T extends { name?: string; title?: string }>(a: T, b: T) =>
    (a.name ?? a.title ?? "").localeCompare(b.name ?? b.title ?? "");

  const parentOf = (id: string | undefined) => (id && known.has(id) ? id : undefined);

  const foldersUnder = (parent: string | undefined) =>
    folders.filter((folder) => parentOf(folder.parentId) === parent).sort(byName);
  const pagesIn = (parent: string | undefined) =>
    pages.filter((page) => parentOf(page.folderId) === parent).sort(byName);

  const rows: DocTreeRow[] = [];
  const walk = (parent: string | undefined, depth: number) => {
    const level = Math.min(depth, MAX_DEPTH);
    for (const page of pagesIn(parent)) {
      rows.push({ kind: "page", id: page.id, label: page.title, depth: level, page });
    }
    for (const folder of foldersUnder(parent)) {
      rows.push({ kind: "folder", id: folder.id, label: folder.name, depth: level, folder });
      walk(folder.id, depth + 1);
    }
  };
  walk(undefined, 0);
  return rows;
}

export type TocEntry = { id: string; text: string };

/**
 * The right-hand "Sommaire": every heading block, in the page's own order.
 *
 * Derived rather than stored, so a heading renamed by one keystroke cannot leave a stale
 * entry behind. A heading with nothing written in it is skipped — it would be a blank
 * line in the rail with nothing to jump to.
 */
export function tableOfContents(blocks: DocBlock[]): TocEntry[] {
  return blocks.flatMap((block) => {
    if (block.kind !== "heading") return [];
    const text = String(block.content.text ?? "").trim();
    return text ? [{ id: block.id, text }] : [];
  });
}

/**
 * "Edited by Tykok 3 min ago", and what to say when there is no name to give.
 *
 * [now] is a parameter rather than read here: this module is imported by the test suite
 * under `environment: "node"`, and a relative time computed against the wall clock is a
 * function that cannot be asserted about.
 */
export function editedLabel(
  page: DocPage,
  names: Record<string, string>,
  now: Date = new Date(),
): string {
  const who = page.editedById ? names[page.editedById] : undefined;
  const prefix = who ? `Edited by ${who}` : "Edited";
  return `${prefix} ${ago(now.getTime() - Date.parse(page.updatedAt))}`;
}

/**
 * Minutes, then hours, then days — the three units screen 22 prints ("il y a 1 h",
 * "hier", "3 j") and no more. Under a minute reads "just now": "0 min ago" is what a
 * clock says, not what a reader means.
 */
function ago(elapsedMs: number): string {
  const minutes = Math.floor(Math.max(elapsedMs, 0) / 60_000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return `${Math.floor(hours / 24)} d ago`;
}
