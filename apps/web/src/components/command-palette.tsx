"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { openTicketMode, ticketAddress, ticketHref } from "@/lib/api";
import {
  usePreferences,
  useProjects,
  useSearchableTickets,
  useDocPages,
  useDocs,
} from "@/lib/queries";
import { useUi } from "@/store/ui";
import {
  countLabel,
  isPickerList,
  nextTab,
  search,
  SEARCH_TABS,
  type PaletteCommand,
  type SearchRow,
  type SearchTab,
} from "./search/results";
import { SearchList } from "./search/list";
import { SearchPreview } from "./search/preview";
import { Backdrop } from "./overlays";
import { Kbd } from "./ui/kbd";

/**
 * Screen 06 — the global search.
 *
 * The palette and the search are one object. That is not a convenience: a second overlay
 * with its own field, its own keys and its own idea of what a result is would be the same
 * gesture taught twice. So what is typed here searches tickets, documents *and* commands
 * at once, with the drawing's preview pane on the right to decide without opening.
 *
 * "Documents" is two things, and this file reads both. A **page** (`DocPage`) is a
 * document written in Kanso and opens at `/docs/[id]`; a **Notion reference** (`Doc`,
 * imported by `search/results.ts` as `NotionRef`) is a page Kanso only points at and
 * opens in Notion. For most of screen 07's life this palette read only the second, so a
 * document written here could not be found by the search Kanso puts on ⌘K. See the long
 * note in `search/results.ts` for the two names and the rename they want.
 *
 * The prop shape is deliberately unchanged. `app/page.tsx` passes `commands` and
 * `onClose` and is frozen for the fan-out; more importantly, `d` and `D` on the chart
 * route the dependency picker through this same overlay, and that has to keep working
 * exactly as it did. See `isPickerList`.
 */

/** The four labels of the tab strip, in `SEARCH_TABS` order. */
const TAB_LABELS: Record<SearchTab, string> = {
  all: "All",
  tickets: "Tickets",
  docs: "Documents",
  commands: "Commands",
};

