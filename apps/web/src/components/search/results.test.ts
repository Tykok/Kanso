import { describe, expect, it } from "vitest";
import type { Doc, Ticket, TicketStatus } from "@/lib/api";
import {
  countLabel,
  highlight,
  isPickerList,
  nextTab,
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
    archived: false,
    mirror: { state: "disabled" },
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
  };
}

const doc = (id: string, title: string): Doc => ({ id, notionPageId: `n-${id}`, title });

const command = (id: string, label: string): PaletteCommand => ({ id, label, run: () => {} });

const tickets = [
  ticket("KAN-142", "Echo suppression drops our own writes"),
  ticket("KAN-119", "Log the suppressed echoes", "done"),
  ticket("KAN-7", "Something else entirely"),
];
const docs = [doc("d1", "Mirror architecture — echo suppression"), doc("d2", "Unrelated notes")];
const commands = [command("ticket.create", "New ticket"), command("app.help", "Keyboard help")];

describe("search", () => {
  /**
   * The point of the screen: one field, three kinds of answer. A palette that searched
   * only its own commands is the three-quarters-missing version the spec calls out.
   */
  it("matches tickets, documents and commands at once", () => {
    const found = search({ query: "echo", tab: "all", tickets, docs, commands });
    expect(found.tickets.map((row) => row.identifier)).toEqual(["KAN-142", "KAN-119"]);
    expect(found.docs.map((row) => row.id)).toEqual(["d1"]);
    // No command mentions "echo", and inventing one would be a result nobody asked for.
    expect(found.commands).toEqual([]);
  });

  it("matches a ticket on its identifier as well as its title", () => {
    const found = search({ query: "kan-7", tab: "all", tickets, docs, commands });
    expect(found.tickets.map((row) => row.identifier)).toEqual(["KAN-7"]);
  });

  it("is insensitive to case and to the spaces around what was typed", () => {
    expect(search({ query: "  ECHO ", tab: "all", tickets, docs, commands }).tickets).toHaveLength(2);
  });

  /**
   * An empty field is not a search for everything. The palette opens on ⌘K with nothing
   * typed, and listing every ticket in the instance there would bury the commands it is
   * also for.
   */
  it("lists the commands and nothing else before anything is typed", () => {
    const found = search({ query: "", tab: "all", tickets, docs, commands });
    expect(found.commands).toHaveLength(2);
    expect(found.tickets).toEqual([]);
    expect(found.docs).toEqual([]);
  });

  it("narrows to one kind when a tab says so", () => {
    const only = search({ query: "echo", tab: "tickets", tickets, docs, commands });
    expect(only.tickets).toHaveLength(2);
    expect(only.docs).toEqual([]);

    const justDocs = search({ query: "echo", tab: "docs", tickets, docs, commands });
    expect(justDocs.tickets).toEqual([]);
    expect(justDocs.docs).toHaveLength(1);
  });

  /**
   * `shown` against `total` is what the counter reads, and the two differ because each
   * group is capped: a list of forty tickets in a 520px panel is a scroll, not an answer.
   */
  it("caps each group and reports what it left out", () => {
    const many = Array.from({ length: 9 }, (_, index) => ticket(`KAN-${index + 1}`, "echo"));
    const found = search({ query: "echo", tab: "all", tickets: many, docs, commands, limit: 5 });
    expect(found.tickets).toHaveLength(5);
    expect(found.shown).toBe(6); // five tickets, one document
    expect(found.total).toBe(10); // nine tickets, one document
  });

  /** The order the drawing lists them in, and the order ↑↓ walks. */
  it("puts tickets before documents before commands", () => {
    const found = search({ query: "e", tab: "all", tickets, docs, commands });
    expect(found.rows.map((row) => row.kind)).toEqual(
      Array(found.tickets.length)
        .fill("ticket")
        .concat(Array(found.docs.length).fill("doc"))
        .concat(Array(found.commands.length).fill("command")),
    );
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
