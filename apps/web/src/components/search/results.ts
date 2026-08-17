import type { Doc, Ticket } from "@/lib/api";

/**
 * Screen 06's arithmetic: what one field matches, in what order, and how much of it is
 * being shown.
 *
 * The palette and the search are the same object — that is the whole point of the screen —
 * so this is where "the same object" is actually defined. `command-palette.tsx` above it
 * draws and dispatches; every question that can be got wrong is answered here, where
 * vitest can ask it.
 */

/** The shape `app/page.tsx` and `ViewsShell` already hand the palette. Unchanged. */
export type PaletteCommand = { id: string; label: string; hint?: string; run: () => void };

export const SEARCH_TABS = ["all", "tickets", "docs", "commands"] as const;
export type SearchTab = (typeof SEARCH_TABS)[number];

/** What `tab` cycles to. Wraps, because in a modal with one field it has nowhere else to go. */
export const nextTab = (tab: SearchTab): SearchTab =>
  SEARCH_TABS[(SEARCH_TABS.indexOf(tab) + 1) % SEARCH_TABS.length];

/**
 * One row of the result list, tagged with what it is.
 *
 * A flat list beside the three groups, because `↑` and `↓` walk *rows* across the group
 * headings and the highlight has to be one index rather than a pair.
 */
export type SearchRow =
  | { kind: "ticket"; key: string; ticket: Ticket }
  | { kind: "doc"; key: string; doc: Doc }
  | { kind: "command"; key: string; command: PaletteCommand };

export type SearchOutcome = {
  tickets: Ticket[];
  docs: Doc[];
  commands: PaletteCommand[];
  /** The three groups in the order the drawing lists them, as one walkable list. */
  rows: SearchRow[];
  /** How many rows are on screen, and how many matched. The counter reads both. */
  shown: number;
  total: number;
};

/** Five per group: a list of forty in a 520px panel is a scroll, not an answer. */
const DEFAULT_LIMIT = 5;

const contains = (haystack: string, needle: string) =>
  haystack.toLowerCase().includes(needle);

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
  docs,
  commands,
  limit = DEFAULT_LIMIT,
}: {
  query: string;
  tab: SearchTab;
  tickets: Ticket[];
  docs: Doc[];
  commands: PaletteCommand[];
  limit?: number;
}): SearchOutcome {
  const needle = query.trim().toLowerCase();

  const wants = (kind: SearchTab) => tab === "all" || tab === kind;

  const matchedTickets =
    needle && wants("tickets")
      ? tickets.filter(
          (ticket) => contains(ticket.title, needle) || contains(ticket.identifier, needle),
        )
      : [];

  const matchedDocs =
    needle && wants("docs")
      ? docs.filter((doc) => contains(doc.title ?? doc.notionPageId, needle))
      : [];

  const matchedCommands = wants("commands")
    ? needle
      ? commands.filter((command) => contains(command.label, needle))
      : commands
    : [];

  const shownTickets = matchedTickets.slice(0, limit);
  const shownDocs = matchedDocs.slice(0, limit);
  // Commands are not capped: the registry is a closed list of about thirty, and hiding
  // the tail of it is how a keyboard-first application loses a command nobody can find.
  const shownCommands = matchedCommands;

  const rows: SearchRow[] = [
    ...shownTickets.map((ticket): SearchRow => ({ kind: "ticket", key: ticket.id, ticket })),
    ...shownDocs.map((doc): SearchRow => ({ kind: "doc", key: doc.id, doc })),
    ...shownCommands.map(
      (command): SearchRow => ({ kind: "command", key: command.id, command }),
    ),
  ];

  return {
    tickets: shownTickets,
    docs: shownDocs,
    commands: shownCommands,
    rows,
    shown: rows.length,
    total: matchedTickets.length + matchedDocs.length + matchedCommands.length,
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
