"use client";

import { Menu } from "@/components/menu";
import { Kbd } from "@/components/ui/kbd";
import { actionById } from "@/lib/actions";
import { VIEW_GROUP_BYS, VIEW_SORT_BYS, type ViewGroupBy, type ViewSortBy } from "@/lib/api";
import { isMac } from "@/lib/platform";
import { usePreferences } from "@/lib/queries";
import { hintFor } from "@/lib/shortcuts";
import { useBindings } from "@/lib/use-bindings";

/**
 * Filter, Group and Order, at the left of the top bar's slot — the three chords, drawn.
 *
 * `Mod+f`, `Mod+g` and `Mod+o` already reach these three controls. This is the same three
 * intentions for the reader who would rather click, which is why each button prints the key
 * beside its label: the top bar is where a keyboard-first application's keyboard is
 * discovered, and nobody opens the help sheet to learn that a list can be grouped.
 *
 * The keys are read from the **effective** bindings — `useBindings()` is the same merge the
 * dispatcher resolves against — so a reader who remapped `Mod+g` sees what they remapped it
 * to. `hintOf` and the defaults would have made this the one surface in the app that
 * advertises a keyboard its owner does not have.
 *
 * ## Each control is drawn only where it does something
 *
 * A control appears when the surface hands over the handler for it, and not otherwise. That
 * is not tidiness: `organise.groupBy` and `organise.sortBy` are refused off a saved view,
 * and `organise.addFilter` is refused on the chart — where nothing mounts the filter box and
 * where the dispatcher would stand down for a dialog nobody draws. A button that opened one
 * anyway would be the same trap the key was fixed for, with a mouse instead of a keyboard.
 *
 * `showViewControls` hides the lot. The bell, the breadcrumb and the `×` are how a reader
 * gets somewhere; these three are a second spelling of something already reachable, so
 * hiding them takes nothing away — and `Preferences` says as much in its own comment.
 */
export function ViewControls({
  onFilter,
  group,
  order,
}: {
  /** Opens the filter box. Absent where none is mounted — the timeline. */
  onFilter?: () => void;
  /** A saved view's grouping. Absent on the main list, which stores no grouping at all. */
  group?: { value: ViewGroupBy; onChange: (next: ViewGroupBy) => void };
  order?: { value: ViewSortBy; onChange: (next: ViewSortBy) => void };
}) {
  const { keys } = useBindings();
  const preferences = usePreferences();
  if (!preferences.showViewControls) return null;

  const mac = isMac();
  const keyOf = (id: string) => hintFor(actionById(id), keys, mac);

  return (
    <div data-testid="view-controls" className="flex shrink-0 items-center gap-1.5">
      {onFilter && (
        <button
          type="button"
          id="view-filter"
          data-testid="view-filter"
          className={CONTROL}
          onClick={onFilter}
        >
          Filter <Hint chord={keyOf("organise.addFilter")} />
        </button>
      )}

      {/*
        * The id is how the key reaches the menu — `document.getElementById("view-group-by")
        * ?.click()`, which is the registry's own trick for a control living in a component
        * tree it knows nothing about. It moved from the chip strip to here with the button;
        * the id came with it, so both keys still land.
        */}
      {group && (
        <Menu
          label="Group by"
          asChild
          trigger={
            <button type="button" id="view-group-by" data-testid="view-group" className={CONTROL}>
              Group <span className="text-faint">{group.value}</span>{" "}
              <Hint chord={keyOf("organise.groupBy")} />
            </button>
          }
          items={VIEW_GROUP_BYS.map((groupBy) => ({
            id: `view.groupBy.${groupBy}`,
            label: groupBy,
            onSelect: () => group.onChange(groupBy),
          }))}
        />
      )}

      {order && (
        <Menu
          label="Order by"
          asChild
          trigger={
            <button type="button" id="view-sort-by" data-testid="view-order" className={CONTROL}>
              Order <span className="text-faint">{order.value}</span>{" "}
              <Hint chord={keyOf("organise.sortBy")} />
            </button>
          }
          items={VIEW_SORT_BYS.map((sortBy) => ({
            id: `view.sortBy.${sortBy}`,
            label: sortBy,
            onSelect: () => order.onChange(sortBy),
          }))}
        />
      )}
    </div>
  );
}

const CONTROL =
  "inline-flex items-center gap-1.5 rounded-md border border-border px-2 py-1 text-12 text-muted-foreground hover:text-foreground";

/**
 * The key, or nothing at all.
 *
 * A reader may unbind any of the three, and an empty keycap beside a word is worse than a
 * button that only says what it does — which is the rule the status bar's `Keys` already
 * holds to for the same reason.
 */
const Hint = ({ chord }: { chord?: string }) =>
  chord === undefined ? null : <Kbd className="border-b">{chord}</Kbd>;
