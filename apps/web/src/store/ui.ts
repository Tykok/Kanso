import { create } from "zustand";
import type { ViewFilters } from "@/lib/api";
import type { Zoom } from "@/lib/timeline-geometry";

/**
 * The three ways the same tickets are drawn: a list of rows, a board of columns, or a
 * Gantt of bars. One scoped query feeds all three — the board is a drawing, not a
 * destination, which is why it has no route of its own.
 */
export type View = "list" | "board" | "timeline";

/** What the ticket list is showing — and, verbatim, part of the tickets query key. */
export type Scope =
  | { kind: "all" }
  | { kind: "team"; id: string }
  | { kind: "project"; id: string };

/**
 * Every overlay the drawn screens ask for, including the four no branch has built yet.
 *
 * Widened once here rather than by each branch as it needs one: this file is read by
 * `page.tsx`, the sidebar, the palette and every action, so six branches each adding a
 * member would be six edits to one union. `bulk` is slice C's selection strip, `triage`
 * its one-at-a-time queue, `conflict` slice D's two-versions chooser, and `blockInsert`
 * slice B's `/` menu inside a document.
 */
export type Overlay =
  | "none"
  | "composer"
  | "palette"
  | "detail"
  | "help"
  | "settings"
  | "bulk"
  | "triage"
  | "conflict"
  | "blockInsert";

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
    }
  // The three the drawn screens need and no branch has built. Same reason as `Overlay`:
  // one widening here beats six edits to one union.
  | { kind: "saveView"; id?: string }
  | { kind: "importMap" }
  /**
   * A request to compose a filter — no longer a dialog, despite the name this union gives
   * it.
   *
   * It used to be one, and the reason was the dispatcher: a page stands its keyboard down
   * entirely while a dialog is open, which is what let `↑↓↵` inside the composer mean what
   * they said instead of also walking the list. §7 replaced the composer with a text box
   * that wants the opposite — its own `↑↓↵`, and every other key still live on the rows
   * behind it — so `filter-input.tsx` **drains** this the moment it arrives: it closes the
   * dialog and focuses its box in the same commit.
   *
   * It stays in the union because it is still the door. `Mod+f` and the Filter button both
   * open it, and neither knows where the box is; the box knows. What it must never become
   * again is something drawn, and `lib/actions/organise.ts` keeps the guard that matters —
   * the chord is refused on the timeline, where no box is mounted to drain it and an
   * undrained dialog would take the whole keyboard down with it.
   *
   * It still carries nothing, for the original reason: the facets come from the server and
   * the answer goes into [UiState.filters], so a copy of either here would be a third place
   * one question is written down.
   */
  | { kind: "filter" };

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
  /**
   * The question the main list is asking — the unsaved saved view.
   *
   * Here rather than in `page.tsx` because three things read it and none of them is the
   * list: the tickets query sends it, the strip draws it, and `SaveViewDialog` is what
   * turns it into a stored view. `query` above is a different thing and stays one — it
   * narrows what is already on screen by title, client-side, and never leaves the browser.
   *
   * Not scoped per team on purpose. A filter is a question somebody is holding, and
   * changing which team it is asked of is a narrower change than being handed a
   * different question; the chips stay on screen across a scope change, which is the
   * only way to tell that the answer got shorter *because* of them.
   */
  filters: ViewFilters;
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
  setFilters: (filters: ViewFilters) => void;
  setShowArchived: (showArchived: boolean) => void;
};

export const useUi = create<UiState>((set) => ({
  scope: { kind: "all" },
  view: "list",
  zoom: "day",
  overlay: "none",
  dialog: { kind: "none" },
  query: "",
  filters: {},
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
  // Passed whole rather than merged key by key, for the reason `SavedViewService.update`
  // gives about the same value: an empty map is a legitimate answer — it is what taking
  // the last chip off leaves behind — and a merge could never express it.
  setFilters: (filters) => set({ filters }),
  setShowArchived: (showArchived) => set({ showArchived }),
}));
