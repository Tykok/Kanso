import type { ShortcutMode } from "@/lib/actions";

/**
 * The headings the help overlay prints, in order, and the mode each one collects.
 *
 * Out here rather than inside `help-overlay.tsx` for one reason: the list has to be
 * complete, and completeness is a claim about the *registry* — every mode any action
 * declares needs a heading, or those keys are drawn nowhere and the sheet quietly
 * describes a smaller keyboard than the reader has. That is exactly what happened to the
 * board: `actions/board.ts` gives all five of its actions `mode: "board"`, the overlay
 * named three modes, and `h` `l` — the board's only non-mouse way to change column —
 * were undiscoverable from the screen whose whole job is to discover keys.
 *
 * A component cannot be asked that question under `environment: "node"`, so the data
 * moved and `shortcut-sections.test.ts` asks it instead. The same reason `burndown.ts`
 * and `chips.ts` are modules and not markup.
 *
 * `undefined` first, and once: a key that works everywhere is not repeated under each
 * view. Then the three drawings, in the order a reader meets them — the list is the
 * default, the board and the chart are the two they switch to — and then the two screens
 * that have keys of their own. Those two are last because they are the narrowest: a saved
 * view's `x` and the queue's four rulings are reachable from one route each, where every
 * heading above them covers a drawing the reader can reach from `/`.
 */
export const SHORTCUT_SECTIONS: readonly { mode: ShortcutMode | undefined; title: string }[] = [
  { mode: undefined, title: "Anywhere" },
  { mode: "list", title: "In the list" },
  { mode: "board", title: "On the board" },
  { mode: "timeline", title: "On the timeline" },
  { mode: "savedView", title: "On a saved view" },
  { mode: "triage", title: "In the triage queue" },
];
