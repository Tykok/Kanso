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

export type ActionContext = {
  scope: Scope;
  teams: Team[];
  projects: Project[];
  tickets: Ticket[];
  selected?: Ticket;
  canConfigure: boolean;
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
   * Space-separated `KeyboardEvent.key` values, so one action can own the two
   * spellings of the same intent (`j` and `ArrowDown`) without a second field
   * that could disagree with this one.
   *
   * Shift is spelled by the key itself: `event.key` for Shift+h is `"H"`, a separate
   * entry, so nothing here has to carry modifier state.
   */
  shortcut?: string;
  /**
   * What a menu prints when the key this action answers is not a key `resolveShortcut`
   * can dispatch on. `hint` is never dispatched; `shortcut` is only printed when there is
   * no `hint`, so the two cannot disagree about which one does what.
   *
   * `Mod+` is canonical and expanded at display time: the registry is a module, and which
   * modifier the reader's keyboard carries is a runtime fact about the reader.
   */
  hint?: string;
  /**
   * The view this action belongs to. Absent means both — most of the registry, since
   * a status change means the same thing wherever the ticket is drawn.
   */
  mode?: View;
  group: ActionGroup;
  when: (ctx: ActionContext) => boolean;
  run: (ctx: ActionContext) => void;
};