export function CommandPalette({
  commands,
  onClose,
  only,
}: {
  commands: PaletteCommand[];
  onClose: () => void;
  /**
   * `"commands"` turns the search off. The dependency picker is detected without it —
   * `page.tsx` cannot pass it — but a caller that can say so should, and integration
   * replacing the sniff with this prop is a one-line change at one call site.
   */
  only?: "commands";
}) {
  const router = useRouter();
  const preferences = usePreferences();
  const projects = useProjects();
  const { select, open } = useUi();
  const [query, setQuery] = useState("");
  const [tab, setTab] = useState<SearchTab>("all");
  const [active, setActive] = useState(0);

  // Two lists of tickets on one screen with different consequences is the one thing this
  // rewrite must not do, so a picker searches nothing.
  const searching = only !== "commands" && !isPickerList(commands);

  // `enabled` on both of these: the picker pays for neither query, and neither does a ⌘K
  // that is closed again before anything is typed — these are two extra requests
  // otherwise.
  const tickets = useSearchableTickets(searching);
  const notionRefs = useDocs(searching);

  /**
   * The documents written here — the half of "documents" this palette used to miss.
   *
   * Gated like the two above, on the same keystroke. `undefined` is every team rather than
   * none: the palette searches across teams, as `useSearchableTickets` does, and a team's
   * own tree is what the `teamId` argument is for on screens 07 and 22.
   */
  const pages = useDocPages(undefined, searching);

  const found = useMemo(
    () =>
      search({
        query,
        tab,
        tickets: searching ? (tickets.data ?? []) : [],
        pages: searching ? (pages.data ?? []) : [],
        notionRefs: searching ? (notionRefs.data ?? []) : [],
        commands,
      }),
    [query, tab, searching, tickets.data, pages.data, notionRefs.data, commands],
  );

  const rows = found.rows;
  const current = rows[active];

  /**
   * Opening a result.
   *
   * `inPage` is `⇧↵` and the drawing says so; `↵` is `preferences.openTicket`, the column
   * screen 02 said this setting lives in.
   *
   * A document opens where it lives, and the two kinds do not live in the same place. A
   * page written here has a reader — slice B built screen 07 — so it opens in the app,
   * like the sidebar's Documents and `favourites.tsx` already do. Only a Notion reference
   * leaves the application, and it does so unconditionally: `search` hands back no
   * reference without a `url`, so there is no `if (url)` here to fall through into a row
   * that closes the palette and does nothing. That fall-through was the second half of
   * this bug, and the type `OpenableNotionRef` is what keeps it from coming back.
   *
   * The old comment here said Kanso had no reader for a document "until slice B builds
   * screen 07". Slice B built it. The comment went stale and the behaviour it justified
   * stayed — which is worth a line here, because a condition written into a comment is a
   * claim nothing re-checks.
   */
  const openRow = (row: SearchRow | undefined, inPage: boolean) => {
    if (!row) return;
    if (row.kind === "command") {
      row.command.run();
      return;
    }
    if (row.kind === "page") {
      onClose();
      router.push(`/docs/${row.page.id}`);
      return;
    }
    if (row.kind === "notionRef") {
      window.open(row.notionRef.url, "_blank", "noreferrer");
      onClose();
      return;
    }
    // The cursor follows what was opened either way: coming back from the ticket page
    // should land on the row that was just read, not on wherever the list was before.
    select(row.ticket.id);
    onClose();
    if (inPage || openTicketMode(preferences) === "page") {
      router.push(ticketHref(ticketAddress(row.ticket)));
    } else {
      open("detail");
    }
  };

  // Typing narrows the list, so the highlight goes back to the top — in the change
  // handler rather than an effect, which would render twice to undo a selection nobody
  // saw. The same applies to changing tab.
  const retype = (next: string) => {
    setQuery(next);
    setActive(0);
  };

  const retab = (next: SearchTab) => {
    setTab(next);
    setActive(0);
  };

  return (
    <Backdrop onClose={onClose} panelClassName="w-[min(820px,94vw)]">
      {/* The overlay is still in front of the whole application, sidebar included, so a
          test asking for "Tickets" needs somewhere to ask it of. `Backdrop` takes only a
          className, which is why the handle sits on this element rather than on the panel. */}
      <div data-testid="palette" className="flex">
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex items-center gap-2.5 px-[18px] py-3.5">
            <span aria-hidden className="text-15 text-faint">
              ⌕
            </span>
            <input
              className="min-w-0 flex-1 border-none bg-transparent p-0 text-15 text-foreground outline-none placeholder:text-faint"
              autoFocus
              aria-label={searching ? "Search tickets, documents and commands" : "Search commands"}
              /**
               * One string for both modes, and it still ends in the words it always did.
               * `keyboard.spec.ts` and `archive.spec.ts` reach this field by
               * `getByPlaceholder("Type a command…")` — a substring match — and those two
               * specs are not slice A's to edit. The precise wording of what this field
               * accepts lives in `aria-label`, which nothing keys on.
               */
              placeholder="Search, or type a command…"
              value={query}
              onChange={(event) => retype(event.target.value)}
              onKeyDown={(event) => {
                // The page answers bare keys on `window`; nothing typed here may also be
                // moving a cursor or changing a status underneath.
                event.stopPropagation();
                if (event.key === "ArrowDown") {
                  event.preventDefault();
                  setActive((index) => Math.min(index + 1, rows.length - 1));
                }
                if (event.key === "ArrowUp") {
                  event.preventDefault();
                  setActive((index) => Math.max(index - 1, 0));
                }
                if (event.key === "Tab" && searching) {
                  // Tab has no other job in a modal with one field, and the drawing puts
                  // the key beside the tab's own name for exactly this reason.
                  event.preventDefault();
                  retab(nextTab(tab));
                }
                if (event.key === "Enter") {
                  event.preventDefault();
                  openRow(current, event.shiftKey);
                }
                if (event.key === "Escape") onClose();
              }}
            />
            {searching && (
              <>
                <span className="text-11 text-faint">{TAB_LABELS[tab]}</span>
                <Kbd>tab</Kbd>
              </>
            )}
          </div>

          {/*
            * `role="group"` with `aria-pressed`, exactly like the view and zoom controls
            * in `app/page.tsx` — not `tablist`/`tab`, which promises a `tabpanel` this
            * strip does not have: the list below is one result list being filtered, not
            * four panels being switched between. It also means `.segmented`, which styles
            * the active button off `aria-pressed`, dresses this strip with no new CSS.
            */}
          {searching && (
            <div className="segmented mx-[18px] mb-2" role="group" aria-label="What to search">
              {SEARCH_TABS.map((candidate) => (
                <button
                  key={candidate}
                  type="button"
                  aria-pressed={tab === candidate}
                  onClick={() => retab(candidate)}
                >
                  {TAB_LABELS[candidate]}
                </button>
              ))}
            </div>
          )}

          <div className="h-px bg-border" />

          <SearchList
            found={found}
            query={query}
            active={current}
            showCommandHeading={searching}
            projects={projects.data ?? []}
            onHover={setActive}
            onOpen={openRow}
          />

          <div className="h-px bg-border" />
          <div className="flex items-center gap-2.5 px-4 py-2.5 text-11 text-faint">
            <span>
              <Kbd>↑</Kbd> <Kbd>↓</Kbd> move
            </span>
            <span>
              <Kbd>↵</Kbd> open
            </span>
            {searching && (
              <span>
                <Kbd>⇧↵</Kbd> in page
              </span>
            )}
            <span className="flex-1" />
            <span data-testid="search-count" role="status">
              {countLabel(found.shown, found.total)}
            </span>
          </div>
        </div>

        {searching && <SearchPreview row={current} projects={projects.data ?? []} />}
      </div>
    </Backdrop>
  );
}

