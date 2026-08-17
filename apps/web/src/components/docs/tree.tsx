"use client";

import { useState } from "react";
import Link from "next/link";
import type { DocFolder, DocPage } from "@/lib/api";
import { cn } from "@/lib/utils";
import { useDocsUi } from "@/store/docs";
import { docTree } from "./outline";

/** The same three indents the sidebar has, and capped at the same depth for the reason. */
const DEPTH_PAD = ["pl-2", "pl-6", "pl-10"];

/**
 * The tree column: screen 22's second panel, and screen 07's "Pages" list.
 *
 * A folder row is a disclosure, not a destination — there is no screen for a folder, and
 * making one would be a second list of the same pages. Clicking it folds it.
 */
export function DocTree({
  folders,
  pages,
  currentPageId,
  onCreateFolder,
}: {
  folders: DocFolder[];
  pages: DocPage[];
  currentPageId?: string;
  /** Absent when the reader may write in no team: then `+` is not drawn at all. */
  onCreateFolder?: (name: string) => void;
}) {
  const collapsed = useDocsUi((state) => state.collapsed);
  const toggleFolder = useDocsUi((state) => state.toggleFolder);
  const [draft, setDraft] = useState<string | undefined>();

  const rows = docTree(folders, pages);

  /**
   * A row is hidden when any folder above it is folded. Read off the row's own ancestry
   * rather than by pruning the walk, so the tree is built once and folding costs nothing
   * but a filter — and a folder whose parent is folded cannot be left drawn.
   */
  const parentsOf = (row: (typeof rows)[number]): string[] => {
    const chain: string[] = [];
    let parent = row.kind === "folder" ? row.folder.parentId : row.page.folderId;
    while (parent) {
      chain.push(parent);
      parent = folders.find((folder) => folder.id === parent)?.parentId;
    }
    return chain;
  };

  const visible = rows.filter((row) => !parentsOf(row).some((id) => collapsed[id]));

  return (
    <div className="flex min-h-0 flex-col gap-3 bg-card px-2 py-3.5">
      <div className="flex items-center gap-2 px-2">
        <span className="flex-1 text-13 font-medium">Tree</span>
        {onCreateFolder && (
          <button
            className="flex size-5 items-center justify-center rounded-sm text-15 text-faint hover:bg-accent hover:text-foreground"
            aria-label="New folder"
            onClick={() => setDraft("")}
          >
            +
          </button>
        )}
      </div>

      {/* Named in place rather than in a dialog: a folder is one word, and the row it
          will become is already where the caret is. `↵` keeps it, `esc` drops it. */}
      {draft !== undefined && onCreateFolder && (
        <input
          autoFocus
          data-testid="doc-folder-draft"
          aria-label="Folder name"
          placeholder="Folder name"
          value={draft}
          className="mx-2 w-auto text-12"
          onChange={(event) => setDraft(event.target.value)}
          onBlur={() => setDraft(undefined)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && draft.trim()) {
              event.preventDefault();
              onCreateFolder(draft.trim());
              setDraft(undefined);
            }
            if (event.key === "Escape") {
              event.preventDefault();
              setDraft(undefined);
            }
            event.stopPropagation();
          }}
        />
      )}

      <div className="flex min-h-0 flex-1 flex-col gap-row overflow-y-auto text-12">
        {visible.length === 0 && (
          <span className="px-2 py-1 text-faint">No document yet.</span>
        )}

        {visible.map((row) =>
          row.kind === "folder" ? (
            <button
              key={row.id}
              data-testid="doc-tree-folder"
              aria-expanded={!collapsed[row.id]}
              className={cn(
                "flex h-7 items-center gap-2 rounded-md text-left text-muted-foreground hover:bg-accent",
                DEPTH_PAD[row.depth],
              )}
              onClick={() => toggleFolder(row.id)}
            >
              <span aria-hidden className="text-[9px] text-faint">
                {collapsed[row.id] ? "▸" : "▾"}
              </span>
              <span className="truncate">{row.label}</span>
            </button>
          ) : (
            <Link
              key={row.id}
              data-testid="doc-tree-page"
              href={`/docs/${row.id}`}
              aria-current={row.id === currentPageId}
              className={cn(
                "flex h-7 items-center rounded-md",
                DEPTH_PAD[row.depth],
                row.id === currentPageId
                  ? "bg-accent-soft font-medium text-foreground shadow-[inset_2px_0_0_var(--primary)]"
                  : "text-muted-foreground hover:bg-accent",
              )}
            >
              <span className="truncate">{row.label}</span>
            </Link>
          ),
        )}
      </div>
    </div>
  );
}
