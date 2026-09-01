import type { Action } from "./types";

/**
 * Slice C — 19 to 23, cycle, triage, saved views and workload.
 *
 * Only two of screen 21's five keys can be registered here, and the reason is worth
 * writing down because it looks like an omission.
 *
 * `resolveShortcut` dispatches on a bare `KeyboardEvent.key` within a `mode` that is one of
 * `list | board | timeline` — a drawing of the ticket list, not a route. So:
 *
 *   - `x` (multi-select) is already `ticket.archive` in the shared bucket. Claiming it with
 *     `mode: "list"` would resolve ahead of core's and silently turn archive into select on
 *     the main list, which is the exact shadowing `indexActions` and the barrel's comment
 *     exist to prevent.
 *   - `⇧↑` and `⇧↓` are inexpressible: `event.key` for Shift+ArrowDown is `"ArrowDown"`,
 *     and the registry carries no modifier state. `Action.shortcut` says as much — Shift is
 *     spelled by the key itself, which only works for letters.
 *   - `esc` is `close()`, which every overlay already shares.
 *
 * The three therefore carry a `hint` and no `shortcut`, which is precisely what `hint` is
 * for: `app.palette` does the same for `⌘K`. They appear in the help sheet and the palette,
 * and `views/[id]` dispatches them from its own handler, where the modifier is readable.
 *
 * `g` and `f` are free — core binds `/` for the filter box, not `f` — so those two are real
 * shortcuts. They open the group-by and filter controls on a saved view; `when` is false
 * everywhere else, because the registry is global and there is nothing to group on a page
 * with no list.
 *
 * `F` is the third, and it is `F` rather than `f` because `f` is taken two entries down —
 * by sort, which is where it landed before there was anything to filter with. Both live
 * in the shared bucket, so `f` is claimed everywhere and `indexActions` would refuse a
 * second claim on it at module load. Shift is spelled by the key itself, which is why
 * this one is expressible where `⇧↑↓` above is not: `event.key` for Shift+f is `"F"`.
 */
const onSavedView = () =>
  typeof window !== "undefined" && window.location.pathname.startsWith("/views/");

/**
 * The two surfaces that draw a list somebody can ask a question of: the main list, and a
 * saved view. They are the same question through two doors — `GET /api/tickets` and
 * stored jsonb — so the key that composes one is the same key on both.
 */
const onOrganisableList = () =>
  typeof window !== "undefined" &&
  (window.location.pathname === "/" || window.location.pathname.startsWith("/views/"));

export const organiseActions: readonly Action[] = [
  {
    id: "organise.addFilter",
    label: "Add a filter…",
    shortcut: "F",
    group: "view",
    when: onOrganisableList,
    /**
     * A dialog and not an overlay, and opened through the store rather than by clicking
     * an element by id the way the two below do.
     *
     * `page.tsx` stands its entire window key handler down while a dialog is open, which
     * is what the composed list needs: `↑↓` inside it walk the offered filters, and
     * without that guard they would also be walking the tickets behind it. The group-by
     * and sort menus want the opposite — they are Radix popovers that shield their own
     * keys — so the two are reached differently on purpose.
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
     * It is also the only route that mounts `SaveViewDialog`: `app/page.tsx` renders it,
     * `OrganiseShell` does not, so opening the dialog from anywhere else sets a store
     * field nothing draws.
     */
    when: () => typeof window !== "undefined" && window.location.pathname === "/",
    /**
     * No key, and no `hint` either, so it stays out of the help sheet and appears only in
     * the palette. Two reasons. The gesture already has a button next to the chips it is
     * about, which is where somebody who has just composed a filter is looking; and the
     * bare letters left are poor mnemonics for it — `s` and `v` say nothing, and the good
     * ones are taken. A palette entry is a real keyboard path without spending a key on
     * something done once per question rather than once per row.
     */
    run: (ctx) => ctx.openDialog({ kind: "saveView" }),
  },
  {
    id: "organise.groupBy",
    label: "Group by…",
    shortcut: "g",
    group: "view",
    when: onSavedView,
    // The control is reached by its id rather than by a ref, the same trick
    // `focusFilter` uses for the filter input: the element lives in a component tree the
    // registry knows nothing about, and an id is what makes it reachable without a store.
    run: () => document.getElementById("view-group-by")?.click(),
  },
  {
    id: "organise.sortBy",
    label: "Sort by…",
    shortcut: "f",
    group: "view",
    when: onSavedView,
    run: () => document.getElementById("view-sort-by")?.click(),
  },
  {
    id: "organise.select",
    label: "Select row",
    hint: "x",
    group: "view",
    when: onSavedView,
    // Dispatched by the page, which is the only place the cursor is known. Registered so
    // the help sheet says the key exists.
    run: () => {},
  },
  {
    id: "organise.selectRange",
    label: "Extend selection",
    hint: "⇧↑↓",
    group: "view",
    when: onSavedView,
    run: () => {},
  },
  {
    id: "organise.clearSelection",
    label: "Cancel selection",
    hint: "esc",
    group: "view",
    when: onSavedView,
    run: () => {},
  },
];
