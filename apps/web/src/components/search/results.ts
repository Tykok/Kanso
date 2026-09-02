import type { Doc, DocPage, Ticket } from "@/lib/api";

/**
 * Screen 06's arithmetic: what one field matches, in what order, and how much of it is
 * being shown.
 *
 * The palette and the search are the same object — that is the whole point of the screen —
 * so this is where "the same object" is actually defined. `command-palette.tsx` above it
 * draws and dispatches; every question that can be got wrong is answered here, where
 * vitest can ask it.
 *
 * ## Two things called "document"
 *
 * Kanso has two, and for a while this file searched one of them:
 *
 * - **`DocPage`** (`lib/api/docs.ts`, `GET /api/docs/pages`) — a document *written here*.
 *   It has a title someone typed, blocks, a folder, and a route of its own, `/docs/[id]`.
 *   This file calls it a **page**.
 * - **`Doc`** (`lib/api/views.ts`, `GET /api/docs`) — a row of `notion_docs`: a Notion
 *   page Kanso references but never authors, so a ticket relation has something to point
 *   at. Kanso cannot show it; it can only send the reader to Notion. This file calls it a
 *   **Notion reference**, and imports `Doc` under the alias `NotionRef` below.
 *
 * The name `Doc` for the second one is what caused the bug — the palette reached for "the
 * document type", got the one Kanso does not author, and a document written in Kanso was
 * unfindable by ⌘K for as long as screen 07 has existed. Aliasing at the import is how
 * this module refuses that name without renaming `lib/api`, which it does not own. The
 * rename the rest of the codebase wants is `Doc` → `NotionRef`: the word "document" then
 * means one thing everywhere, and the thing it means is the one with a reader.
 */

/**
 * `lib/api`'s `Doc`, under the name it should have had. Type-only, so nothing about
 * `lib/api` changes and no second definition of the wire shape exists.
 */
type NotionRef = Doc;

/** The shape `app/page.tsx` and `ViewsShell` already hand the palette. Unchanged. */
export type PaletteCommand = { id: string; label: string; hint?: string; run: () => void };

export const SEARCH_TABS = ["all", "tickets", "docs", "commands"] as const;
export type SearchTab = (typeof SEARCH_TABS)[number];

/** What `tab` cycles to. Wraps, because in a modal with one field it has nowhere else to go. */
export const nextTab = (tab: SearchTab): SearchTab =>
  SEARCH_TABS[(SEARCH_TABS.indexOf(tab) + 1) % SEARCH_TABS.length];

/**
 * A Notion reference Kanso can actually open.
 *
 * `url` is optional on the wire and the invariant belongs in a type rather than in a
 * comment: the row that opens one of these calls `window.open(row.notionRef.url)` with no
 * `if` around it, because `search` cannot hand back one without a url. An `if` there is
 * how the old code produced a focusable row that answered ↵ by closing the palette.
 */
export type OpenableNotionRef = NotionRef & { url: string };

/**
 * One row of the result list, tagged with what it is.
 *
 * A flat list beside the groups, because `↑` and `↓` walk *rows* across the group
 * headings and the highlight has to be one index rather than one per group.
 *
 * `page` and `notionRef` are two kinds and not one kind with a flag, because they answer
 * `↵` differently: a page is a `router.push`, a reference is a new tab in another
 * application. A flag would let a caller forget to read it; a discriminant makes the
 * compiler ask which one every time.
 */
export type SearchRow =
  | { kind: "ticket"; key: string; ticket: Ticket }
  | { kind: "page"; key: string; page: DocPage }
  | { kind: "notionRef"; key: string; notionRef: OpenableNotionRef }
  | { kind: "command"; key: string; command: PaletteCommand };

/**
 * A row's key, prefixed by kind.
 *
 * `doc_pages.id` and `notion_docs.id` are two id spaces generated apart, and nothing
 * stops one string being a valid id in both. `key` is what the list matches a drawn row
 * against, so an unprefixed collision aims `↑`/`↓` at the wrong one of the two — silently,
 * and only for the pair that collided. Tickets and commands keep their bare ids: those
 * were never ambiguous, and rewriting them would churn the two blocks of `list.tsx` this
 * fix has no business in.
 */
