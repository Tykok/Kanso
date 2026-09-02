import { claimed, runClaim } from "./claims";
import type { Action } from "./types";

/**
 * Slice C — 19 to 23, cycle, triage, saved views and workload.
 *
 * This file used to open with a long explanation of why only two of screen 21's five keys
 * could be registered here. All five are registered now, and the paragraph is worth
 * keeping in outline because each of the three obstacles fell to a different part of §6:
 *
 *  - **`x` (select a row)** collided with `ticket.archive`, which holds `x` in the shared
 *    bucket. It now claims `x` in the `savedView` bucket instead — a mode of its own, so
 *    the key means "select" on the one screen where a selection exists and "archive"
 *    everywhere else, which is exactly what `mode` was already doing for the chart's `h`.
 *  - **`⇧↑` and `⇧↓`** were inexpressible: `event.key` for Shift+ArrowDown is still
 *    `"ArrowDown"`, and `Action.shortcut` held one bare key with no modifier state. A
 *    chord holds the modifier (`./chords.ts`), so they are ordinary bindings.
 *  - **The bodies** — toggling a selection, extending a range, ruling on a triage ticket
 *    — are page state that `ActionContext` does not carry and must not grow. The page
 *    claims them (`./claims.ts`), which is also why `when` here is "somebody can do this"
 *    rather than a `window.location.pathname` test.
 *
 * What is left of the route sniffing is the three actions that reach a *control* — the
 * filter dialog, the group-by menu, the sort menu. Those are read off `window.location`
 * rather than from a router because this module is imported by a test suite running under
 * `environment: "node"`, and by the registry, which is a module and has no hooks. Same
 * arrangement `favourites.ts` documents.
 *
 * §6.4 respells the three chords: `Mod+f` filters, `Mod+g` groups, `Mod+o` orders. They
 * were `F`, `g` and `f` — a set nobody could remember, because `f` meant *sort* (it landed
 * there before there was anything to filter with) and filtering had to take `F` to get out
 * of its way. Three chords on one hand, each the initial of what it does, and the letters
 * they give up go back to the pool. `Mod+f` deliberately shadows find-in-page, as it does
 * in Notion, Linear and Slack; the typing guard is what keeps the browser's own find
 * reachable from inside any input, and §11 of the design argues it.
 */

/**
 * The two surfaces that draw a list somebody can ask a question of: the main list, and a
 * saved view. They are the same question through two doors — `GET /api/tickets` and
 * stored jsonb — so the key that composes one is the same key on both.
 */
const onOrganisableList = () =>
  typeof window !== "undefined" &&
  (window.location.pathname === "/" || window.location.pathname.startsWith("/views/"));

/**
 * The main list specifically, which is the only one of those two doors that can be
 * showing something other than rows.
 *
 * `onOrganisableList` deliberately does not distinguish them — the question a filter
 * composes is the same question either way. This does, for one reason: `page.tsx` mounts
 * `ListFilters`, and with it the only **drain** of `dialog.kind === "filter"`, under
 * `view !== "timeline"`. A saved view has no chart, so its `view` is whatever the store
 * happens to be holding from the last visit to `/` and must not be read here.
 *
 * Drain, not draw, since §7: nothing renders that dialog any more. `filter-input.tsx`
 * closes it and focuses its own box on arrival, which is why the refusal below matters
 * more now than when it was written — on the chart there is no box to drain it, and a
 * `filter` left standing in the store takes the whole keyboard down with it.
 */
const onMainList = () => typeof window !== "undefined" && window.location.pathname === "/";

const onSavedView = () =>
  typeof window !== "undefined" && window.location.pathname.startsWith("/views/");

