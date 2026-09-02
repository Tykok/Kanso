import type { Dialog, Overlay, Scope, View } from "@/store/ui";
import type {
  FavouriteTarget,
  Project,
  Team,
  Ticket,
  TimelineDependency,
} from "../api";
import type { PatchInput } from "../queries";
import type { Zoom } from "../timeline-geometry";

/**
 * The shapes every slice's actions are written against, in their own module so that
 * `core.ts` and the six slice files can import them without importing the barrel that
 * imports *them* — which is the cycle this file exists to break.
 */
export type ActionGroup = "ticket" | "team" | "project" | "view" | "app";

/**
 * Which screen a key belongs to.
 *
 * The three `View`s — the drawings of one scoped query — plus the two screens that had to
 * keep their own handler because a key means something else on them and nowhere else:
 * `x` selects a row on a saved view where it archives a ticket everywhere, and `a` `b`
 * `d` `x` rule on a triage queue. `resolveShortcut` asks the screen's own bucket before
 * the shared one, so those five keys are the whole reason this is not just `View`.
 *
 * A mode is not a route. `/cycles`, `/trash`, `/docs` and the rest answer the shared
 * bucket in `list`, because what they draw is a list of records and the keys they need are
 * the keys every screen needs. A mode is added when a *key* has to mean two things, and
 * `components/shortcut-sections.test.ts` fails until the `?` sheet has a heading for it —
 * a mode with no heading is a key the reader is told does not exist.
 */
export type ShortcutMode = View | "savedView" | "triage";

export type ActionContext = {
  scope: Scope;
  teams: Team[];
  projects: Project[];
  tickets: Ticket[];
  selected?: Ticket;
  canConfigure: boolean;
  /**
   * Whether this person's seat writes at all — false only for `InstanceRole.VIEWER`.
   *
   * A courtesy, not a permission: the server refuses the request whatever this says
   * (`ReadOnlySeat` and `TicketAccess`). What it buys is honesty — a reader shown a
   * composer that 403s has been lied to, and a menu of things that will all fail is
   * worse than a shorter menu.
   */
  canWrite: boolean;
  /** Which drawing of the same rows is on screen — the list, or the chart. */
  view: View;
  zoom: Zoom;
  /**
   * The arrows the chart is drawing, and an empty list anywhere it is not.
   *
   * Filled from the timeline query — the same cache entry the chart reads, so this costs
   * no second request — and empty while that query is in flight. `timeline.unlink` is
   * therefore the first action whose availability depends on a fetch: with no edges
   * loaded, nothing knows whether there is anything to erase, and saying so is more
   * honest than offering a picker that would open empty.
   */
  dependencies: TimelineDependency[];

  open: (overlay: Overlay) => void;
  close: () => void;
  openDialog: (dialog: Dialog) => void;
  setScope: (scope: Scope) => void;
  setZoom: (zoom: Zoom) => void;
  move: (delta: number) => void;
  focusFilter: () => void;
  startRename: (id: string) => void;
  /**
   * `PatchInput`, not `{ id } & Record<string, unknown>`: every keyboard-driven patch
   * used to reach the wire unchecked, so a misspelled field type-checked its way to a
   * 400. The `satisfies` clauses below predate this and are now redundant — they are
   * kept because a wire value written out in full is still worth reading.
   */
  patchTicket: (input: PatchInput) => void;
  deleteTicket: (id: string) => void;
  unarchive: (target: { kind: "team" | "project"; id: string }) => void;
  /** Scrolls the chart back to today. A no-op anywhere the chart is not rendered. */
  recentre: () => void;
  /**
   * Asks for a predecessor for [successorId]. The picker is the command palette the
   * app already has, filtered to candidates — a modal link mode would be the only
   * modal gesture in the interface, a whole mental model bought for one arrow.
   */
  startLink: (successorId: string) => void;
  /**
   * Asks which predecessor of [successorId] to erase. Same picker as [startLink], for the
   * same reason: the palette is the app's only list, and one gesture is not worth a
   * second way of being in a state.
   */
  startUnlink: (successorId: string) => void;
  /**
   * What this context is *about*, when a caller can say so and the route cannot.
   *
   * Absent almost everywhere, and `favourites.ts` then works it out from the route and the
   * scope. The sidebar's own rows set it, because they are the one caller whose ctx names a
   * team or a project that is **not** what the page is showing: a row menu opened while a
   * saved view is on screen would otherwise pin the view it is sitting in front of.
   */
  favourite?: FavouriteTarget;
  /**
   * Pins [target] to the top of this person's sidebar, or un-pins it if it is already
   * there — see `actions/favourites.ts` for why the toggle is one gesture and not a pair.
   *
   * A toggle rather than an `add`/`remove` pair here too, and for a second reason: the
   * registry must not have to know the current state to run, since `when` is answered
   * from the route and the scope and neither of those has loaded a favourites list.
   */
  toggleFavourite: (target: FavouriteTarget) => void;
  logout: () => void;
};

