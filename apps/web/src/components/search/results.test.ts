import { describe, expect, it } from "vitest";
import type { Doc as NotionRef, DocPage, Ticket, TicketStatus } from "@/lib/api";
import {
  countLabel,
  highlight,
  isPickerList,
  nextTab,
  notionRefKey,
  pageKey,
  search,
  SEARCH_TABS,
  type PaletteCommand,
} from "./results";

function ticket(identifier: string, title: string, status: TicketStatus = "todo"): Ticket {
  return {
    id: identifier.toLowerCase(),
    identifier,
    number: Number(identifier.split("-")[1]),
    teamId: "team",
    title,
    status,
    priority: "none",
    assigneeIds: [],
    docIds: [],
    customFields: {},
    // Required on `Ticket` for the reason `customFields` beside it is: the server always
    // sends the key, so a factory that omits it is not a ticket the API can produce.
    pullRequests: [],
    archived: false,
    mirror: { state: "disabled" },
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
  };
}

/** A document written in Kanso — screen 07's own object, with a route of its own. */
const page = (id: string, title: string, notionPageId?: string): DocPage => ({
  id,
  teamId: "team",
  title,
  notionPageId,
  createdAt: "2026-08-01T00:00:00Z",
  updatedAt: "2026-08-01T00:00:00Z",
});

/**
 * A Notion page Kanso only references. `url` is optional on the wire, and that is the
 * whole of the second half of this file: a reference without one has no destination.
 */
const notionRef = (id: string, title: string, url?: string): NotionRef => ({
  id,
  notionPageId: `n-${id}`,
  title,
  url: url ?? `https://notion.so/${id}`,
});

const unlinkedRef = (id: string, title: string): NotionRef => ({
  id,
  notionPageId: `n-${id}`,
  title,
});

const command = (id: string, label: string): PaletteCommand => ({ id, label, run: () => {} });

const tickets = [
  ticket("KAN-142", "Echo suppression drops our own writes"),
  ticket("KAN-119", "Log the suppressed echoes", "done"),
  ticket("KAN-7", "Something else entirely"),
];
const pages = [
  page("p1", "Echo suppression — how we decided"),
  page("p2", "Onboarding notes"),
];
const notionRefs = [
  notionRef("d1", "Mirror architecture — echo suppression"),
  notionRef("d2", "Unrelated notes"),
];
const commands = [command("ticket.create", "New ticket"), command("app.help", "Keyboard help")];

const all = { tickets, pages, notionRefs, commands };

