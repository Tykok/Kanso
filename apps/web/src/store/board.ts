import { create } from "zustand";

/**
 * The one thing the board holds that `store/ui.ts` does not: a request to open a card.
 *
 * The board's cursor is `ui.selectedId` — the same ticket is selected in all three
 * drawings, and a second cursor here would let the list and the board disagree about
 * which row is being looked at. What is genuinely the board's is *how* `↵` opens that
 * ticket, and the answer is a preference: `preferences.openTicket` decides between the
 * panel and the page.
 *
 * An action cannot read that preference and cannot navigate — `ActionContext` carries
 * neither the query cache nor the router, deliberately, since it is a module the test
 * suite imports under `environment: "node"`. So `board.open` states the *intent* here
 * and `BoardView`, which already has both the preference and the router, decides. One
 * value, set by the key and cleared by the view that acted on it.
 *
 * `store/ui.ts` is closed for the fan-out and this is a slice's own state, which is
 * exactly the case that spec says to answer with `store/<slice>.ts`.
 */
type BoardState = {
  /** The identifier of a card the keyboard asked to open, until the view has done it. */
  requestedOpen?: string;
  requestOpen: (identifier: string) => void;
  /** Called by the view once it has opened the panel or pushed the route. */
  openHandled: () => void;
};

export const useBoard = create<BoardState>((set) => ({
  requestOpen: (identifier) => set({ requestedOpen: identifier }),
  // Cleared rather than compared: the same card opened twice in a row has to fire
  // twice, and an unchanged value would look to the effect like nothing happened.
  openHandled: () => set({ requestedOpen: undefined }),
}));
