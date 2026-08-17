import { create } from "zustand";

/**
 * The document editor's own local state.
 *
 * `store/ui.ts` is frozen for the fan-out and already carries the one member this
 * screen needed from it — the `blockInsert` overlay. What is here is everything else a
 * page being *typed in* has to remember and no other surface has any use for: which
 * block the caret is in, which picker is open, and which folders are collapsed.
 *
 * Separate from the server cache for the same reason `ui.ts` is: a background refetch
 * of a document must not move the caret or close a menu under someone's fingers.
 */
export type DocMention = "ticket" | "person";

type DocsState = {
  /**
   * The block the caret is in — the anchor `/`, `#` and `c` insert after. Undefined
   * means the end of the page, which is where a gesture made with nothing focused goes.
   */
  anchorBlockId?: string;

  /** Which picker is open: `#` chooses a ticket, `@` a person. */
  mention?: DocMention;

  /**
   * Folders the reader has folded shut, by id. Collapsed rather than expanded state so
   * a folder created while the tree is on screen appears open, which is what somebody
   * who has just made one expects.
   */
  collapsed: Record<string, boolean>;

  setAnchor: (blockId?: string) => void;
  openMention: (mention: DocMention) => void;
  closeMention: () => void;
  toggleFolder: (folderId: string) => void;
};

export const useDocsUi = create<DocsState>((set) => ({
  collapsed: {},

  setAnchor: (anchorBlockId) => set({ anchorBlockId }),
  openMention: (mention) => set({ mention }),
  closeMention: () => set({ mention: undefined }),
  toggleFolder: (folderId) =>
    set((state) => ({
      collapsed: { ...state.collapsed, [folderId]: !state.collapsed[folderId] },
    })),
}));
