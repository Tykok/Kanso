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
 */
const onSavedView = () =>
  typeof window !== "undefined" && window.location.pathname.startsWith("/views/");

export const organiseActions: readonly Action[] = [
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