export const organiseActions: readonly Action[] = [
  {
    id: "organise.addFilter",
    label: "Add a filter…",
    defaultKeys: ["Mod+f"],
    group: "view",
    /**
     * Refused while the chart is the drawing, and the refusal is load-bearing rather than
     * tidy: nothing on `/` draws this dialog on the timeline, so the old `when` opened a
     * dialog nobody rendered — and the dispatcher stands down entirely for an open dialog.
     * `Mod+f` on the chart would therefore kill `h` `l` `[` `]` `t` `d` with nothing on
     * screen admitting why, until Escape closed the thing that was never drawn.
     *
     * The chips strip stays off the chart for the reason `page.tsx` gives — a strip of
     * facets over a query they do not narrow would claim the plan had been filtered — so
     * the honest fix is here, where the key is offered, and not a mount that would put
     * the strip back.
     */
    when: (ctx) => onOrganisableList() && !(onMainList() && ctx.view === "timeline"),
    /**
     * A dialog and not an overlay, and opened through the store rather than by clicking
     * an element by id the way the two below do.
     *
     * The dispatcher stands down entirely while a dialog is open, which is what the
     * composed list needs: `↑↓` inside it walk the offered filters, and without that
     * guard they would also be walking the tickets behind it. The group-by and sort menus
     * want the opposite — they are Radix popovers that shield their own keys — so the two
     * are reached differently on purpose.
     */
    run: (ctx) => ctx.openDialog({ kind: "filter" }),
  },
  {
    id: "organise.saveView",
    writes: true,
    label: "Save this question as a view",
    group: "view",
    /**
     * The main list only, and not a saved view — which is already the answer to this.
     * It is also the only route that mounts `SaveViewDialog`: `app/(app)/page.tsx` renders
     * it, so opening the dialog from anywhere else sets a store field nothing draws.
     */
    when: () => onMainList(),
    /**
     * No key, and none coming. Two reasons. The gesture already has a button next to the
     * chips it is about, which is where somebody who has just composed a filter is
     * looking; and it is done once per question rather than once per row, which is what a
     * palette entry is for.
     */
    run: (ctx) => ctx.openDialog({ kind: "saveView" }),
  },
  {
    id: "organise.groupBy",
    label: "Group by…",
    defaultKeys: ["Mod+g"],
    group: "view",
    when: onSavedView,
    // The control is reached by its id rather than by a ref, the same trick
    // `focusFilter` uses for the filter input: the element lives in a component tree the
    // registry knows nothing about, and an id is what makes it reachable without a store.
    run: () => document.getElementById("view-group-by")?.click(),
  },
  {
    id: "organise.sortBy",
    // "Order", not "Sort", in §6.4's table — and `Mod+o` rather than the `f` this used to
    // answer. The id keeps its name: `SavedView.sortBy` is the field on the wire, and
    // renaming an action id to match a label would break every menu that names it.
    label: "Order by…",
    defaultKeys: ["Mod+o"],
    group: "view",
    when: onSavedView,
    run: () => document.getElementById("view-sort-by")?.click(),
  },

  /*
   * The saved view's own two keys, claimed by the screen that holds the selection.
   *
   * `mode: "savedView"` throughout, and only `organise.select` needs it: `x` is
   * `ticket.archive` in the shared bucket, and one key cannot be two actions in one mode —
   * `indexActions` refuses it at module load. `⇧↑↓` are free everywhere and could have
   * been shared; they are here so that `?` prints all three under the screen they belong
   * to, which is the one screen where any of them does anything.
   */
  {
    id: "organise.select",
    label: "Select row",
    defaultKeys: ["x"],
    mode: "savedView",
    group: "view",
    // It used to be a `hint` with an empty `run`: the sheet said `x` and the registry
    // dispatched nothing, and the page answered the key from its own handler. That is the
    // divergence `defaultKeys` exists to make impossible.
    when: () => claimed("organise.select"),
    run: () => runClaim("organise.select"),
  },
  /*
   * `⇧↓` and `⇧↑` — a pair of actions rather than the one the design names, and the reason
   * is the shape of `run`.
   *
   * `Action.run` takes the context and nothing else: no key, no direction, no argument. So
   * every reversible gesture in this registry is two entries — `ticket.moveDown`/`moveUp`,
   * `timeline.shiftEarlier`/`shiftLater`, `timeline.shrinkEnd`/`growEnd`,
   * `board.columnLeft`/`columnRight` — and one entry holding both chords would have to
   * read which of them was pressed, which is a channel nothing here has and nothing here
   * should grow for one gesture. The inverse argument is in `favourites.ts`: a *toggle* has
   * to be one action, because `resolveShortcut` answers before `when` and the half that did
   * not hold the key would be unreachable exactly when it was the applicable half.
   */
  {
    id: "organise.selectRangeDown",
    label: "Extend selection down",
    defaultKeys: ["Shift+ArrowDown"],
    mode: "savedView",
    group: "view",
    when: () => claimed("organise.selectRangeDown"),
    run: () => runClaim("organise.selectRangeDown"),
  },
  {
    id: "organise.selectRangeUp",
    label: "Extend selection up",
    defaultKeys: ["Shift+ArrowUp"],
    mode: "savedView",
    group: "view",
    when: () => claimed("organise.selectRangeUp"),
    run: () => runClaim("organise.selectRangeUp"),
  },

  /*
   * Screen 20's four rulings, `mode: "triage"`.
   *
   * A mode rather than the shared bucket, and `x` is what forces it: closing a triage
   * ticket without action and archiving a ticket are two different writes, and the queue
   * is not a list of rows that anything else's `x` could sensibly reach. `d` follows the
   * other three into the same bucket rather than being left shared — the four are one
   * vocabulary and splitting them across two buckets would be a detail somebody has to
   * remember later.
   *
   * The keys are exactly the ones `triage-view.tsx` hardcoded and the ones its four
   * buttons still print, because the buttons and the keys now read the same table.
   * `organise.clearSelection` is gone from this file: `Escape` is `app.back`, and the
   * saved view's own meaning for it is a `PageShell.onEscape` claim, which is what puts
   * clearing a selection *between* "close what is open" and "leave the page".
   */
  {
    id: "triage.accept",
    writes: true,
    label: "Accept into the cycle",
    defaultKeys: ["a"],
    mode: "triage",
    group: "ticket",
    when: () => claimed("triage.accept"),
    run: () => runClaim("triage.accept"),
  },
  {
    id: "triage.defer",
    writes: true,
    label: "Send to backlog",
    defaultKeys: ["b"],
    mode: "triage",
    group: "ticket",
    when: () => claimed("triage.defer"),
    run: () => runClaim("triage.defer"),
  },
  {
    id: "triage.duplicate",
    writes: true,
    label: "Mark duplicate",
    defaultKeys: ["d"],
    mode: "triage",
    group: "ticket",
    when: () => claimed("triage.duplicate"),
    run: () => runClaim("triage.duplicate"),
  },
  {
    id: "triage.reject",
    writes: true,
    label: "Close without action",
    defaultKeys: ["x"],
    mode: "triage",
    group: "ticket",
    when: () => claimed("triage.reject"),
    run: () => runClaim("triage.reject"),
  },
];
