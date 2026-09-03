import { describe, expect, it } from "vitest";
import type { Ticket, TicketLink, TicketLinkType } from "@/lib/api";
import { linkGroups } from "./ticket-links";

const ticket = (id: string): Ticket => ({ id, title: id.toUpperCase() }) as Ticket;

const link = (id: string, type: TicketLinkType, outgoing: boolean): TicketLink => ({
  ticket: ticket(id),
  type,
  outgoing,
  symmetric: type === "relates",
});

describe("linkGroups", () => {
  it("reads one directed row as two different sentences depending on which end asked", () => {
    expect(linkGroups([link("a", "blocks", true)]).map((g) => g.label)).toEqual(["Blocks"]);
    expect(linkGroups([link("a", "blocks", false)]).map((g) => g.label)).toEqual(["Blocked by"]);
    expect(linkGroups([link("a", "duplicates", true)]).map((g) => g.label)).toEqual(["Duplicates"]);
    expect(linkGroups([link("a", "duplicates", false)]).map((g) => g.label)).toEqual([
      "Duplicated by",
    ]);
  });

  it("gives a symmetric row one label whichever end asked", () => {
    // The server stores `relates` once, with the smaller uuid first, so which end is
    // `from` is an accident of two random uuids. A panel that read `outgoing` here would
    // say "relates to" on one ticket and something else on the other, at random.
    expect(linkGroups([link("a", "relates", true)]).map((g) => g.label)).toEqual(["Related to"]);
    expect(linkGroups([link("a", "relates", false)]).map((g) => g.label)).toEqual(["Related to"]);
  });

  it("orders the sections the same way whatever order the rows arrive in", () => {
    const rows = [
      link("rel", "relates", true),
      link("dup", "duplicates", true),
      link("blocks", "blocks", true),
      link("blocked", "blocks", false),
      link("dupby", "duplicates", false),
    ];

    expect(linkGroups(rows).map((g) => g.label)).toEqual([
      "Blocked by",
      "Blocks",
      "Duplicates",
      "Duplicated by",
      "Related to",
    ]);
    expect(linkGroups(rows.toReversed()).map((g) => g.label)).toEqual(
      linkGroups(rows).map((g) => g.label),
    );
  });

  it("gathers several edges of one kind under one heading", () => {
    const groups = linkGroups([link("a", "blocks", false), link("b", "blocks", false)]);

    expect(groups).toHaveLength(1);
    expect(groups[0].tickets.map((t) => t.id)).toEqual(["a", "b"]);
  });

  it("draws no heading over nothing", () => {
    expect(linkGroups([])).toEqual([]);
    expect(linkGroups([link("a", "relates", true)]).map((g) => g.label)).toEqual(["Related to"]);
  });
});
