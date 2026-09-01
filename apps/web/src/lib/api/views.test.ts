import { describe, expect, it } from "vitest";
import { DEFAULT_PREFERENCES, type Preferences } from "./core";
import { isTicketId, openTicketMode, parseTicketKey, ticketAddress, ticketHref } from "./views";

describe("parseTicketKey", () => {
  it("splits the identifier people actually type", () => {
    expect(parseTicketKey("KAN-142")).toEqual({ teamKey: "KAN", number: 142 });
    expect(parseTicketKey("PLT-24")).toEqual({ teamKey: "PLT", number: 24 });
    // `^[A-Z0-9]{2,8}$` is `TeamService.KEY_PATTERN`, digits included.
    expect(parseTicketKey("E1AB-7")).toEqual({ teamKey: "E1AB", number: 7 });
  });

  /**
   * A key pasted out of a mail client or typed into the address bar arrives in
   * whatever case the sender used, and the server stores it uppercase. Normalising
   * here is what keeps `/t/kan-142` from 404ing on a ticket that exists.
   */
  it("uppercases the team key rather than 404ing on a lowercase link", () => {
    expect(parseTicketKey("kan-142")).toEqual({ teamKey: "KAN", number: 142 });
  });

  it("refuses what is not an identifier", () => {
    // No number at all: `/t/KAN` names a team, not a ticket.
    expect(parseTicketKey("KAN")).toBeNull();
    expect(parseTicketKey("KAN-")).toBeNull();
    expect(parseTicketKey("KAN-abc")).toBeNull();
    // Numbers start at one, so a zero is a malformed link and not an empty result.
    expect(parseTicketKey("KAN-0")).toBeNull();
    expect(parseTicketKey("KAN-1.5")).toBeNull();
    // One character is below `@Size(min = 2)`, nine is above `max = 8`.
    expect(parseTicketKey("K-1")).toBeNull();
    expect(parseTicketKey("ABCDEFGHI-1")).toBeNull();
    expect(parseTicketKey("")).toBeNull();
  });

  it("round-trips through the href the board and the palette link to", () => {
    expect(ticketHref("KAN-142")).toBe("/t/KAN-142");
    expect(parseTicketKey("KAN-142")).toEqual(parseTicketKey(ticketHref("KAN-142").slice(3)));
  });
});

const DRAFT_ID = "3f2ab1c4-9d0e-4a76-8b21-5c7e0f9a1234";

/**
 * The second address `/t/{key}` answers to, for the tickets that have no identifier to
 * put in a URL. The two shapes cannot be confused for one another, which is what lets one
 * route serve both without a flag in the path.
 */
describe("isTicketId", () => {
  it("recognises a UUID", () => {
    expect(isTicketId(DRAFT_ID)).toBe(true);
    expect(isTicketId(DRAFT_ID.toUpperCase())).toBe(true);
  });

  it("does not mistake an identifier for one, nor a UUID for an identifier", () => {
    expect(isTicketId("KAN-142")).toBe(false);
    expect(isTicketId("KAN")).toBe(false);
    expect(isTicketId("")).toBe(false);
    // The one that matters: the two parsers must not both claim the same string.
    expect(parseTicketKey(DRAFT_ID)).toBeNull();
  });

  it("refuses a UUID with a piece missing, rather than requesting it and 404ing", () => {
    expect(isTicketId(DRAFT_ID.slice(0, -1))).toBe(false);
    expect(isTicketId(`${DRAFT_ID}-extra`)).toBe(false);
  });
});

describe("ticketAddress", () => {
  it("is the identifier when there is one, because that is the half a person can dictate", () => {
    expect(ticketAddress({ id: DRAFT_ID, identifier: "KAN-142" })).toBe("KAN-142");
    expect(ticketHref(ticketAddress({ id: DRAFT_ID, identifier: "KAN-142" }))).toBe("/t/KAN-142");
  });

  /**
   * A ticket no team has claimed has no identifier at all, and the id is what addresses
   * it. The id also survives the attach that mints the identifier — so a link made now
   * still resolves after the ticket is filed, which is more than the identifier can say
   * for itself when a ticket moves between teams.
   */
  it("falls back to the id, which is the address that never changes", () => {
    expect(ticketAddress({ id: DRAFT_ID, identifier: null })).toBe(DRAFT_ID);
    expect(ticketHref(ticketAddress({ id: DRAFT_ID, identifier: null }))).toBe(`/t/${DRAFT_ID}`);
    // And it round-trips back through the route's own parser, onto the by-id branch.
    expect(isTicketId(ticketHref(ticketAddress({ id: DRAFT_ID, identifier: null })).slice(3))).toBe(
      true,
    );
  });
});

describe("openTicketMode", () => {
  /**
   * The column has landed and `Preferences` carries it, so the interesting case is no
   * longer an absent field but a *present wrong* one: a server that drifted outside the
   * closed set must still get the answer that cannot lose unsaved work.
   */
  it("defaults to the panel", () => {
    expect(openTicketMode(DEFAULT_PREFERENCES)).toBe("panel");
    expect(
      openTicketMode({ ...DEFAULT_PREFERENCES, openTicket: "sideways" as never }),
    ).toBe("panel");
  });

  it("reads the page out of a preference that carries one", () => {
    expect(openTicketMode({ ...DEFAULT_PREFERENCES, openTicket: "page" })).toBe("page");
    expect(openTicketMode({ ...DEFAULT_PREFERENCES, openTicket: "panel" })).toBe("panel");
  });

  // A value the closed vocabulary does not hold is a server that drifted, and the
  // panel is the answer that cannot lose work.
  it("falls back to the panel on a value it does not know", () => {
    const drifted = { ...DEFAULT_PREFERENCES, openTicket: "sidebar" } as unknown as Preferences;
    expect(openTicketMode(drifted)).toBe("panel");
  });
});
