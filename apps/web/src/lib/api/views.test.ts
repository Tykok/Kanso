import { describe, expect, it } from "vitest";
import { DEFAULT_PREFERENCES, type Preferences } from "./core";
import { openTicketMode, parseTicketKey, ticketHref } from "./views";

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

describe("openTicketMode", () => {
  /**
   * `user_preferences.open_ticket` is slice 0's column and is not on this branch, so
   * the reader has to answer for a `Preferences` that does not carry the field yet —
   * and answer the same way it will once a stored `'panel'` starts arriving.
   */
  it("defaults to the panel", () => {
    expect(openTicketMode(DEFAULT_PREFERENCES)).toBe("panel");
    expect(openTicketMode({ ...DEFAULT_PREFERENCES, openTicket: undefined })).toBe("panel");
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