export const pageKey = (id: string) => `page:${id}`;
export const notionRefKey = (id: string) => `notion:${id}`;

export type SearchOutcome = {
  tickets: Ticket[];
  /** Documents written in Kanso. Open in the app. */
  pages: DocPage[];
  /** Notion pages Kanso only references, and only the ones it has a link for. */
  notionRefs: OpenableNotionRef[];
  commands: PaletteCommand[];
  /** The groups in the order the drawing lists them, as one walkable list. */
  rows: SearchRow[];
  /** How many rows are on screen, and how many matched. The counter reads both. */
  shown: number;
  total: number;
};

/** Five per group: a list of forty in a 520px panel is a scroll, not an answer. */
const DEFAULT_LIMIT = 5;

const contains = (haystack: string, needle: string) =>
  haystack.toLowerCase().includes(needle);

/** What the list prints for a reference, and therefore what a query has to match. */
const refLabel = (ref: NotionRef) => ref.title ?? ref.notionPageId;

/**
 * Everything one query matches.
 *
 * An empty field lists the commands and nothing else. ⌘K opens on an empty field, and
 * every ticket in the instance dumped there would bury the commands the palette is also
 * for — which is what makes "no query" a different question from "a query matching all".
 */
export function search({
  query,
  tab,
  tickets,
  pages,
  notionRefs,
  commands,
  limit = DEFAULT_LIMIT,
}: {
  query: string;
  tab: SearchTab;
  tickets: Ticket[];
  pages: DocPage[];
  notionRefs: NotionRef[];
  commands: PaletteCommand[];
  limit?: number;
}): SearchOutcome {
  const needle = query.trim().toLowerCase();

  const wants = (kind: SearchTab) => tab === "all" || tab === kind;

  const matchedTickets =
    needle && wants("tickets")
      ? tickets.filter(
          (ticket) => contains(ticket.title, needle) || contains(ticket.identifier ?? "", needle),
        )
      : [];

  // One tab for both kinds of document, not two. "Documents" is a question about
  // documents, and a reader asking it does not know — and should not have to — which of
  // Kanso's two tables the answer lives in. That is the same mistake as the bug, moved
  // into the tab strip. Where the two differ is in how they are *drawn*, below.
  const matchedPages =
    needle && wants("docs") ? pages.filter((page) => contains(page.title, needle)) : [];

  /**
   * The Notion pages that are worth offering.
   *
   * Two filters beyond the query, both about not drawing a row that lies:
   *
   * 1. No `url`, no row. `notion_docs` exists so a relation has something to point at, and
   *    a row with no link is a reference Kanso holds an id for and nothing else. Offered
   *    as a result it is focusable, hoverable, and answers ↵ by closing the palette — the
   *    reader concludes the search is broken, which is the worse outcome of the two. The
   *    alternative considered was keeping it with the reason printed in the row ("no link
   *    on this reference"); rejected because the row is still in the walkable sequence, so
   *    `↑`/`↓` and ↵ still land on it, and a keyboard-first palette that answers ↵ with an
   *    apology is a dead end with better manners. If these turn out to be common, the fix
   *    is a `url` on the server, not a row here.
   * 2. A reference the reader can reach *inside* Kanso is drawn once, as the page. A
   *    mirrored `DocPage` carries the Notion page id it came from, and that page also has
   *    a `notion_docs` row; matching both puts two rows with the same title and two
   *    different destinations in front of someone who was only looking for their notes.
   *    Kanso's own reader wins — it is in the app, and `/docs/[id]` links out to Notion
   *    anyway, so nothing is lost by preferring it.
   */
  const mirrored = new Set(
    pages.map((page) => page.notionPageId).filter((id): id is string => Boolean(id)),
  );
  const matchedRefs =
    needle && wants("docs")
      ? notionRefs.filter(
          (ref): ref is OpenableNotionRef =>
            Boolean(ref.url) && !mirrored.has(ref.notionPageId) && contains(refLabel(ref), needle),
        )
      : [];

  const matchedCommands = wants("commands")
    ? needle
      ? commands.filter((command) => contains(command.label, needle))
      : commands
    : [];

  const shownTickets = matchedTickets.slice(0, limit);
  const shownPages = matchedPages.slice(0, limit);
  const shownRefs = matchedRefs.slice(0, limit);
  // Commands are not capped: the registry is a closed list of about thirty, and hiding
  // the tail of it is how a keyboard-first application loses a command nobody can find.
  const shownCommands = matchedCommands;

  const rows: SearchRow[] = [
    ...shownTickets.map((ticket): SearchRow => ({ kind: "ticket", key: ticket.id, ticket })),
    ...shownPages.map((page): SearchRow => ({ kind: "page", key: pageKey(page.id), page })),
    ...shownRefs.map(
      (notionRef): SearchRow => ({
        kind: "notionRef",
        key: notionRefKey(notionRef.id),
        notionRef,
      }),
    ),
    ...shownCommands.map(
      (command): SearchRow => ({ kind: "command", key: command.id, command }),
    ),
  ];

  return {
    tickets: shownTickets,
    pages: shownPages,
    notionRefs: shownRefs,
    commands: shownCommands,
    rows,
    shown: rows.length,
    // The two filters above happen before this count on purpose: a reference that is not
    // on screen must not be in "1 of 2 results" either. A counter that counts rows the
    // reader cannot see is the same missing document told as a number.
    total:
      matchedTickets.length + matchedPages.length + matchedRefs.length + matchedCommands.length,
  };
}

