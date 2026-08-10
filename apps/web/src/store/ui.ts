import { create } from "zustand";
import type { Zoom } from "@/lib/timeline-geometry";

/** The two ways the same tickets are drawn: a list of rows, or a Gantt of bars. */
export type View = "list" | "timeline";

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
  view: View;
  zoom: Zoom;
  /**
   * An arrow being drawn, from the bar its handle was pressed on. Set for the length of
   * one gesture and read by three places at once — the bar that started it, the arrow
   * layer drawing the rubber band, and the view resolving where it was dropped — which
   * is why it lives here rather than in the chart's own state: the alternative is the
   * same value threaded through two component trees that already read this store.
   */
  linking?: { fromId: string };
  overlay: Overlay;
  dialog: Dialog;
  query: string;
  showArchived: boolean;

  setScope: (scope: Scope) => void;
  select: (id?: string) => void;
  setView: (view: View) => void;
  setZoom: (zoom: Zoom) => void;
  startLinking: (fromId: string) => void;
  stopLinking: () => void;
  open: (overlay: Overlay) => void;
  close: () => void;
  openDialog: (dialog: Dialog) => void;
  setQuery: (query: string) => void;
  setShowArchived: (showArchived: boolean) => void;
};

export const useUi = create<UiState>((set) => ({
  scope: { kind: "all" },
  view: "list",
  zoom: "day",
  overlay: "none",
  dialog: { kind: "none" },
  query: "",
  showArchived: false,

  // A new scope is a new list, so no cursor from the old one can survive it.
  setScope: (scope) => set({ scope, selectedId: undefined }),
  select: (selectedId) => set({ selectedId }),
  /**
   * `selectedId` deliberately survives: the same ticket is selected in both views, and
   * dropping the cursor here would make a toggle between two drawings of one set of
   * rows feel like a navigation away from the row being looked at.
   */
  setView: (view) => set({ view }),
  setZoom: (zoom) => set({ zoom }),
  startLinking: (fromId) => set({ linking: { fromId } }),
  // Called on every ending a gesture has — dropped on a bar, dropped on nothing, or
  // taken away by the system — so no path can leave a rubber band drawn by nobody.
  stopLinking: () => set({ linking: undefined }),
  open: (overlay) => set({ overlay }),
  // Escape is one key and means one thing, whichever of the two is on screen.
  close: () => set({ overlay: "none", dialog: { kind: "none" } }),
  openDialog: (dialog) => set({ dialog }),
  setQuery: (query) => set({ query }),
  setShowArchived: (showArchived) => set({ showArchived }),
}));
