import { create } from "zustand";

/** What the ticket list is showing — and, verbatim, part of the tickets query key. */
export type Scope =
  | { kind: "all" }
  | { kind: "team"; id: string }
  | { kind: "project"; id: string };

export type Overlay = "none" | "composer" | "palette" | "detail" | "help" | "settings";

/**
 * A dialog names the entity it is about, so opening one needs no second call to
 * seed it. `disposition` carries a severity rather than splitting into two kinds:
 * archiving and deleting ask the same questions and differ only in the answer.
 */
export type Dialog =
  | { kind: "none" }
  | { kind: "team"; id?: string; parentTeamId?: string }
  | { kind: "project"; id?: string; teamId?: string }
  | {
      kind: "disposition";
      target: { kind: "team" | "project"; id: string };
      severity: "archive" | "delete";
    };

/**
 * Purely local interface state: what is focused, what is open.
 *
 * Deliberately separate from the server cache. Selection has to survive a
 * background refetch without flicker, and a refetch must not move the cursor
 * under someone's fingers.
 */
type UiState = {
  scope: Scope;
  selectedId?: string;
  overlay: Overlay;
  dialog: Dialog;
  query: string;
  showArchived: boolean;

  setScope: (scope: Scope) => void;
  select: (id?: string) => void;
  open: (overlay: Overlay) => void;
  close: () => void;
  openDialog: (dialog: Dialog) => void;
  setQuery: (query: string) => void;
  setShowArchived: (showArchived: boolean) => void;
};

export const useUi = create<UiState>((set) => ({
  scope: { kind: "all" },
  overlay: "none",
  dialog: { kind: "none" },
  query: "",
  showArchived: false,

  // A new scope is a new list, so no cursor from the old one can survive it.
  setScope: (scope) => set({ scope, selectedId: undefined }),
  select: (selectedId) => set({ selectedId }),
  open: (overlay) => set({ overlay }),
  // Escape is one key and means one thing, whichever of the two is on screen.
  close: () => set({ overlay: "none", dialog: { kind: "none" } }),
  openDialog: (dialog) => set({ dialog }),
  setQuery: (query) => set({ query }),
  setShowArchived: (showArchived) => set({ showArchived }),
}));