export type Action = {
  id: string;
  label: string;
  /**
   * The chords that reach this action out of the box, in the order they are printed.
   *
   * One field where there were two. `shortcut` held space-separated bare
   * `KeyboardEvent.key` values and was dispatched; `hint` held a string that was *printed*
   * and never dispatched, and existed because three intentions could not be written as a
   * bare key at all — `⌘K` needed a modifier, `⇧↑↓` needed one the registry had no way to
   * read, and `x` on a saved view needed a screen the registry could not name. Each of the
   * three ended up hardcoded in a page's own `keydown` handler with a matching string
   * here, and the two halves had to be kept saying the same thing by hand. Twice they were
   * not: `organise.select` printed `x` and dispatched nothing, and the board's five keys
   * were dispatched and printed nowhere.
   *
   * A chord is parseable, so there is now one spelling instead of two fields, and §6.5's
   * capture UI can *produce* what this holds rather than asking somebody to type it. See
   * `./chords.ts` for the grammar, including why Shift is a prefix and `?` is not.
   *
   * Absent means the keyboard does not reach it — the palette and the row menus do. That
   * is the right answer for `ticket.delete`, for the five priorities (`Shift+p` opens a
   * picker over them instead of spending five keys) and for `timeline.schedule`, which
   * lost its key in §6.4 on the maintainer's ruling and kept both its other doors.
   *
   * **Defaults, not bindings.** What a key actually does is `mergeBindings` in
   * `lib/shortcuts.ts`, which lays these down and then applies the reader's overrides. No
   * surface reads this field to print a key: they read the effective map, or they would
   * describe a keyboard the reader has already changed.
   */
  defaultKeys?: readonly string[];
  /**
   * The screen this action belongs to. Absent means all of them — most of the registry,
   * since a status change means the same thing wherever the ticket is drawn.
   */
  mode?: ShortcutMode;
  group: ActionGroup;
  /**
   * Whether running this changes something the whole instance shares.
   *
   * Declared rather than inferred, the same way `McpTool.writes` is on the server and
   * for the same reason: a `run` that eventually reaches a mutation is not something a
   * predicate can read, and an action that answered the question itself would be a
   * second place to keep in step. `permits` in `./index` is the only reader.
   *
   * Absent means false, and false is the right default here even though it is the
   * permissive one — the alternative hides *reads* from a read-only seat, which is the
   * product inverted. `core.test.ts` is what stops a new write from being forgotten: it
   * classifies every id in the registry and fails on one it has never seen.
   *
   * Toggling a favourite is deliberately not a write. It changes one person's sidebar,
   * the server allows it on a read-only seat (`ReadOnlySeat.OWN_SCREEN`), and hiding it
   * here would take away something the API is happy to answer.
   */
  writes?: boolean;
  when: (ctx: ActionContext) => boolean;
  run: (ctx: ActionContext) => void;
};
