import { describe, expect, it } from "vitest";
import type { Ticket } from "./api";
import { heldWrite, settlementOf } from "./offline-write";

const ticket = (overrides: Partial<Ticket> = {}): Ticket => ({
  id: "t1",
  identifier: "KAN-142",
  number: 142,
  teamId: "team-a",
  title: "The board jumps on every status key",
  status: "todo",
  priority: "none",
  assigneeIds: [],
  docIds: [],
  pullRequests: [],
  archived: false,
  customFields: {},
  mirror: { state: "pending" },
  createdAt: "2026-09-01T10:00:00Z",
  updatedAt: "2026-09-01T10:00:00Z",
  ...overrides,
});

describe("what the offline banner is told about a held patch", () => {
  it("names the field and the word the reader saw on the control", () => {
    const held = heldWrite("t1", ticket(), { status: "in_review" });

    // `in_review` is the wire value. Nobody pressed a key called `in_review`.
    expect(held.summary).toBe("status → In review");
  });

  it("prints the identifier a reader can look up", () => {
    expect(heldWrite("t1", ticket(), { status: "done" }).reference).toBe("KAN-142");
  });

  it("falls back to a draft's title, since a draft has no identifier to print", () => {
    const draft = ticket({ identifier: undefined, number: undefined, teamId: undefined });

    // The alternative is a row in the banner headed by nothing, about a ticket the
    // reader owns and can still open.
    expect(heldWrite("t1", draft, { priority: "urgent" }).reference).toBe(
      "The board jumps on every status key",
    );
  });

  it("prints the bare id when no cached row can name the ticket", () => {
    // `findTicket` answers nothing for a row this tab has never listed. A banner row
    // headed by a uuid is poor and still better than a write nobody can see.
    expect(heldWrite("t1", undefined, { status: "done" }).reference).toBe("t1");
  });

  it("reads as one line when one gesture changed two fields", () => {
    const held = heldWrite("t1", ticket(), { status: "done", estimate: 5 });

    expect(held.summary).toBe("status → Done, estimate → 5");
  });

  it("says a bound was cleared rather than pointing an arrow at nothing", () => {
    const held = heldWrite("t1", ticket(), { unset: ["due"] });

    expect(held.summary).toBe("due cleared");
  });

  it("says a row was archived, not that a field of it was edited", () => {
    expect(heldWrite("t1", ticket(), { archived: true }).summary).toBe("archived");
    expect(heldWrite("t1", ticket({ archived: true }), { archived: false }).summary).toBe("unarchived");
  });

  it("carries the request the queue will replay, and the id stays in the path", () => {
    const held = heldWrite("t1", ticket(), { status: "done", unset: ["estimate"] });

    expect(held.request).toEqual({
      path: "/api/tickets/t1",
      method: "PATCH",
      // `id` names the row in the path and is not a field of it — a body carrying one
      // would be replayed against a server that has no such field to write.
      body: { status: "done", unset: ["estimate"] },
    });
  });

  it("still says something when the patch carries a field it has no phrase for", () => {
    const held = heldWrite("t1", ticket(), { description: "a longer account" });

    // A summary is what the banner prints; an empty one is a row the reader cannot act
    // on. Every field gets a phrase, and the fallback is the field's own name.
    expect(held.summary).toBe("description edited");
  });
});

describe("how a patch settled", () => {
  it("is saved when the server answered with the row it wrote", () => {
    expect(settlementOf(ticket({ status: "done" }), null)).toBe("saved");
  });

  it("is held when there is no row and no error, which is the queue having taken it", () => {
    // `withOfflineFallback` answers `undefined` for a write it queued. This is the whole
    // reason this function exists: a refusal answers `undefined` too.
    expect(settlementOf(undefined, null)).toBe("held");
  });

  it("is refused when the server said no, even though it left no row either", () => {
    expect(settlementOf(undefined, { status: 403 })).toBe("refused");
  });
});
