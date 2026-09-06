"use client";

import { Search } from "lucide-react";
import { actionById } from "@/lib/actions";
import { isMac } from "@/lib/platform";
import { hintFor } from "@/lib/shortcuts";
import { useBindings } from "@/lib/use-bindings";
import { useUi } from "@/store/ui";

/**
 * The way into the search for everyone who is not holding `⌘K`.
 *
 * The palette *is* the search — `search/results.ts` says so at length, and it answers
 * over tickets, documents written here, Notion references and every command the registry
 * permits. It had exactly two doors: a chord, and a row buried in the brand menu. On a
 * phone that is no door at all, which made the one screen `Kanso - Mobile.dc.html` calls
 * "la recherche — remplace toute la navigation latérale" unreachable on the device it
 * was drawn for.
 *
 * So: a button, at the same place on every width. Not a mobile affordance with a desktop
 * twin — a reader who would rather click has the same claim to it as a reader who would
 * rather not remember a chord, and two controls would be two things to keep in step.
 *
 * `open("palette")` and nothing else, which is the body of `app.palette` verbatim
 * (`lib/actions/core.ts`). The alternative — dispatching the action through the registry
 * — would put this behind `when`, and `app.palette`'s `when` is `() => true`: a
 * permission check that can only ever answer yes, paid for on every render.
 *
 * Beside the bell and the `×` rather than inside `<TopbarSlot>`, and for their reason: a
 * page may withhold what it puts in the slot, and the ability to search is true of the
 * session rather than of the screen you happen to be on.
 *
 * The keycap is in the `title` rather than drawn next to the glyph, which is the rule the
 * `×` already states — a `<kbd>` on every route is a keyboard lesson repeated on every
 * screen — and it is read from the *effective* bindings, so a reader who remapped `⌘K`
 * is told what they remapped it to and a reader who unbound it is told nothing at all
 * rather than a key that does not work.
 */
export function SearchButton() {
  const { keys } = useBindings();
  const open = useUi((state) => state.open);
  const chord = hintFor(actionById("app.palette"), keys, isMac());

  return (
    <button
      type="button"
      data-testid="shell-search"
      aria-label="Search"
      title={chord === undefined ? "Search" : `Search — ${chord}`}
      className="flex size-6 shrink-0 items-center justify-center rounded-sm text-muted-foreground hover:bg-accent hover:text-foreground"
      onClick={() => open("palette")}
    >
      <Search aria-hidden className="size-4" />
    </button>
  );
}