/** `3 of 12 results`, and the two spellings either side of it. */
export function countLabel(shown: number, total: number): string {
  if (total === 0) return "No results";
  return `${shown} of ${total} result${total === 1 ? "" : "s"}`;
}

/** One run of text, and whether the query put it there. */
export type HighlightPart = { text: string; hit: boolean };

/**
 * What was typed, marked wherever it appears.
 *
 * Split on the lowercased haystack and sliced out of the original, so the marked run
 * keeps the casing it was written with — `Echo` stays `Echo` when `echo` was typed. Not a
 * regex: a query is text, and `.` or `(` typed into a search field must find a dot or a
 * bracket rather than throw or match everything.
 */
export function highlight(text: string, query: string): HighlightPart[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [{ text, hit: false }];

  const parts: HighlightPart[] = [];
  const lower = text.toLowerCase();
  let cursor = 0;

  for (let at = lower.indexOf(needle); at !== -1; at = lower.indexOf(needle, cursor)) {
    if (at > cursor) parts.push({ text: text.slice(cursor, at), hit: false });
    parts.push({ text: text.slice(at, at + needle.length), hit: true });
    cursor = at + needle.length;
  }

  if (parts.length === 0) return [{ text, hit: false }];
  if (cursor < text.length) parts.push({ text: text.slice(cursor), hit: false });
  return parts;
}

/** The two id prefixes `app/page.tsx` builds its dependency picker out of. */
const PICKER_PREFIXES = ["timeline.link.", "timeline.unlink."];

/**
 * Whether this list is the dependency picker rather than the command list.
 *
 * `d` and `D` on the chart route the picker through this same overlay, and `app/page.tsx`
 * — frozen for the fan-out — says so only by what it passes: an early return whose every
 * id carries one of two prefixes. The ordinary list always carries registry actions
 * (`ticket.create`'s `when` is `() => true`), so "every id is a picker id" separates the
 * two without either caller changing.
 *
 * Keying on an id prefix is what `menu-items.ts` and `actionById` already do — an action
 * id is a name other modules address by, not an implementation detail. It is still a
 * sniff, and the optional `only` prop on the palette is there so a caller that *can* say
 * so does; this answers for the one that cannot.
 */
export function isPickerList(commands: PaletteCommand[]): boolean {
  return (
    commands.length > 0 &&
    commands.every((command) => PICKER_PREFIXES.some((prefix) => command.id.startsWith(prefix)))
  );
}
