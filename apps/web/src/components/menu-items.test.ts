import { describe, expect, it, vi } from "vitest";
import type { ActionContext } from "@/lib/actions";
import { DEFAULT_MERGE } from "@/lib/shortcuts";
import type { Team, Ticket } from "@/lib/api";
import { statusMenuItems } from "./menu-items";

/**
 * The status menu on a pill — `KAN-92`.
 *
 * It used to be six action ids, which is what every other menu in the app is. That
 * stopped being possible when the digits became positional: an action's label can only
 * say "the team's 3rd", because the shortcuts settings screen lists the registry with no
 * ticket in hand. So this menu is built from the ticket's own catalogue instead, and the
 * registry is asked only for the key to print beside each row.
 */

const team = (id: string, statuses: Team["statuses"]): Team =>
  ({
    id,
    name: `Team ${id}`,
    key: id.toUpperCase(),
    archived: false,
    ticketCount: 0,
    mirror: { state: "synced" },
    editable: true,
    statuses,
  }) as Team;

const atelier = team("atelier", [
  { key: "boite", label: "Boîte", category: "backlog", position: 0 },
  { key: "devis", label: "Devis", category: "backlog", position: 1 },
  { key: "en_cours", label: "En cours", category: "started", position: 2 },
  { key: "livre", label: "Livré", category: "completed", position: 3 },
]);

const row = { id: "row", teamId: "atelier", status: "devis" } as Ticket;

const ctx = (overrides: Partial<ActionContext> = {}) =>
  ({
    teams: [atelier],
    selected: row,
    canWrite: true,
    patchTicket: vi.fn(),
    close: vi.fn(),
    ...overrides,
  }) as unknown as ActionContext;

describe("the status menu", () => {
  it("lists the words the ticket's own team chose, in the team's own order", () => {
    const items = statusMenuItems(ctx(), DEFAULT_MERGE.keys);

    expect(items.map((item) => item.label)).toEqual([
      "Set status: Boîte",
      "Set status: Devis",
      "Set status: En cours",
      "Set status: Livré",
    ]);
  });

  it("prints the digit that reaches each one", () => {
    // The menu is where the positional keys are learnt: the label says the word and the
    // hint says the number, which is the pairing no other surface can make.
    const items = statusMenuItems(ctx(), DEFAULT_MERGE.keys);

    expect(items.map((item) => item.hint)).toEqual(["1", "2", "3", "4"]);
  });

  it("writes the key that word stands for", () => {
    const context = ctx();
    const items = statusMenuItems(context, DEFAULT_MERGE.keys);
    items[3].onSelect();

    expect(context.patchTicket).toHaveBeenCalledWith({ id: "row", status: "livre" });
    expect(context.close).toHaveBeenCalled();
  });

  it("names a seventh word with no key beside it", () => {
    // The digits stop at six and the catalogue does not. A row with no hint is the
    // truthful drawing — the word is reachable here and through `⇧s`, by no digit — and
    // `actionById` throws on an id the registry has never had, so this is the case that
    // decides whether the menu can be built at all.
    const seventh = { ...atelier.statuses[3], key: "archive", label: "Archivé", position: 6 };
    const long = team("long", [
      ...atelier.statuses,
      { key: "pose", label: "Pose", category: "started", position: 4 },
      { key: "facture", label: "Facturé", category: "completed", position: 5 },
      seventh,
    ]);
    const items = statusMenuItems(
      ctx({ teams: [long], selected: { ...row, teamId: "long" } }),
      DEFAULT_MERGE.keys,
    );

    expect(items.map((item) => item.label)).toHaveLength(7);
    expect(items[6].label).toBe("Set status: Archivé");
    expect(items[6].hint).toBeUndefined();
  });

  it("is empty for a seat that cannot write, like every other menu of writes", () => {
    expect(statusMenuItems(ctx({ canWrite: false }), DEFAULT_MERGE.keys)).toEqual([]);
  });

  it("is empty with no row to act on", () => {
    expect(statusMenuItems(ctx({ selected: undefined }), DEFAULT_MERGE.keys)).toEqual([]);
  });
});