describe("search", () => {
  /**
   * The point of the screen: one field, every kind of answer. A palette that searched
   * only its own commands is the three-quarters-missing version the spec calls out.
   */
  it("matches tickets, both kinds of document and commands at once", () => {
    const found = search({ query: "echo", tab: "all", ...all });
    expect(found.tickets.map((row) => row.identifier)).toEqual(["KAN-142", "KAN-119"]);
    expect(found.pages.map((row) => row.id)).toEqual(["p1"]);
    expect(found.notionRefs.map((row) => row.id)).toEqual(["d1"]);
    // No command mentions "echo", and inventing one would be a result nobody asked for.
    expect(found.commands).toEqual([]);
  });

  /**
   * The defect this file exists to lock down.
   *
   * `Doc` and `DocPage` are two entities that shared one word, and the palette searched
   * only the first: a document written in Kanso, opened and titled and edited here, was
   * unfindable by the search Kanso puts on ⌘K. Someone typing the title of what they
   * wrote yesterday got "Nothing matches that." — with no Notion page in the instance to
   * even hint at why. So this asks the question with the Notion side empty, because the
   * old code could pass a mixed assertion by matching a reference that happened to share
   * a word.
   */
  it("finds a document written in Kanso even when nothing is referenced from Notion", () => {
    const found = search({
      query: "onboarding",
      tab: "all",
      tickets,
      pages,
      notionRefs: [],
      commands,
    });
    expect(found.pages.map((row) => row.title)).toEqual(["Onboarding notes"]);
    expect(found.rows.map((row) => row.kind)).toEqual(["page"]);
    expect(found.total).toBe(1);
  });

  it("matches a ticket on its identifier as well as its title", () => {
    const found = search({ query: "kan-7", tab: "all", ...all });
    expect(found.tickets.map((row) => row.identifier)).toEqual(["KAN-7"]);
  });

  it("is insensitive to case and to the spaces around what was typed", () => {
    expect(search({ query: "  ECHO ", tab: "all", ...all }).tickets).toHaveLength(2);
  });

  /**
   * An empty field is not a search for everything. The palette opens on ⌘K with nothing
   * typed, and listing every ticket in the instance there would bury the commands it is
   * also for.
   */
  it("lists the commands and nothing else before anything is typed", () => {
    const found = search({ query: "", tab: "all", ...all });
    expect(found.commands).toHaveLength(2);
    expect(found.tickets).toEqual([]);
    expect(found.pages).toEqual([]);
    expect(found.notionRefs).toEqual([]);
  });

  it("narrows to one kind when a tab says so", () => {
    const only = search({ query: "echo", tab: "tickets", ...all });
    expect(only.tickets).toHaveLength(2);
    expect(only.pages).toEqual([]);
    expect(only.notionRefs).toEqual([]);

    // One tab, both kinds of document: "Documents" is a question about documents, and a
    // reader asking it does not know which of Kanso's two tables the answer lives in.
    const documents = search({ query: "echo", tab: "docs", ...all });
    expect(documents.tickets).toEqual([]);
    expect(documents.pages).toHaveLength(1);
    expect(documents.notionRefs).toHaveLength(1);
  });

  /**
   * `shown` against `total` is what the counter reads, and the two differ because each
   * group is capped: a list of forty tickets in a 520px panel is a scroll, not an answer.
   */
  it("caps each group and reports what it left out", () => {
    const many = Array.from({ length: 9 }, (_, index) => ticket(`KAN-${index + 1}`, "echo"));
    const found = search({ query: "echo", tab: "all", ...all, tickets: many, limit: 5 });
    expect(found.tickets).toHaveLength(5);
    expect(found.shown).toBe(7); // five tickets, one Kanso page, one Notion reference
    expect(found.total).toBe(11); // nine tickets, one Kanso page, one Notion reference
  });

  /** The two document groups are capped apart, because they are drawn apart. */
  it("caps the two kinds of document independently", () => {
    const manyPages = Array.from({ length: 7 }, (_, index) => page(`p${index}`, "echo"));
    const manyRefs = Array.from({ length: 7 }, (_, index) => notionRef(`d${index}`, "echo"));
    const found = search({
      query: "echo",
      tab: "docs",
      tickets: [],
      pages: manyPages,
      notionRefs: manyRefs,
      commands: [],
      limit: 5,
    });
    expect(found.pages).toHaveLength(5);
    expect(found.notionRefs).toHaveLength(5);
    expect(found.total).toBe(14);
  });

  /** The order the drawing lists them in, and the order ↑↓ walks. */
  it("puts tickets before Kanso documents before Notion references before commands", () => {
    const found = search({ query: "e", tab: "all", ...all });
    expect(found.rows.map((row) => row.kind)).toEqual(
      Array(found.tickets.length)
        .fill("ticket")
        .concat(Array(found.pages.length).fill("page"))
        .concat(Array(found.notionRefs.length).fill("notionRef"))
        .concat(Array(found.commands.length).fill("command")),
    );
  });

  /**
   * A reference with no `url` is not a destination.
   *
   * `notion_docs` holds a row per Notion page a relation can point at, and the `url` is
   * optional on that row. Offered as a result it becomes a focusable row that answers ↵
   * by closing the palette and doing nothing — the reader is left to conclude the search
   * is broken. So it is not offered, and the counter does not count it either: "1 of 2
   * results" about a row that is not on screen is the same lie told twice.
   */
  it("leaves out a Notion reference Kanso has no link for", () => {
    const found = search({
      query: "notes",
      tab: "docs",
      tickets: [],
      pages: [],
      notionRefs: [notionRef("d2", "Unrelated notes"), unlinkedRef("d3", "Notes with no link")],
      commands: [],
    });
    expect(found.notionRefs.map((row) => row.id)).toEqual(["d2"]);
    expect(found.total).toBe(1);
  });

  /** Every reference that survives has a `url`, so the row that opens it needs no branch. */
  it("hands back references whose url is known to be there", () => {
    const found = search({
      query: "mirror",
      tab: "docs",
      tickets: [],
      pages: [],
      notionRefs,
      commands: [],
    });
    expect(found.notionRefs.map((row) => row.url)).toEqual(["https://notion.so/d1"]);
  });

  /** A titleless reference is still findable by the id the row would print. */
  it("matches a Notion reference on its page id when it has no title", () => {
    const untitled: NotionRef = { id: "d9", notionPageId: "n-abcdef", url: "https://notion.so/x" };
    const found = search({
      query: "abcdef",
      tab: "docs",
      tickets: [],
      pages: [],
      notionRefs: [untitled],
      commands: [],
    });
    expect(found.notionRefs.map((row) => row.id)).toEqual(["d9"]);
  });

  /**
   * One page, one row.
   *
   * A `DocPage` that mirrors a Notion page carries that page's id, and the same page has
   * a `notion_docs` row so tickets can relate to it. Both matched, the reader gets two
   * rows with the same title and two different destinations — which is the confusion this
   * whole pass is about, drawn on screen. Kanso's own reader wins: it is in the app, it is
   * the one `/docs/[id]` can show, and Notion is one ↗ away from it anyway.
   */
  it("drops a Notion reference that a Kanso page already mirrors", () => {
    const found = search({
      query: "spec",
      tab: "docs",
      tickets: [],
      pages: [page("p9", "Mirror spec", "n-d7")],
      notionRefs: [notionRef("d7", "Mirror spec")],
      commands: [],
    });
    expect(found.pages.map((row) => row.id)).toEqual(["p9"]);
    expect(found.notionRefs).toEqual([]);
    expect(found.total).toBe(1);
  });

  /**
   * The two id spaces are not one id space.
   *
   * `key` is what the list matches a drawn row against, and a `DocPage` id and a
   * `notion_docs` id are generated apart: nothing stops one string being both. Unprefixed
   * keys made `↑`/`↓` land on the wrong row of the two — silently, and only for the pair
   * that collided.
   */
  it("keys the two kinds of document apart even when the ids collide", () => {
    const found = search({
      query: "collide",
      tab: "docs",
      tickets: [],
      pages: [page("same", "Collide, in Kanso")],
      notionRefs: [notionRef("same", "Collide, in Notion")],
      commands: [],
    });
    expect(found.rows.map((row) => row.key)).toEqual([pageKey("same"), notionRefKey("same")]);
    expect(new Set(found.rows.map((row) => row.key)).size).toBe(2);
  });
});

