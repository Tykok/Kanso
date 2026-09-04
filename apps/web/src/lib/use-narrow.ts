"use client";

import { useCallback, useSyncExternalStore } from "react";

/**
 * The one width the application asks about in TypeScript, and the same 720px every
 * `max-[720px]:` in the tree already means.
 *
 * Almost nothing needs this: a column that should disappear disappears in CSS, which is
 * why the shell, the drawer and the ticket grid all do it there and none of them call
 * this. What CSS cannot do is *unask a question* — the board and the timeline are not
 * columns to hide but two other drawings of the list, each with its own fetch, its own
 * cursor and, on the timeline, a horizontal axis that does not fit a phone at any zoom.
 * `Kanso - Mobile.dc.html` closes on the rule this exists to keep: "si l'écran demande
 * deux mains et une souris, il n'a pas de version téléphone."
 *
 * `useSyncExternalStore` rather than a `useState` and an effect, for the reason it was
 * added to React: the server has no window, and a value read in an effect is a value the
 * first paint did not have — the phone would draw the timeline once and then replace it.
 * The server snapshot is `false`, so the markup React sends is the wide one and a narrow
 * client corrects it in the same commit as hydration rather than a frame later.
 */
const NARROW = "(max-width: 720px)";

export function useNarrow(): boolean {
  const subscribe = useCallback((onChange: () => void) => {
    const query = window.matchMedia(NARROW);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(NARROW).matches,
    () => false,
  );
}
