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
  teamId?: string;
  selectedId?: string;
  overlay: Overlay;
  query: string;

  setTeam: (teamId?: string) => void;
  select: (id?: string) => void;
  open: (overlay: Overlay) => void;
  close: () => void;
  setQuery: (query: string) => void;
};

export const useUi = create<UiState>((set) => ({
  overlay: "none",
  query: "",

  setTeam: (teamId) => set({ teamId, selectedId: undefined }),
  select: (selectedId) => set({ selectedId }),
  open: (overlay) => set({ overlay }),
  close: () => set({ overlay: "none" }),
  setQuery: (query) => set({ query }),
}));