describe("countLabel", () => {
  it("reads as the drawing's counter", () => {
    expect(countLabel(3, 12)).toBe("3 of 12 results");
  });

  /** One result is not "1 results", and the palette is read every day. */
  it("is singular for one", () => {
    expect(countLabel(1, 1)).toBe("1 of 1 result");
  });

  it("says plainly when there is nothing", () => {
    expect(countLabel(0, 0)).toBe("No results");
  });
});

describe("nextTab", () => {
  it("cycles and wraps, because tab has nowhere else to go in a modal with one field", () => {
    expect(nextTab("all")).toBe("tickets");
    expect(nextTab("commands")).toBe("all");
    expect(SEARCH_TABS).toEqual(["all", "tickets", "docs", "commands"]);
  });
});

describe("highlight", () => {
  it("marks what was typed, wherever it appears", () => {
    expect(highlight("Echo suppression", "echo")).toEqual([
      { text: "Echo", hit: true },
      { text: " suppression", hit: false },
    ]);
    expect(highlight("Log the suppressed echoes", "echo")).toEqual([
      { text: "Log the suppressed ", hit: false },
      { text: "echo", hit: true },
      { text: "es", hit: false },
    ]);
  });

  it("marks every occurrence and not only the first", () => {
    expect(highlight("echo echo", "echo").filter((part) => part.hit)).toHaveLength(2);
  });

  it("is one unmarked part when nothing was typed or nothing matched", () => {
    expect(highlight("Echo", "")).toEqual([{ text: "Echo", hit: false }]);
    expect(highlight("Echo", "zz")).toEqual([{ text: "Echo", hit: false }]);
  });

  /** A needle with regex punctuation in it must be a needle, not a pattern. */
  it("treats what was typed as text", () => {
    expect(highlight("a.b", ".")).toEqual([
      { text: "a", hit: false },
      { text: ".", hit: true },
      { text: "b", hit: false },
    ]);
  });
});

describe("isPickerList", () => {
  /**
   * `d` and `D` on the chart route the dependency picker through this same overlay, and
   * `app/page.tsx` — frozen for the fan-out — says so only by the commands it passes: an
   * early return whose every id is one of two prefixes. Searching tickets underneath that
   * list would put two lists of tickets on screen with different consequences, so the
   * picker is recognised and the search stays out of its way.
   */
  it("recognises the dependency picker by the ids page.tsx builds", () => {
    expect(isPickerList([command("timeline.link.abc", "Wait for KAN-139")])).toBe(true);
    expect(isPickerList([command("timeline.unlink.abc", "Stop waiting for KAN-139")])).toBe(true);
  });

  it("is not fooled by the ordinary list, which always carries registry actions", () => {
    // `ticket.create`'s `when` is `() => true`, so this list is never empty in practice.
    expect(isPickerList(commands)).toBe(false);
    expect(isPickerList([...commands, command("timeline.link.abc", "Wait for KAN-139")])).toBe(false);
    // Nothing at all is not a picker either: it is a context with no available action.
    expect(isPickerList([])).toBe(false);
  });
});
