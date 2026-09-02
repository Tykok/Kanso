/**
 * When a revealed sidebar is out, and when letting it retract would be a flicker.
 *
 * Pure, and in a module of its own for the reason `burndown.ts` and `timeline-geometry.ts`
 * are: the two rules below are the whole of what makes a hover reveal feel deliberate
 * rather than twitchy, and both are answered from numbers. A reveal that closes half a
 * pixel early is invisible in a screenshot and obvious in an assertion.
 *
 * Nothing here touches the DOM or the window — the caller reads a rect and hands the
 * numbers over. That is what lets the flicker rule be a unit test instead of a browser.
 */

/**
 * Why the panel is out. Three independent reasons, any one of which holds it, because a
 * reveal has three ways in and each has its own idea of when it is over.
 *
 * They are separate booleans and not one enum on purpose. A panel entered with the
 * pointer and then tabbed into is held by both, and dropping the pointer hold when the
 * reader's mouse wanders off must not take the focus with it — which is exactly what a
 * single "opened by" field would do, since it can only remember the last answer.
 */
export type RevealHold = {
  /** The pointer is inside the hot zone or inside the panel. */
  pointer: boolean;
  /** Focus is inside the panel — Tab reached it, or something in it took the focus. */
  focus: boolean;
  /** The top bar's panel button was pressed, and nothing has dismissed it since. */
  button: boolean;
};

export const NOTHING_HELD: RevealHold = { pointer: false, focus: false, button: false };

export const isRevealed = (hold: RevealHold): boolean =>
  hold.pointer || hold.focus || hold.button;

/**
 * Compared field by field so the store below can refuse to notify on a no-op. Three
 * booleans, so this is cheaper than the `JSON.stringify` the temptation would be, and
 * unlike a reference check it survives the `{ ...held, ...patch }` that produces every
 * next value.
 */
export const sameHold = (a: RevealHold, b: RevealHold): boolean =>
  a.pointer === b.pointer && a.focus === b.focus && a.button === b.button;

/**
 * The pointer has left the panel. Does that mean the reader turned to the content, or
 * that they reached past the window?
 *
 * The panel runs the full height of the viewport down its left edge, so there are only
 * two ways out of it, and they mean opposite things:
 *
 *   - **rightwards, into the content.** The reader is done with the column. Retract, and
 *     retract immediately — a reveal that lingers is a reveal that has to be dismissed.
 *   - **off the top, the bottom or the left of the window.** The address bar, the tab
 *     strip, the OS dock, the desktop. The reader has not looked away from the column at
 *     all; grazing the top of the window on the way to a bookmark would snap it shut and
 *     then, on the way back, open it again. That is the flicker, and it is the single
 *     most common way a hover panel comes to feel broken.
 *
 * The third case is subtler and is why this takes the panel's own right edge rather than
 * just asking "is the pointer outside". A `mouseleave` can fire with the pointer still
 * inside the panel's footprint: the column opens popovers — the brand menu under the
 * seal, a team row's `⋯` — and those are portalled to the body, so they are not
 * descendants of the panel and crossing onto one *is* a leave. Retracting there would
 * unmount the trigger and take the menu with it, so anything left of the right edge
 * holds. The caller has a second guard for the popovers that open past that edge.
 */
export function retractsOnLeave(
  pointer: { x: number; y: number },
  panel: { right: number },
  viewport: { height: number },
): boolean {
  if (pointer.y <= 0 || pointer.y >= viewport.height || pointer.x <= 0) return false;
  return pointer.x >= panel.right;
}
