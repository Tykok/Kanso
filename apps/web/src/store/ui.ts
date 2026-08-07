import { create } from "zustand";

type Overlay = "none" | "composer" | "palette" | "detail" | "help" | "settings";

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
